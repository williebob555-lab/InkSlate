package com.inkslate.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.inkslate.core.DesignConfig
import com.inkslate.editor.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

@OptIn(ExperimentalTestApi::class)
class WorkspaceControlsTest {
    private val shots = File("build/workspace-controls-review").also { it.mkdirs() }
    private fun DesktopComposeUiTest.shot(name: String) {
        waitForIdle()
        ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(shots, "$name.png"))
    }

    @Test fun `document controls fit all twelve workspace sizes in both themes`() {
        val sizes = listOf(360 to 640, 640 to 360, 390 to 844, 844 to 390, 600 to 960, 960 to 600,
            800 to 1280, 1280 to 800, 1024 to 1366, 1366 to 1024, 1280 to 720, 1600 to 1000)
        for ((w, h) in sizes) for (dark in listOf(false, true)) runDesktopComposeUiTest(width = w, height = h) {
            var creating by mutableStateOf(false)
            setContent { InkSlateTheme(dark) {
                if (creating) NewDocumentDialog({}, {}) else Box(Modifier.size(40.dp)) {
                    DocumentActionsMenu(true, {}, "Physics notes.pdf", "All changes saved",
                        listOf(DocumentMenuAction("Share", Icons.Default.Share, {}), DocumentMenuAction("Export", Icons.Default.FileDownload, {})),
                        listOf(DocumentMenuAction("Find in document", Icons.Default.Search, {}), DocumentMenuAction("Pages", Icons.Default.ViewModule, {})),
                        listOf(DocumentMenuSection("View & appearance", Icons.Default.ColorLens, listOf(DocumentMenuAction("Fit page", Icons.Default.AspectRatio, {}))),
                            DocumentMenuSection("History", Icons.Default.History, listOf(DocumentMenuAction("Version history", Icons.Default.History, {}))),
                            DocumentMenuSection("File options", Icons.Default.Settings, listOf(DocumentMenuAction("Rules for this file", Icons.Default.Settings, {})))),
                        DocumentMenuAction("Clear active layer on this page", Icons.Default.DeleteOutline, {}))
                }
            } }
            val menu = onNodeWithTag("document-actions-menu").fetchSemanticsNode().boundsInRoot
            assertTrue("menu stays horizontally in $w x $h", menu.left >= 0 && menu.right <= w + 1)
            assertTrue("menu stays vertically in $w x $h", menu.top >= 0 && menu.bottom <= h + 1)
            onNodeWithText("Share").assertIsDisplayed()
            onNodeWithText("History").performScrollTo().performClick()
            onNodeWithText("Version history").assertIsDisplayed()
            shot("menu-$w-$h-${if (dark) "dark" else "light"}")
            runOnIdle { creating = true }
            onNodeWithText("Create").assertIsDisplayed()
            onNodeWithText("Size", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            shot("fixed-$w-$h-${if (dark) "dark" else "light"}")
            onNodeWithText("Infinite whiteboard").performScrollTo().performClick()
            onNodeWithText("Size", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithText("Create").assertIsDisplayed()
            shot("infinite-$w-$h-${if (dark) "dark" else "light"}")
        }
    }

    @Test fun `infinite creation hides size and page count while fixed page choices are retained`() = runDesktopComposeUiTest(width = 600, height = 900) {
        var created: BlankDocumentFactory.Spec? = null
        setContent { InkSlateTheme(false) { NewDocumentDialog({}, { created = it }) } }
        onNodeWithText("Shape").assertExists()
        onNodeWithText("A4", useUnmergedTree = true).performScrollTo().performClick()
        shot("new-fixed-pages")
        onNodeWithText("Infinite whiteboard").performScrollTo().performClick()
        onNodeWithText("Size", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("A4", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("Pages: 1").assertDoesNotExist()
        shot("new-infinite-whiteboard")
        onNodeWithText("Fixed pages").performClick()
        onNodeWithText("A4", useUnmergedTree = true).assertExists()
        onNodeWithText("Infinite whiteboard").performScrollTo().performClick()
        onNodeWithText("Create").performClick()
        assertTrue(created!!.autoGrow)
        assertEquals(1, created!!.pageCount)
        assertEquals(BlankDocumentFactory.PageSize.LETTER, created!!.pageSize)
    }

    @Test fun `mixer controls stay left of full height faders at narrow and tablet widths`() {
        for ((width, height) in listOf(208 to 128, 220 to 160, 280 to 220, 244 to 160)) for (dark in listOf(false, true)) {
            runDesktopComposeUiTest(width = width, height = height) {
                setContent { InkSlateTheme(dark) { CompositionLocalProvider(LocalDensity provides Density(1f, if (width == 244) 1.3f else 1f)) {
                    LayerMixerPanel(7, 1, listOf(1f, .6f, .25f), 2, DesignConfig.Mixer(),
                        {}, {}, { _, _ -> }, {}, {}, Modifier.fillMaxSize())
                } } }
                for (layer in 1..3) {
                    val controls = onNodeWithTag("layer-$layer-controls").fetchSemanticsNode().boundsInRoot
                    val fader = onNodeWithTag("layer-$layer-fader").fetchSemanticsNode().boundsInRoot
                    assertTrue(controls.right <= fader.left)
                    assertEquals(4f, fader.top, .5f)
                    assertEquals(height - 4f, fader.bottom, .5f)
                    assertTrue(fader.right <= width)
                }
                shot("mixer-$width-$height-${if (dark) "dark" else "light"}")
            }
        }
    }

    @Test fun `mixer actions and fader endpoints remain independent`() = runDesktopComposeUiTest(width = 280, height = 180) {
        var writing by mutableStateOf(1)
        var visible by mutableStateOf(7)
        var filters by mutableStateOf(0)
        val levels = mutableStateListOf(1f, .5f, 1f)
        setContent { InkSlateTheme { LayerMixerPanel(visible, writing, levels, filters, DesignConfig.Mixer(),
            { visible = visible xor it }, { writing = it },
            { layer, value -> levels[Integer.numberOfTrailingZeros(layer)] = value }, {},
            { filters = filters xor it }, Modifier.fillMaxSize()) } }
        onNodeWithContentDescription("Write only on layer 2").performClick()
        assertEquals(2, writing); assertEquals(7, visible)
        onNodeWithContentDescription("Place layer 3 filter").performClick()
        assertEquals(4, filters)
        onNodeWithTag("layer-2-fader").performTouchInput { down(center.copy(y = 0f)); up() }
        assertEquals(1f, levels[1], .001f)
        onNodeWithTag("layer-2-fader").performTouchInput { down(center.copy(y = height.toFloat() - 1)); up() }
        assertEquals(0f, levels[1], .001f)
        assertEquals(1f, levels[0], .001f); assertEquals(1f, levels[2], .001f)
    }

    @Test fun `document menu navigates categories without save buttons or nested popups`() = runDesktopComposeUiTest(width = 390, height = 844) {
        var invoked = ""
        setContent { InkSlateTheme(false) { Box(Modifier.fillMaxSize()) {
            DocumentActionsMenu(true, {}, "Physics notes.pdf", "All changes saved",
                listOf(DocumentMenuAction("Share", Icons.Default.Share, { invoked = "share" }),
                    DocumentMenuAction("Export", Icons.Default.FileDownload, { invoked = "export" })),
                listOf(DocumentMenuAction("Find in document", Icons.Default.Search, {}),
                    DocumentMenuAction("Pages", Icons.Default.ViewModule, {})),
                listOf(DocumentMenuSection("View & appearance", Icons.Default.ColorLens,
                    listOf(DocumentMenuAction("Fit page", Icons.Default.AspectRatio, { invoked = "fit" }),
                        DocumentMenuAction("Night", Icons.Default.ColorLens, {}, selected = true))),
                    DocumentMenuSection("History", Icons.Default.History, listOf(DocumentMenuAction("Version history", Icons.Default.History, {})))),
                DocumentMenuAction("Clear active layer on this page", Icons.Default.DeleteOutline, {}))
        } } }
        onNodeWithText("Save into the document").assertDoesNotExist()
        shot("document-menu-root")
        onNodeWithText("View & appearance").performScrollTo().performClick()
        onNodeWithText("Fit page").assertIsDisplayed()
        onNodeWithContentDescription("Selected").assertExists()
        shot("document-menu-view")
        onNodeWithContentDescription("Back to document actions").performClick()
        onNodeWithText("Share").performClick()
        assertEquals("share", invoked)
    }
}
