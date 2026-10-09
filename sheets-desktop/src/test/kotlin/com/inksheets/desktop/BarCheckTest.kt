package com.inksheets.desktop

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
import com.inksheets.core.omr.Recognizer
import com.inksheets.core.omr.Score
import com.inksheets.ui.ScoreTools
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * The bars put right by finger, in InkSheets as it ships: Check colours every bar (green, yellow,
 * red), a tap on a red bar opens Fix docked at the foot, three readings offered, one picked (kept -
 * the bar no longer in doubt, on to the next red bar), Back (undone, that bar again), "None of
 * these" (others offered) - the buttons never moving - and Done. Pictures to `sheets-desktop/build/touch/`.
 */
@OptIn(ExperimentalTestApi::class)
class BarCheckTest {
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")
    private val shots = File("build/touch").also { it.mkdirs() }

    @After
    fun reset() {
        SimulatedTouch.on = false
        ScoreTools.scoreSource = null
        ScoreTools.endCheck()
        AppFlavor.home = null; AppFlavor.paneOverlay = null; AppFlavor.onHomeShown = null; AppFlavor.onHome = null; AppFlavor.settingsSection = null
        AppFlavor.musicView = false; AppFlavor.fingerPans = false; AppFlavor.alwaysFullscreen = false
    }

    private fun DesktopComposeUiTest.settle(frames: Int = 12) = repeat(frames) { mainClock.advanceTimeBy(16); Thread.sleep(4) }

