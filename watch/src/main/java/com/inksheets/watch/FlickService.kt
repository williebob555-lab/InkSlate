package com.inksheets.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.Wearable
import com.inksheets.core.watch.Cue
import com.inksheets.core.watch.FlickDetector
import com.inksheets.core.watch.FlickModel
import com.inksheets.core.watch.Sample
import com.inksheets.core.watch.WatchWire
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * Listening for flicks: the gyroscope read a hundred times a second, with the screen off, for as
 * long as the player wants - a foreground service holding the processor awake. Each flick goes to
 * the phone, which turns the page (here, or on the tablet it is the remote of); the watch ticks
 * when it sends one, and buzzes long when the phone says the page did not turn.
 *
 * Calibrating, it sends the phone every reading instead, with the cues the phone gave noted among
 * them, and turns no pages.
 */
class FlickService : Service(), SensorEventListener {
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var wake: PowerManager.WakeLock? = null
    private var detector: FlickDetector? = null
    private var ax = 0f; private var ay = 0f; private var az = 0f
    /** Sensor clock to elapsedRealtime, in ns: they are the same on most watches, but not promised. */
    private var clockOffset: Long? = null
    private val batch = ArrayList<Sample>()
    private var seq = 0
    private val waiting = HashMap<Int, Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("flicks").apply { start() }
        handler = Handler(thread.looper)
        ServiceCompat.startForeground(this, NOTE_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "inksheets:flicks").apply {
            setReferenceCounted(false)
            // Released when listening stops; four hours at most, should it never be told to.
            acquire(4 * 60 * 60 * 1000L)
        }
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(this, it, SAMPLE_US, 0, handler) }
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SAMPLE_US, 0, handler) }
        running = this
        WatchState.phoneAt = System.currentTimeMillis()
        getSystemService(NotificationManager::class.java).cancel(TAP_ID)
        handler.postDelayed(quiet, QUIET_CHECK_MS)
        loadModel(this)
        WatchState.listening = true
        WatchState.changed()
        sayHello(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    /** Nothing from the phone for a few minutes: it is not in use (closed, let go, out of reach) - stop. */
    private val quiet: Runnable = object : Runnable {
        override fun run() {
            if (!WatchState.calibrating && System.currentTimeMillis() - WatchState.phoneAt > WatchWire.QUIET_MS) {
                Log.i(TAG, "No word from the phone - stopping")
                buzz(this@FlickService, longArrayOf(0, 60, 100, 60))
                stopSelf()
                return
            }
            handler.postDelayed(this, QUIET_CHECK_MS)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(this)
        wake?.takeIf { it.isHeld }?.release()
        thread.quitSafely()
        running = null
        WatchState.listening = false
        WatchState.calibrating = false
        WatchState.cue = null
        WatchState.changed()
        sayHello(this)
        super.onDestroy()
    }

    /** The flicks to listen for, as the phone last sent them. */
    fun useModel(model: FlickModel?) = handler.post { detector = model?.let(::FlickDetector) }

    /** Calibrating: send every reading to the phone, turn nothing. */
    fun calibrate(on: Boolean) = handler.post {
        WatchState.calibrating = on
        batch.clear()
        pendingCues.clear()
        if (!on) WatchState.cue = null
        WatchState.changed()
        sayHello(this)
    }

    private val pendingCues = ArrayList<Cue>()

    /** A calibration's cue: noted where it falls among the readings, and buzzed. */
    fun cue(kind: String) {
        val at = SystemClock.elapsedRealtime()
        handler.post {
            when (kind) {
                "next" -> { pendingCues += Cue(at, true); buzz(this, longArrayOf(0, 350)) }
                "back" -> { pendingCues += Cue(at, false); buzz(this, longArrayOf(0, 150, 120, 150)) }
                "done" -> buzz(this, longArrayOf(0, 80, 80, 80, 80, 80))
            }
            WatchState.cue = kind
            WatchState.changed()
        }
    }

    /** The phone's answer to flick [n]: turned, or why not. */
    fun turned(n: Int, ok: Boolean, why: String?) = handler.post {
        waiting.remove(n) ?: return@post
        if (!ok) failed(why ?: "The page did not turn")
    }

    override fun onSensorChanged(e: SensorEvent) {
        val offset = clockOffset ?: (SystemClock.elapsedRealtimeNanos() - e.timestamp).let { if (abs(it) < 1_000_000_000L) 0L else it }.also { clockOffset = it }
        val t = (e.timestamp + offset) / 1_000_000L
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> { ax = e.values[0]; ay = e.values[1]; az = e.values[2] }
            Sensor.TYPE_GYROSCOPE -> {
                val gx = e.values[0]; val gy = e.values[1]; val gz = e.values[2]
                if (WatchState.calibrating) {
                    batch += Sample(t, gx, gy, gz, ax, ay, az)
                    if (batch.size >= BATCH) {
                        val bytes = WatchWire.packBatch(batch.toList(), pendingCues.toList())
                        batch.clear(); pendingCues.clear()
                        send(this, WatchWire.SAMPLES, bytes)
                    }
                    return
                }
                val d = detector ?: return
                when (d.feed(t, gx, gy, gz)) {
                    FlickDetector.NEXT -> flicked(true)
                    FlickDetector.BACK -> flicked(false)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun flicked(next: Boolean) {
        val n = ++seq
        waiting[n] = SystemClock.elapsedRealtime()
        buzz(this, longArrayOf(0, 30))
        WatchState.lastFlick = if (next) "Next ▶" else "◀ Back"
        WatchState.lastFlickAt = System.currentTimeMillis()
        WatchState.changed()
        send(this, WatchWire.FLICK, "${if (next) "next" else "back"}:$n".toByteArray()) { sent ->
            if (!sent) handler.post { if (waiting.remove(n) != null) failed("The phone is not in reach") }
        }
        // No answer at all: the phone did not get it, or InkSheets is not running there.
        handler.postDelayed({ if (waiting.remove(n) != null) failed("No answer from the phone - is InkSheets open on it?") }, ANSWER_MS)
    }

    private fun failed(why: String) {
        buzz(this, longArrayOf(0, 700))
        WatchState.problem = why
        WatchState.changed()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Listening for flicks", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, WatchActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("InkSheets")
            .setContentText("Listening for flicks")
            .setSmallIcon(R.drawable.ic_flick)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val TAG = "InkSheetsWatch"
        private const val CHANNEL = "flicks"
        private const val NOTE_ID = 1
        /** 100 readings a second. */
        private const val SAMPLE_US = 10_000
        /** Readings a calibration message carries: a fifth of a second. */
        private const val BATCH = 20
        private const val ANSWER_MS = 1500L
        private const val PREFS = "watch"
        private const val K_MODEL = "model"
        private const val K_STOPPED = "stopped_session"
        private const val TAP_ID = 2
        private const val QUIET_CHECK_MS = 30_000L

        @Volatile var running: FlickService? = null
            private set

        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, FlickService::class.java)); true
        }.getOrElse { Log.w(TAG, "Could not start listening: ${it.message}"); false }

        fun stop(context: Context) { context.stopService(Intent(context, FlickService::class.java)) }

        /** Stopped by hand on the watch: not started by the phone again in the same session. */
        fun stopByHand(context: Context) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(K_STOPPED, WatchState.session).apply()
            stop(context)
        }

        fun stoppedSession(context: Context): String? = context.getSharedPreferences(PREFS, MODE_PRIVATE).getString(K_STOPPED, null)

        /**
         * The phone is in use: listen. The system may refuse a start from the background (when the
         * app has not been let off battery saving); then a buzz and a notification to tap instead.
         */
        fun startFromPhone(context: Context) {
            if (start(context)) return
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("start", "Start listening", NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(
                context, 1,
                Intent(context, WatchActivity::class.java).putExtra(WatchActivity.LISTEN, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            runCatching {
                nm.notify(TAP_ID, Notification.Builder(context, "start")
                    .setContentTitle("InkSheets")
                    .setContentText("Tap to listen for flicks")
                    .setSmallIcon(R.drawable.ic_flick)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build())
            }
            buzz(context, longArrayOf(0, 200, 150, 200))
        }

        fun saveModel(context: Context, text: String?) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(K_MODEL, text).apply()
            loadModel(context)
        }

        fun loadModel(context: Context): FlickModel? {
            val model = FlickModel.decode(context.getSharedPreferences(PREFS, MODE_PRIVATE).getString(K_MODEL, null))
            WatchState.model = model?.name?.ifEmpty { "calibrated" }
            WatchState.changed()
            running?.useModel(model)
            return model
        }

        fun version(context: Context): String =
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

        fun sayHello(context: Context) {
            val hello = WatchWire.Hello(running != null, WatchState.model, version(context), WatchState.calibrating)
            send(context, WatchWire.HELLO, hello.encode().toByteArray())
        }

        /** Send to the phone (every node in reach - there is one); [done] hears whether it went. */
        fun send(context: Context, path: String, data: ByteArray, done: ((Boolean) -> Unit)? = null) {
            val app = context.applicationContext
            Wearable.getNodeClient(app).connectedNodes
                .addOnSuccessListener { nodes ->
                    if (nodes.isEmpty()) { done?.invoke(false); return@addOnSuccessListener }
                    var left = nodes.size
                    var any = false
                    for (node in nodes) Wearable.getMessageClient(app).sendMessage(node.id, path, data)
                        .addOnCompleteListener { r -> if (r.isSuccessful) any = true; if (--left == 0) done?.invoke(any) }
                }
                .addOnFailureListener { done?.invoke(false) }
        }

        fun buzz(context: Context, pattern: LongArray) {
            val v = context.getSystemService(Vibrator::class.java) ?: return
            runCatching { v.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
        }
    }
}

/** What the watch's screen shows, kept wherever it is changed (the service, the phone's messages). */
object WatchState {
    @Volatile var listening = false
    @Volatile var calibrating = false
    /** The calibration's prompt now: "play", "next", "back", "done"; null when not calibrating. */
    @Volatile var cue: String? = null
    @Volatile var model: String? = null
    @Volatile var lastFlick: String? = null
    @Volatile var lastFlickAt = 0L
    @Volatile var problem: String? = null
    /** When the phone last said anything, and the session it said to listen for. */
    @Volatile var phoneAt = 0L
    @Volatile var session: String? = null
    val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    fun changed() { main.post { listeners.forEach { it() } } }
}
