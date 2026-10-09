package com.inksheets.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * InkSheets as it runs, played with fingers the way Windows delivers them to a desktop program
 * (see [SimulatedTouch]): a real part opened, the buttons brought and used, pages turned by taps and
 * flicks, a finger dragged over the music with the tools out (never a mark), a pinch and a two-finger
 * pan, and a tap after them. Pictures of every step to `sheets-desktop/build/touch/`.
 */
@OptIn(ExperimentalTestApi::class)
class SheetsTouchTest {
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")
    private val shots = File("build/touch").also { it.mkdirs() }

    @After
    fun reset() {
        SimulatedTouch.on = false
        AppFlavor.onHomeShown = null; AppFlavor.onHome = null; AppFlavor.settingsSection = null
        AppFlavor.musicView = false; AppFlavor.fingerPans = false; AppFlavor.alwaysFullscreen = false
        AppFlavor.home = null; AppFlavor.paneOverlay = null
        com.inkslate.core.Perform.onPosition = null
    }

    private fun DesktopComposeUiTest.settle(frames: Int = 12) = repeat(frames) { mainClock.advanceTimeBy(16); Thread.sleep(4) }

    private fun DesktopComposeUiTest.image(): java.awt.image.BufferedImage {
        settle()
        return onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage()
    }

    @Test
    fun `a player's fingers on InkSheets`() {
        // A copy of a real part (the real library's is only read).
        val src = File(music, "Imported/PEP BAND/Music/Party Medley/Party Medley - Trumpet in Bb 1.pdf")
        assumeTrue(src.isFile)
        val lib = File("build/touch-library").apply { deleteRecursively(); mkdirs() }
        val part = File(lib, src.name).also { src.copyTo(it) }

        // The app exactly as it ships (see installInkSheets), its library pointed at the copy.
        val sheets = installInkSheets { it.setPref("sheets_library", lib.absolutePath); it.setPref("sheets_strip_collapsed", "false") }
        val home = AppFlavor.home!!
        var openFile: ((File) -> Unit)? = null
        AppFlavor.home = { open, settings -> openFile = open; home(open, settings) }
        // The page in front, as InkSheets itself knows it.
        fun page() = sheets().pageShown.first
        val w = 1600f; val h = 1000f
        var step = 0

        runDesktopComposeUiTest(width = w.toInt(), height = h.toInt()) {
            mainClock.autoAdvance = false
            setContent { InkSlateTheme { AppRoot(Shortcuts(), NavigationHooks()) } }
            settle(30)
            runOnIdle { openFile!!(part) }
            val until = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < until && sheets().pageShown.second == 0) settle(4)
            repeat(20) { settle() }
            SimulatedTouch.on = true
            val root = onAllNodes(isRoot()).onFirst()
            fun shot(name: String) = ImageIO.write(image(), "png", File(shots, "sheets-%02d-%s.png".format(++step, name)))
            fun at(p: Offset) { SimulatedTouch.stamp(); root.performMouseInput { moveTo(p) } }
            fun tapAt(p: Offset) {
                at(p); SimulatedTouch.stamp(); root.performMouseInput { press() }
                settle(3)
                SimulatedTouch.stamp(); root.performMouseInput { release() }
                settle(40)
            }
            fun tap(fx: Float, fy: Float) = tapAt(Offset(w * fx, h * fy))
            fun tapNode(n: SemanticsNodeInteraction) = tapAt(n.fetchSemanticsNode().boundsInRoot.center)
            fun drag(fx1: Float, fy1: Float, fx2: Float, fy2: Float, frames: Int) {
                at(Offset(w * fx1, h * fy1)); SimulatedTouch.stamp(); root.performMouseInput { press() }
                for (i in 1..frames) { at(Offset(w * (fx1 + (fx2 - fx1) * i / frames), h * (fy1 + (fy2 - fy1) * i / frames))); settle(1) }
                SimulatedTouch.stamp(); root.performMouseInput { release() }
                settle(40)
            }
            fun strokes() = com.inkslate.core.Perform.inkOf?.invoke(part.absolutePath)?.pages?.values?.sumOf { it.size } ?: 0
            fun shown(text: String) = onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

            shot("opened")
            val first = page()
            val ink = strokes()

            // The middle of the page: the buttons come.
            tap(0.5f, 0.45f)
            shot("centre-tap")
            // With the buttons up, a tap in the middle folds them away; another brings them back.
            println("after one centre tap: Tuner shown ${shown("Tuner")}")
            val folded = !shown("Tuner")
            tap(0.5f, 0.45f)
            shot("centre-tap-again")
            println("after two centre taps: Tuner shown ${shown("Tuner")}")
            assertTrue("a tap in the middle folds the buttons away", folded)
            assertTrue("and another brings them back", shown("Tuner"))

            // A button pressed by a finger: the metronome's panel opens, and closes again.
            tapNode(onAllNodesWithText("Metronome", useUnmergedTree = true).onFirst())
            shot("metronome")
            println("after tapping Metronome: page ${page()}")
            // Off again, so its clicks do not run on under the rest.
            tapNode(onAllNodesWithText("Metronome", useUnmergedTree = true).onFirst())

            // Pages: a tap at the right, one at the left, a flick each way.
            tap(0.93f, 0.5f)
            shot("tap-right")
            assertEquals("a tap at the right turns on", first + 1, page())
            tap(0.07f, 0.5f)
            assertEquals("a tap at the left turns back", first, page())
            drag(0.7f, 0.5f, 0.25f, 0.5f, 6)
            assertEquals("a flick turns on", first + 1, page())
            drag(0.25f, 0.5f, 0.7f, 0.5f, 6)
            assertEquals("and back", first, page())
            shot("after-flicks")

            // A finger over the music with the tools out: it moves, it never writes.
            drag(0.45f, 0.3f, 0.5f, 0.7f, 40)
            shot("finger-drag")
            assertEquals("a finger never writes", ink, strokes())

            // Two fingers: spread apart - the page grows - then moved together.
            for (i in 0..20) { val half = 100f + i * 12f; SimulatedTouch.finger(1, 800f - half, 500f); SimulatedTouch.finger(2, 800f + half, 500f); settle(1) }
            SimulatedTouch.lift(1); SimulatedTouch.lift(2); settle(30)
            shot("pinch")
            for (i in 0..15) { SimulatedTouch.finger(1, 700f - i * 10f, 500f); SimulatedTouch.finger(2, 900f - i * 10f, 500f); settle(1) }
            SimulatedTouch.lift(1); SimulatedTouch.lift(2); settle(30)
            shot("two-finger-pan")
            assertEquals("fingers never write", ink, strokes())

            // A tap still does what a tap does after all that.
            tap(0.93f, 0.5f)
            shot("tap-after-gestures")
            println("page after a tap following the gestures: ${page()}")
        }
    }
}
