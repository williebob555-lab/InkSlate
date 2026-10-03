package com.inksheets.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.inksheets.core.RemoteLink
import com.inksheets.core.watch.Cue
import com.inksheets.core.watch.FlickModel
import com.inksheets.core.watch.FlickTrainer
import com.inksheets.core.watch.Sample
import com.inksheets.core.watch.Session
import com.inksheets.core.watch.WatchWire
import java.io.File

/**
 * The watch's link to the phone: messages each way, through the watch's own pairing (on Android,
 * the Wear OS data layer). Null where there is none.
 */
interface WatchLink {
    /** Hear what the watch says: a path of [WatchWire] and its bytes. Off the UI thread. */
    fun start(onMessage: (path: String, data: ByteArray) -> Unit)
    fun stop()
    /** Send to the watch; [done] hears whether it reached one. */
    fun send(path: String, data: ByteArray, done: (Boolean) -> Unit = {})
    /** The watches connected now, by name; empty when none. Off the UI thread. */
    fun watches(onResult: (List<String>) -> Unit)
}

/**
 * Turning pages with a flick of the watch (experimental). The watch listens and says "next" or
 * "back"; this device turns the page - its own, or, while it is the remote of a tablet, the
 * tablet's - and tells the watch whether it did, so a turn that did not happen is felt.
 *
 * Calibrations are kept by name (an instrument: a bass arm and a trombone arm move nothing alike),
 * each from every recording made for it; the one in use is sent to the watch.
 */
class WatchFlicks(private val state: SheetsState) {
    private val link: WatchLink? get() = state.platform.watch
    val available: Boolean get() = link != null

    var on by mutableStateOf(state.platform.pref(K_ON) == "true")
        private set

    /** What the watch last said of itself, and when; null till it has. */
    var hello by mutableStateOf<WatchWire.Hello?>(null)
        private set
    var helloAt by mutableStateOf(0L)
        private set
    /** Watches connected, by name. */
    var watches by mutableStateOf(emptyList<String>())
        private set
    var lookedAt by mutableStateOf(0L)
        private set

    /** The last flick: which way, when, and what came of it. */
    data class Flick(val next: Boolean, val at: Long, val result: String)
    var lastFlick by mutableStateOf<Flick?>(null)
        private set

    /** The calibrations kept, by name; and the one in use. */
    val calibrations = mutableStateListOf<String>()
    var active by mutableStateOf(state.platform.pref(K_ACTIVE))
        internal set
    /** The result of the last training of each calibration, for showing how it stands. */
    var report by mutableStateOf<FlickTrainer.Report?>(null)
        internal set

    private var started = false

    // ---- the watch listening while this device is in use, and only then ---------------------
    //
    // While this device is the remote of a tablet, or shows music itself, the watch is told to
    // listen - and told again each minute. A watch that hears nothing for a few minutes stops by
    // itself: the app closed, the remote let go, or the phone out of reach. Stopped by hand (here
    // or on the watch), it stays stopped till the next time this device is in use.
    private val appRun = System.currentTimeMillis()
    private var useCount = 0
    private var inUse = false
    private var beatAt = 0L
    private var pausedUse = -1
    private val session: String get() = "$appRun-$useCount"

    private fun inUseNow(): Boolean = state.remote.connected || state.currentPath != null

    /** Every few seconds: start the watch listening as this device comes into use, and keep it so. */
    private fun tick() {
        if (!on || link == null) return
        val now = inUseNow()
        if (now && !inUse) { useCount++; beatAt = 0L }
        inUse = now
        if (!now || pausedUse == useCount) return
        if (System.currentTimeMillis() - beatAt >= BEAT_MS) {
            beatAt = System.currentTimeMillis()
            link?.send(WatchWire.LISTEN, "on:$session".toByteArray())
        }
    }

