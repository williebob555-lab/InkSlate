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
import com.inksheets.ui.ScoreTools
import com.inksheets.ui.Transcriber
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * A page read by an earlier reader (before the reader got better) says so on the music strip and
 * offers reading it again; read again, the prompt goes and the reading stays. Pressed as a finger
 * presses it, in the app as it ships.
 */
@OptIn(ExperimentalTestApi::class)
class OlderReadingTest {
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")

    @After
    fun reset() {
        SimulatedTouch.on = false
        ScoreTools.scoreSource = null
        ScoreTools.open = false
        AppFlavor.onHomeShown = null; AppFlavor.onHome = null; AppFlavor.settingsSection = null
        AppFlavor.musicView = false; AppFlavor.fingerPans = false; AppFlavor.alwaysFullscreen = false
        AppFlavor.home = null; AppFlavor.paneOverlay = null
    }

    private fun DesktopComposeUiTest.settle(frames: Int = 12) = repeat(frames) { mainClock.advanceTimeBy(16); Thread.sleep(4) }

    @Test
    fun `a page an older reader read is offered to be read again`() {
        val src = File(music, "Imported/PEP BAND/Music/Diva/Diva- Trumpet 1.pdf")
        assumeTrue(src.isFile)
        val lib = File("build/older-library").apply { deleteRecursively(); mkdirs() }
        val part = File(lib, src.name).also { src.copyTo(it) }
        val sheets = installInkSheets { it.setPref("sheets_library", lib.absolutePath); it.setPref("sheets_read_music", "true"); it.setPref("sheets_strip_collapsed", "false") }
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
            // Read it, then make the reading an earlier reader's: its folder named for the reader before.
            runOnIdle { Transcriber.read(sheets(), part) { } }
            val end = System.currentTimeMillis() + 180_000
            while (Transcriber.busy != null && System.currentTimeMillis() < end) settle(10)
            val readings = File(lib, ".inksheets/readings")
            val now = readings.listFiles { f -> f.isDirectory && f.name.startsWith("r") && !f.name.startsWith("edits") }!!.single()
            val version = now.name.drop(1).substringBefore('-').toInt()
            assertTrue(now.renameTo(File(readings, "r${version - 1}-" + now.name.substringAfter('-'))))
            settle(20)
            assertEquals("page 1 is an older reader's", setOf(0), Transcriber.olderPages(sheets(), part))
            assertTrue("the older reading still shows", ScoreTools.scoreHere(sheets())?.hasRead(0) == true)
            SimulatedTouch.on = true
            runOnIdle { ScoreTools.open = false }
            settle()
            val root = onAllNodes(isRoot()).onFirst()
            fun tap(text: String) {
                val node = onAllNodesWithText(text, substring = false, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
                    ?: error("no '$text' on screen")
                val c = node.boundsInRoot.center
                SimulatedTouch.stamp(); root.performMouseInput { moveTo(c); press() }
                settle(3); SimulatedTouch.stamp(); root.performMouseInput { release() }
                settle(20)
            }
            tap("Music")
            settle(30)
            assertTrue("it says so", onAllNodesWithText("Older", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            ImageIO.write(root.captureToImage().toAwtImage(), "png", File("build/touch/older-reading.png").also { it.parentFile.mkdirs() })
            tap("Page 1")
            val again = System.currentTimeMillis() + 180_000
            settle(10)
            while (Transcriber.busy != null && System.currentTimeMillis() < again) settle(10)
            settle(30)
            assertEquals("read again: nothing older", emptySet<Int>(), Transcriber.olderPages(sheets(), part))
            assertTrue("and read", ScoreTools.scoreHere(sheets())?.hasRead(0) == true)
            assertTrue("the prompt is gone", onAllNodesWithText("Older", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
            runOnIdle { ScoreTools.close(sheets()) }
        }
    }
}
