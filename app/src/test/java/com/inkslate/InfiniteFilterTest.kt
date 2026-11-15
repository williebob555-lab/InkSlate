package com.inkslate

import android.graphics.Matrix
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import com.inkslate.core.InkCanvas
import com.inkslate.core.LayerFilter
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
class InfiniteFilterTest {
    private fun view(infinite: Boolean) = DrawingView(RuntimeEnvironment.getApplication()).apply {
        if (infinite) canvas = InkCanvas.startingAt(600f, 800f, ownPaper = true)
        setPages(listOf(600f to 800f), resetView = true)
        layout(0, 0, 600, 800)
        toggleLayerFilter(2)
    }
    @Suppress("UNCHECKED_CAST")
    private fun filters(v: DrawingView) = DrawingView::class.java.getDeclaredField("layerFilters")
        .apply { isAccessible = true }.get(v) as MutableList<LayerFilter>
    private fun screen(v: DrawingView, x: Float, y: Float): FloatArray = floatArrayOf(x, y).also {
        (DrawingView::class.java.getDeclaredField("pageToView").apply { isAccessible = true }.get(v) as Matrix).mapPoints(it)
    }
    private fun grab(v: DrawingView, x: Float, y: Float) {
        val p = screen(v, x, y)
        val began = DrawingView::class.java.getDeclaredMethod("beginLayerFilterGesture",
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(v, p[0], p[1], false)
        assertEquals(true, began)
    }
    private fun drag(v: DrawingView, x: Float, y: Float) {
        val p = screen(v, x, y)
        DrawingView::class.java.getDeclaredMethod("dragLayerFilter", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(v, p[0], p[1])
    }
    private fun event(v: DrawingView, x: Float, y: Float, span: Float): MotionEvent {
        val points = listOf(screen(v, x - span / 2, y), screen(v, x + span / 2, y))
        val properties = Array(2) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(2) { i -> MotionEvent.PointerCoords().apply { this.x = points[i][0]; this.y = points[i][1]; pressure = 1f } }
        val at = SystemClock.uptimeMillis()
        return MotionEvent.obtain(at, at, MotionEvent.ACTION_MOVE, 2, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    @Test fun `infinite drag crosses original paper edges in both directions without resizing`() {
        val v = view(true)
        val before = filters(v).single()
        val cx = (before.left + before.right) / 2; val cy = (before.top + before.bottom) / 2
        grab(v, cx, cy)
        drag(v, cx - 2000f, cy - 3000f)
        val negative = filters(v).single()
        assertEquals(before.left - 2000f, negative.left, .01f)
        assertEquals(before.top - 3000f, negative.top, .01f)
        drag(v, cx + 4000f, cy + 5000f)
        val positive = filters(v).single()
        assertEquals(before.left + 4000f, positive.left, .01f)
        assertEquals(before.width, positive.width, .01f)
        assertEquals(before.height, positive.height, .01f)
        assertTrue(v.strokesSnapshot().isEmpty())
    }

    @Test fun `infinite corner resize crosses negative and positive paper edges`() {
        val v = view(true); val before = filters(v).single()
        grab(v, before.left, before.top); drag(v, -1500f, -2500f)
        var f = filters(v).single()
        assertEquals(-1500f, f.left, .01f); assertEquals(-2500f, f.top, .01f)
        grab(v, f.right, f.bottom); drag(v, 3000f, 4000f)
        f = filters(v).single()
        assertEquals(3000f, f.right, .01f); assertEquals(4000f, f.bottom, .01f)
    }

    @Test fun `two finger gesture has no hidden position or size limit on an infinite board`() {
        val v = view(true); val before = filters(v).single()
        val cx = (before.left + before.right) / 2; val cy = (before.top + before.bottom) / 2
        val begin = event(v, cx, cy, 80f)
        try {
            assertEquals(true, DrawingView::class.java.getDeclaredMethod("tryBeginLayerFilterTransform", MotionEvent::class.java)
                .apply { isAccessible = true }.invoke(v, begin))
        } finally { begin.recycle() }
        val move = event(v, cx - 2000f, cy + 3000f, 320f)
        try {
            DrawingView::class.java.getDeclaredMethod("updateLayerFilterTransform", MotionEvent::class.java)
                .apply { isAccessible = true }.invoke(v, move)
        } finally { move.recycle() }
        val f = filters(v).single()
        assertEquals(before.width * 4, f.width, .02f)
        assertEquals(cx - 2000f, (f.left + f.right) / 2, .02f)
        assertEquals(cy + 3000f, (f.top + f.bottom) / 2, .02f)
    }

    @Test fun `PDF filters still stop at page edges`() {
        val v = view(false); val before = filters(v).single()
        grab(v, (before.left + before.right) / 2, (before.top + before.bottom) / 2)
        drag(v, -2000f, 4000f)
        val f = filters(v).single()
        assertEquals(0f, f.left, .01f); assertEquals(800f, f.bottom, .01f)
    }

    @Test fun `new infinite filter is centred in the current panned view`() {
        val v = view(true)
        v.toggleLayerFilter(2)
        val forward = DrawingView::class.java.getDeclaredField("pageToView").apply { isAccessible = true }.get(v) as Matrix
        val inverse = DrawingView::class.java.getDeclaredField("viewToPage").apply { isAccessible = true }.get(v) as Matrix
        forward.setTranslate(1500f, -2000f); forward.invert(inverse)
        val centre = v.viewCentreOnPage()
        v.toggleLayerFilter(2)
        val f = filters(v).single()
        assertEquals(centre[0], (f.left + f.right) / 2, .01f)
        assertEquals(centre[1], (f.top + f.bottom) / 2, .01f)
    }
}
