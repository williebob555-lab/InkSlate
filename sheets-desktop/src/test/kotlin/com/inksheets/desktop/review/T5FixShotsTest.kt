package com.inksheets.desktop.review

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.inkslate.desktop.AppFlavor
import com.inkslate.desktop.AppRoot
import com.inkslate.desktop.InkSlateTheme
import com.inkslate.desktop.NavigationHooks
import com.inkslate.desktop.Shortcuts
import com.inkslate.desktop.SimulatedTouch
import com.inksheets.core.omr.Score
import com.inksheets.desktop.installInkSheets
import com.inksheets.ui.ScoreTools
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * T5 round 1: Check / Fix / Clean in the real app at laptop, phone, phone landscape and tablet
 * sizes, on a scan (Chester) and a born-digital part (Sweet Caroline, Trumpet 1). Records where
 * Fix's fixed buttons are in each mode. Pictures to build/t5/ui/.
 */
@OptIn(ExperimentalTestApi::class)
class T5FixShotsTest {
    private val shots = File("build/t5/ui").also { it.mkdirs() }

    @After
    fun reset() {
        SimulatedTouch.on = false
        ScoreTools.scoreSource = null
        ScoreTools.endCheck()
        AppFlavor.home = null; AppFlavor.paneOverlay = null; AppFlavor.onHomeShown = null; AppFlavor.onHome = null; AppFlavor.settingsSection = null
        AppFlavor.musicView = false; AppFlavor.fingerPans = false; AppFlavor.alwaysFullscreen = false
    }

    private fun DesktopComposeUiTest.settle(frames: Int = 12) = repeat(frames) { mainClock.advanceTimeBy(16); Thread.sleep(4) }