    init {
        listCalibrations()
        if (on) start()
        java.util.Timer("watch-beat", true).scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() = state.platform.onMain { tick() }
        }, 2_000, 5_000)
    }

    fun turn(value: Boolean) {
        on = value
        state.platform.setPref(K_ON, value.toString())
        if (value) start() else stop()
    }

    private fun start() {
        val l = link ?: return
        if (started) return
        started = true
        runCatching { l.start { path, data -> state.platform.onMain { heard(path, data) } } }
            .onFailure { started = false; state.platform.log("Watch: could not start - ${it.message}") }
        look()
    }

    private fun stop() {
        if (!started) return
        started = false
        runCatching { link?.stop() }
    }

    /** Ask which watches are there and what they are doing. */
    fun look() {
        val l = link ?: return
        l.watches { names -> state.platform.onMain { watches = names; lookedAt = System.currentTimeMillis() } }
        l.send(WatchWire.PING, ByteArray(0))
    }

    /** Start or stop the watch listening, by hand: stopped, it stays so till this device is next in use. */
    fun listen(value: Boolean) {
        pausedUse = if (value) -1 else useCount
        beatAt = System.currentTimeMillis()
        link?.send(WatchWire.LISTEN, (if (value) "on:$session" else "off").toByteArray()) { look() }
    }

    private fun heard(path: String, data: ByteArray) {
        when (path) {
            WatchWire.HELLO -> WatchWire.Hello.decode(String(data))?.let { h ->
                hello = h; helloAt = System.currentTimeMillis()
                // A watch without the calibration in use (new, or reinstalled) is given it.
                val model = activeModel()
                if (model != null && h.model != model.name && !h.calibrating) sendModel(model)
            }
            WatchWire.FLICK -> {
                val text = String(data)
                val next = text.startsWith("next")
                val seq = text.substringAfter(':', "0")
                flicked(next) { why -> link?.send(WatchWire.TURNED, "$seq:${why ?: "ok"}".toByteArray()) }
            }
            WatchWire.SAMPLES -> calibration?.take(data)
        }
    }

    /**
     * Turn the page for a flick: on the device this one is the remote of, or here. [answer] hears
     * null once it is done, or why it was not - in time for the watch to buzz.
     */
    private fun flicked(next: Boolean, answer: (String?) -> Unit) {
        val action = if (next) "NEXT_PAGE" else "PREVIOUS_PAGE"
        val word = if (next) "Next page" else "Page back"
        val remote = state.remote
        val target = remote.target
        fun done(result: String, why: String?) {
            lastFlick = Flick(next, System.currentTimeMillis(), result)
            state.platform.log("Watch: $word - $result")
            answer(why)
        }
        if (target != null) {
            if (!remote.connected) return done("not sent: not connected to ${target.name}", "Not connected to ${target.name}")
            val name = "Watch: $word"
            remote.send(RemoteLink.Command(action = action), name)
            // The tablet says it got it within moments; the watch waits a second and a half for the answer.
            val sentAt = System.currentTimeMillis()
            fun check() {
                val p = remote.lastPress
                when {
                    p?.name == name && p.got == true -> done("turned on ${target.name}", null)
                    p?.name == name && p.got == false -> done("${target.name} did not get it", "${target.name} did not get it")
                    System.currentTimeMillis() - sentAt > 1100 -> done("sent to ${target.name}", null)
                    else -> java.util.Timer("watch-turn", true).schedule(object : java.util.TimerTask() {
                        override fun run() = state.platform.onMain { check() }
                    }, 50)
                }
            }
            check()
            return
        }
        if (state.currentPath == null) return done("nothing open to turn", "No music open on the phone")
        remote.performHere(RemoteLink.Command(action = action))
        done("turned here", null)
    }

    // ---- calibrations ----------------------------------------------------------------------

    private val folder: File get() = File(state.platform.localFolder, "watch")

    private fun listCalibrations() {
        calibrations.clear()
        calibrations += folder.listFiles()?.filter { it.isDirectory && it.listFiles()?.any { f -> f.extension == "csv" } == true }
            ?.map { it.name }?.sorted().orEmpty()
    }

    private fun sessionsOf(name: String): List<Session> =
        File(folder, safe(name)).listFiles()?.filter { it.extension == "csv" }?.sortedBy { it.name }
            ?.mapNotNull { runCatching { WatchWire.readSession(it.readText()) }.getOrNull() }.orEmpty()

    fun activeModel(): FlickModel? = FlickModel.decode(state.platform.pref(K_MODEL))

    private fun sendModel(model: FlickModel?) {
        link?.send(WatchWire.MODEL, model?.encode()?.toByteArray() ?: ByteArray(0)) { look() }
    }

    /** Use [name]'s calibration: trained again from all its recordings, and sent to the watch. */
    fun use(name: String) {
        Thread({
            val r = FlickTrainer.train(sessionsOf(name), name)
            state.platform.onMain {
                report = r
                active = name
                state.platform.setPref(K_ACTIVE, name)
                state.platform.setPref(K_MODEL, r.model?.encode())
                sendModel(r.model)
            }
        }, "watch-train").apply { isDaemon = true; start() }
    }

    fun delete(name: String) {
        File(folder, safe(name)).deleteRecursively()
        listCalibrations()
        if (active == name) {
            active = null; report = null
            state.platform.setPref(K_ACTIVE, null)
            state.platform.setPref(K_MODEL, null)
            sendModel(null)
        }
    }

    // ---- calibrating -------------------------------------------------------------------------

    /** One calibration going on: the cues it gives, and the readings coming back. */
    class Calibration internal constructor(private val owner: WatchFlicks, val name: String) {
        private val link get() = owner.link
        private val state get() = owner.state
        /** "starting", "playing", "next", "back", "learning", "done", "failed". */
        var phase by mutableStateOf("starting")
        /** Seconds till the next cue (or the end), for the screen. */
        var secondsLeft by mutableStateOf(0)
        var progress by mutableStateOf(0f)
        var problem by mutableStateOf<String?>(null)
        var result by mutableStateOf<FlickTrainer.Report?>(null)
        /** Readings arrived so far. */
        var heard by mutableStateOf(0)

        private val samples = ArrayList<Sample>()
        private val cues = ArrayList<Cue>()
        private val plan: List<Pair<Long, String>>
        private val total: Long
        private var startedAt = 0L
        private var timer: java.util.Timer? = null
        private var gaps = 0

        init {
            // Playing first, then the cues at uneven gaps, then playing to finish.
            val r = java.util.Random()
            val kinds = listOf("next", "next", "back", "next", "back", "next", "next", "back", "next", "back")
            var t = FIRST_CUE_MS
            plan = kinds.map { k -> (t to k).also { t += 8_000L + r.nextInt(6_000) } }
            total = plan.last().first + TAIL_MS
        }

        internal fun take(bytes: ByteArray) {
            val (s, c) = WatchWire.unpackBatch(bytes) ?: return
            synchronized(samples) {
                val last = samples.lastOrNull()?.t
                if (last != null && s.isNotEmpty() && s.first().t - last > 100) gaps++
                samples += s; cues += c
            }
            heard += s.size
            if (phase == "starting" && s.isNotEmpty()) begin()
        }

        fun start() {
            val l = link ?: return fail("No watch link on this device.")
            l.send(WatchWire.CALIBRATE, "start".toByteArray()) { ok ->
                state.platform.onMain { if (!ok) fail("The watch is not in reach. Check it is connected to this phone.") }
            }
            // No readings soon: the watch app is not open, or not installed.
            schedule(6_000) { if (phase == "starting") fail("Nothing came from the watch. Open InkSheets on the watch, then start again.") }
        }

        private fun begin() {
            startedAt = System.currentTimeMillis()
            phase = "playing"
            link?.send(WatchWire.CUE, "play".toByteArray())
            val t = java.util.Timer("watch-calibration", true)
            timer = t
            fun ms(at: Long) = (at * timeScale).toLong()
            for ((at, kind) in plan) {
                t.schedule(task { if (phase != "failed") { phase = kind; link?.send(WatchWire.CUE, kind.toByteArray()) } }, ms(at))
                t.schedule(task { if (phase == kind) { phase = "playing"; link?.send(WatchWire.CUE, "play".toByteArray()) } }, ms(at + CUE_SHOWN_MS))
            }
            t.schedule(task { finish() }, ms(total))
            t.scheduleAtFixedRate(task {
                val elapsed = ((System.currentTimeMillis() - startedAt) / timeScale).toLong()
                progress = (elapsed.toFloat() / total).coerceIn(0f, 1f)
                val nextAt = plan.firstOrNull { it.first > elapsed }?.first ?: total
                secondsLeft = ((nextAt - elapsed + 999) / 1000).toInt()
            }, 0, 250)
        }

        private fun finish() {
            if (phase == "failed") return
            phase = "learning"
            timer?.cancel()
            link?.send(WatchWire.CUE, "done".toByteArray())
            // The last readings are still on their way.
            schedule(800) {
                link?.send(WatchWire.CALIBRATE, "stop".toByteArray())
                val session = synchronized(samples) { Session(samples.sortedBy { it.t }, cues.sortedBy { it.t }) }
                if (session.cues.size < plan.size / 2) return@schedule fail(
                    "Only ${session.cues.size} of ${plan.size} cues reached the watch - it may have lost its link to the phone. Try again."
                )
                Thread({
                    val text = WatchWire.writeSession(session)
                    val stamp = java.text.SimpleDateFormat("yyyy-MM-dd-HHmmss", java.util.Locale.US).format(java.util.Date())
                    runCatching { File(owner.folder, safe(name)).apply { mkdirs() }.resolve("$stamp.csv").writeText(text) }
                    // A copy in the library (Training/Watch): it syncs, so it can be looked at on the laptop.
                    runCatching {
                        state.root?.let { root ->
                            File(root, "${com.inksheets.core.LibraryScan.TRAINING}/Watch").apply { mkdirs() }
                                .resolve("${safe(state.platform.deviceId)}-${safe(name)}-$stamp.csv").writeText(text)
                        }
                    }
                    val r = FlickTrainer.train(owner.sessionsOf(name), name)
                    state.platform.onMain {
                        owner.listCalibrations()
                        result = r
                        owner.report = r
                        if (gaps > 0) state.platform.log("Watch: calibration readings had $gaps gaps")
                        r.model?.let { model ->
                            owner.active = name
                            state.platform.setPref(K_ACTIVE, name)
                            state.platform.setPref(K_MODEL, model.encode())
                            owner.sendModel(model)
                        }
                        phase = "done"
                    }
                }, "watch-train").apply { isDaemon = true; start() }
            }
        }

        fun cancel() {
            timer?.cancel()
            if (phase != "done") {
                link?.send(WatchWire.CUE, "done".toByteArray())
                link?.send(WatchWire.CALIBRATE, "stop".toByteArray())
            }
            phase = "failed"
            owner.calibration = null
        }

        private fun fail(why: String) {
            timer?.cancel()
            link?.send(WatchWire.CALIBRATE, "stop".toByteArray())
            problem = why
            phase = "failed"
        }

        private fun task(block: () -> Unit) = object : java.util.TimerTask() {
            override fun run() = state.platform.onMain(block)
        }

        private fun schedule(ms: Long, block: () -> Unit) = java.util.Timer("watch-wait", true).schedule(task(block), ms)
    }

    var calibration by mutableStateOf<Calibration?>(null)
        internal set

    /** Calibrate [name]: a new one, or more recordings for one there is. */
    fun calibrate(name: String): Calibration {
        calibration?.cancel()
        return Calibration(this, name.trim().ifEmpty { "My instrument" }).also { calibration = it; it.start() }
    }

    fun closeCalibration() { calibration?.takeIf { it.phase != "done" && it.phase != "failed" }?.cancel(); calibration = null }

    companion object {
        private const val K_ON = "sheets_watch_on"
        private const val K_ACTIVE = "sheets_watch_active"
        private const val K_MODEL = "sheets_watch_model"
        /** Playing before the first cue. */
        const val FIRST_CUE_MS = 30_000L
        /** Playing after the last. */
        const val TAIL_MS = 10_000L
        /** How long a cue stays up. */
        const val CUE_SHOWN_MS = 2_500L
        /** How often the watch is told to keep listening; it stops after [WatchWire.QUIET_MS] without. */
        const val BEAT_MS = 60_000L
        /** How much of real time a calibration's second takes: 1, but less in tests, which cannot play for three minutes. */
        var timeScale = 1.0

        private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim().ifEmpty { "calibration" }
    }
}
