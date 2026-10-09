package com.inksheets.desktop

import com.inkslate.core.RenderGate
import com.inkslate.desktop.DesktopSources
import com.inksheets.core.omr.Scores
import com.inksheets.ui.Transcriber
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * "I'm just sitting here waiting for a page to render. It's been like 30 seconds." The page on the
 * screen is drawn before any reading goes on: with a whole-file read running in the background on a
 * real multi-page part, a page asked for by the screen comes back within 1.5x its idle time + 300 ms,
 * and the reading it leaves behind is exactly the one made with nothing else going on.
 * (Also prints the same measurement with the gate off: what the screen waited before.)
 */
class ScreenFirstTest {
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")

    @After
    fun reset() { RenderGate.enabled = true }

    private fun part(): File? {
        val dir = File(music, "Imported/PEP BAND/Music/Sweet Caroline")
        val names = listOf("Sweet Caroline - Full Score.pdf", "SweetC - Trumpet 1.pdf", "SweetC - Clarinet.pdf", "SweetC - Tuba.pdf")
        return names.map { File(dir, it) }.filter { it.isFile }.maxByOrNull { f ->
            DesktopSources.open(f)?.use { it.pageCount } ?: 0
        }
    }

    private fun median(v: List<Long>) = v.sorted()[v.size / 2]

    /** Pages drawn for the screen (each at a width not drawn before: never from the page cache), the time each took. */
    private fun screenDraws(file: File, pages: Int, firstWidth: Int, gapMs: Long): List<Long> =
        DesktopSources.open(file)!!.use { src ->
            (0 until pages).map { i ->
                Thread.sleep(gapMs)
                val t = System.nanoTime()
                val img = src.render(i % src.pageCount, firstWidth + i * 7)
                assertTrue(img != null)
                (System.nanoTime() - t) / 1_000_000
            }
        }

    @Test
    fun `the page on the screen is drawn first while a whole part is being read`() {
        val src = part()
        assumeTrue(src != null)
        val lib = File("build/screen-first-library").apply { deleteRecursively(); mkdirs() }
        val file = src!!.copyTo(File(lib, "part.pdf"))
        val sheets = installInkSheets { it.setPref("sheets_library", lib.absolutePath) }
        val state = sheets()
        val pages = DesktopSources.open(file)!!.use { it.pageCount }
        println("part: ${src.name}, $pages pages")
        assumeTrue(pages >= 3)

        // The reading with nothing else going on: the one to match.
        val t0 = System.currentTimeMillis()
        val quiet = Transcriber.readQuietly(state, file)!!
        println("quiet read: ${System.currentTimeMillis() - t0} ms")
        val expected = Scores.encode(quiet)

        // The screen's page, idle (warm: the renderer has been used already).
        screenDraws(file, 2, 1500, 0)
        val idle = median(screenDraws(file, 5, 1600, 100))
        println("idle screen draw: $idle ms")
        val bound = idle * 3 / 2 + 300

        fun readWhileDrawing(label: String, gate: Boolean): Pair<List<Long>, String> {
            RenderGate.enabled = gate
            var done = false
            val finished = java.util.concurrent.CountDownLatch(1)
            javax.swing.SwingUtilities.invokeAndWait { Transcriber.read(state, file, again = true) { done = true; finished.countDown() } }
            // The reading under way: past its "getting ready".
            val until = System.currentTimeMillis() + 30_000
            while (Transcriber.progress == null && !done && System.currentTimeMillis() < until) Thread.sleep(20)
            // Draws asked for the way a person turns pages: one, a look, the next.
            val draws = screenDraws(file, 8, if (gate) 2000 else 2100, 150)
            println("$label: screen draws while reading (ms): $draws   [bound $bound]")
            assertTrue("the read did not finish", finished.await(300, java.util.concurrent.TimeUnit.SECONDS))
            val got = Transcriber.cached(state, file)!!
            return draws to Scores.encode(got)
        }

        val (off, offScore) = readWhileDrawing("gate off (before)", false)
        val (on, onScore) = readWhileDrawing("gate on  (after) ", true)
        println("summary: idle $idle ms; before: max ${off.max()} ms, median ${median(off)} ms; after: max ${on.max()} ms, median ${median(on)} ms")

        assertEquals("the reading is not the one made with nothing else going on (gate on)", expected, onScore)
        assertEquals("the reading is not the one made with nothing else going on (gate off)", expected, offScore)
        assertTrue("a page for the screen took ${on.max()} ms with a read going on; idle $idle ms, bound $bound ms", on.max() <= bound)
    }
}
