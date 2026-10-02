package com.inksheets.core.omr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A clef, key or time put right by hand: from its bar on, until the print changes it. */
class SignaturesTest {
    private fun bar(n: Int, key: Int, time: TimeSig = TimeSig(4, 4), vararg steps: Int) =
        Measure(n, 0, 0, Box(0, 0, 100, 40), 10f, Clef.TREBLE, Key(key), time,
            steps.map { st -> Note(listOf(st), listOf(Pitch.fromDiatonic(Clef.TREBLE.at(st), Key(key).alterOf(Clef.TREBLE.at(st).mod(7)))), Duration(4), 0f) })

    @Test
    fun `a key put right carries on until the reading changes it, the notes pitched anew`() {
        // Read with no flats for three bars, then two flats (a printed change); B (step 4) in each.
        val s = Score(listOf(bar(1, 0, steps = intArrayOf(4, 4, 4, 4)), bar(2, 0, steps = intArrayOf(4, 4, 4, 4)), bar(3, 0, steps = intArrayOf(4, 4, 4, 4)),
            bar(4, -2, steps = intArrayOf(4, 4, 4, 4))), 1, listOf(100))
        // It is really one flat from bar 2.
        val fixed = Signatures.apply(s, mapOf(2 to SigFix(key = -1)))
        assertEquals(0, fixed.measures[0].key.fifths)
        assertEquals(-1, fixed.measures[1].key.fifths)
        assertEquals(-1, fixed.measures[2].key.fifths)
        assertEquals("B flat under the fix", -1, (fixed.measures[1].events[0] as Note).pitches[0].alter)
        assertEquals("the print's own change after it stands", -2, fixed.measures[3].key.fifths)
    }

    @Test
    fun `an accidental written carries on through its bar against the key put right`() {
        val n1 = Note(listOf(4), listOf(Pitch.fromDiatonic(Clef.TREBLE.at(4), 1)), Duration(4), 0f, accidentals = mapOf(4 to 1))
        val n2 = Note(listOf(4), listOf(Pitch.fromDiatonic(Clef.TREBLE.at(4), 0)), Duration(4), 1f)
        val out = Signatures.repitch(listOf(n1, n2), Clef.TREBLE, Key(-1))
        assertEquals(1, (out[0] as Note).pitches[0].alter)
        assertEquals("the sharp carries on in the bar", 1, (out[1] as Note).pitches[0].alter)
    }

    @Test
    fun `a time put right says again what the bars come to`() {
        // Three quarters a bar, read as 4/4: in doubt. Put right as 3/4: sure.
        val bars = (1..3).map { n -> bar(n, 0, TimeSig(4, 4), 4, 4, 4).let { if (n > 1) it.copy(doubts = listOf("3 beats found, 4 expected")) else it } }
        val fixed = Signatures.apply(Score(bars, 1, listOf(100)), mapOf(1 to SigFix(beats = 3, beatType = 4)))
        assertTrue(fixed.measures.all { it.sure })
        assertEquals(TimeSig(3, 4), fixed.measures[2].time)
        // Clef put right: the same steps, other pitches.
        val bass = Signatures.apply(Score(bars, 1, listOf(100)), mapOf(1 to SigFix(clef = Clef.BASS)))
        assertEquals(Clef.BASS.at(4), (bass.measures[0].events[0] as Note).pitches[0].let { it.octave * 7 + it.step })
    }

    @Test
    fun `no bar number comes twice - a page numbered from 1 again moved on past the one before`() {
        fun b(n: Int, page: Int, bars: Int = 1) = bar(n, 0).copy(page = page, bars = bars)
        // Pages 1-2 read as bars 1-5 (a rest of two bars among them); page 3 read on its own, from 1 again.
        val out = Scores.numberedOnce(listOf(b(1, 0), b(2, 0), b(3, 0, 2), b(5, 1), b(1, 2), b(2, 2), b(3, 2)))
        assertEquals(listOf(1, 2, 3, 5, 6, 7, 8), out.map { it.number })
        // Numbers already in order: left as they are (a pickup's 0, a gap the print has).
        assertEquals(listOf(0, 1, 2, 9), Scores.numberedOnce(listOf(b(0, 0), b(1, 0), b(2, 0), b(9, 1))).map { it.number })
    }
}
