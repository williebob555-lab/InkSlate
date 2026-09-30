package com.inksheets.core.omr

import org.junit.Assert.assertEquals
import org.junit.Test

/** Repeats and endings played in the order a band plays them. */
class PlayOrderTest {
    private fun bar(n: Int, start: Boolean = false, end: Boolean = false, ending: Int = 0) =
        Measure(n, 0, 0, Box(0, 0, 1, 1), 1f, Clef.TREBLE, Key(0), TimeSig(4, 4), emptyList(), repeatStart = start, repeatEnd = end, ending = ending)

    @Test
    fun `a repeat is played twice`() {
        assertEquals(listOf(1, 2, 3, 2, 3, 4), PlayOrder.bars(listOf(bar(1), bar(2, start = true), bar(3, end = true), bar(4))))
        // From the top when no start is marked.
        assertEquals(listOf(1, 2, 1, 2, 3), PlayOrder.bars(listOf(bar(1), bar(2, end = true), bar(3))))
    }

    @Test
    fun `first and second endings`() {
        val bars = listOf(bar(1, start = true), bar(2), bar(3, ending = 1), bar(4, ending = 1, end = true), bar(5, ending = 2), bar(6))
        assertEquals(listOf(1, 2, 3, 4, 1, 2, 5, 6), PlayOrder.bars(bars))
    }

    @Test
    fun `two repeated sections one after another`() {
        val bars = listOf(bar(1, start = true), bar(2, end = true), bar(3, start = true), bar(4, end = true), bar(5))
        assertEquals(listOf(1, 2, 1, 2, 3, 4, 3, 4, 5), PlayOrder.bars(bars))
    }

    @Test
    fun `a repeat across a page break turns back to it`() {
        fun on(page: Int, n: Int, start: Boolean = false, end: Boolean = false) =
            Measure(n, page, 0, Box(0, 0, 1, 1), 1f, Clef.TREBLE, Key(0), TimeSig(4, 4), emptyList(), repeatStart = start, repeatEnd = end)
        val score = Score(listOf(on(0, 1), on(0, 2, start = true), on(1, 3), on(1, 4, end = true), on(1, 5)), 2)
        // At 60 a bar is 4 s: page 2 at bar 3 (8 s), back to page 1 for bar 2 again (16 s), on at bar 3 (20 s).
        assertEquals(listOf(8_000L to 1, 16_000L to 0, 20_000L to 1), ScoreAudio.pageChanges(PlayOrder.unrolled(score), 60.0))
    }
}
