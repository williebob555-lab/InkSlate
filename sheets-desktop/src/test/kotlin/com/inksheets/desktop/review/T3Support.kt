package com.inksheets.desktop.review

import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.desktop.DesktopSheetsPlatform
import com.inksheets.ui.AudioOut
import com.inksheets.ui.AudioPlayer
import com.inksheets.ui.Microphone
import com.inksheets.ui.SheetsPlatform
import com.inksheets.ui.WatchLink
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** T3 (practice tools and everything that listens): shared fakes. Nothing here touches a real device. */
internal object T3 {
    val out = File("build/t3out").apply { mkdirs() }

    /** Where the real library's files were copied for these tests (read only originals stay put). */
    val lib = File("build/t3lib")

    /** Write [text] under build/t3out and print it. */
    private val started = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun say(file: String, text: String) {
        println(text)
        synchronized(this) {
            if (started.add(file)) File(out, file).writeText("")
            File(out, file).appendText(text + "\n")
        }
    }

    fun clean(file: String) { File(out, file).delete() }

    /** Copy the copied-library folders into a fresh temp library (so a test can change it). */
    fun library(into: File, vararg folders: String): File {
        for (f in folders) File(lib, f).copyRecursively(File(into, f), overwrite = true)
        return into
    }

    fun flushMain() { javax.swing.SwingUtilities.invokeAndWait { } }
}

/**
 * A microphone behaving like the desktop's [com.inksheets.desktop.DesktopSheetsPlatform] one: ONE input
 * line, so [start] stops whoever was listening before, and [stop] stops them all. Delivers [clip]
 * (looped silence when null) in real time / [speedUp], and keeps a log of what was asked of it.
 */
internal open class T3Mic(
    override val sampleRate: Int = 48_000,
    private val clip: FloatArray? = null,
    private val gain: Float = 0.4f,
    private val speedUp: Int = 1,
    private val silent: Boolean = false,
    private val cutAfterClip: Boolean = false
) : Microphone {
    val log = CopyOnWriteArrayList<String>()
    @Volatile private var generation = 0
    @Volatile private var thread: Thread? = null
    @Volatile var chunksDelivered = 0L
    @Volatile var heardSeconds = 0.0
    override var inUse: String? = "Fake microphone"
    override val devices: List<String> = listOf("Fake microphone", "Fake headset (USB)")
    override var device: String? = null

    val running: Boolean get() = thread?.isAlive == true

    override fun start(onChunk: (FloatArray) -> Unit): Boolean {
        stop()   // like the real one: a second listener silences the first
        log += "start"
        val mine = ++generation
        thread = Thread({
            val block = FloatArray(sampleRate / 20)
            var at = 0
            val random = java.util.Random(5)
            while (generation == mine) {
                if (clip != null && !silent) {
                    for (i in block.indices) {
                        block[i] = if (at < clip.size) clip[at] * gain + (random.nextGaussian() * 0.004).toFloat() else (random.nextGaussian() * 0.004).toFloat()
                        at++
                    }
                } else java.util.Arrays.fill(block, 0f)
                if (cutAfterClip && clip != null && at > clip.size + sampleRate) break
                chunksDelivered++
                heardSeconds += 0.05
                runCatching { onChunk(block.copyOf()) }
                try { Thread.sleep((50L / speedUp).coerceAtLeast(1)) } catch (e: InterruptedException) { break }
            }
        }, "t3-mic").apply { isDaemon = true; start() }
        return true
    }

    override fun stop() {
        log += "stop"
        generation++
        thread?.interrupt()
    }
}

