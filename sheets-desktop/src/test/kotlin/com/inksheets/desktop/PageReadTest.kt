package com.inksheets.desktop

import androidx.compose.ui.geometry.Offset
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Just the page being worked on read, in InkSheets as it ships: a long part opened, turned to its
 * third page, "Page 3" tapped on the music strip - that page alone read, its bars numbered as
 * printed - then on to the fourth, "Page 4" offered and tapped, carrying on from the third.
 * Pictures of each step to `sheets-desktop/build/touch/`.
 */
@OptIn(ExperimentalTestApi::class)
class PageReadTest {
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")
    private val shots = File("build/touch").also { it.mkdirs() }

    @After
    fun reset() {
        SimulatedTouch.on = false
        ScoreTools.open = false
        AppFlavor.home = null; AppFlavor.paneOverlay = null; AppFlavor.onHomeShown = null; AppFlavor.onHome = null; AppFlavor.settingsSection = null
        AppFlavor.musicView = false; AppFlavor.fingerPans = false; AppFlavor.alwaysFullscreen = false
    }

    private fun DesktopComposeUiTest.settle(frames: Int = 12) = repeat(frames) { mainClock.advanceTimeBy(16); Thread.sleep(4) }

    @Test
    fun `reading just the page in front`() {
        val src = File(music, "MobileSheets/Be Glad Then, America.pdf")
        assumeTrue(src.isFile)
        val lib = File("build/page-library").apply { deleteRecursively(); mkdirs() }
        val part = File(lib, "Be Glad.pdf").also { src.copyTo(it) }
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
            SimulatedTouch.on = true
            val root = onAllNodes(isRoot()).onFirst()
            fun shot(name: String) { settle(); ImageIO.write(root.captureToImage().toAwtImage(), "png", File(shots, "page-%02d-%s.png".format(++step, name))) }
            fun tapAt(p: Offset) {
                SimulatedTouch.stamp(); root.performMouseInput { moveTo(p); press() }
                settle(3); SimulatedTouch.stamp(); root.performMouseInput { release() }
                settle(40)
            }
            fun tapText(text: String) = tapAt(onAllNodesWithText(text, useUnmergedTree = true).onFirst().fetchSemanticsNode().boundsInRoot.center)
            fun shown(text: String) = onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            fun waitRead() {
                val end = System.currentTimeMillis() + 120_000
                settle(10)
                while (System.currentTimeMillis() < end && com.inksheets.ui.Transcriber.busy != null) settle(10)
                settle(20)
            }
            // To the third page, by taps at the right edge.
            repeat(2) { tapAt(Offset(1600 * 0.93f, 500f)) }
            assertEquals("on the third page", 2, sheets().pageShown.first)
            runOnIdle { ScoreTools.open = true }
            repeat(10) { settle() }
            shot("strip-unread")
            assertTrue("the whole part offered", shown("Read"))
            assertTrue("this page offered", shown("Page 3"))

            tapText("Page 3")
            waitRead()
            shot("page-3-read")
            val one = ScoreTools.scoreHere(sheets())!!
            println("after page 3: read ${one.readPages}, ${one.measures.size} bars, numbers ${one.measures.map { it.number }}, sure ${one.measures.count { it.sure }}")
            assertEquals("just the third page read", listOf(2), one.readPages)
            assertTrue("its bars all on it", one.measures.isNotEmpty() && one.measures.all { it.page == 2 })
            assertTrue("no longer offered on a page read", !shown("Page 3"))

            // On to the fourth: not read, so offered; read, it carries on from the third.
            tapAt(Offset(1600 * 0.93f, 500f))
            assertEquals("on the fourth page", 3, sheets().pageShown.first)
            settle(20)
            shot("page-4-offered")
            assertTrue("the next page offered", shown("Page 4"))
            tapText("Page 4")
            waitRead()
            shot("page-4-read")
            val two = ScoreTools.scoreHere(sheets())!!
            val third = two.measures.filter { it.page == 2 }; val fourth = two.measures.filter { it.page == 3 }
            println("after page 4: read ${two.readPages}, page 4 numbers ${fourth.map { it.number }}")
            assertEquals("both read", listOf(2, 3), two.readPages)
            assertEquals("the third page's bars kept", one.measures.size, third.size)
            assertTrue("the fourth carries on from the third", fourth.isNotEmpty() && fourth.first().number == third.last().number + third.last().bars ||
                fourth.first().number > third.last().number)
        }
    }
}
