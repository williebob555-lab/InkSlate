package com.inksheets.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.inkslate.data.EventLog

/** Music-only foreground lifetime. Never restarts playback after a process death or app update. */
class RecordingPlaybackService : Service() {
    private lateinit var media: MediaSession
    private var lastStartId = 0
    private var attachedRevision = -1L
    private var attachedOwner: RecordingAudioSession? = null

    override fun onCreate() {
        super.onCreate()
        live = this
        val notifications = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Recording playback", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Playback controls while an InkSheets recording is running"
            setSound(null, null)
        })
        media = MediaSession(this, "InkSheets recording").apply {
            @Suppress("DEPRECATION")
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSession.Callback() {
                override fun onPause() { owner?.pause() }
                override fun onStop() { owner?.pause() }
                override fun onPlay() { owner?.play() }
                override fun onSeekTo(pos: Long) { owner?.seek(pos) }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == PAUSE) {
            val session = owner
            if (session == null) stopSelf(startId) else session.pause()
            return START_NOT_STICKY
        }
        val session = owner
        if (session == null) { stopSelf(startId); return START_NOT_STICKY }
        attachedRevision = revision
        attachedOwner = session
        try {
            startForeground(ID, notification(session))
            session.foregroundReady()
        } catch (error: Exception) {
            owner = null
            session.foregroundFailed("Paused: background audio could not start (${error.message})")
            EventLog.warn("sheets", "Recording foreground service: ${error.message}")
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (live === this) {
            live = null
            // A queued stop for the old recording must not cancel an explicit newer play,
            // even when both use the same player object during a rapid file switch.
            if (attachedRevision == revision && owner === attachedOwner) {
                val interrupted = owner
                owner = null
                interrupted?.serviceStopped()
            }
        }
        if (::media.isInitialized) { media.isActive = false; media.release() }
        super.onDestroy()
    }

    private fun notification(session: RecordingAudioSession): Notification {
        media.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, session.recordingName)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "InkSheets")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, session.durationMs).build())
        media.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO)
            .setState(if (session.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                session.positionMs, if (session.playing) session.speed.toFloat() else 0f).build())
        val pause = PendingIntent.getService(this, 0, Intent(this, RecordingPlaybackService::class.java).setAction(PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = launch?.let { PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(session.recordingName)
            .setContentText(session.interruptionReason ?: "InkSheets · recording playback")
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true).setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause,
                if (session.playing) "Pause" else "Stop", pause).build())
            // MediaSession notifications remain usable on Android 13+ without a notification
            // permission prompt. The session also supplies lock-screen/headset pause controls.
            .setStyle(Notification.MediaStyle().setMediaSession(media.sessionToken).setShowActionsInCompactView(0))
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .build()
    }

    private fun refreshNotification(session: RecordingAudioSession) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID, notification(session))
    }

    companion object {
        private const val CHANNEL = "inksheets-recording"
        private const val ID = 4820
        private const val PAUSE = "com.inksheets.recording.PAUSE"
        private val main = Handler(Looper.getMainLooper())
        @Volatile private var owner: RecordingAudioSession? = null
        @Volatile private var live: RecordingPlaybackService? = null
        private var revision = 0L // All mutations occur on the main Looper.
        internal fun isOwner(session: RecordingAudioSession) = owner === session

        internal fun start(context: Context, session: RecordingAudioSession) {
            main.post {
                val previous = owner
                revision++
                owner = session
                if (previous !== session) previous?.pause()
                try {
                    ContextCompat.startForegroundService(context, Intent(context, RecordingPlaybackService::class.java))
                } catch (error: Exception) {
                    if (owner === session) owner = null
                    session.foregroundFailed("Paused: Android could not start background playback (${error.message})")
                }
            }
        }

        internal fun stop(context: Context, session: RecordingAudioSession) {
            main.post {
                if (owner !== session) return@post
                owner = null
                live?.stopForeground(STOP_FOREGROUND_REMOVE)
                // No restart request: completion, explicit pause, unplug and permanent focus
                // loss all end the foreground lifetime without losing the prepared position.
                val stoppedRevision = revision
                main.postDelayed({
                    // Briefly retain only the non-foreground service during a file change.
                    // stopSelf(startId) also respects a newer Android start command in flight.
                    if (owner == null && revision == stoppedRevision) live?.let { it.stopSelf(it.lastStartId) }
                }, 1_000L)
            }
        }

        internal fun refresh(session: RecordingAudioSession) {
            main.post {
                if (owner === session) live?.let { service ->
                    runCatching { service.refreshNotification(session) }
                        .onFailure { EventLog.warn("sheets", "Recording notification: ${it.message}") }
                }
            }
        }
    }
}
