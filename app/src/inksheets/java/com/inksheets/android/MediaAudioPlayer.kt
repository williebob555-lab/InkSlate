package com.inksheets.android

import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import com.inkslate.data.EventLog
import com.inksheets.ui.AudioPlayer
import java.io.File
import kotlin.math.pow

/**
 * Recordings on the tablet, through Android's own player: it reads every format the tablet
 * does, and changes speed and pitch independently itself. The A-B loop is kept by a check
 * every 20 ms, which is well inside the time a musician can hear.
 */
class MediaAudioPlayer : AudioPlayer {

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var loop: Pair<Long, Long>? = null

    private val watch = object : Runnable {
        override fun run() {
            val p = player ?: return
            loop?.let { (a, b) -> if (p.isPlaying && p.currentPosition >= b) p.seekTo(a.toInt()) }
            if (p.isPlaying) main.postDelayed(this, 20)
        }
    }

    override var speed: Double = 1.0
        set(v) { field = v; applyParams() }

    override var pitch: Int = 0
        set(v) { field = v; applyParams() }

    override fun load(file: File): Boolean = runCatching {
        release()
        val loaded = MediaPlayer().apply {
            setDataSource(file.path)
            prepare()
        }
        player = loaded
        true
    }.onFailure { EventLog.warn("sheets", "Could not load ${file.name}: ${it.message}") }.getOrDefault(false)

    override fun play() {
        val p = player ?: return
        runCatching {
            // Setting the speed on a paused player starts it on some versions, so the speed goes
            // on as part of starting, never before.
            p.playbackParams = params()
            if (!p.isPlaying) p.start()
        }
        main.removeCallbacks(watch)
        main.post(watch)
    }

    override fun pause() {
        runCatching { player?.pause() }
        main.removeCallbacks(watch)
    }

    override val playing: Boolean get() = runCatching { player?.isPlaying == true }.getOrDefault(false)

    override fun seek(ms: Long) {
        runCatching { player?.seekTo(ms.toInt()) }
    }

    override val positionMs: Long get() = runCatching { player?.currentPosition?.toLong() ?: 0 }.getOrDefault(0)
    override val durationMs: Long get() = runCatching { player?.duration?.toLong() ?: 0 }.getOrDefault(0)

    override fun setLoop(startMs: Long?, endMs: Long?) {
        loop = if (startMs != null && endMs != null && endMs > startMs) startMs to endMs else null
    }

    override fun release() {
        main.removeCallbacks(watch)
        runCatching { player?.release() }
        player = null
    }

    private fun params() = PlaybackParams()
        .setSpeed(speed.toFloat())
        .setPitch(2.0.pow(pitch / 12.0).toFloat())

    private fun applyParams() {
        val p = player ?: return
        if (playing) runCatching { p.playbackParams = params() }
    }
}
