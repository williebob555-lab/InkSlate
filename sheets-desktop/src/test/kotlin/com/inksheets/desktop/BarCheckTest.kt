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
 * The bars in doubt gone through by finger, in InkSheets as it ships: the Fix button, the bar lit
 * and three readings of it offered, one picked (kept - the bar no longer in doubt), "None of these"
 * (others offered), Done. Pictures of each step to `sheets-desktop/build/touch/`.
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
        val sheets = installInkSheets { it.setPref("sheets_library", lib.absolutePath) }
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
            tapText("Fix ")
            shot("first-bar")
            assertTrue("going through them", ScoreTools.checking)
            val first = ScoreTools.checkBars.first()
            println("offered for bar $first: " + ScoreTools.offered.joinToString(" | ") { it.changes.joinToString("; ").ifEmpty { "as read" } })
            assertTrue("readings offered", ScoreTools.offered.isNotEmpty())
            // Pick the first: kept, the bar no longer in doubt, on to the next.
            // The first reading's card, by its label.
            tapText(ScoreTools.offered.first().changes.joinToString("; ").ifEmpty { "As read" })
            shot("picked")
            val now = ScoreTools.scoreHere(sheets())!!.measures.first { it.number == first }
            assertTrue("the bar picked is no longer in doubt", now.sure)
            assertEquals("on to the next", 1, ScoreTools.checkAt)
            // None of these: others offered (or on, if there are none).
            tapText("None of these")
            shot("none-of-these")
            println("after none of these: asked again ${ScoreTools.askedAgain}, offered ${ScoreTools.offered.size}, at ${ScoreTools.checkAt}")
            tapText("Done")
            shot("done")
            assertTrue("done", !ScoreTools.checking)
        }
    }
}
