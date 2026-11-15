package com.inkslate

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import com.inkslate.core.InkCanvas
import com.inkslate.core.InkPoint
import com.inkslate.core.NoteGrid
import com.inkslate.core.NoteGridRow
import com.inkslate.core.Stroke
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
class ManualNoteGridTest {
    private fun view(grid: NoteGrid = NoteGrid()) = DrawingView(RuntimeEnvironment.getApplication()).apply {
        canvas = InkCanvas.startingAt(600f, 800f, background = "NOTE_GRID", ownPaper = true)
            .copy(noteGrid = grid)
        setPages(listOf(600f to 800f), resetView = true)
        layout(0, 0, 600, 800)
    }

    private fun stroke(id: String, x: Float, y: Float) = Stroke(
        id, Stroke.Kind.FREEHAND, 0xFF000000.toInt(), 2f,
        listOf(InkPoint(x, y, 2f), InkPoint(x + 40f, y + 20f, 2f))
    )

    @Test fun `manually added empty sections remain after all annotations are deleted`() {
        val view = view()
        view.addNoteGridColumn(0)
        assertTrue(view.canvas!!.noteGrid!!.manualSections)
        assertEquals(2, view.canvas!!.noteGrid!!.rows.single().columns.size)
        view.setStrokes(listOf(stroke("a", 20f, 20f), stroke("b", 380f, 20f)))
        view.selectAll()
        view.deleteSelection()
        assertTrue(view.strokesSnapshot().isEmpty())
        assertEquals(listOf(360f, 360f), view.canvas!!.noteGrid!!.rows.single().columns)
        assertTrue(view.canvas!!.noteGrid!!.manualSections)
        view.undo()
        assertEquals(2, view.strokesSnapshot().size)
        assertEquals(2, view.canvas!!.noteGrid!!.rows.single().columns.size)
    }

    @Test fun `inserting a row carries lower ink and undo restores structure and coordinates together`() {
        val grid = NoteGrid(rows = listOf(NoteGridRow(), NoteGridRow()), manualSections = true)
        val view = view(grid)
        val first = stroke("first", 20f, 20f)
        val below = stroke("below", 20f, 260f)
        view.setStrokes(listOf(first, below))
        view.addNoteGridRow(0)
        assertEquals(3, view.canvas!!.noteGrid!!.rows.size)
        assertEquals(first, view.strokesSnapshot().single { it.id == first.id })
        val moved = view.strokesSnapshot().single { it.id == below.id }
        assertEquals(480f, moved.points.first().y, .001f)
        assertEquals(1, view.model.undoStack.size)
        view.undo()
        assertEquals(grid.rows, view.canvas!!.noteGrid!!.rows)
        assertEquals(setOf(first, below), view.strokesSnapshot().toSet())
        view.redo()
        assertEquals(3, view.canvas!!.noteGrid!!.rows.size)
        assertEquals(moved, view.strokesSnapshot().single { it.id == below.id })
    }

    @Test fun `removing a section merges its boundary without deleting or moving ink`() {
        val grid = NoteGrid(rows = listOf(NoteGridRow(220f, listOf(360f, 360f))), manualSections = true)
        val view = view(grid)
        val marks = listOf(stroke("left", 20f, 20f), stroke("right", 380f, 20f))
        view.setStrokes(marks)
        view.removeNoteGridSection(NoteGrid.Cell(0, 1))
        assertEquals(listOf(720f), view.canvas!!.noteGrid!!.rows.single().columns)
        assertEquals(marks, view.strokesSnapshot())
        view.undo()
        assertEquals(grid.rows, view.canvas!!.noteGrid!!.rows)
        assertEquals(marks, view.strokesSnapshot())
        view.redo()
        assertEquals(listOf(720f), view.canvas!!.noteGrid!!.rows.single().columns)
        assertEquals(marks, view.strokesSnapshot())
    }

    @Test fun `a stroke beyond both edges grows its existing section without creating others`() {
        val view = view()
        DrawingView::class.java.getDeclaredField("live").apply { isAccessible = true }.set(view,
            arrayListOf(InkPoint(20f, 20f, 2f), InkPoint(1200f, 900f, 2f)))
        DrawingView::class.java.getDeclaredMethod("commitLive", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(view, false)
        val grid = view.canvas!!.noteGrid!!
        assertEquals(1, grid.rows.size)
        assertEquals(1, grid.rows.single().columns.size)
        assertTrue(grid.rows.single().columns.single() > 1200f)
        assertTrue(grid.rows.single().height > 900f)
        assertEquals(1, view.strokesSnapshot().size)
    }

    private fun addColumnControl(view: DrawingView): Pair<Float, Float> {
        val controls = DrawingView::class.java.getDeclaredMethod("noteGridControls")
            .apply { isAccessible = true }.invoke(view) as List<*>
        val add = controls.filterNotNull().single { control ->
            control.javaClass.getDeclaredField("kind").apply { isAccessible = true }.get(control).toString() == "ADD_COLUMN"
        }
        return add.javaClass.getDeclaredField("x").apply { isAccessible = true }.getFloat(add) to
            add.javaClass.getDeclaredField("y").apply { isAccessible = true }.getFloat(add)
    }

    private fun pen(view: DrawingView, action: Int, x: Float, y: Float, at: Long) {
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
        val coordinates = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = .6f; size = 1f }
        val event = MotionEvent.obtain(at, at, action, 1, arrayOf(properties), arrayOf(coordinates),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
        try { assertTrue(view.onTouchEvent(event)) } finally { event.recycle() }
    }

    @Test fun `tapping the plus control adds a section without drawing ink`() {
        val view = view()
        val (x, y) = addColumnControl(view)
        val at = SystemClock.uptimeMillis()
        pen(view, MotionEvent.ACTION_DOWN, x, y, at)
        assertEquals(1, view.canvas!!.noteGrid!!.rows.single().columns.size)
        pen(view, MotionEvent.ACTION_UP, x, y, at + 20)
        assertEquals(2, view.canvas!!.noteGrid!!.rows.single().columns.size)
        assertTrue(view.canvas!!.noteGrid!!.manualSections)
        assertTrue(view.strokesSnapshot().isEmpty())
        assertEquals(1, view.model.undoStack.size)
    }

    @Test fun `dragging away from a section control cancels without drawing or adding a section`() {
        val view = view()
        val (x, y) = addColumnControl(view)
        val at = SystemClock.uptimeMillis()
        pen(view, MotionEvent.ACTION_DOWN, x, y, at)
        pen(view, MotionEvent.ACTION_MOVE, x + 150f, y + 150f, at + 20)
        pen(view, MotionEvent.ACTION_UP, x + 150f, y + 150f, at + 30)
        assertEquals(1, view.canvas!!.noteGrid!!.rows.single().columns.size)
        assertTrue(view.strokesSnapshot().isEmpty())
        assertFalse(view.model.canUndo())
    }
}