/** An output that is only pulled by the test: [render] returns what the speakers would have got. */
internal class T3Out(override val sampleRate: Int = 48_000, private val pump: Boolean = false) : AudioOut {
    @Volatile var fill: ((FloatArray) -> Unit)? = null
    val log = CopyOnWriteArrayList<String>()
    @Volatile var heard = FloatArray(0)
    @Volatile private var generation = 0
    override fun start(fill: (FloatArray) -> Unit) {
        this.fill = fill; log += "start"
        if (pump) {
            val mine = ++generation
            Thread({
                val block = FloatArray(480)       // 10 ms of sound at a time, in real time, like a sound card
                var kept = FloatArray(0)
                while (generation == mine) {
                    fill(block)
                    if (kept.size < sampleRate * 20) kept += block.copyOf()
                    heard = kept
                    try { Thread.sleep(10) } catch (e: InterruptedException) { break }
                }
            }, "t3-out").apply { isDaemon = true; start() }
        }
    }
    override fun stop() { fill = null; generation++; log += "stop" }
    fun render(seconds: Double): FloatArray {
        val f = fill ?: return FloatArray(0)
        val total = (seconds * sampleRate).toInt()
        val all = FloatArray(total)
        var at = 0
        val block = FloatArray(512)
        while (at < total) {
            f(block)
            val n = minOf(block.size, total - at)
            System.arraycopy(block, 0, all, at, n)
            at += n
        }
        return all
    }
}

/** A recording player with a log: position runs with the wall clock at [speed] while playing. */
internal class T3Player(private val lengths: (File) -> Long = { 180_000L }) : AudioPlayer {
    val log = CopyOnWriteArrayList<String>()
    @Volatile var loaded: File? = null
    @Volatile private var startedAt = 0L
    @Volatile private var base = 0L
    @Volatile override var playing = false
        private set
    override var speed: Double = 1.0
        set(v) { rebase(); field = v; log += "speed $v" }
    override var pitch: Int = 0
        set(v) { field = v; log += "pitch $v" }
    override var volume: Double = 1.0
        set(v) { field = v; log += "volume $v" }
    var loop: Pair<Long?, Long?> = null to null

    private fun rebase() { base = positionMs; startedAt = System.currentTimeMillis() }
    override fun load(file: File): Boolean { log += "load ${file.name}"; if (playing) log += "(was playing)"; playing = false; loaded = file; base = 0; return true }
    override fun play() { log += "play ${loaded?.name}"; rebase(); playing = true }
    override fun pause() { log += "pause"; base = positionMs; playing = false }
    override fun seek(ms: Long) { log += "seek $ms"; base = ms; startedAt = System.currentTimeMillis() }
    override val positionMs: Long get() = if (playing) base + ((System.currentTimeMillis() - startedAt) * speed).toLong() else base
    override val durationMs: Long get() = loaded?.let(lengths) ?: 0L
    override fun setLoop(startMs: Long?, endMs: Long?) { loop = startMs to endMs; log += "loop $startMs..$endMs" }
    override fun release() { log += "release" }
}

/** A made-up system around the real file decoder, for state-level tests. */
internal class T3Platform(
    val root: File,
    val mic: Microphone? = T3Mic(),
    val out: AudioOut? = null,
    val player: AudioPlayer? = null,
    private val watchLink: WatchLink? = null,
    val mesh: com.inksheets.ui.MeshRadio? = null,
    private val synchronous: Boolean = false
) : SheetsPlatform {
    private val decoder by lazy { DesktopSheetsPlatform {} }
    val prefs = HashMap<String, String?>().apply { put("sheets_library", root.absolutePath) }
    val events = CopyOnWriteArrayList<String>()
    override val deviceId = "t3"
    override val deviceName = "T3 stand"
    override val startFolder = root
    override fun pref(key: String) = prefs[key]
    override fun setPref(key: String, value: String?) { prefs[key] = value }
    override fun openPart(song: Song, part: Part, file: File) {}
    override fun pageText(file: File, page: Int): String? = null
    override val audioOut: AudioOut? get() = out
    override val microphone: Microphone? get() = mic
    override fun onMain(block: () -> Unit) = if (synchronous) block() else javax.swing.SwingUtilities.invokeLater(block)
    override val localFolder: File get() = File(root.parentFile, "local-t3-" + root.name)
    override fun audioPlayer(): AudioPlayer? = player
    override fun decodeAudio(file: File, onChunk: (FloatArray, Int) -> Unit) = decoder.decodeAudio(file, onChunk)
    override fun log(message: String) { events += message; println("[log] $message") }
    override val watch: WatchLink? get() = watchLink
    override fun meshRadio() = mesh
}
