package com.inkslate

import com.inkslate.core.*
import com.inkslate.ink.DrawingView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectionPropertiesEditTest {
    private fun mark(layer: Int = 1) = Stroke("mark", Stroke.Kind.FREEHAND, 0xFF000000.toInt(), 2f,
        listOf(InkPoint(100f, 100f, 1f), InkPoint(150f, 150f, 3f)), layerMask = layer)
    private fun view(vararg strokes: Stroke) = DrawingView(RuntimeEnvironment.getApplication()).apply {
        setPages(listOf(600f to 800f), resetView = true)
        layout(0, 0, 600, 800)
        setStrokes(strokes.toList())
        visibleLayerMask = AnnotationLayers.ALL
        selectAll()
    }

    @Test fun `color and weight stay selected do not change the pen and undo separately`() {
        val v = view(mark())
        val penColor = v.penConfig.color
        val penWidth = v.penConfig.strokeWidth
        v.restyleSelectedAppearance(0xFFDC2626.toInt(), null)
        v.restyleSelectedAppearance(null, 4f)
        assertEquals(1, v.selectionCount())
        assertEquals(penColor, v.penConfig.color)
        assertEquals(penWidth, v.penConfig.strokeWidth)
        assertEquals(listOf(2f, 6f), v.selectedStrokes().single().points.map { it.width })
        v.undo()
        assertEquals(2f, v.strokesSnapshot().single().baseWidth)
        assertEquals(0xFFDC2626.toInt(), v.strokesSnapshot().single().color)
        v.undo()
        assertEquals(listOf(mark()), v.strokesSnapshot())
    }

    @Test fun `restyling legacy shared ink leaves the other layer unchanged`() {
        val original = mark(3)
        val v = view(original)
        v.restyleSelectedAppearance(0xFFDC2626.toInt(), 4f)
        val retained = v.strokesSnapshot().single { it.layerMask == 2 }
        assertEquals(original.copy(layerMask = 2, updatedUtc = retained.updatedUtc), retained)
        assertEquals(1, v.selectedStrokes().single().layerMask)
        v.undo()
        assertEquals(listOf(original), v.strokesSnapshot())
        v.redo()
        assertEquals(2, v.strokesSnapshot().size)
    }

    @Test fun `copy to a layer retains original in place and selects independently deletable copy`() {
        val v = view(mark())
        v.transferSelectionLayer(4, true)
        assertEquals(4, v.selectedStrokes().single().layerMask)
        assertEquals(mark().points, v.selectedStrokes().single().points)
        assertEquals(1, v.recordLayerMask)
        // Starting the next pen gesture resolves the global/filter target again. The retained
        // selection must still edit its own destination rather than that unrelated channel.
        DrawingView::class.java.getDeclaredField("liveLayerMask").apply { isAccessible = true }.setInt(v, 1)
        v.deleteSelection()
        assertEquals(listOf(mark()), v.strokesSnapshot())
        v.undo(); v.undo()
        assertEquals(listOf(mark()), v.strokesSnapshot())
    }

    @Test fun `move shared ink keeps untouched memberships and one undo restores the exact original`() {
        val original = mark(3)
        val v = view(original)
        v.transferSelectionLayer(4, false)
        assertEquals(setOf(2, 4), v.strokesSnapshot().map { it.layerMask }.toSet())
        assertEquals(4, v.selectedStrokes().single().layerMask)
        v.undo()
        assertEquals(listOf(original), v.strokesSnapshot())
    }
}
