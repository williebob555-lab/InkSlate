package com.inksheets.core.omr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarryFixTest {
    private fun p(s: String): Pitch {
        val step = "CDEFGAB".indexOf(s[0])
        val alter = s.count { it == '#' } - s.count { it == 'b' }
        return Pitch(step, s.last().digitToInt(), alter)
    }

    private fun note(clef: Clef, pitch: String, base: Int = 4, x: Float = 0f): Note {
        val pi = p(pitch)
        return Note(listOf(clef.topLine - pi.diatonic), listOf(pi), Duration(base), x)
    }

    private fun bar(n: Int, key: Int, events: List<Event>, clef: Clef = Clef.TREBLE, doubts: List<String> = emptyList(), bars: Int = 1) =
        Measure(n, 0, 0, Box(0, 0, 100, 40), 8f, clef, Key(key), TimeSig(4, 4), events, doubts = doubts, bars = bars)

    /** Four quarter notes at [pitches], spaced 10 apart. */
    private fun quarters(vararg pitches: String, clef: Clef = Clef.TREBLE): List<Event> =
        pitches.mapIndexed { i, s -> note(clef, s, 4, i * 10f) }

    private fun score(vararg bars: Measure) = Score(bars.toList(), 1)

    private fun names(events: List<Event>) = events.filterIsInstance<Note>().map { it.pitches.joinToString("+") }

    // Concert flute (Bb): bars 1 and 3 read right; bar 2 read wrong (D5 for C5), put right.
    private val fluteRead = score(
        bar(1, -2, quarters("Bb4", "C5", "D5", "Eb5")),
        bar(2, -2, quarters("F5", "D5", "D5", "Bb4"), doubts = listOf("a note unclear")),
        bar(3, -2, quarters("Bb4", "Bb4", "C5", "D5"))
    )
    private val fluteFix = quarters("F5", "Eb5", "D5", "Bb4")

    @Test
    fun `a transposed unison is carried into a B-flat trumpet and an E-flat alto sax`() {
        // Trumpet (+2): the same line a tone higher, key C; read the same wrong way.
        val trumpetRead = score(
            bar(1, 0, quarters("C5", "D5", "E5", "F5")),
            bar(2, 0, quarters("G5", "E5", "E5", "C5"), doubts = listOf("x")),
            bar(3, 0, quarters("C5", "C5", "D5", "E5"))
        )
        val t = CarryFix.carry(fluteRead, mapOf(2 to fluteFix), 0, 2, trumpetRead, 2)
        assertNotNull(t)
        assertEquals(false, t!!.confirmed)
        assertEquals(listOf("G5", "F5", "E5", "C5"), names(t.events))
        // x positions are the trumpet reading's own.
        assertEquals(listOf(0f, 10f, 20f, 30f), t.events.map { it.x })

        // Alto sax (+9): up a major sixth, key G (one sharp), F# in the key.
        val altoRead = score(
            bar(1, 1, quarters("G5", "A5", "B5", "C6")),
            bar(2, 1, quarters("D6", "B5", "B5", "G5")),
            bar(3, 1, quarters("G5", "G5", "A5", "B5"))
        )
        val a = CarryFix.carry(fluteRead, mapOf(2 to fluteFix), 0, 2, altoRead, 9)!!
        assertEquals(listOf("D6", "C6", "B5", "G5"), names(a.events))
    }

    @Test
    fun `a flat note and its accidental are spelled for the other key`() {
        // Concert Eb in bar 2 put right to Eb; the trumpet's written F, with no accidental (key C).
        val trumpetRead = score(
            bar(1, 0, quarters("C5", "D5", "E5", "F5")),
            bar(2, 0, quarters("G5", "E5", "E5", "C5")),
            bar(3, 0, quarters("C5", "C5", "D5", "E5"))
        )
        val t = CarryFix.carry(fluteRead, mapOf(2 to fluteFix), 0, 2, trumpetRead, 2)!!
        val second = t.events[1] as Note
        assertEquals("F5", second.pitches[0].toString())
        assertTrue(second.accidentals.isEmpty())
        // Concert flute in Bb with the fix an Eb# ... instead a concert F# (flute key Bb): written G# in C.
        val fix = quarters("F5", "F#5", "D5", "Bb4")
        val t2 = CarryFix.carry(fluteRead, mapOf(2 to fix), 0, 2, trumpetRead, 2)!!
        val g = t2.events[1] as Note
        assertEquals("G#5", g.pitches[0].toString())
        assertEquals(mapOf(g.steps[0] to 1), g.accidentals)
    }

    @Test
    fun `an octave doubling is carried to the octave it is written in`() {
        // Piccolo-like octave: the other part reads the same line an octave lower.
        val lowRead = score(
            bar(1, -2, quarters("Bb3", "C4", "D4", "Eb4")),
            bar(2, -2, quarters("F4", "D4", "D4", "Bb3")),
            bar(3, -2, quarters("Bb3", "Bb3", "C4", "D4"))
        )
        val c = CarryFix.carry(fluteRead, mapOf(2 to fluteFix), 0, 2, lowRead, 0)!!
        assertEquals(listOf("F4", "Eb4", "D4", "Bb3"), names(c.events))
    }

    @Test
    fun `a bar that only looks like it is left alone`() {
        // One note different (E5 for D5 at the third): not the same measure.
        val other = score(
            bar(1, 0, quarters("C5", "D5", "E5", "F5")),
            bar(2, 0, quarters("G5", "E5", "F5", "C5")),
            bar(3, 0, quarters("C5", "C5", "D5", "E5"))
        )
        assertNull(CarryFix.carry(fluteRead, mapOf(2 to fluteFix), 0, 2, other, 2))
        // Different rhythm: a half note for two quarters.
        val rhythm = score(
            bar(1, 0, quarters("C5", "D5", "E5", "F5")),
            bar(2, 0, listOf(note(Clef.TREBLE, "G5", 4, 0f), note(Clef.TREBLE, "E5", 4, 10f), note(Clef.TREBLE, "E5", 2, 20f))),
            bar(3, 0, quarters("C5", "C5", "D5", "E5"))
        )
        assertNull(CarryFix.carry(fluteRead, mapOf(2 to fluteFix), 0, 2, rhythm, 2))
        // Only part of the line an octave out (one note an octave away): not a whole-octave difference.
        val partial = score(
            bar(1, 0, quarters("C5", "D5", "E5", "F5")),
            bar(2, 0, quarters("G5", "E5", "E4", "C5")),
            bar(3, 0, quarters("C5", "C5", "D5", "E5"))
        )
        assertNull(CarryFix.carry(fluteRead, mapOf(2 to fluteFix), 0, 2, partial, 2))
        // The key is not the transposed one (flute in Bb; the other part in G, not C).
        val wrongKey = score(
            bar(1, 1, quarters("C5", "D5", "E5", "F5")),
            bar(2, 1, quarters("G5", "E5", "E5", "C5")),
            bar(3, 1, quarters("C5", "C5", "D5", "E5"))
        )
        assertNull(CarryFix.carry(fluteRead, mapOf(2 to fluteFix), 0, 2, wrongKey, 2))
        // A different clef.
        val bass = score(
            bar(1, 0, quarters("C3", "D3", "E3", "F3", clef = Clef.BASS), Clef.BASS),
            bar(2, 0, quarters("G3", "E3", "E3", "C3", clef = Clef.BASS), Clef.BASS),
            bar(3, 0, quarters("C3", "C3", "D3", "E3", clef = Clef.BASS), Clef.BASS)
        )
        assertNull(CarryFix.carry(fluteRead, mapOf(2 to fluteFix), 0, 2, bass, 2))
    }

    @Test
    fun `a neighbouring bar must agree in rhythm`() {
        val trumpet = { r1: List<Event>, r3: List<Event> -> score(
            bar(1, 0, r1), bar(2, 0, quarters("G5", "E5", "E5", "C5")), bar(3, 0, r3)) }
        val half = listOf(note(Clef.TREBLE, "C5", 2, 0f), note(Clef.TREBLE, "D5", 2, 10f))
        // Both neighbours differ in rhythm: left alone.
        assertNull(CarryFix.carry(fluteRead, mapOf(2 to fluteFix), 0, 2, trumpet(half, half), 2))
        // One neighbour agrees: carried.
        assertNotNull(CarryFix.carry(fluteRead, mapOf(2 to fluteFix), 0, 2, trumpet(half, quarters("C5", "C5", "D5", "E5")), 2))
        // A lone bar with no neighbour at all: left alone.
        val lone = Score(listOf(fluteRead.measures[1]), 1)
        assertNull(CarryFix.carry(lone, mapOf(2 to fluteFix), 0, 2, lone, 0))
    }

    @Test
    fun `empty, rest and multi-bar rest bars are never matched`() {
        val rests: List<Event> = listOf(Rest(Duration(1), 0f))
        val restsRead = score(bar(1, -2, quarters("Bb4", "C5", "D5", "Eb5")), bar(2, -2, rests), bar(3, -2, quarters("Bb4", "C5", "D5", "Eb5")))
        assertNull(CarryFix.carry(restsRead, mapOf(2 to rests), 0, 2, restsRead, 0))
        // A multi-bar rest on one side.
        val multi = score(
            bar(1, 0, quarters("C5", "D5", "E5", "F5")),
            bar(2, 0, quarters("G5", "E5", "E5", "C5"), bars = 4),
            bar(3, 0, quarters("C5", "C5", "D5", "E5"))
        )
        assertNull(CarryFix.carry(fluteRead, mapOf(2 to fluteFix), 0, 2, multi, 2))
    }

    @Test
    fun `a reading already the fix is only confirmed`() {
        val trumpetRead = score(
            bar(1, 0, quarters("C5", "D5", "E5", "F5")),
            bar(2, 0, quarters("G5", "F5", "E5", "C5"), doubts = listOf("unsure")),
            bar(3, 0, quarters("C5", "C5", "D5", "E5"))
        )
        val c = CarryFix.carry(fluteRead, mapOf(2 to fluteFix), 0, 2, trumpetRead, 2)!!
        assertTrue(c.confirmed)
        assertEquals(listOf("G5", "F5", "E5", "C5"), names(c.events))
        // Sure already: nothing to do.
        val sure = trumpetRead.copy(measures = trumpetRead.measures.map { it.copy(doubts = emptyList()) })
        assertNull(CarryFix.carry(fluteRead, mapOf(2 to fluteFix), 0, 2, sure, 2))
    }

    @Test
    fun `carried fixes are kept as text and read back`() {
        val list = listOf(CarriedFix(2, "p1", "Flute", 2, fluteFix, false), CarriedFix(5, "p1", "Flute", 5, fluteFix, true))
        assertEquals(list, Scores.decodeCarried(Scores.encodeCarried(list)))
        assertEquals(emptyList<CarriedFix>(), Scores.decodeCarried("not json"))
    }
}
