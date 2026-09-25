package com.inkslate.desktop

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * InkSheets' music view in the real workspace: a part opened from Home should fill the screen -
 * no toolbars until asked for, the whole page fitted and centred. Photographed to
 * `desktop/build/music-view/`.
 */
@OptIn(ExperimentalTestApi::class)
class MusicViewUiTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val shots = File("build/music-view").also { it.mkdirs() }

    @After
    fun reset() {
        AppFlavor.musicView = false
        AppFlavor.home = null
    }

    private fun DesktopComposeUiTest.settle(frames: Int = 12) {
        repeat(frames) {
            mainClock.advanceTimeBy(16)
            Thread.sleep(4)
        }
    }

    private fun DesktopComposeUiTest.image(): java.awt.image.BufferedImage {
        settle()
        return onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage()
    }

    /** The box around the paper: the pixels near white, which nothing else on a dark screen is. */
    private fun paperBox(img: java.awt.image.BufferedImage): IntArray? {
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1
        for (y in 0 until img.height step 2) for (x in 0 until img.width step 2) {
            val c = img.getRGB(x, y)
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            if (r > 245 && g > 245 && b > 245) {
                if (x < minX) minX = x; if (y < minY) minY = y
                if (x > maxX) maxX = x; if (y > maxY) maxY = y
            }
        }
        return if (maxX < 0) null else intArrayOf(minX, minY, maxX, maxY)
    }

    @Test
    fun `a part opens fitted, centred and without toolbars`() {
        val dir = temp.newFolder()
        val part = BlankDocumentFactory.create(dir, BlankDocumentFactory.Spec(name = "Part", pageCount = 2)).getOrThrow()
        AppFlavor.musicView = true
        AppFlavor.home = { open, _ -> Button(onClick = { open(part) }) { Text("Open the part") } }

        runDesktopComposeUiTest(width = 1600, height = 1000) {
            mainClock.autoAdvance = false
            setContent { InkSlateTheme { AppRoot(Shortcuts(), NavigationHooks()) } }
            settle()
            onNodeWithText("Open the part").performClick()
            val until = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < until) {
                settle(4)
                if (paperBox(image()) != null) break
            }
            repeat(10) { settle() }
            val img = image()
            ImageIO.write(img, "png", File(shots, "part.png"))
            val box = paperBox(img) ?: throw AssertionError("no page on screen")
            val (left, top, right, bottom) = box.toList()
            println("paper at $left,$top to $right,$bottom on ${img.width}x${img.height}")
            val toolbars = onAllNodesWithContentDescription("Ruler", useUnmergedTree = true).fetchSemanticsNodes().size
            assertEquals("the tools stay away until asked for", 0, toolbars)
            val height = bottom - top
            assertTrue("the page should use most of the height (got $height of ${img.height})", height > img.height * 0.8)
            val centre = (left + right) / 2
            assertTrue("the page should be centred (centre $centre of ${img.width})", kotlin.math.abs(centre - img.width / 2) < img.width * 0.03)

            // The Tools button: the pen, marker, eraser and text come back, the homework tools do not.
            runOnIdle { com.inkslate.core.Perform.workspace!!(com.inkslate.core.PerformAction.FULLSCREEN) }
            repeat(4) { settle() }
            ImageIO.write(image(), "png", File(shots, "tools.png"))
            fun shown(label: String) = onAllNodesWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNodes().size
            for (label in listOf("Draw", "Marker", "Erase", "Text", "Undo")) assertTrue("$label is there", shown(label) > 0)
            for (label in listOf("Ruler", "Table", "Shapes", "Capture", "Picture")) assertEquals("$label is not for music", 0, shown(label))
        }
    }
}
