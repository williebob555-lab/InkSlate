package com.inksheets.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeckGridTest {
    private fun b(name: String, x: Int? = null, y: Int? = null) = RemoteButton(RemoteButton.ACTION, name, x = x, y = y)
    private fun cells(list: List<RemoteButton>) = list.associate { it.id to (it.x to it.y) }

    @Test
    fun `buttons without a cell fill the free ones row by row, placed ones stay put`() {
        val grid = DeckGrid(3, 2)
        val placed = grid.place(listOf(b("A"), b("B", 0, 0), b("C")))!!
        assertEquals(mapOf("A" to (1 to 0), "B" to (0 to 0), "C" to (2 to 0)), cells(placed))
    }

    @Test
    fun `a button dragged onto another swaps with it, onto a gap just moves`() {
        val grid = DeckGrid(2, 2)
        val deck = grid.place(listOf(b("A"), b("B"), b("C")))!!
        assertEquals(mapOf("A" to (1 to 0), "B" to (0 to 0), "C" to (0 to 1)), cells(grid.move(deck, 0, 1, 0)))
        assertEquals((1 to 1), cells(grid.move(deck, 0, 1, 1))["A"])
    }

    @Test
    fun `a button dropped from the library pushes the one there to the nearest gap, and a full grid refuses`() {
        val grid = DeckGrid(2, 2)
        val deck = grid.place(listOf(b("A"), b("B"), b("C")))!!
        val added = grid.add(deck, b("D"), 0, 0)!!
        assertEquals(mapOf("A" to (1 to 1), "B" to (1 to 0), "C" to (0 to 1), "D" to (0 to 0)), cells(added))
        assertNull(grid.add(added, b("E"), 0, 0))
    }

    @Test
    fun `a grid made smaller keeps what fits where it was, and refuses to drop buttons`() {
        val big = DeckGrid(3, 3)
        val deck = big.place(listOf(b("A", 0, 0), b("B", 2, 2)))!!
        assertEquals(mapOf("A" to (0 to 0), "B" to (1 to 0)), cells(big.resized(deck, DeckGrid(2, 2))!!))
        // Moving and adding: the first free cell, row by row.
        assertEquals(1 to 0, DeckGrid(2, 2).firstFree(listOf(b("A", 0, 0))))
        assertNull(big.resized(big.place((1..5).map { b("$it") })!!, DeckGrid(2, 2)))
        assertEquals(DeckGrid(4, 5), DeckGrid.parse("4x5"))
    }
}
