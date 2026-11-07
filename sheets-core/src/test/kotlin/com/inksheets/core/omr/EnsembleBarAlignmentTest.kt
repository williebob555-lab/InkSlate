package com.inksheets.core.omr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnsembleBarAlignmentTest {
    private fun bar(number: Int, pitch: Int, bars: Int = 1, repeatStart: Boolean = false, repeatEnd: Boolean = false) =
        Measure(number, 0, 0, Box(0, 0, 100, 40), 10f, Clef.TREBLE, Key(0), TimeSig(4, 4),
            listOf(Note(listOf(0), listOf(Pitch(0, pitch)), Duration(4), 20f)),
            repeatStart = repeatStart, repeatEnd = repeatEnd, bars = bars)

    @Test fun `a bad printed number does not shift later parts`() {
        val lead = Score(listOf(bar(1, 0), bar(2, 1), bar(3, 2), bar(4, 3)), 1)
        val other = Score(listOf(bar(1, 4), bar(99, 5), bar(3, 6), bar(4, 7)), 1)
        val aligned = EnsembleBarAlignment.align(lead, lead.measures, other)
        assertEquals(listOf(4, 5, 6, 7), aligned.map { it!!.events.filterIsInstance<Note>().single().pitches.single().octave })
    }

    @Test fun `the lead repeat order is shared by every part`() {
        val lead = Score(listOf(bar(1, 0, repeatStart = true), bar(2, 1, repeatEnd = true), bar(3, 2)), 1)
        val other = Score(listOf(bar(1, 4), bar(2, 5), bar(3, 6)), 1)
        val played = PlayOrder.unrolled(lead).measures
        val aligned = EnsembleBarAlignment.align(lead, played, other)
        assertEquals(listOf(4, 5, 4, 5, 6), aligned.map { it!!.events.filterIsInstance<Note>().single().pitches.single().octave })
    }

    @Test fun `multi measure rests occupy silent canonical bars`() {
        val lead = Score(listOf(bar(1, 0), bar(2, 1), bar(3, 2)), 1)
        val other = Score(listOf(bar(1, 4), bar(2, 5, bars = 2)), 1)
        val aligned = EnsembleBarAlignment.align(lead, lead.measures, other)
        assertEquals(4, aligned[0]!!.events.filterIsInstance<Note>().single().pitches.single().octave)
        assertNull(aligned[1])
        assertNull(aligned[2])
    }

    @Test fun `printed multi measure rest gets one continuous progress pass`() {
        val rest = bar(1, 0, bars = 4).copy(events = listOf(Rest(Duration(1), 20f)))
        val lead = Score(listOf(rest, bar(5, 1)), 1)
        val synth = Synth(1_000).apply { harmonicLimit = 1; roomEnabled = false }
        val player = EnsemblePlayer(synth, lead, emptyList(), 1, 5, 60.0)
        player.fill(FloatArray(4_000))
        assertTrue("one quarter of the printed rest", player.barProgress in .24f..26f)
        player.fill(FloatArray(8_000))
        assertTrue("three quarters of the printed rest", player.barProgress in .74f..76f)
    }
}
