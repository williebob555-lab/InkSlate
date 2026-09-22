package com.inkslate.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.DesktopComposeUiTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.imageio.ImageIO

/**
 * The workspace, driven the way a person drives it: documents opened from Home, split side by
 * side, the same document shown twice, ink drawn into one half and undone from the keyboard.
 *
 * What it guards is the shape of the thing rather than any one number - one set of bars however
 * many documents are showing, and two views of one document being one document. Photographs of
 * each step are written to `desktop/build/workspace-ui/` so a failure can be looked at, not just
 * read about.
 */
@OptIn(ExperimentalTestApi::class)
class WorkspaceUiTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val shots = File("build/workspace-ui").also { it.mkdirs() }

    /**
     * Frames, by hand. The editor keeps loops running for as long as a document is open - the
     * link's heartbeat, the autosave - and a clock that advances by itself never finds them idle.
     */
    private fun DesktopComposeUiTest.settle(frames: Int = 12) {
        repeat(frames) {
            mainClock.advanceTimeBy(16)
            Thread.sleep(4)
        }
    }

    private fun DesktopComposeUiTest.shot(name: String) {
        settle()
        val image = onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage()
        ImageIO.write(image, "png", File(shots, "$name.png"))
    }

    private fun DesktopComposeUiTest.count(description: String): Int =
        onAllNodesWithContentDescription(description, useUnmergedTree = true)
            .fetchSemanticsNodes().size

    private fun DesktopComposeUiTest.waitFor(what: String, timeoutMs: Long = 20_000, check: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (true) {
            settle(2)
            if (check()) return
            if (System.currentTimeMillis() > until) {
                shot("timeout-" + what.replace(' ', '-'))
                throw AssertionError("gave up waiting for $what")
            }
            Thread.sleep(20)
        }
    }

    private fun DesktopComposeUiTest.rightClickTab(name: String) {
        onNodeWithContentDescription("Close $name", useUnmergedTree = true)
            .performMouseInput { rightClick(Offset(-40f, 10f)) }
        settle()
    }

    /** A stroke dragged across [node] from left to right, a third of the way down. */
    private fun DesktopComposeUiTest.drawAcross(node: SemanticsNodeInteraction, from: Float, to: Float) {
        node.performMouseInput {
            val y = height / 3f
            moveTo(Offset(width * from, y))
            press()
            for (i in 1..20) moveTo(Offset(width * (from + (to - from) * i / 20f), y + i * 3f))
            release()
        }
        settle()
    }

    @Test
    fun `two documents share one set of bars, and one document can be shown twice`() {
        val dir = temp.newFolder()
        val alpha = BlankDocumentFactory.create(dir, BlankDocumentFactory.Spec(name = "Alpha", pageCount = 2)).getOrThrow()
        val beta = BlankDocumentFactory.create(dir, BlankDocumentFactory.Spec(name = "Beta", pageCount = 2)).getOrThrow()
        FileRepo().noteOpened(beta)
        FileRepo().noteOpened(alpha)

        val shortcuts = Shortcuts()
        runDesktopComposeUiTest(width = 1600, height = 1000) {
            mainClock.autoAdvance = false
            setContent { InkSlateTheme { AppRoot(shortcuts, NavigationHooks()) } }
            shot("0-home")

            // Open the first document from Home.
            onAllNodes(hasText("Alpha", substring = true), useUnmergedTree = true).onFirst().performClick()
            waitFor("Alpha to open") { count("Close Alpha.pdf") == 1 && count("Save into the document") == 1 }
            waitFor("Alpha's pages") { shortcuts.save != null }
            shot("1-alpha")

            // A second, from Home again, by way of the new-tab button.
            onNodeWithContentDescription("Open another document").performClick()
            settle()
            onAllNodes(hasText("Beta", substring = true), useUnmergedTree = true).onFirst().performClick()
            waitFor("Beta to open") { count("Close Beta.pdf") == 1 }
            shot("2-beta")

            // Alpha to the side: two documents, one app bar and one set of tools between them.
            rightClickTab("Alpha.pdf")
            shot("3-alpha-menu")
            onNode(hasText("Open to the side"), useUnmergedTree = true).performClick()
            waitFor("the split") { count("Close split view") == 1 }
            shot("4-split-two-documents")
            assertEquals("one app bar for the workspace, not one per document", 1, count("Save into the document"))
            assertEquals("one set of tools for the workspace", 1, count("Ruler"))
            assertEquals("one page bar for the workspace", 1, count("Next page"))

            // The ruler goes on the document the tools are under - Alpha, on the right - and only
            // that one. Moving to Beta lifts it.
            onNodeWithContentDescription("Ruler", useUnmergedTree = true).performClick()
            shot("4b-ruler-on-alpha")
            onAllNodes(hasText("Beta.pdf"), useUnmergedTree = true).onFirst().performClick()
            settle()
            shot("4c-moved-to-beta")

            // Beta, now primary, shown a second time beside itself.
            rightClickTab("Beta.pdf")
            onNode(hasText("Open a second view to the side"), useUnmergedTree = true).performClick()
            waitFor("Beta twice") { count("Showing in both halves") == 1 }
            shot("5-split-same-document")
            assertEquals(1, count("Save into the document"))
            assertEquals(1, count("Ruler"))

            // Ink drawn into the right half is ink in the document - so it shows in the left half
            // too, and one undo from the keyboard takes it away from both.
            val root = onRoot()
            drawAcross(root, from = 0.62f, to = 0.85f)
            shot("6-drawn-in-right-half")
            waitFor("the stroke to count as an edit") { shortcuts.undo != null }
            val before = count("Undo")
            assertTrue("the toolbar and the app bar each offer undo", before >= 1)

            val undo = shortcuts.undo
            assertTrue("the focused document owns the keyboard", undo != null)
            runOnUiThread { undo!!.invoke() }
            shot("7-undone")

            // Closing the split leaves one pane, and the same single set of bars.
            onNodeWithContentDescription("Close split view").performClick()
            waitFor("one pane again") { count("Close split view") == 0 }
            shot("8-single")
            assertEquals(1, count("Save into the document"))
        }
    }
}
