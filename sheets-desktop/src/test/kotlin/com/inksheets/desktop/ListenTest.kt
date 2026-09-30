package com.inksheets.desktop

import com.inkslate.core.Perform
import com.inkslate.core.PerformAction
import com.inksheets.core.AudioTrack
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.core.TimeStretch
import com.inksheets.ui.AudioOut
import com.inksheets.ui.Microphone
import com.inksheets.ui.SheetsPlatform
import com.inksheets.ui.SheetsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The Listen button end to end: a real recording from the music folder, "played in the room" -
 * slower, quieter, with noise - into a microphone that delivers it in real time; the pages turn
 * as the recording's turns come, and Listen switches itself off when the music stops.
 */
class ListenTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val source = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets/Imported/PEP BAND/Music/Sweet Caroline/Sweet Caroline 2009.mp3")

    /** The recording, slowed and with noise, handed over at [speedUp] times real time. */
    private class RoomMic(private val clip: FloatArray, override val sampleRate: Int, private val speed: Double, private val speedUp: Int) : Microphone {
        @Volatile private var thread: Thread? = null
        @Volatile var heardSeconds = 0.0
        override fun start(onChunk: (FloatArray) -> Unit): Boolean {
            thread = Thread {
                val live = TimeStretch(clip, sampleRate).apply { speed = this@RoomMic.speed }
                val random = java.util.Random(3)
                val block = FloatArray(sampleRate / 20)
                var quietBlocks = 0
                while (!Thread.currentThread().isInterrupted) {
                    if (!live.atEnd) live.read(block) else { block.fill(0f); quietBlocks++ }
                    for (i in block.indices) block[i] = block[i] * 0.4f + (random.nextGaussian() * 0.005).toFloat()
                    onChunk(block.copyOf())
                    heardSeconds += 0.05
                    if (quietBlocks > 400) break
                    try { Thread.sleep((50L / speedUp).coerceAtLeast(1)) } catch (e: InterruptedException) { break }
                }
            }.apply { isDaemon = true; start() }
            return true
        }
        override fun stop() { thread?.interrupt() }
    }

    @Test
    fun `listening turns the pages as the recording's turns come, and stops when the music does`() {
        assumeTrue("no recording here", source.isFile)
        val root = tmp.newFolder("Music")
        val rec = File(root, "Band/Sweet Caroline.mp3").apply { parentFile.mkdirs(); source.copyTo(this) }
        File(root, "Band/Sweet Caroline - Alto Sax.pdf").writeText("x")
        val decoder = DesktopSheetsPlatform {}
        // 60 s of the recording, heard at 90%.
        var clip = FloatArray(0); var rate = 44_100
        decoder.decodeAudio(rec) { s, r -> if (clip.size < r * 60) { clip += s; rate = r } }
        clip = clip.copyOf(minOf(clip.size, rate * 60))
        File(root, "Band/Sweet Caroline.mp3").delete()
        val short = File(root, "Band/Sweet Caroline.wav").apply { writeWav(this, clip, rate) }
        val mic = RoomMic(clip, rate, speed = 0.9, speedUp = 8)
        val platform = object : SheetsPlatform {
            private val prefs = HashMap<String, String?>().apply { put("sheets_library", root.absolutePath) }
            override val deviceId = "stand"
            override val startFolder = root
            override fun pref(key: String) = prefs[key]
            override fun setPref(key: String, value: String?) { prefs[key] = value }
            override fun openPart(song: Song, part: Part, file: File) {}
            override fun pageText(file: File, page: Int): String? = null
            override val audioOut: AudioOut? = null
            override val microphone: Microphone = mic
            override fun onMain(block: () -> Unit) = javax.swing.SwingUtilities.invokeLater(block)
            override val deviceName = "Stand"
            override val localFolder: File get() = File(root.parentFile, "local")
            override fun decodeAudio(file: File, onChunk: (FloatArray, Int) -> Unit) = decoder.decodeAudio(file, onChunk)
            override fun log(message: String) = println(message)
        }
        val state = SheetsState(platform)
        state.change {
            editSong(ensureSong("Sweet Caroline").id) {
                parts = listOf(Part(id = com.inksheets.core.Library.partIdFor("Band/Sweet Caroline - Alto Sax.pdf"), file = "Band/Sweet Caroline - Alto Sax.pdf"))
                audio = listOf(AudioTrack(state.relative(short)!!))
            }
        }
        state.listenTurns = true
        state.current = state.library!!.songs.first { it.title == "Sweet Caroline" }
        state.pageShown = 0 to 4
        val turns = java.util.Collections.synchronizedList(ArrayList<Double>())
        Perform.document = { a ->
            if (a == PerformAction.NEXT_PAGE) {
                turns += mic.heardSeconds
                state.pageShown = (state.pageShown.first + 1) to state.pageShown.second
            }
            true
        }
        try {
            javax.swing.SwingUtilities.invokeAndWait { com.inksheets.ui.Listener.start(state) }
            val until = System.currentTimeMillis() + 60_000
            // What the button says as it goes, each change with the time heard.
            val said = ArrayList<Pair<Double, String>>()
            while (System.currentTimeMillis() < until && (com.inksheets.ui.Listener.active || turns.isEmpty())) {
                Thread.sleep(20)
                var words: String? = null
                javax.swing.SwingUtilities.invokeAndWait { words = com.inksheets.ui.Listener.summary(state) }
                val w = words ?: continue
                if (said.lastOrNull()?.second != w) said += mic.heardSeconds to w
            }
            said.forEach { (t, w) -> println("%6.1f s  %s".format(t, w)) }
            println("turns at ${turns.map { "%.1f".format(it) }} s heard; stopped: ${com.inksheets.ui.Listener.status}")
            // Each turn was said before it came - counted down - and said after as done.
            for ((i, t) in turns.withIndex()) {
                assertTrue("no countdown before turn ${i + 1}: $said", said.any { (at, w) -> w.startsWith("Turn in") && at in t - 15.0..t })
                assertTrue("turn ${i + 1} not shown as done: $said", said.any { (at, w) -> w == "Turned to page ${i + 2} (guessed)" && at in t - 0.5..t + 2.0 })
            }
            assertTrue("it never said it hears nothing wrongly: $said", said.none { it.second.startsWith("Hears nothing") })
            // Guessed turns: a quarter, a half and three quarters of the recording, heard at 90%,
            // each a second early.
            assertEquals(3, turns.size)
            val expected = listOf(15.0, 30.0, 45.0).map { it / 0.9 - 1.0 / 0.9 }
            turns.zip(expected).forEach { (got, want) -> assertTrue("turn at $got, wanted about $want", kotlin.math.abs(got - want) < 3.0) }
            assertFalse(com.inksheets.ui.Listener.active)
        } finally {
            javax.swing.SwingUtilities.invokeAndWait { com.inksheets.ui.Listener.stop(state) }
            Perform.document = null
        }
    }

    /** A microphone that opens and gives nothing - an unplugged headset's, a muted interface. */
    private class DeadMic : Microphone {
        @Volatile private var thread: Thread? = null
        override val sampleRate = 48_000
        override fun start(onChunk: (FloatArray) -> Unit): Boolean {
            thread = Thread {
                while (!Thread.currentThread().isInterrupted) {
                    onChunk(FloatArray(480))
                    try { Thread.sleep(10) } catch (e: InterruptedException) { break }
                }
            }.apply { isDaemon = true; start() }
            return true
        }
        override fun stop() { thread?.interrupt() }
        override val inUse = "Microphone (USB Audio Device)"
    }

    /** A dead microphone is said to be one, by name, within a few seconds - never shown as waiting for the music. */
    @Test
    fun `a microphone that hears nothing says so`() {
        assumeTrue("no recording here", source.isFile)
        val root = tmp.newFolder("Music")
        val rec = File(root, "Band/Sweet Caroline.mp3").apply { parentFile.mkdirs(); source.copyTo(this) }
        val decoder = DesktopSheetsPlatform {}
        val platform = object : SheetsPlatform {
            private val prefs = HashMap<String, String?>().apply { put("sheets_library", root.absolutePath) }
            override val deviceId = "stand"
            override val startFolder = root
            override fun pref(key: String) = prefs[key]
            override fun setPref(key: String, value: String?) { prefs[key] = value }
            override fun openPart(song: Song, part: Part, file: File) {}
            override fun pageText(file: File, page: Int): String? = null
            override val audioOut: AudioOut? = null
            override val microphone: Microphone = DeadMic()
            override fun onMain(block: () -> Unit) = javax.swing.SwingUtilities.invokeLater(block)
            override val deviceName = "Stand"
            override val localFolder: File get() = File(root.parentFile, "local")
            override fun decodeAudio(file: File, onChunk: (FloatArray, Int) -> Unit) = decoder.decodeAudio(file, onChunk)
            override fun log(message: String) = println(message)
        }
        val state = SheetsState(platform)
        state.change { editSong(ensureSong("Sweet Caroline").id) { audio = listOf(AudioTrack(state.relative(rec)!!)) } }
        state.listenTurns = true
        state.current = state.library!!.songs.first { it.title == "Sweet Caroline" }
        state.pageShown = 0 to 4
        try {
            javax.swing.SwingUtilities.invokeAndWait { com.inksheets.ui.Listener.start(state) }
            val t0 = System.currentTimeMillis()
            var words: String? = null
            while (System.currentTimeMillis() - t0 < 20_000) {
                Thread.sleep(100)
                javax.swing.SwingUtilities.invokeAndWait { words = com.inksheets.ui.Listener.summary(state) }
                if (words?.startsWith("Hears nothing") == true) break
            }
            println("after ${(System.currentTimeMillis() - t0) / 1000.0} s: $words")
            assertEquals("Hears nothing yet - Microphone (USB Audio Device)", words)
        } finally {
            javax.swing.SwingUtilities.invokeAndWait { com.inksheets.ui.Listener.stop(state) }
        }
    }

    private fun writeWav(file: File, samples: FloatArray, rate: Int) {
        val data = java.nio.ByteBuffer.allocate(samples.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        samples.forEach { data.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
        val format = javax.sound.sampled.AudioFormat(rate.toFloat(), 16, 1, true, false)
        javax.sound.sampled.AudioSystem.write(
            javax.sound.sampled.AudioInputStream(java.io.ByteArrayInputStream(data.array()), format, samples.size.toLong()),
            javax.sound.sampled.AudioFileFormat.Type.WAVE, file
        )
    }
}
