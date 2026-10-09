package com.inksheets.desktop.review

import com.inksheets.core.Library
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.ui.AudioOut
import com.inksheets.ui.Microphone
import com.inksheets.ui.SheetsPlatform
import com.inksheets.ui.SheetsState
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** A pretend device for two-devices-in-one-JVM tests. Prefs are kept, so a "restart" can reuse the platform. */
class T4Platform(root: File, override val deviceName: String = "Test stand", val out: AudioOut? = null) : SheetsPlatform {
    val prefs = HashMap<String, String?>().apply { put("sheets_library", root.absolutePath) }
    val opened = CopyOnWriteArrayList<String>()
    val logs = CopyOnWriteArrayList<String>()
    override val deviceId = deviceName.replace(' ', '-') + "-" + root.name
    override val startFolder = root
    override fun pref(key: String) = prefs[key]
    override fun setPref(key: String, value: String?) { prefs[key] = value }
    override fun openPart(song: Song, part: Part, file: File) { opened += part.file }
    override fun pageText(file: File, page: Int): String? = null
    override val audioOut: AudioOut? = out
    override val microphone: Microphone? = null
    var input: com.inksheets.core.ControllerInput? = null
    override val controllers: com.inksheets.core.ControllerInput? get() = input
    override fun onMain(block: () -> Unit) = javax.swing.SwingUtilities.invokeLater(block)
    override fun log(message: String) { logs += message }
    override val localFolder: File get() = File(startFolder.parentFile, "local-$deviceId")
}

/** Sound out pulled in real time, thrown away. */
class T4Out : AudioOut {
    override val sampleRate = 48_000
    @Volatile private var thread: Thread? = null
    override fun start(fill: (FloatArray) -> Unit) {
        stop()
        thread = Thread {
            val buf = FloatArray(480)
            while (!Thread.currentThread().isInterrupted) { fill(buf); try { Thread.sleep(10) } catch (e: InterruptedException) { break } }
        }.apply { isDaemon = true; start() }
    }
    override fun stop() { thread?.interrupt(); thread = null }
}

/** A controller that says what it is told: a pedal, switch or fader, plugged in. */
class T4Input(val devices: List<String> = listOf("T4 Pedal")) : com.inksheets.core.ControllerInput {
    @Volatile var send: ((com.inksheets.core.ControlEvent) -> Unit)? = null
    @Volatile var started = 0
    override fun start(onEvent: (com.inksheets.core.ControlEvent) -> Unit, onDevices: (List<String>) -> Unit) { send = onEvent; started++; onDevices(devices) }
    override fun stop() { send = null }
    fun say(vararg e: com.inksheets.core.ControlEvent, gapMs: Long = 5) { for (x in e) { send!!(x); Thread.sleep(gapMs) }; Thread.sleep(60) }
    fun cc(n: Int, v: Int, ch: Int = 1) = com.inksheets.core.ControlEvent("T4 Pedal", com.inksheets.core.ControlEvent.CC, ch, n, v)
    fun note(n: Int, v: Int, ch: Int = 1) = com.inksheets.core.ControlEvent("T4 Pedal", com.inksheets.core.ControlEvent.NOTE, ch, n, v)
}

object T4Lib {
    val titles = listOf(
        "Hey Baby", "Crab Rave", "September", "Sweet Caroline", "Tom Sawyer", "Groove Is in the Heart", "Hot Hot Hot",
        "Boots on the Ground", "Seven Nation Army", "Uptown Funk", "Land of a Thousand Dances", "Shout", "Iron Man",
        "Zombie Nation", "Mr. Brightside", "Hey Song", "24K Magic", "Fight Song", "Take On Me"
    )

    /** A small library (stub files) with a setlist of [titles]; ids as the scan makes them. */
    fun make(root: File, name: String = "Test stand", platform: T4Platform = T4Platform(root, name), titles: List<String> = T4Lib.titles, extra: Int = 0): Pair<SheetsState, T4Platform> {
        val all = titles + (1..extra).map { "Extra song %03d".format(it) }
        all.forEach { File(root, "Band/$it - Alto Sax.pdf").apply { parentFile.mkdirs(); if (!exists()) writeText(name) } }
        val state = SheetsState(platform)
        state.change {
            for (t in all) editSong(ensureSong(t).id) {
                parts = listOf(Part(id = Library.partIdFor("Band/$t - Alto Sax.pdf"), file = "Band/$t - Alto Sax.pdf", instrument = "alto-sax"))
            }
        }
        state.chooseProfile("alto-sax")
        return state to platform
    }
}
