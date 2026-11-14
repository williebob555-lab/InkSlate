package com.inkslate

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
class NoteGridDrawingTest {
    private fun view() = DrawingView(RuntimeEnvironment.getApplication()).apply {
        canvas = InkCanvas.startingAt(600f, 800f, background = "NOTE_GRID", ownPaper = true)
            .copy(noteGrid = NoteGrid(rows = listOf(NoteGridRow(220f, listOf(360f, 360f)))))
    }

    private fun stroke(id: String, x: Float, endX: Float) = Stroke(
        id, Stroke.Kind.FREEHAND, 0xFF000000.toInt(), 2f,
        listOf(InkPoint(x, 20f, 2f), InkPoint(endX, 40f, 2f))
    )

    private fun set(view: DrawingView, name: String, value: Any?) {
        DrawingView::class.java.getDeclaredField(name).apply { isAccessible = true }.set(view, value)
    }

    private fun call(view: DrawingView, name: String) {
        DrawingView::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(view)
    }

    @Test fun `live resize leaves saved state untouched and cancellation discards it`() {
        val view = view()
        val neighbor = stroke("neighbor", 380f, 420f)
        view.setStrokes(listOf(neighbor))
        val before = view.canvas
        set(view, "live", arrayListOf(InkPoint(20f, 20f, 2f), InkPoint(500f, 40f, 2f)))
        call(view, "updateNoteGridPreview")
        call(view, "refreshNoteGridPreview")
        val preview = DrawingView::class.java.getDeclaredField("noteGridPreview")
            .apply { isAccessible = true }.get(view) as NoteGrid.Fit
        assertTrue(preview.grid.rows[0].columns[0] > 500f)
        assertEquals(before, view.canvas)
        assertEquals(listOf(neighbor), view.strokesSnapshot())
        assertFalse(view.model.canUndo())
        DrawingView::class.java.getDeclaredMethod("commitLive", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(view, true)
        assertEquals(before, view.canvas)
        assertEquals(listOf(neighbor), view.strokesSnapshot())
        assertNull(DrawingView::class.java.getDeclaredField("noteGridPreview")
            .apply { isAccessible = true }.get(view))
        assertFalse(view.model.canUndo())
    }

    @Test fun `pen up commits growth and neighbor movement as one reversible edit`() {
        val view = view()
        val neighbor = stroke("neighbor", 380f, 420f)
        view.setStrokes(listOf(neighbor))
        val originalGrid = view.canvas!!.noteGrid!!
        set(view, "live", arrayListOf(InkPoint(20f, 20f, 2f), InkPoint(500f, 40f, 2f)))
        DrawingView::class.java.getDeclaredMethod("commitLive", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(view, false)
        val committed = view.strokesSnapshot()
        val fittedGrid = view.canvas!!.noteGrid!!
        assertEquals(2, committed.size)
        assertTrue(fittedGrid.rows[0].columns[0] > 500f)
        assertTrue(committed.single { it.id == neighbor.id }.points.first().x > 500f)
        assertEquals(1, view.model.undoStack.size)
        view.undo()
        assertEquals(listOf(neighbor), view.strokesSnapshot())
        assertEquals(originalGrid.rows, view.canvas!!.noteGrid!!.rows)
        view.redo()
        assertEquals(committed.toSet(), view.strokesSnapshot().toSet())
        assertEquals(fittedGrid.rows, view.canvas!!.noteGrid!!.rows)
    }

    @Test fun `another pane editing ink refreshes a live preview even when dimensions stay the same`() {
        val view = view()
        val neighbor = stroke("neighbor", 380f, 420f)
        view.setStrokes(listOf(neighbor))
        view.model.attach(view)
        set(view, "live", arrayListOf(InkPoint(20f, 20f, 2f), InkPoint(500f, 40f, 2f)))
        call(view, "updateNoteGridPreview")
        call(view, "refreshNoteGridPreview")
        val updated = neighbor.copy(color = 0xFF00FF00.toInt())
        view.model.strokes[0] = updated
        view.model.moved(null)
        call(view, "refreshNoteGridPreview")
        @Suppress("UNCHECKED_CAST")
        val shown = DrawingView::class.java.getDeclaredField("noteGridPreviewStrokes")
            .apply { isAccessible = true }.get(view) as List<Stroke>
        assertEquals(updated.color, shown.single().color)
        assertEquals(listOf(updated), view.strokesSnapshot())
        view.model.detach(view)
    }

    @Test fun `deleting wide content shrinks the grid and undo restores both`() {
        val view = view()
        val expanded = NoteGrid(rows = listOf(NoteGridRow(500f, listOf(800f, 360f))))
        view.canvas = view.canvas!!.copy(noteGrid = expanded)
        val marks = listOf(stroke("wide", 20f, 740f), stroke("neighbor", 820f, 860f))
        view.setStrokes(marks)
        @Suppress("UNCHECKED_CAST")
        val selection = DrawingView::class.java.getDeclaredField("selection")
            .apply { isAccessible = true }.get(view) as MutableSet<String>
        selection.add("wide")
        view.deleteSelection()
        assertEquals(listOf(360f, 360f), view.canvas!!.noteGrid!!.rows[0].columns)
        assertEquals(220f, view.canvas!!.noteGrid!!.rows[0].height, .001f)
        val remaining = view.strokesSnapshot().single()
        assertEquals("neighbor", remaining.id)
        assertEquals(380f, remaining.points.first().x, .001f)
        view.undo()
        assertEquals(expanded.rows, view.canvas!!.noteGrid!!.rows)
        assertEquals(marks.toSet(), view.strokesSnapshot().toSet())
        view.redo()
        assertEquals(listOf(remaining), view.strokesSnapshot())
        assertEquals(listOf(360f, 360f), view.canvas!!.noteGrid!!.rows[0].columns)
    }
}
