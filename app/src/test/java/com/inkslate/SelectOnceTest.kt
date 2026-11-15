package com.inkslate

import android.graphics.Matrix
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import com.inkslate.core.InkPoint
import com.inkslate.core.Stroke
import com.inkslate.core.Tool
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
class SelectOnceTest {
    private fun mark(id: String = "original") = Stroke(
        id, Stroke.Kind.RECT, 0xFF000000.toInt(), 2f,
        listOf(InkPoint(100f, 100f, 2f), InkPoint(300f, 250f, 2f))
    )

    private fun view() = DrawingView(RuntimeEnvironment.getApplication()).apply {
        setPages(listOf(600f to 800f), resetView = true)
        layout(0, 0, 600, 800)
        setStrokes(listOf(mark()))
        penConfig.tool = Tool.SELECT
    }

    private fun pen(view: DrawingView, action: Int, x: Float, y: Float, at: Long) {
        val position = floatArrayOf(x, y)
        (DrawingView::class.java.getDeclaredField("pageToView").apply { isAccessible = true }
            .get(view) as Matrix).mapPoints(position)
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_STYLUS
        }
        val coordinates = MotionEvent.PointerCoords().apply {
            this.x = position[0]
            this.y = position[1]
            pressure = .6f
            size = 1f
        }
        val event = MotionEvent.obtain(at, at, action, 1, arrayOf(properties), arrayOf(coordinates),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
        try { assertTrue(view.onTouchEvent(event)) } finally { event.recycle() }
    }

    @Test fun `duplicate stays selected and movable then outside contact draws with restored tool`() {
        val view = view()
        view.selectAll()
        view.duplicateSelection()
        val duplicate = view.selectedStrokes().single()
        assertNotEquals("original", duplicate.id)
        var exits = 0
        view.onSelectionOutsideDown = {
            exits++
            view.penConfig.tool = Tool.DRAW
            true
        }
        val at = SystemClock.uptimeMillis()
        pen(view, MotionEvent.ACTION_DOWN, 210f, 180f, at)
        pen(view, MotionEvent.ACTION_MOVE, 260f, 220f, at + 20)
        pen(view, MotionEvent.ACTION_UP, 260f, 220f, at + 30)
        val moved = view.selectedStrokes().single()
        assertEquals(duplicate.id, moved.id)
        assertTrue(moved.points.first().x > duplicate.points.first().x)
        assertEquals(Tool.SELECT, view.penConfig.tool)
        assertEquals(0, exits)

        pen(view, MotionEvent.ACTION_DOWN, 450f, 500f, at + 100)
        assertEquals(1, exits)
        assertTrue(view.selectedStrokes().isEmpty())
        assertEquals(Tool.DRAW, view.penConfig.tool)
        pen(view, MotionEvent.ACTION_MOVE, 480f, 530f, at + 120)
        pen(view, MotionEvent.ACTION_UP, 480f, 530f, at + 130)
        val drawn = view.strokesSnapshot().single { it.kind == Stroke.Kind.FREEHAND }
        assertEquals(450f, drawn.points.first().x, .01f)
        assertEquals(500f, drawn.points.first().y, .01f)
        assertEquals(3, view.strokesSnapshot().size)
    }

    @Test fun `selection body and its resize handle do not end select once`() {
        val view = view()
        view.selectAll()
        var exits = 0
        view.onSelectionOutsideDown = { exits++; true }
        val at = SystemClock.uptimeMillis()
        pen(view, MotionEvent.ACTION_DOWN, 200f, 175f, at)
        pen(view, MotionEvent.ACTION_UP, 200f, 175f, at + 10)
        pen(view, MotionEvent.ACTION_DOWN, 100f, 100f, at + 100)
        pen(view, MotionEvent.ACTION_UP, 100f, 100f, at + 110)
        assertEquals(0, exits)
        assertEquals(1, view.selectedStrokes().size)
        assertEquals(Tool.SELECT, view.penConfig.tool)
    }

    @Test fun `ordinary persistent select remains select after an outside contact`() {
        val view = view()
        view.selectAll()
        val at = SystemClock.uptimeMillis()
        pen(view, MotionEvent.ACTION_DOWN, 450f, 500f, at)
        pen(view, MotionEvent.ACTION_UP, 450f, 500f, at + 10)
        assertEquals(Tool.SELECT, view.penConfig.tool)
        assertEquals(listOf(mark()), view.strokesSnapshot())
        assertTrue(view.selectedStrokes().isEmpty())
    }

    @Test fun `one shot with no selection can still start its first marquee`() {
        val view = view()
        view.lassoSelect = false
        var exits = 0
        view.onSelectionOutsideDown = { exits++; true }
        val at = SystemClock.uptimeMillis()
        pen(view, MotionEvent.ACTION_DOWN, 80f, 80f, at)
        pen(view, MotionEvent.ACTION_MOVE, 320f, 270f, at + 20)
        pen(view, MotionEvent.ACTION_UP, 320f, 270f, at + 30)
        assertEquals(0, exits)
        assertEquals("original", view.selectedStrokes().single().id)
        assertEquals(Tool.SELECT, view.penConfig.tool)
    }
}
