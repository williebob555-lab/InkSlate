package com.inksheets.desktop

import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.ui.AudioOut
import com.inksheets.ui.Microphone
import com.inksheets.ui.SheetsPlatform
import com.inksheets.ui.SheetsState
import com.inksheets.ui.WatchFlicks
import com.inksheets.ui.WatchLink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Calibrating with a watch, start to end, against a made-up watch on a made-up arm: the cues reach
 * it, its readings come back, the flicks are learned and sent to it - and a flick it sends then
 * gets its answer.
 */
class WatchFlicksTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Platform(root: File, val fake: WatchLink) : SheetsPlatform {
        private val prefs = HashMap<String, String?>().apply { put("sheets_library", root.absolutePath) }
        override val deviceName = "Phone"
        override val deviceId = "phone"
        override val startFolder = root
        override fun pref(key: String) = prefs[key]
        override fun setPref(key: String, value: String?) { prefs[key] = value }
        override fun openPart(song: Song, part: Part, file: File) {}
        override fun pageText(file: File, page: Int): String? = null
        override val audioOut: AudioOut? = null
        override val microphone: Microphone? = null
        override fun onMain(block: () -> Unit) = javax.swing.SwingUtilities.invokeLater(block)
        override val localFolder: File get() = File(startFolder.parentFile, "local-$deviceId")
        override val watch: WatchLink get() = fake
    }

    @After
    fun normalTime() { WatchFlicks.timeScale = 1.0 }

    private fun waitFor(what: String, ms: Long = 20_000, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!ok()) { check(System.currentTimeMillis() < until) { "Timed out waiting for $what" }; Thread.sleep(20) }
    }

    @Test
    fun `a calibration cues the watch, learns from what it sends, and gives the watch the result`() {
        WatchFlicks.timeScale = 0.02
        val fake = FakeWatch(0.02)
        val root = tmp.newFolder("Music")
        val state = SheetsState(Platform(root, fake))
        val watch = state.watch
        watch.turn(true)
        waitFor("the watch to answer") { watch.hello != null }

        val c = watch.calibrate("Bass")
        waitFor("the calibration to finish") { c.phase == "done" || c.phase == "failed" }
        assertEquals(c.problem, "done", c.phase)
        val r = assertNotNull(c.result).let { c.result!! }
        println("Watch calibration: right ${r.right}, wrong ${r.wrong}, missed ${r.missed}, false ${r.falseTurns}, " +
            "playing ${r.nextPlaying} slowest ${r.nextSlowest} margin ${r.margin}, problem ${r.problem}")
        assertNotNull(r.model)
        assertEquals(0, r.falseTurns)
        assertTrue("right ${r.right}", r.right >= 9)
        assertEquals(listOf("next", "back"), fake.cuesHeard.filter { it == "next" || it == "back" }.distinct().sorted().reversed())
        waitFor("the watch to have it") { fake.model?.name == "Bass" }
        assertEquals(listOf("Bass"), watch.calibrations.toList())
        assertEquals("Bass", watch.active)
        // Kept here, and a copy in the library to look at elsewhere.
        assertEquals(1, File(state.platform.localFolder, "watch/Bass").listFiles()!!.size)
        assertEquals(1, File(root, "Training/Watch").listFiles()!!.size)

        // A flick with nothing open: the watch is told so, and buzzes.
        fake.flick(true, 1)
        waitFor("the answer") { fake.turned.isNotEmpty() }
        assertEquals("1:No music open on the phone", fake.turned.single())
        waitFor("the last flick shown") { watch.lastFlick != null }
        assertEquals(true, watch.lastFlick!!.next)

        // Something open: turned here.
        javax.swing.SwingUtilities.invokeAndWait { state.currentPath = "x.pdf" }
        fake.flick(false, 2)
        waitFor("the second answer") { fake.turned.size == 2 }
        assertEquals("2:ok", fake.turned[1])
    }

    @Test
    fun `the watch is told to listen while the phone is in use, and stopped by hand it stays stopped`() {
        val fake = FakeWatch(1.0)
        val state = SheetsState(Platform(tmp.newFolder("Music"), fake))
        state.watch.turn(true)
        Thread.sleep(2_600)
        assertTrue("nothing open, nothing to listen for: ${fake.listens}", fake.listens.isEmpty())
        javax.swing.SwingUtilities.invokeAndWait { state.currentPath = "x.pdf"; state.homeInFront = false }
        waitFor("the watch told to listen", 10_000) { fake.listens.isNotEmpty() }
        assertTrue(fake.listens.first(), fake.listens.first().startsWith("on:"))
        // Home in front: no part to turn - the watch is told to stop at once, not left for minutes.
        javax.swing.SwingUtilities.invokeAndWait { state.homeInFront = true }
        waitFor("the watch told to stop on Home", 10_000) { fake.listens.last() == "off" }
        javax.swing.SwingUtilities.invokeAndWait { state.homeInFront = false }
        waitFor("the watch told to listen again", 10_000) { fake.listens.last().startsWith("on:") }
        javax.swing.SwingUtilities.invokeAndWait { state.watch.listen(false) }
        waitFor("the watch told to stop", 5_000) { fake.listens.last() == "off" }
        Thread.sleep(6_000)
        assertEquals("off", fake.listens.last())
    }

    @Test
    fun `the calibration in use follows the instrument of the part showing`() {
        WatchFlicks.timeScale = 0.02
        val fake = FakeWatch(0.02)
        val state = SheetsState(Platform(tmp.newFolder("Music"), fake))
        val watch = state.watch
        watch.turn(true)
        for ((name, ids) in listOf("Trombone" to listOf("trombone", "bass-trombone"), "Bass" to listOf("bass-guitar", "string-bass"))) {
            javax.swing.SwingUtilities.invokeAndWait { watch.setInstruments(name, ids) }
            val c = watch.calibrate(name)
            waitFor("$name calibrated") { c.phase == "done" || c.phase == "failed" }
            assertEquals(c.problem, "done", c.phase)
            javax.swing.SwingUtilities.invokeAndWait { watch.closeCalibration() }
        }
        waitFor("the watch to have Bass") { fake.model?.name == "Bass" }
        fun show(instrument: String) = javax.swing.SwingUtilities.invokeAndWait {
            state.current = Song("s-$instrument", "A song", parts = listOf(Part(file = "$instrument.pdf", instrument = instrument)))
        }
        show("trombone")
        waitFor("the watch to switch to Trombone", 15_000) { fake.model?.name == "Trombone" && watch.active == "Trombone" }
        show("string-bass")
        waitFor("the watch to switch to Bass", 15_000) { fake.model?.name == "Bass" }
        // A part with no calibration of its own: the one in use stays.
        show("trumpet")
        Thread.sleep(6_000)
        assertEquals("Bass", fake.model?.name)
        assertEquals("Trombone", watch.instrumentsOf("Trombone").let { if ("trombone" in it) "Trombone" else "?" })
    }

    @Test
    fun `a calibration with no watch app answering says what to do`() {
        WatchFlicks.timeScale = 0.02
        val silent = object : WatchLink {
            override fun start(onMessage: (path: String, data: ByteArray) -> Unit) {}
            override fun stop() {}
            override fun send(path: String, data: ByteArray, done: (Boolean) -> Unit) = done(true)
            override fun watches(onResult: (List<String>) -> Unit) = onResult(listOf("Galaxy Watch7"))
        }
        val state = SheetsState(Platform(tmp.newFolder("Music"), silent))
        state.watch.turn(true)
        val c = state.watch.calibrate("Trombone")
        waitFor("it to give up", 10_000) { c.phase == "failed" }
        assertTrue(c.problem!!, c.problem!!.contains("Open InkSheets on the watch"))
    }
}
