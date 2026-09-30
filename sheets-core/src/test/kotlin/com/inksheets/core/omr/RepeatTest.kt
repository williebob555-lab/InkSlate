package com.inksheets.core.omr

import org.junit.Assert.assertEquals
import org.junit.Test

/** Repeat barlines engraved, read back, and played in their order. */
class RepeatTest {
    @Test
    fun `repeats are read and played twice`() {
        val r = java.util.Random(7)
        val space = 18f
        // Two lines of four: a repeat inside the first line, one from the start of the second to its end.
        val bars = (1..8).map { n -> TestPages.randomMeasure(r, n, Clef.TREBLE, Key(0), TimeSig(4, 4), n == 1 || n == 5) }
            .map { m -> m.copy(repeatStart = m.number == 2 || m.number == 5, repeatEnd = m.number == 3 || m.number == 8) }
        val lines = listOf(bars.take(4), bars.drop(4))
        val drawings = lines.map { Engraver.line(it) }
        val ink = Ink((drawings.maxOf { it.width } * space + 4 * space).toInt(), (2 * 14 * space + 6 * space).toInt())
        drawings.forEachIndexed { i, d -> Engraver.paint(ink, d, space, 2 * space, (4 + i * 14) * space) }
        TestPages.shot("repeats", ink)
        val read = Recognizer().read(ink)
        println(read.measures.map { "${it.number}${if (it.repeatStart) "|:" else ""}${if (it.repeatEnd) ":|" else ""}" })
        assertEquals(bars.map { it.repeatStart }, read.measures.map { it.repeatStart })
        assertEquals(bars.map { it.repeatEnd }, read.measures.map { it.repeatEnd })
        assertEquals(listOf(1, 2, 3, 2, 3, 4, 5, 6, 7, 8, 5, 6, 7, 8), PlayOrder.bars(read.measures))
    }

    @Test
    fun `first and second endings are read and taken in turn`() {
        val r = java.util.Random(11)
        val space = 18f
        val bars = (1..5).map { n -> TestPages.randomMeasure(r, n, Clef.TREBLE, Key(0), TimeSig(4, 4), n == 1) }
            .map { m -> m.copy(repeatStart = m.number == 1, repeatEnd = m.number == 3, ending = when (m.number) { 3 -> 1; 4 -> 2; else -> 0 }) }
        val d = Engraver.line(bars)
        val ink = Ink((d.width * space + 4 * space).toInt(), (14 * space).toInt())
        Engraver.paint(ink, d, space, 2 * space, 6 * space)
        TestPages.shot("endings", ink)
        val read = Recognizer().read(ink)
        println(read.measures.map { "${it.number}${if (it.repeatStart) "|:" else ""}${if (it.repeatEnd) ":|" else ""}${if (it.ending > 0) "[${it.ending}]" else ""}" })
        assertEquals(bars.map { it.ending }, read.measures.map { it.ending })
        assertEquals(listOf(1, 2, 3, 1, 2, 4, 5), PlayOrder.bars(read.measures))
    }

    @Test
    fun `D S al Coda is read and taken`() {
        val r = java.util.Random(5)
        val space = 18f
        // Segno at bar 2, To Coda at bar 3, D.S. after bar 4, the coda from bar 5.
        val bars = (1..6).map { n -> TestPages.randomMeasure(r, n, Clef.TREBLE, Key(0), TimeSig(4, 4), n == 1) }
            .map { m -> m.copy(segno = m.number == 2, coda = m.number == 3 || m.number == 5) }
        val d = Engraver.line(bars)
        val ink = Ink((d.width * space + 4 * space).toInt(), (16 * space).toInt())
        Engraver.paint(ink, d, space, 2 * space, 8 * space)
        TestPages.shot("ds-al-coda", ink)
        val read = Recognizer().read(ink)
        println(read.measures.map { "${it.number}${if (it.segno) "S" else ""}${if (it.coda) "C" else ""}" })
        assertEquals(bars.map { it.segno }, read.measures.map { it.segno })
        assertEquals(bars.map { it.coda }, read.measures.map { it.coda })
        assertEquals(listOf(1, 2, 3, 4, 2, 3, 5, 6), PlayOrder.unrolled(Score(read.measures, 1)).measures.map { it.number })
    }
}
