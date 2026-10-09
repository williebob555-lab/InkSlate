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
 * The redraw over the print in InkSheets as it ships: a part read by the app itself (Transcriber,
 * as the Read button does), the clean view turned on, and the screen pictured - to see that what
 * is drawn sits on the print. -Dinksheets.omr.file=pdf (24K Magic's bass by default).
 */
@OptIn(ExperimentalTestApi::class)
class RedrawInAppTest {
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")
    private val shots = File("build/touch").also { it.mkdirs() }

    @After
    fun reset() {
        ScoreTools.showUnderlay(false); ScoreTools.open = false
        AppFlavor.home = null; AppFlavor.paneOverlay = null; AppFlavor.onHomeShown = null; AppFlavor.onHome = null; AppFlavor.settingsSection = null
        AppFlavor.musicView = false; AppFlavor.fingerPans = false; AppFlavor.alwaysFullscreen = false
    }

    private fun DesktopComposeUiTest.settle(frames: Int = 12) = repeat(frames) { mainClock.advanceTimeBy(16); Thread.sleep(4) }

    @Test
    fun `the redraw sits on the print in the app`() {
        val src = File(System.getProperty("inksheets.omr.file") ?: File(music, "Imported/PEP BAND/Music/24K Magic/24K Magic - Electric Bass.pdf").path)
        assumeTrue(src.isFile)
        val lib = File("build/redraw-library").apply { deleteRecursively(); mkdirs() }
        val part = File(lib, src.name).also { src.copyTo(it) }
        val sheets = installInkSheets { it.setPref("sheets_library", lib.absolutePath) }
        val home = AppFlavor.home!!
        var openFile: ((File) -> Unit)? = null
        AppFlavor.home = { open, settings -> openFile = open; home(open, settings) }
        runDesktopComposeUiTest(width = 1400, height = 1000) {
            mainClock.autoAdvance = false
            setContent { InkSlateTheme { AppRoot(Shortcuts(), NavigationHooks()) } }
            settle(30)
            runOnIdle { openFile!!(part) }
            val until = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < until && sheets().pageShown.second == 0) settle(4)
            repeat(10) { settle() }
            var done = false
            runOnIdle { com.inksheets.ui.Transcriber.read(sheets(), part) { done = true } }
            val end = System.currentTimeMillis() + 180_000
            while (!done && System.currentTimeMillis() < end) settle(10)
            runOnIdle { ScoreTools.showUnderlay(true) }
            repeat(20) { settle() }
            val root = onAllNodes(isRoot()).onFirst()
            ImageIO.write(root.captureToImage().toAwtImage(), "png", File(shots, "redraw-in-app.png"))
            // And the whole part cleaned (Clean on the music strip), as the app draws it.
            runOnIdle { ScoreTools.showUnderlay(false); ScoreTools.cleanWhole(sheets(), true) }
            repeat(20) { settle() }
            ImageIO.write(root.captureToImage().toAwtImage(), "png", File(shots, "redraw-in-app-clean.png"))
            runOnIdle { ScoreTools.cleanWhole(sheets(), false) }
            val score = ScoreTools.scoreHere(sheets())
            println("REDRAW-APP read: ${score?.measures?.size} bars, widths ${score?.pageWidths}, read pages ${score?.readPages}, first box ${score?.measures?.firstOrNull()?.box}, last box ${score?.measures?.lastOrNull()?.box}")
            val peek = sheets().platform.peek(part)!!
            val img = peek.render(0, 3200)!!
            val src2 = com.inkslate.desktop.DesktopSources.open(part, detached = true)!!
            println("REDRAW-APP render at 3200: ${img.width}x${img.height}; page as the canvas has it: ${src2.pageDim(0)}")
            src2.close(); peek.close()
            // The bars are placed by the width each page was read at: the staves end near the page's
            // right edge (0.957 across on this part), as printed - read at one width and placed by
            // another, they end well short or past it (was 0.75 when a small page came back narrower).
            val reach = score!!.measures.maxOf { it.box.right }.toFloat() / score.pageWidths[0]
            println("REDRAW-APP staves end ${"%.3f".format(reach)} of the page across")
            org.junit.Assert.assertTrue("staves end where printed: $reach", reach in 0.9f..0.99f)
        }
    }
}
