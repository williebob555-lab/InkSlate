package com.inkslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteGridTest {
    @Test
    fun `rows have independent column counts`() {
        val grid = NoteGrid().addColumn(0).addRow()
        assertEquals(2, grid.rows[0].columns.size)
        assertEquals(1, grid.rows[1].columns.size)
        assertTrue(grid.manualSections)
    }

    @Test
    fun `overflow grows its cell and shifts only following content`() {
        val reflow = NoteGrid().accommodate(
            100f, 100f, Box(80f, 80f, 430f, 280f)
        )
        assertTrue(reflow.shiftRight > 0f)
        assertTrue(reflow.shiftDown > 0f)
        assertEquals(reflow.shiftRight, reflow.shiftFor(500f, 100f).first, .001f)
        assertEquals(0f, reflow.shiftFor(100f, 100f).first, .001f)
        assertEquals(reflow.shiftDown, reflow.shiftFor(100f, 400f).second, .001f)
    }

    @Test
    fun `single section has no outer border`() {
        assertTrue(NoteGrid().segments(Box(-100f, -100f, 1000f, 1000f)).isEmpty())
    }

    @Test
    fun `top and side sections stay open with only shared separators`() {
        val grid = NoteGrid(rows = listOf(
            NoteGridRow(100f, listOf(120f, 180f)),
            NoteGridRow(140f, listOf(80f, 100f, 120f))
        ))
        val actual = grid.segments(Box(-10f, -10f, 500f, 500f))
        assertEquals(setOf(
            NoteGrid.Segment(120f, 0f, 120f, 100f),
            NoteGrid.Segment(0f, 100f, 300f, 100f),
            NoteGrid.Segment(80f, 100f, 80f, 240f),
            NoteGrid.Segment(180f, 100f, 180f, 240f)
        ), actual.toSet())
        assertEquals("Shared row boundary is painted once", 4, actual.size)
    }

    @Test
    fun `unequal row widths do not border unmatched outer sections`() {
        val grid = NoteGrid(rows = listOf(
            NoteGridRow(100f, listOf(100f)),
            NoteGridRow(100f, listOf(150f, 150f)),
            NoteGridRow(100f, listOf(200f))
        ))
        val horizontal = grid.segments(Box(-10f, -10f, 500f, 500f))
            .filter { it.y0 == it.y1 }
        assertEquals(listOf(
            NoteGrid.Segment(0f, 100f, 100f, 100f),
            NoteGrid.Segment(0f, 200f, 200f, 200f)
        ), horizontal)
    }

    @Test
    fun `reflow moves interior separators while leaving expanded perimeter open`() {
        val original = NoteGrid(rows = listOf(
            NoteGridRow(100f, listOf(100f, 100f)),
            NoteGridRow(100f, listOf(200f))
        ))
        val reflow = original.accommodate(50f, 50f, Box(40f, 40f, 130f, 120f))
        val expanded = reflow.grid.segments(Box(-10f, -10f, 1000f, 1000f))
        val columnRight = 100f + reflow.shiftRight
        val rowBottom = 100f + reflow.shiftDown
        assertEquals(listOf(
            NoteGrid.Segment(columnRight, 0f, columnRight, rowBottom),
            NoteGrid.Segment(0f, rowBottom, 200f, rowBottom)
        ), expanded)
    }

    @Test
    fun `viewport outside all shared separators gets no lines`() {
        val grid = NoteGrid(rows = listOf(NoteGridRow(100f, listOf(100f, 100f))))
        assertTrue(grid.segments(Box(210f, 0f, 500f, 100f)).isEmpty())
        assertTrue(grid.segments(Box(0f, 110f, 200f, 200f)).isEmpty())
    }

    @Test
    fun `fitting after deletion compacts both axes and translates downstream content`() {
        val grid = NoteGrid(
            rows = listOf(NoteGridRow(500f, listOf(600f, 300f)), NoteGridRow(300f, listOf(400f))),
            newColumnWidth = 100f, newRowHeight = 100f
        )
        val first = NoteGrid.Cell(0, 0)
        val right = NoteGrid.Cell(0, 1)
        val below = NoteGrid.Cell(1, 0)
        val fit = grid.fitContents(listOf(
            NoteGrid.Content(first, Box(20f, 20f, 300f, 180f)),
            NoteGrid.Content(right, Box(620f, 20f, 650f, 80f)),
            NoteGrid.Content(below, Box(20f, 520f, 60f, 550f))
        ))
        assertEquals(listOf(328f, 100f), fit.grid.rows[0].columns)
        assertEquals(208f, fit.grid.rows[0].height, .001f)
        assertEquals(-272f to 0f, fit.shiftFor(right))
        assertEquals(0f to -292f, fit.shiftFor(below))
        assertEquals(348f to 20f, fit.remap(620f, 20f, right))
        assertEquals(20f to 228f, fit.remap(20f, 520f, below))
    }

    @Test
    fun `all objects in a cell constrain its size so removing one never clips another`() {
        val grid = NoteGrid(
            rows = listOf(NoteGridRow(600f, listOf(800f))),
            newColumnWidth = 100f, newRowHeight = 100f
        )
        val cell = NoteGrid.Cell(0, 0)
        val fit = grid.fitContents(listOf(
            NoteGrid.Content(cell, Box(10f, 10f, 50f, 50f)),
            NoteGrid.Content(cell, Box(60f, 120f, 480f, 370f))
        ))
        assertEquals(508f, fit.grid.rows.single().columns.single(), .001f)
        assertEquals(398f, fit.grid.rows.single().height, .001f)
        assertEquals(0f to 0f, fit.shiftFor(cell))
    }

    @Test
    fun `unfinished stroke grows its original cell before lift without moving the pen origin`() {
        val grid = NoteGrid(
            rows = listOf(
                NoteGridRow(400f, listOf(300f)),
                NoteGridRow(400f, listOf(300f, 300f, 300f)),
                NoteGridRow(400f, listOf(300f))
            ),
            newColumnWidth = 100f, newRowHeight = 100f, modifiedAt = 77L
        )
        val writing = grid.cellAt(320f, 420f)
        assertEquals(NoteGrid.Cell(1, 1), writing)
        val live = NoteGrid.Content(writing, Box(310f, 410f, 650f, 900f))
        val preview = grid.fitContents(listOf(live), pinnedCell = writing)
        assertEquals(0f to 0f, preview.shiftFor(writing))
        assertEquals(300f to 400f, preview.grid.originOf(writing))
        assertEquals(378f, preview.grid.rows[1].columns[1], .001f)
        assertEquals(528f, preview.grid.rows[1].height, .001f)
        assertEquals(78f to 0f, preview.shiftFor(NoteGrid.Cell(1, 2)))
        assertEquals(0f to 128f, preview.shiftFor(NoteGrid.Cell(2, 0)))
        assertEquals("A preview cannot publish a newer committed layout", 77L, preview.grid.modifiedAt)
    }

    @Test
    fun `clearing all content resets to one open section at the preset minimum`() {
        val grid = NoteGrid(
            rows = listOf(NoteGridRow(900f, listOf(800f, 700f)), NoteGridRow(600f, listOf(500f))),
            newColumnWidth = 120f, newRowHeight = 90f
        )
        val fit = grid.fitContents(emptyList())
        assertEquals(listOf(NoteGridRow(90f, listOf(120f))), fit.grid.rows)
        assertTrue(fit.grid.segments(Box(-100f, -100f, 2000f, 2000f)).isEmpty())
        assertEquals(grid.contentGap, fit.grid.contentGap, .001f)
        assertEquals(grid.modifiedAt, fit.grid.modifiedAt)
    }

    @Test
    fun `moved content crossing left and top boundaries is kept intact inside its assigned section`() {
        val grid = NoteGrid(
            rows = listOf(NoteGridRow(200f, listOf(200f, 200f))),
            newColumnWidth = 100f, newRowHeight = 100f
        )
        val cell = NoteGrid.Cell(0, 1)
        val fit = grid.fitContents(listOf(NoteGrid.Content(cell, Box(180f, -20f, 270f, 40f))))
        assertEquals(100f to 0f, fit.remap(180f, -20f, cell))
        assertEquals(190f to 60f, fit.remap(270f, 40f, cell))
        assertEquals(118f, fit.grid.rows[0].columns[1], .001f)
        assertEquals(100f, fit.grid.rows[0].height, .001f)
    }

    @Test
    fun `off grid content grows the last explicit section without creating sections`() {
        val grid = NoteGrid(newColumnWidth = 100f, newRowHeight = 100f)
        val cell = grid.cellAt(250f, 450f)
        assertEquals(NoteGrid.Cell(0, 0), cell)
        val fit = grid.fitContents(listOf(NoteGrid.Content(cell, Box(240f, 440f, 390f, 590f))))
        assertEquals(1, fit.grid.rows.size)
        assertEquals(1, fit.grid.rows[0].columns.size)
        assertEquals(418f, fit.grid.rows[0].columns[0], .001f)
        assertEquals(618f, fit.grid.rows[0].height, .001f)
    }

    @Test
    fun `far coordinates and negative coordinates resolve within the existing layout`() {
        val grid = NoteGrid(rows = listOf(
            NoteGridRow(100f, listOf(100f)), NoteGridRow(200f, listOf(120f, 160f))
        ))
        assertEquals(NoteGrid.Cell(0, 0), grid.cellAt(-500f, -500f))
        assertEquals(NoteGrid.Cell(1, 1), grid.cellAt(50_000f, 50_000f))
        assertEquals(NoteGrid.Cell(1, 0), grid.cellAt(20f, 50_000f))
        val overflow = grid.accommodate(50_000f, 50_000f, Box(49_990f, 49_990f, 50_010f, 50_010f))
        assertEquals(2, overflow.grid.rows.size)
        assertEquals(listOf(1, 2), overflow.grid.rows.map { it.columns.size })
    }

    @Test
    fun `stale memberships fit within existing topology rather than adding sections`() {
        val grid = NoteGrid()
        val cell = NoteGrid.Cell(50, 50)
        val fit = grid.fitContents(listOf(NoteGrid.Content(cell, Box(20f, 20f, 500f, 500f))), pinnedCell = cell)
        assertEquals(1, fit.grid.rows.size)
        assertEquals(1, fit.grid.rows[0].columns.size)
        assertEquals(528f, fit.grid.rows[0].height, .001f)
        assertEquals(0f to 0f, fit.shiftFor(cell))
    }

    @Test
    fun `explicit empty sections remain after all ink is removed`() {
        val grid = NoteGrid().addColumn(0).addRow()
        val fitted = grid.fitContents(emptyList()).grid
        assertEquals(listOf(2, 1), fitted.rows.map { it.columns.size })
        assertTrue(fitted.manualSections)
        assertTrue(fitted.segments(Box(0f, 0f, 2000f, 2000f)).isNotEmpty())
    }

    @Test
    fun `explicit section choices survive saving and reopening an empty notebook`() {
        val grid = NoteGrid().addColumn(0).addRow()
        val canvas = InkCanvas.startingAt(600f, 800f, background = "NOTE_GRID", ownPaper = true)
            .copy(noteGrid = grid)
        val document = InkDocument(docId = "explicit-sections",
            source = InkDocument.SourceRef(name = "notes.pdf", kind = "pdf"), canvas = canvas)
        val reopened = requireNotNull(InkDocument.parse(document.serialize()))
        assertEquals(grid, reopened.canvas?.noteGrid)
        assertEquals(listOf(2, 1), reopened.canvas!!.noteGrid!!.fitContents(emptyList()).grid.rows.map { it.columns.size })
    }

    @Test
    fun `adding below a chosen row keeps independent columns and inserts before later rows`() {
        val first = NoteGridRow(300f, listOf(200f, 400f))
        val last = NoteGridRow(500f, listOf(800f))
        val grid = NoteGrid(rows = listOf(first, last), newRowHeight = 120f)
        assertEquals(listOf(first, NoteGridRow(120f, listOf(600f)), last), grid.addRow(0).rows)
        assertEquals(listOf(first, last, NoteGridRow(120f, listOf(800f))), grid.addRow().rows)
    }

    @Test
    fun `removing a column merges its space and retains later boundary coordinates`() {
        val grid = NoteGrid(rows = listOf(NoteGridRow(100f, listOf(100f, 200f, 300f))))
        assertEquals(listOf(300f, 300f), grid.removeCell(NoteGrid.Cell(0, 1)).rows[0].columns)
        assertEquals(listOf(300f, 300f), grid.removeCell(NoteGrid.Cell(0, 0)).rows[0].columns)
        val lastRemoved = grid.removeCell(NoteGrid.Cell(0, 2))
        assertEquals(listOf(100f, 500f), lastRemoved.rows[0].columns)
        assertEquals(100f to 0f, lastRemoved.originOf(NoteGrid.Cell(0, 1)))
        assertTrue(lastRemoved.manualSections)
    }

    @Test
    fun `removing a single column row merges height and extends neighbor width without moving later rows`() {
        val last = NoteGridRow(90f, listOf(80f, 120f))
        val grid = NoteGrid(rows = listOf(
            NoteGridRow(100f, listOf(100f, 200f)), NoteGridRow(140f, listOf(500f)), last
        ))
        val merged = grid.removeCell(NoteGrid.Cell(1, 0))
        assertEquals(listOf(NoteGridRow(240f, listOf(100f, 400f)), last), merged.rows)
        assertEquals(0f to 240f, merged.originOf(NoteGrid.Cell(1, 0)))
        assertEquals(grid.originOf(NoteGrid.Cell(2, 0)), merged.originOf(NoteGrid.Cell(1, 0)))
    }

    @Test
    fun `removing the first single column row merges with the next and final section remains`() {
        val grid = NoteGrid(rows = listOf(
            NoteGridRow(140f, listOf(500f)), NoteGridRow(100f, listOf(100f, 200f))
        ))
        val merged = grid.removeCell(NoteGrid.Cell(0, 0))
        assertEquals(listOf(NoteGridRow(240f, listOf(100f, 400f))), merged.rows)
        val last = NoteGrid().copy(manualSections = true)
        assertEquals(last, last.removeCell(NoteGrid.Cell(0, 0)))
    }

    @Test
    fun `legacy layouts with different preset settings still merge commutatively`() {
        val a = NoteGrid(rows = listOf(NoteGridRow(400f, listOf(200f))),
            contentGap = 28f, newColumnWidth = 100f, newRowHeight = 100f)
        val b = NoteGrid(rows = listOf(NoteGridRow(200f, listOf(300f, 200f))),
            contentGap = 36f, newColumnWidth = 180f, newRowHeight = 160f)
        assertEquals(a.mergeWith(b), b.mergeWith(a))
        assertEquals(listOf(NoteGridRow(400f, listOf(300f, 200f))), a.mergeWith(b).rows)
    }

    @Test
    fun `fitting relocated contents again is stable after both positive and negative shifts`() {
        val grid = NoteGrid(
            rows = listOf(NoteGridRow(500f, listOf(500f, 200f)), NoteGridRow(100f, listOf(100f))),
            newColumnWidth = 100f, newRowHeight = 100f
        )
        val contents = listOf(
            NoteGrid.Content(NoteGrid.Cell(0, 0), Box(10f, 10f, 180f, 180f)),
            NoteGrid.Content(NoteGrid.Cell(0, 1), Box(520f, 20f, 900f, 280f)),
            NoteGrid.Content(NoteGrid.Cell(1, 0), Box(10f, 510f, 50f, 550f))
        )
        val fit = grid.fitContents(contents)
        val relocated = contents.map { content ->
            val (left, top) = fit.remap(content.bounds.left, content.bounds.top, content.cell)
            val (right, bottom) = fit.remap(content.bounds.right, content.bounds.bottom, content.cell)
            content.copy(bounds = Box(left, top, right, bottom))
        }
        val next = fit.grid.fitContents(relocated)
        assertEquals(fit.grid, next.grid)
        relocated.forEach { assertEquals(0f to 0f, next.shiftFor(it.cell)) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `nonfinite content cannot poison fitted dimensions`() {
        NoteGrid().fitContents(listOf(NoteGrid.Content(NoteGrid.Cell(0, 0), Box(0f, 0f, Float.NaN, 20f))))
    }
}