    @Test
    fun `going through the bars in doubt`() {
        val src = File(music, "MobileSheets/Chester.pdf")
        assumeTrue(src.isFile)
        val lib = File("build/check-library").apply { deleteRecursively(); mkdirs() }
        val part = File(lib, src.name).also { src.copyTo(it) }
        // Its first page read (as the app would have).
        val (ink, _) = OmrRealPagesTest().renderAt(part, 0)!!
        val reading = Recognizer().read(ink, 0)
        val score = Score(reading.measures, 1, listOf(ink.width))
        ScoreTools.scoreSource = { if (it == part.absolutePath) score else null }
        val sheets = installInkSheets { it.setPref("sheets_library", lib.absolutePath); it.setPref("sheets_strip_collapsed", "false") }
        val home = AppFlavor.home!!
        var openFile: ((File) -> Unit)? = null
        AppFlavor.home = { open, settings -> openFile = open; home(open, settings) }
        var step = 0
        runDesktopComposeUiTest(width = 1600, height = 1000) {
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
            fun shot(name: String) { settle(); ImageIO.write(root.captureToImage().toAwtImage(), "png", File(shots, "check-%02d-%s.png".format(++step, name))) }
            fun tapText(text: String) {
                val c = onAllNodesWithText(text, substring = true, useUnmergedTree = true).onFirst().fetchSemanticsNode().boundsInRoot.center
                SimulatedTouch.stamp(); root.performMouseInput { moveTo(c); press() }
                settle(3); SimulatedTouch.stamp(); root.performMouseInput { release() }
                settle(30)
            }
            shot("tools")
            val doubtful = score.measures.count { !it.sure && it.bars == 1 }
            println("bars in doubt on the page: $doubtful")
            // (Another test may have left the colours on: this starts from the tools as they open.)
            if (ScoreTools.colours) runOnIdle { ScoreTools.choose(ScoreTools.Tool.CHECK) }
            settle(10)
            tapText("Check")
            assertTrue("colours on", ScoreTools.colours)
            shot("colours")
            // A tap on the first red bar (as the page hands a tap on it to the tool).
            val red = score.measures.first { !it.sure && it.bars == 1 }.number
            runOnIdle { ScoreTools.chooseGoOn(true); ScoreTools.fixBar(sheets(), red) }
            settle(30)
            shot("first-bar")
            assertTrue("going through them", ScoreTools.checking)
            assertEquals("the bar tapped is up first", red, ScoreTools.checkBars.first())
            assertEquals("then every other red bar", doubtful, ScoreTools.checkBars.size)
            fun spot(text: String) = onAllNodesWithText(text, substring = false, useUnmergedTree = true).onFirst().fetchSemanticsNode().boundsInRoot
            val buttons = listOf("◀ Back", "None of these", "Skip", "Close Fix")
            val placed = buttons.associateWith { spot(it) }
            println("offered for bar $red: " + ScoreTools.offered.joinToString(" | ") { it.changes.joinToString("; ").ifEmpty { "as read" } })
            assertTrue("readings offered", ScoreTools.offered.isNotEmpty())
            val lookEnd = System.currentTimeMillis() + 90_000
            while (ScoreTools.looking && System.currentTimeMillis() < lookEnd) settle(10)
            shot("looked-again")
            assertTrue("the bar as printed shown", ScoreTools.barPicture != null)
            tapText(ScoreTools.offered.first().changes.joinToString("; ").ifEmpty { "As read" })
            shot("picked")
            assertTrue("the bar picked is no longer in doubt", ScoreTools.scoreHere(sheets())!!.measures.first { it.number == red }.sure)
            assertEquals("on to the next", 1, ScoreTools.checkAt)
            // The buttons are where they were, whatever the bar.
            for (b in buttons) assertEquals("$b has not moved", placed.getValue(b), spot(b))
            // Back: the pick undone, the bar up again.
            tapText("◀ Back")
            shot("back")
            assertEquals("back to the first", 0, ScoreTools.checkAt)
            assertTrue("its fix undone", !ScoreTools.scoreHere(sheets())!!.measures.first { it.number == red }.sure)
            tapText("None of these")
            shot("none-of-these")
            println("after none of these: asked again ${ScoreTools.askedAgain}, offered ${ScoreTools.offered.size}, at ${ScoreTools.checkAt}")
            for (b in buttons) assertEquals("$b has not moved", placed.getValue(b), spot(b))
            tapText("Extra note")
            shot("extra-note")
            tapText("Bar number")
            shot("bar-number")
            tapText("Cancel")
            // Editing a reading by hand: only the editor's own buttons - no Skip or Close Fix under them.
            tapText("Edit")
            shot("edit-by-hand")
            assertTrue("editing", ScoreTools.editing != null)
            assertTrue("no Skip while editing", onAllNodesWithText("Skip", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
            assertTrue("Use this there", onAllNodesWithText("Use this", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            tapText("Cancel")
            assertTrue("back to the readings", ScoreTools.editing == null)
            tapText("Close Fix")
            shot("done")
            assertTrue("done", !ScoreTools.checking)
        }
    }

    @Test
    fun `a bar read as sure said to be wrong is fixed there and then`() {
        val src = File(music, "MobileSheets/Chester.pdf")
        assumeTrue(src.isFile)
        val lib = File("build/wrong-library").apply { deleteRecursively(); mkdirs() }
        val part = File(lib, src.name).also { src.copyTo(it) }
        val (ink, _) = OmrRealPagesTest().renderAt(part, 0)!!
        val reading = Recognizer().read(ink, 0)
        val score = Score(reading.measures, 1, listOf(ink.width))
        val sure = score.measures.first { it.sure && it.bars == 1 }.number
        ScoreTools.scoreSource = { if (it == part.absolutePath) score else null }
        val sheets = installInkSheets { it.setPref("sheets_library", lib.absolutePath); it.setPref("sheets_strip_collapsed", "false") }
        val home = AppFlavor.home!!
        var openFile: ((File) -> Unit)? = null
        AppFlavor.home = { open, settings -> openFile = open; home(open, settings) }
        runDesktopComposeUiTest(width = 1600, height = 1000) {
            mainClock.autoAdvance = false
            setContent { InkSlateTheme { AppRoot(Shortcuts(), NavigationHooks()) } }
            settle(30)
            runOnIdle { openFile!!(part) }
            val until = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < until && sheets().pageShown.second == 0) settle(4)
            repeat(10) { settle() }
            runOnIdle { ScoreTools.open = true; ScoreTools.select(sure..sure) }
            repeat(10) { settle() }
            SimulatedTouch.on = true
            val root = onAllNodes(isRoot()).onFirst()
            fun tapText(text: String) {
                val c = onAllNodesWithText(text, substring = false, useUnmergedTree = true).onFirst().fetchSemanticsNode().boundsInRoot.center
                SimulatedTouch.stamp(); root.performMouseInput { moveTo(c); press() }
                settle(3); SimulatedTouch.stamp(); root.performMouseInput { release() }
                settle(30)
            }
            // Check on, the green bar tapped: Fix for it alone (then back to the page).
            runOnIdle { ScoreTools.toggleCheck(sheets()); ScoreTools.chooseGoOn(false); ScoreTools.fixBar(sheets(), sure) }
            settle(30)
            ImageIO.write(root.captureToImage().toAwtImage(), "png", File(shots, "wrong-01-marked.png"))
            // Just that bar to go through - not the part's every bar in doubt.
            assertEquals("that bar alone", listOf(sure), ScoreTools.checkBars)
            assertTrue("going through the bars", ScoreTools.checking)
            assertEquals("the bar said to be wrong is up first", sure, ScoreTools.barUp(sheets())?.number)
            assertTrue("readings offered", ScoreTools.offered.isNotEmpty())
            runOnIdle { ScoreTools.fix(part.absolutePath, sure, ScoreTools.offered.first().events) }
            settle(10)
            assertTrue("put right, sure again", ScoreTools.scoreHere(sheets())!!.measures.first { it.number == sure }.sure)
            runOnIdle { ScoreTools.endCheck() }
        }
    }
}
