package com.inkslate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.inkslate.core.AnnotationLayers
import com.inkslate.core.EraserMode
import com.inkslate.core.InkPoint
import com.inkslate.core.LayerFilter
import com.inkslate.core.Stroke
import com.inkslate.ink.DrawingView
import com.inkslate.ink.InkClipboard
import com.inkslate.ink.StrokeRasteriser
import org.junit.After
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
class LayerEraseIsolationTest {
    @After fun clearClipboard() { InkClipboard.clear() }

    private fun stroke(id: String = "shared", layer: Int = AnnotationLayers.ONE or AnnotationLayers.TWO) = Stroke(
        id, Stroke.Kind.FREEHAND, 0xFF2764BE.toInt(), 4f,
        (0..200 step 5).map { InkPoint(it.toFloat(), 40f, 4f) }, layerMask = layer
    )

    private fun view(vararg strokes: Stroke) = DrawingView(RuntimeEnvironment.getApplication()).apply {
        setStrokes(strokes.toList())
        recordLayerMask = AnnotationLayers.ONE
        visibleLayerMask = AnnotationLayers.ALL
        DrawingView::class.java.getDeclaredField("liveLayerMask")
            .apply { isAccessible = true }.setInt(this, AnnotationLayers.ONE)
        DrawingView::class.java.getDeclaredField("liveEraser")
            .apply { isAccessible = true }.setFloat(this, 8f)
    }

    private fun erase(view: DrawingView, mode: EraserMode) {
        view.penConfig.eraserMode = mode
        eraseAlong(view, 100f)
        finishErase(view)
    }

    private fun eraseAlong(view: DrawingView, x: Float) {
        DrawingView::class.java.getDeclaredMethod("eraseAlong", List::class.java)
            .apply { isAccessible = true }.invoke(view, listOf(x, 40f))
    }

    private fun finishErase(view: DrawingView) {
        DrawingView::class.java.getDeclaredMethod("pushEraseOp")
            .apply { isAccessible = true }.invoke(view)
    }

    private fun assertOtherChannelIntact(original: Stroke, actual: Stroke) {
        assertChannelIntact(original, actual, AnnotationLayers.TWO)
    }

    private fun assertChannelIntact(original: Stroke, actual: Stroke, layer: Int) {
        assertEquals(original.id, actual.id)
        assertEquals(layer, actual.layerMask)
        assertEquals(original.copy(layerMask = layer, updatedUtc = actual.updatedUtc), actual)
    }

    private fun filter(view: DrawingView): LayerFilter {
        val filter = LayerFilter(0, AnnotationLayers.TWO, 80f, 20f, 120f, 60f)
        @Suppress("UNCHECKED_CAST")
        val filters = DrawingView::class.java.getDeclaredField("layerFilters")
            .apply { isAccessible = true }.get(view) as MutableList<LayerFilter>
        filters.add(filter)
        return filter
    }

    private fun hasInk(strokes: List<Stroke>, layer: Int, x: Float): Boolean = strokes.any {
        (it.layerMask and layer) != 0 && it.hitTest(x, 40f, 1f)
    }

    @Test fun `whole eraser inside a filter removes its layer while preserving global recording layer`() {
        val original = stroke()
        val view = view(original)
        filter(view)
        erase(view, EraserMode.STROKE)
        val result = view.strokesSnapshot()
        assertChannelIntact(original, result.single(), AnnotationLayers.ONE)
        view.undo()
        assertEquals(listOf(original), view.strokesSnapshot())
        view.redo()
        assertEquals(result, view.strokesSnapshot())
    }

    @Test fun `partial eraser inside a filter only splits that filter layer`() {
        val original = stroke()
        val view = view(original)
        filter(view)
        erase(view, EraserMode.PARTIAL)
        val result = view.strokesSnapshot()
        assertChannelIntact(original, result.single { it.id == original.id }, AnnotationLayers.ONE)
        val fragments = result.filter { it.id != original.id }
        assertEquals(2, fragments.size)
        assertTrue(fragments.all { it.layerMask == AnnotationLayers.TWO })
        assertFalse(hasInk(fragments, AnnotationLayers.TWO, 100f))
        assertTrue(hasInk(fragments, AnnotationLayers.TWO, 20f))
        assertTrue(hasInk(fragments, AnnotationLayers.TWO, 180f))
    }

    @Test fun `eraser outside a filter returns to the global recording layer`() {
        val original = stroke()
        val view = view(original)
        filter(view)
        view.penConfig.eraserMode = EraserMode.STROKE
        eraseAlong(view, 20f)
        finishErase(view)
        assertOtherChannelIntact(original, view.strokesSnapshot().single())
    }