    private fun run(tag: String, w: Int, h: Int, pdf: File) {
        assumeTrue(pdf.isFile)
        val reading = T5Data.readingOf(pdf)
        assumeTrue("no reading of ${pdf.name}", reading != null)
        val score = T5Data.load(reading!!)!!
        val lib = File("build/t5-lib-$tag").apply { deleteRecursively(); mkdirs() }
        val part = File(lib, pdf.name).also { pdf.copyTo(it) }
        ScoreTools.scoreSource = { if (it == part.absolutePath) score else null }
        val sheets = installInkSheets { it.setPref("sheets_library", lib.absolutePath) }
        val home = AppFlavor.home!!
        var openFile: ((File) -> Unit)? = null
        AppFlavor.home = { open, settings -> openFile = open; home(open, settings) }
        val reds = score.measures.filter { !it.sure && it.bars == 1 }.map { it.number }
        println("T5 FIX [$tag] ${pdf.name}: ${score.measures.size} bars, ${reds.size} not sure; certainties ${score.measures.groupingBy { it.certainty }.eachCount()}")
        var step = 0
        runDesktopComposeUiTest(width = w, height = h) {
            mainClock.autoAdvance = false
            setContent { InkSlateTheme { AppRoot(Shortcuts(), NavigationHooks()) } }
            settle(30)
            runOnIdle { openFile!!(part) }
            val until = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < until && sheets().pageShown.second == 0) settle(4)
            repeat(10) { settle() }
            runOnIdle { ScoreTools.open = true }
            repeat(10) { settle() }
            SimulatedTouch.on = true
            val root = onAllNodes(isRoot()).onFirst()
            fun shot(name: String) { settle(); ImageIO.write(root.captureToImage().toAwtImage(), "png", File(shots, "$tag-%02d-%s.png".format(++step, name))) }
            fun nodeBounds(text: String, exact: Boolean = false): Rect? = runCatching {
                onAllNodesWithText(text, substring = !exact, useUnmergedTree = true).onFirst().fetchSemanticsNode().boundsInRoot
            }.getOrNull()
            fun tapText(text: String, exact: Boolean = false): Boolean {
                val b = nodeBounds(text, exact) ?: return false
                SimulatedTouch.stamp(); root.performMouseInput { moveTo(b.center); press() }
                settle(3); SimulatedTouch.stamp(); root.performMouseInput { release() }
                settle(30)
                return true
            }
            val fixed = listOf("Back", "None of these", "Skip", "Done")
            val wrongRow = listOf("Wrong:", "Pitch", "Length", "Extra note", "Missing note", "Rests", "Grace note", "Clef, key, time", "Bar number", "Not one bar")
            val first = HashMap<String, Rect?>()
            fun record(mode: String) {
                val parts = ArrayList<String>()
                for (n in fixed + wrongRow + listOf("Next red bar", "Close")) {
                    val b = nodeBounds(n, exact = true) ?: nodeBounds(n)
                    val key = n
                    val moved = if (n in fixed && mode != "first") { val f = first[n]; if (f == null && b == null) "" else if (f != null && b != null && f == b) "" else " MOVED/GONE" } else ""
                    if (mode == "first") first[n] = b
                    val inside = b == null || (b.left >= -1 && b.top >= -1 && b.right <= w + 1 && b.bottom <= h + 1)
                    parts += "$key=" + (b?.let { "(%.0f,%.0f %.0fx%.0f)".format(it.left, it.top, it.width, it.height) } ?: "-") + moved + (if (!inside) " OFFSCREEN" else "")
                }
                println("T5 FIX [$tag] $mode: " + parts.joinToString("  "))
            }
            shot("tools")
            if (ScoreTools.colours) runOnIdle { ScoreTools.choose(ScoreTools.Tool.CHECK) }
            settle(10)
            val checkTapped = tapText("Check")
            println("T5 FIX [$tag] Check button found=$checkTapped colours=${ScoreTools.colours}")
            if (!ScoreTools.colours) runOnIdle { ScoreTools.choose(ScoreTools.Tool.CHECK) }
            shot("check-colours")
            if (reds.isEmpty()) { println("T5 FIX [$tag] no red bars to fix"); return@runDesktopComposeUiTest }
            runOnIdle { ScoreTools.chooseGoOn(true); ScoreTools.fixBar(sheets(), reds.first()) }
            settle(40)
            val lookEnd = System.currentTimeMillis() + 60_000
            while (ScoreTools.looking && System.currentTimeMillis() < lookEnd) settle(10)
            shot("fix-first-bar")
            record("first")
            println("T5 FIX [$tag] offered ${ScoreTools.offered.size}: " + ScoreTools.offered.joinToString(" | ") { it.changes.joinToString("; ").ifEmpty { "as read" } })
            // Skip to the second red bar: buttons must not move.
            tapText("Skip", exact = true); shot("fix-second-bar"); record("second bar")
            // Wrong: row modes.
            for (what in listOf("Extra note", "Missing note", "Pitch", "Rests", "Grace note")) {
                if (tapText(what, exact = true)) { shot("wrong-" + what.replace(' ', '-')); record("Wrong:$what") }
                if (ScoreTools.editing != null) { tapText("Cancel", exact = true); settle(10) }
            }
            if (tapText("Clef, key, time", exact = true)) { shot("clef-key-time"); record("sig"); tapText("Cancel", exact = true) }
            if (tapText("Bar number", exact = true)) { shot("bar-number"); record("number"); tapText("Cancel", exact = true) }
            // Pick: Edit mode from the first offered reading.
            val editTapped = tapText("Edit", exact = true)
            if (editTapped) { shot("edit"); record("edit mode"); tapText("Cancel", exact = true) }
            // Back past the first bar.
            val before = ScoreTools.checkAt
            repeat(3) { tapText("Back", exact = true) }
            println("T5 FIX [$tag] Back x3 from bar index $before -> ${ScoreTools.checkAt}, checking=${ScoreTools.checking}")
            tapText("Done", exact = true)
            println("T5 FIX [$tag] after Done checking=${ScoreTools.checking}")
            // Clean: the whole part over the page.
            runOnIdle { ScoreTools.choose(ScoreTools.Tool.CHECK) }
            settle(5)
            runOnIdle { ScoreTools.cleanWhole(sheets(), true) }
            settle(60)
            shot("clean-whole")
            println("T5 FIX [$tag] cleaned bars=${ScoreTools.cleanCount(sheets())} of ${score.measures.size} wholeFlag=${ScoreTools.cleanedWhole(sheets())}")
            runOnIdle { ScoreTools.undoClean(sheets()) }
            settle(30)
            shot("clean-undone")
            println("T5 FIX [$tag] after undo cleaned bars=${ScoreTools.cleanCount(sheets())}")
        }
        val edits = File(lib, ".inksheets/readings").walkTopDown().filter { it.isFile && it.parentFile.name == "edits" }.map { it.name + "=" + it.readText().take(60) }.toList()
        println("T5 FIX [$tag] edit files written: $edits")
    }

    private val chester get() = T5Data.pdfs().first { it.name == "Time Warp - Trombone 1.pdf" }
    private val sweet get() = T5Data.pdfs().first { it.name == "Diva- Trumpet 1.pdf" }

    @Test fun `laptop scan`() = run("laptop-scan", 1366, 768, chester)
    @Test fun `laptop digital`() = run("laptop-digital", 1366, 768, sweet)
    @Test fun `phone scan`() = run("phone-scan", 390, 844, chester)
    @Test fun `phone digital`() = run("phone-digital", 390, 844, sweet)
    @Test fun `phone landscape scan`() = run("land-scan", 844, 390, chester)
    @Test fun `tablet scan`() = run("tablet-scan", 1280, 800, chester)
    @Test fun `tablet digital`() = run("tablet-digital", 1280, 800, sweet)
}
