package com.inksheets.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import com.inkslate.data.EventLog
import com.inksheets.ui.AudioPlayer
import java.io.File
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow

/** One decoder Looper for loading, focus, parameters, seeks and the A–B check. UI reads snapshots. */
internal class RecordingAudioSession(private val app: Context) : AudioPlayer {
    private val thread = HandlerThread("recording-player").apply { start() }
    private val handler = Handler(thread.looper)
    private val manager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val policy = PlaybackInterruptionPolicy()
    private val generation = AtomicLong()
    @Volatile private var requested = false
    private var player: MediaPlayer? = null
    private var loop: Pair<Long, Long>? = null
    private var receiverRegistered = false
    private var focusHeld = false
    private var focusRegistered = false
    @Volatile private var position = 0L
    @Volatile private var duration = 0L
    @Volatile private var isPlaying = false
    @Volatile private var reason: String? = null
    @Volatile internal var recordingName = "Recording"
        private set
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes).setWillPauseWhenDucked(true).setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener({ change -> focusChanged(change) }, handler).build()
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                requested = false
                handler.post {
                    policy.headphonesDisconnected(); reason = "Paused: headphones disconnected"
                    pauseDecoder(); relinquishAudio()
                    EventLog.info("sheets", "Recording paused because headphones disconnected")
                }
            }
        }
    }
    private val watch = object : Runnable {
        override fun run() {
            val p = player ?: return
            try {
                isPlaying = p.isPlaying; position = p.currentPosition.toLong()
                loop?.let { (a, b) -> if (isPlaying && position >= b) { p.seekTo(a.toInt()); position = a } }
                if (isPlaying) handler.postDelayed(this, 20)
            } catch (error: Exception) { fail("Recording stopped: ${error.message}") }
        }
    }
    @Volatile override var speed = 1.0
        set(value) { field = if (value.isFinite()) value.coerceIn(.25, 3.0) else 1.0; handler.post { applyParams() } }
    @Volatile override var pitch = 0
        set(value) { field = value.coerceIn(-24, 24); handler.post { applyParams() } }
    @Volatile override var volume = 1.0
        set(value) {
            field = if (value.isFinite()) value.coerceIn(0.0, 1.0) else 1.0
            handler.post { runCatching { player?.setVolume(field.toFloat(), field.toFloat()) }
                .onFailure { EventLog.warn("sheets", "Recording volume: ${it.message}") } }
        }
    override val playing get() = isPlaying
    override val positionMs get() = position
    override val durationMs get() = duration
    override val interruptionReason get() = reason

    /** Synchronous by interface contract, called by the shared loader off the UI thread. */
    override fun load(file: File): Boolean {
        requested = false
        val ticket = generation.incrementAndGet()
        val task = FutureTask {
            if (ticket != generation.get()) return@FutureTask false
            disposeDecoder(); reason = null
            recordingName = file.nameWithoutExtension.ifBlank { "Recording" }
            var loaded: MediaPlayer? = null
            try {
                loaded = MediaPlayer()
                loaded.apply {
                    setAudioAttributes(attributes)
                    setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK)
                    setDataSource(file.path)
                    setOnCompletionListener { p ->
                        if (p !== player) return@setOnCompletionListener
                        val range = loop
                        if (requested && range != null && range.second >= this@RecordingAudioSession.duration - 20) {
                            p.seekTo(range.first.toInt()); position = range.first; p.start()
                            handler.removeCallbacks(watch); handler.post(watch)
                            return@setOnCompletionListener
                        }
                        position = this@RecordingAudioSession.duration
                        requested = false; policy.completed(); this@RecordingAudioSession.isPlaying = false
                        handler.removeCallbacks(watch); relinquishAudio()
                    }
                    setOnErrorListener { p, what, extra ->
                        if (p === player) fail("Recording decoder error ($what, $extra)")
                        true
                    }
                    prepare(); setVolume(volume.toFloat(), volume.toFloat())
                }
                if (ticket != generation.get()) { loaded.release(); return@FutureTask false }
                player = loaded; duration = loaded.duration.toLong(); position = 0L
                true
            } catch (error: Exception) {
                loaded?.release(); fail("Could not load ${file.name}: ${error.message}"); false
            }
        }
        if (Looper.myLooper() === handler.looper) task.run() else handler.post(task)
        return try { task.get() } catch (error: Exception) {
            EventLog.warn("sheets", "Recording preparation: ${error.message}"); false
        }
    }

    override fun play() {
        val ticket = generation.get()
        requested = true
        handler.post {
            if (!requested || ticket != generation.get() || player == null) return@post
            policy.userPlay(); reason = null
            // Android 15+ requires top-app or foreground-service status before requesting focus.
            RecordingPlaybackService.start(app, this)
        }
    }
    internal fun foregroundReady() {
        handler.post {
            if (!requested || !policy.playRequested || player == null || !RecordingPlaybackService.isOwner(this)) {
                RecordingPlaybackService.stop(app, this); return@post
            }
            try {
                if (!focusHeld) {
                    focusHeld = manager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                    if (focusHeld) focusRegistered = true
                }
                if (!focusHeld) { fail("Paused: audio is in use by another app"); return@post }
                registerNoisyReceiver(); startDecoder()
            } catch (error: Exception) { fail("Could not start recording: ${error.message}") }
        }
    }
    internal fun foregroundFailed(message: String) { handler.post { fail(message) } }
    internal fun serviceStopped() {
        requested = false
        handler.post {
            if (isPlaying || policy.waitingForFocus) {
                reason = "Paused: background playback stopped"
                policy.userPause(); pauseDecoder(); relinquishAudio()
            }
        }
    }
    override fun pause() {
        requested = false
        handler.post { policy.userPause(); pauseDecoder(); relinquishAudio() }
    }
    override fun seek(ms: Long) {
        handler.post {
            val target = ms.coerceIn(0L, duration).coerceAtMost(Int.MAX_VALUE.toLong())
            runCatching { player?.seekTo(target.toInt()); position = target }
                .onFailure { EventLog.warn("sheets", "Recording seek: ${it.message}") }
            RecordingPlaybackService.refresh(this)
        }
    }
    override fun setLoop(startMs: Long?, endMs: Long?) {
        handler.post {
            val a = startMs?.coerceAtLeast(0)?.coerceAtMost(duration)
            val b = endMs?.coerceAtMost(duration)
            loop = if (a != null && b != null && b > a) a to b else null
        }
    }
    override fun release() {
        requested = false; generation.incrementAndGet(); isPlaying = false
        handler.post { disposeDecoder(); reason = null }
    }
    private fun focusChanged(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                focusHeld = true
                focusRegistered = true
                if (policy.focusGained() && requested) startDecoder()
                else if (!requested) relinquishAudio()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                focusHeld = false
                policy.focusLost(true, isPlaying); reason = "Paused: another app needs audio"
                pauseDecoder(); RecordingPlaybackService.refresh(this)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                requested = false; policy.focusLost(false, isPlaying); reason = "Paused: audio focus lost"
                pauseDecoder(); relinquishAudio()
            }
        }
    }
    private fun startDecoder() {
        val p = player ?: return
        if (!requested || !policy.playRequested) return
        try {
            // Android may start on playbackParams assignment: only apply it inside explicit start.
            p.playbackParams = params()
            if (!p.isPlaying) p.start()
            isPlaying = p.isPlaying; reason = null
            handler.removeCallbacks(watch); handler.post(watch)
            RecordingPlaybackService.refresh(this)
        } catch (error: Exception) { fail("Recording playback failed: ${error.message}") }
    }
    private fun pauseDecoder() {
        runCatching { player?.let { if (it.isPlaying) it.pause(); position = it.currentPosition.toLong() } }
            .onFailure { EventLog.warn("sheets", "Recording pause: ${it.message}") }
        isPlaying = false; handler.removeCallbacks(watch)
    }
    private fun registerNoisyReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") app.registerReceiver(noisy, filter)
        receiverRegistered = true
    }
    private fun relinquishAudio() {
        if (focusRegistered) runCatching { manager.abandonAudioFocusRequest(focusRequest) }
            .onFailure { EventLog.warn("sheets", "Audio focus release: ${it.message}") }
        focusHeld = false
        focusRegistered = false
        if (receiverRegistered) { runCatching { app.unregisterReceiver(noisy) }; receiverRegistered = false }
        RecordingPlaybackService.stop(app, this)
    }
    private fun disposeDecoder() {
        policy.userPause(); pauseDecoder(); relinquishAudio()
        player?.release(); player = null; loop = null; duration = 0L; position = 0L
    }
    private fun fail(message: String) {
        requested = false; policy.failed(); reason = message; pauseDecoder(); relinquishAudio()
        EventLog.warn("sheets", message)
    }
    private fun params() = PlaybackParams().setSpeed(speed.toFloat()).setPitch(2.0.pow(pitch / 12.0).toFloat())
    private fun applyParams() {
        if (isPlaying) runCatching { player?.playbackParams = params() }
            .onFailure { fail("Could not adjust recording: ${it.message}") }
    }
}