    @Test fun `partial path crossing filter boundary erases each layer only in its own region`() {
        val original = stroke()
        val view = view(original)
        filter(view)
        view.penConfig.eraserMode = EraserMode.PARTIAL
        DrawingView::class.java.getDeclaredMethod("eraseAlong", List::class.java)
            .apply { isAccessible = true }.invoke(view, listOf(100f, 40f, 20f, 40f))
        finishErase(view)
        val result = view.strokesSnapshot()
        assertTrue(hasInk(result, AnnotationLayers.ONE, 100f))
        assertFalse(hasInk(result, AnnotationLayers.ONE, 40f))
        assertTrue(hasInk(result, AnnotationLayers.TWO, 40f))
        assertFalse(hasInk(result, AnnotationLayers.TWO, 100f))
        assertTrue(hasInk(result, AnnotationLayers.ONE, 180f))
        assertTrue(hasInk(result, AnnotationLayers.TWO, 180f))
        view.undo()
        assertEquals(listOf(original), view.strokesSnapshot())
        view.redo()
        assertEquals(result.toSet(), view.strokesSnapshot().toSet())
    }

    @Test fun `filter recording overrides a globally hidden muted layer without changing mixer state`() {
        val view = view()
        filter(view)
        view.visibleLayerMask = 0
        view.layerOpacities = floatArrayOf(0f, 0f, 0f)
        val write = DrawingView::class.java.getDeclaredMethod("writeLayerMask",
            Int::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            .apply { isAccessible = true }
        assertEquals(AnnotationLayers.TWO, write.invoke(view, 0, 100f, 40f))
        assertEquals(AnnotationLayers.ONE, write.invoke(view, 0, 20f, 40f))
        assertEquals(AnnotationLayers.ONE, view.recordLayerMask)
        assertEquals(0, view.visibleLayerMask)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), view.layerOpacities, 0f)
    }

    @Test fun `filter rendering reveals its hidden layer locally and leaves global view unchanged`() {
        val first = stroke("one", AnnotationLayers.ONE).copy(color = Color.RED)
        val second = stroke("two", AnnotationLayers.TWO).copy(color = Color.BLUE)
        val view = view(first, second)
        val filter = filter(view)
        view.visibleLayerMask = 0
        val image = Bitmap.createBitmap(220, 80, Bitmap.Config.ARGB_8888)
        try {
            image.eraseColor(Color.WHITE)
            StrokeRasteriser.colorFilter = null
            val render = DrawingView::class.java.getDeclaredMethod("drawLayeredStrokes",
                Canvas::class.java, List::class.java, List::class.java)
                .apply { isAccessible = true }
            render.invoke(view, Canvas(image), listOf(first, second), listOf(filter))
            val inside = image.getPixel(100, 40)
            assertTrue(Color.blue(inside) > Color.red(inside))
            assertEquals(Color.WHITE, image.getPixel(40, 40))
            assertEquals(0, view.visibleLayerMask)
            view.visibleLayerMask = AnnotationLayers.ONE
            image.eraseColor(Color.WHITE)
            render.invoke(view, Canvas(image), listOf(first, second), listOf(filter))
            val outside = image.getPixel(40, 40)
            assertTrue(Color.red(outside) > Color.blue(outside))
            assertEquals(AnnotationLayers.ONE, view.visibleLayerMask)
            assertEquals(AnnotationLayers.ONE, view.recordLayerMask)
        } finally { image.recycle() }
    }

    @Test fun `selection deletion removes only active membership from shared legacy ink`() {
        val original = stroke()
        val view = view(original)
        view.selectAll()
        view.deleteSelection()
        val remaining = view.strokesSnapshot().single()
        assertOtherChannelIntact(original, remaining)
        view.undo()
        assertEquals(listOf(original), view.strokesSnapshot())
        view.redo()
        assertEquals(listOf(remaining), view.strokesSnapshot())
    }

    @Test fun `selection deletion leaves an independent coincident object in another layer untouched`() {
        val first = stroke("one", AnnotationLayers.ONE)
        val second = stroke("two", AnnotationLayers.TWO)
        val view = view(first, second)
        view.selectAll()
        view.deleteSelection()
        assertEquals(listOf(second), view.strokesSnapshot())
        view.undo()
        assertEquals(setOf(first, second), view.strokesSnapshot().toSet())
        view.redo()
        assertEquals(listOf(second), view.strokesSnapshot())
    }

    @Test fun `whole eraser retains other shared membership and undo restores the original`() {
        val original = stroke()
        val view = view(original)
        erase(view, EraserMode.STROKE)
        val remaining = view.strokesSnapshot().single()
        assertOtherChannelIntact(original, remaining)
        assertTrue(remaining.hitTest(100f, 40f, 1f))
        view.undo()
        assertEquals(listOf(original), view.strokesSnapshot())
        view.redo()
        assertEquals(listOf(remaining), view.strokesSnapshot())
    }

    @Test fun `partial eraser makes fragments only in active layer while other layer keeps full geometry`() {
        val original = stroke()
        val view = view(original)
        erase(view, EraserMode.PARTIAL)
        val result = view.strokesSnapshot()
        val preserved = result.single { it.id == original.id }
        assertOtherChannelIntact(original, preserved)
        val fragments = result.filter { it.id != original.id }
        assertEquals(2, fragments.size)
        assertEquals(2, fragments.map { it.id }.toSet().size)
        assertTrue(fragments.all { it.layerMask == AnnotationLayers.ONE })
        assertFalse(fragments.any { it.hitTest(100f, 40f, 1f) })
        assertTrue(fragments.any { it.hitTest(20f, 40f, 1f) })
        assertTrue(fragments.any { it.hitTest(180f, 40f, 1f) })
        view.undo()
        assertEquals(listOf(original), view.strokesSnapshot())
        view.redo()
        assertEquals(result.toSet(), view.strokesSnapshot().toSet())
    }

    @Test fun `eraser uses the recording layer latched when its gesture began`() {
        val first = stroke("one", AnnotationLayers.ONE)
        val second = stroke("two", AnnotationLayers.TWO)
        val view = view(first, second)
        // A later setting change cannot make an ongoing eraser start removing another layer.
        view.recordLayerMask = AnnotationLayers.TWO
        erase(view, EraserMode.STROKE)
        assertEquals(listOf(second), view.strokesSnapshot())
    }

    @Test fun `repeated partial erasing in one gesture undoes to exactly the shared source`() {
        val original = stroke()
        val view = view(original)
        view.penConfig.eraserMode = EraserMode.PARTIAL
        eraseAlong(view, 100f)
        eraseAlong(view, 40f)
        finishErase(view)
        val result = view.strokesSnapshot()
        assertOtherChannelIntact(original, result.single { it.id == original.id })
        val fragments = result.filter { it.id != original.id }
        assertEquals(3, fragments.size)
        assertTrue(fragments.all { it.layerMask == AnnotationLayers.ONE })
        assertFalse(fragments.any { it.hitTest(40f, 40f, 1f) || it.hitTest(100f, 40f, 1f) })
        assertEquals(1, view.model.undoStack.size)
        view.undo()
        assertEquals(listOf(original), view.strokesSnapshot())
        view.redo()
        assertEquals(result.toSet(), view.strokesSnapshot().toSet())
    }

    @Test fun `clear page removes only active membership on the current page`() {
        val shared = stroke()
        val otherPage = stroke("other-page", AnnotationLayers.ONE).copy(pageIndex = 1)
        val otherLayer = stroke("other-layer", AnnotationLayers.TWO)
        val view = view(shared, otherPage, otherLayer)
        view.clearPage()
        val result = view.strokesSnapshot()
        assertOtherChannelIntact(shared, result.single { it.id == shared.id })
        assertEquals(otherPage, result.single { it.id == otherPage.id })
        assertEquals(otherLayer, result.single { it.id == otherLayer.id })
        view.undo()
        assertEquals(setOf(shared, otherPage, otherLayer), view.strokesSnapshot().toSet())
        view.redo()
        assertEquals(result.toSet(), view.strokesSnapshot().toSet())
    }

    @Test fun `cut copies selected content and preserves its other shared membership`() {
        val original = stroke()
        val view = view(original)
        view.selectAll()
        assertEquals(1, view.cutSelection())
        assertEquals(1, InkClipboard.count)
        val remaining = view.strokesSnapshot().single()
        assertOtherChannelIntact(original, remaining)
        view.undo()
        assertEquals(listOf(original), view.strokesSnapshot())
        view.redo()
        assertEquals(listOf(remaining), view.strokesSnapshot())
    }

    @Test fun `pasting legacy shared clipboard content records only in the current active layer`() {
        val original = stroke()
        val view = view(original)
        InkClipboard.put(listOf(original), 612f, 792f)
        view.recordLayerMask = AnnotationLayers.THREE
        assertEquals(1, view.paste())
        val result = view.strokesSnapshot()
        assertEquals(original, result.single { it.id == original.id })
        val pasted = result.single { it.id != original.id }
        assertEquals(AnnotationLayers.THREE, pasted.layerMask)
        assertEquals(listOf(pasted), view.selectedStrokes())
        view.undo()
        assertEquals(listOf(original), view.strokesSnapshot())
        view.redo()
        assertEquals(result.toSet(), view.strokesSnapshot().toSet())
    }

    @Test fun `duplicating shared content creates copies only in the active recording layer`() {
        val original = stroke()
        val view = view(original)
        view.selectAll()
        view.duplicateSelection()
        val result = view.strokesSnapshot()
        assertEquals(original, result.single { it.id == original.id })
        val copy = result.single { it.id != original.id }
        assertEquals(AnnotationLayers.ONE, copy.layerMask)
        assertEquals(original.points.map { it.copy(x = it.x + 14f, y = it.y + 14f) }, copy.points)
        assertEquals(listOf(copy), view.selectedStrokes())
        view.undo()
        assertEquals(listOf(original), view.strokesSnapshot())
        view.redo()
        assertEquals(result.toSet(), view.strokesSnapshot().toSet())
    }
}
