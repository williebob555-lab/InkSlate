package com.inkslate.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.inkslate.core.SelectionProperties
import com.inkslate.editor.ContextActionBar
import com.inkslate.editor.ContextActions
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@OptIn(ExperimentalComposeUiApi::class, ExperimentalTestApi::class)
class ContextActionBarTest {
    private val graph = SelectionProperties("Graph", 1, 0xFF1D4ED8.toInt(), 1.5f, true, true, 2)

    @Test fun `real toolbar layouts at phone tablet desktop and enlarged text sizes`() {
        val out = File("build/context-bar-review").also { it.mkdirs() }
        val cases = listOf(
            "graph" to graph,
            "picture" to SelectionProperties("Picture", 1, null, null, false, false, 1),
            "mixed" to SelectionProperties("12 objects", 12, null, null, true, true, null),
            "placing" to graph)
        val sizes = listOf(320 to 640, 360 to 640, 640 to 360, 390 to 844, 844 to 390,
            600 to 960, 960 to 600, 800 to 1280, 1280 to 800, 1024 to 1366,
            1366 to 1024, 1280 to 720, 1600 to 1000)
        for ((name, p) in cases) for ((width, height) in sizes) for (dark in listOf(false, true)) {
            val scene = ImageComposeScene(width, height, Density(1f)) {
                InkSlateTheme(darkTheme = dark) {
                    CompositionLocalProvider(LocalDensity provides Density(1f, if (width == 600) 1.3f else 1f)) {
                        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            if (name == "placing") ContextActionBar(p,
                                ContextActions(restyle = { _, _ -> }, done = {}, edit = {}), placing = true)
                            ToolBar(ToolState(), selectionCount = if (name == "placing") 0 else 1, actions = ToolBarActions(
                                selectionProperties = p,
                                onEditStamp = if (name == "graph") ({}) else null,
                                canCrop = name == "picture", onSavePicture = if (name == "picture") ({}) else null,
                                onTransferSelectionLayer = { _, _ -> }))
                        }
                    }
                }
            }
            try {
                scene.render()
                File(out, "$name-$width-$height-${if (dark) "dark" else "light"}.png")
                    .writeBytes(scene.render().encodeToData()!!.bytes)
            } finally { scene.close() }
        }
    }

    @Test fun `enlarged labels fit their buttons without text clipping`() = runDesktopComposeUiTest {
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                    Box(Modifier.width(600.dp)) {
                        ContextActionBar(graph, ContextActions(restyle = { _, _ -> }, duplicate = {}, delete = {}, done = {}))
                    }
                }
            }
        }
        for (label in listOf("Duplicate", "Delete", "Done")) {
            val node = onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            assertTrue("$label has layout", results.isNotEmpty())
            // Desktop paragraphs retain fractional glyph widths while their reported size is an
            // integer. didOverflowWidth alone reports a false overflow for subpixel rounding.
            // Compare the actual line extent with the visible node and reject real clipping.
            assertTrue("$label must fit its visible bounds", results.all { layout ->
                !layout.didOverflowHeight && (0 until layout.lineCount).all { line ->
                    !layout.isLineEllipsized(line) &&
                        layout.getLineRight(line) - layout.getLineLeft(line) <= node.boundsInRoot.width + 1f
                }
            })
        }
    }

    @Test fun `selection properties edit the object and layer menu distinguishes moving from copying`() = runDesktopComposeUiTest {
        var color: Int? = null
        var layer = 0
        var copied = false
        var done = false
        setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp)) {
                    ContextActionBar(graph, ContextActions(restyle = { c, _ -> color = c },
                        duplicate = {}, delete = {}, done = { done = true }, copy = {}, cut = {},
                        transferLayer = { l, c -> layer = l; copied = c }))
                }
            }
        }
        onNodeWithContentDescription("Selection color").performClick()
        onNodeWithContentDescription("Color #DC2626").performClick()
        assertEquals(0xFFDC2626.toInt(), color)
        onNodeWithText("Layer · 2").performClick()
        onAllNodesWithText("Layer 3")[1].performClick()
        assertEquals(4, layer); assertTrue(copied)
        onNodeWithText("Done").performClick()
        assertTrue(done)
    }

    @Test fun `fixed actions remain visible and do not overlap at narrow widths`() = runDesktopComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    ContextActionBar(graph, ContextActions(restyle = { _, _ -> }, duplicate = {}, delete = {},
                        done = {}, copy = {}, cut = {}, edit = {}))
                }
            }
        }
        val duplicate = onNodeWithContentDescription("Duplicate").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val delete = onNodeWithContentDescription("Delete").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val done = onNodeWithTag("context-done").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(duplicate.right <= delete.left)
        assertTrue(delete.right <= done.left)
        assertTrue(done.right <= 320)
    }
}
