package com.inkslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteGridTest {
    @Test
    fun `rows have independent column counts`() {
        var grid = NoteGrid()
        grid = grid.accommodate(500f, 40f, Box(480f, 30f, 530f, 70f)).grid
        grid = grid.accommodate(40f, 300f, Box(30f, 290f, 80f, 330f)).grid
        assertEquals(2, grid.rows[0].columns.size)
        assertEquals(1, grid.rows[1].columns.size)
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
}
