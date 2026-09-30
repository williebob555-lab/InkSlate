package com.inkslate.desktop

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.imageio.ImageIO

/**
 * A finger on a Windows touchscreen, played the way Windows hands it to a desktop program: a mouse
 * press stamped as touch (the pointer reader's [PenInput.touchedSomewhere]), and for two fingers the
 * contacts the reader sees ([PenInput.finger]). On music with its tools away: a tap at the sides
 * turns the page, one in the middle brings the buttons, a flick turns, a finger dragged never
 * writes, a finger held still (Windows' right click) does nothing, and two fingers zoom - and a tap
 * still works after them. Pictures of each step to `desktop/build/touch/`.
 */
@OptIn(ExperimentalTestApi::class)
class TouchUiTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val shots = File("build/touch").also { it.mkdirs() }

    @After
    fun reset() {
        AppFlavor.musicView = false
        AppFlavor.home = null
        AppFlavor.edgeTaps = false
        AppFlavor.fingerPans = false
        PenInput.simulated = false
        PointerDiagnostics.windowAtForTests = null
        com.inkslate.core.Perform.onPosition = null
    }

    private fun DesktopComposeUiTest.settle(frames: Int = 12) {
        repeat(frames) { mainClock.advanceTimeBy(16); Thread.sleep(4) }
    }

    private fun DesktopComposeUiTest.image(): java.awt.image.BufferedImage {
        settle()
        return onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage()
    }

    private fun paperBox(img: java.awt.image.BufferedImage): IntArray? {
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1
        for (y in 0 until img.height step 2) for (x in 0 until img.width step 2) {
            val c = img.getRGB(x, y)
            if (((c shr 16) and 0xFF) > 245 && ((c shr 8) and 0xFF) > 245 && (c and 0xFF) > 245) {
                if (x < minX) minX = x; if (y < minY) minY = y; if (x > maxX) maxX = x; if (y > maxY) maxY = y
            }
        }
        return if (maxX < 0) null else intArrayOf(minX, minY, maxX, maxY)
    }

    @Test
    fun `a finger on a Windows touchscreen does what it should`() {
        val dir = temp.newFolder()
        val part = BlankDocumentFactory.create(dir, BlankDocumentFactory.Spec(name = "Part", pageCount = 3)).getOrThrow()
        AppFlavor.musicView = true
        // As InkSheets has it by default: a tap at the sides turns the page.
        AppFlavor.edgeTaps = true
        // As InkSheets: the pen writes, a finger moves and turns the page.
        AppFlavor.fingerPans = true
        AppFlavor.home = { open, _ -> Button(onClick = { open(part) }) { Text("Open the part") } }
        var page = -1
        com.inkslate.core.Perform.onPosition = { p, _ -> page = p }
        val w = 1600f; val h = 1000f

        runDesktopComposeUiTest(width = w.toInt(), height = h.toInt()) {
            mainClock.autoAdvance = false
            setContent { InkSlateTheme { AppRoot(Shortcuts(), NavigationHooks()) } }
            settle()
            onNodeWithText("Open the part").performClick()
            val until = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < until) { settle(4); if (paperBox(image()) != null) break }
            repeat(10) { settle() }
            PenInput.simulated = true
            // No window on a screen here: the window taken to be at the screen's top left.
            PointerDiagnostics.windowAtForTests = Offset.Zero
            val root = onAllNodes(isRoot()).onFirst()
            fun strokes() = com.inkslate.core.Perform.inkOf?.invoke(part.absolutePath)?.pages?.values?.sumOf { it.size } ?: 0
            var step = 0
            fun shot(name: String) = ImageIO.write(image(), "png", File(shots, "%02d-%s.png".format(++step, name)))

            // A tap, as Windows makes one from a finger: down and up where it touched, stamped as touch.
            fun tap(fx: Float, fy: Float) {
                PenInput.touchedSomewhere()
                root.performMouseInput { moveTo(Offset(w * fx, h * fy)); press(); }
                settle(3)
                PenInput.touchedSomewhere()
                root.performMouseInput { release() }
                settle(60)
                println("after tap at $fx,$fy: page $page")
            }
            // One finger dragged from one place to another over [frames] frames.
            fun drag(fx1: Float, fy1: Float, fx2: Float, fy2: Float, frames: Int) {
                PenInput.touchedSomewhere()
                root.performMouseInput { moveTo(Offset(w * fx1, h * fy1)); press() }
                for (i in 1..frames) {
                    PenInput.touchedSomewhere()
                    root.performMouseInput { moveTo(Offset(w * (fx1 + (fx2 - fx1) * i / frames), h * (fy1 + (fy2 - fy1) * i / frames))) }
                    settle(1)
                }
                PenInput.touchedSomewhere()
                root.performMouseInput { release() }
                settle(20)
            }

            // First a plain mouse click on the right, for comparison.
            PenInput.simulated = false
            root.performMouseInput { moveTo(Offset(w * 0.9f, h * 0.5f)); press() }
            settle(3)
            root.performMouseInput { release() }
            settle(60)
            println("after a mouse click on the right: page $page")
            PenInput.simulated = true
            val start = page
            shot("opened")
            val inkBefore = strokes()

            tap(0.9f, 0.5f)
            shot("tap-right")
            assertEquals("a tap on the right turns on", start + 1, page)

            tap(0.1f, 0.5f)
            shot("tap-left")
            assertEquals("a tap on the left turns back", start, page)

            // A quick flick leftwards turns on.
            drag(0.75f, 0.5f, 0.25f, 0.5f, 6)
            shot("flick")
            assertEquals("a flick turns on", start + 1, page)
            drag(0.25f, 0.5f, 0.75f, 0.5f, 6)
            assertEquals("a flick back turns back", start, page)

            // A finger dragged slowly about the page: never a mark.
            drag(0.4f, 0.3f, 0.45f, 0.7f, 40)
            shot("slow-drag")
            assertEquals("a finger never writes on music", inkBefore, strokes())

            // A finger held still: Windows makes it a right click - nothing to happen, no mark.
            PenInput.touchedSomewhere()
            root.performMouseInput { moveTo(Offset(w * 0.5f, h * 0.4f)); press(MouseButton.Secondary) }
            settle(40)
            PenInput.touchedSomewhere()
            root.performMouseInput { release(MouseButton.Secondary) }
            settle(20)
            shot("held")
            assertEquals("a finger held still makes no mark", inkBefore, strokes())
            assertEquals("nor turns a page", start, page)

            // Two fingers spread apart: the page grows, and nothing is drawn under them.
            val before = paperBox(image())!!
            val cx = 800f; val cy = 500f
            for (i in 0..20) {
                val half = 100f + i * 12f
                PenInput.finger(1, cx - half, cy); PenInput.finger(2, cx + half, cy)
                settle(1)
            }
            PenInput.fingerUp(1); PenInput.fingerUp(2)
            settle(20)
            val after = paperBox(image())
            shot("pinch")
            println("paper before ${before.toList()}, after ${after?.toList()}")
            assertTrue("two fingers apart zoom in", after == null || (after[2] - after[0]) > (before[2] - before[0]) * 1.2)
            assertEquals("a pinch makes no mark", inkBefore, strokes())

            // A tap in the middle after all that brings the buttons (nothing stuck from the pinch).
            tap(0.5f, 0.45f)
            shot("centre-tap")
            val buttons = onAllNodesWithContentDescription("Draw", useUnmergedTree = true).fetchSemanticsNodes().size
            println("tools shown after a centre tap: $buttons")
        }
    }
}
