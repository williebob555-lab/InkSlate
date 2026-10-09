package com.inksheets.core.omr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceTest {
    private val rate = 1000
    // 60 bpm: a quarter is a second, 1000 samples.
    private fun note(x: Float, base: Int = 4, tie: Boolean = false, marks: List<String> = emptyList(), midi: Int = 60) =
        Note(listOf(4), listOf(Pitch(0, midi / 12 - 1)), Duration(base), x, tie = tie, articulations = marks)

    private fun bar(n: Int, events: List<Event>, directions: List<Direction> = emptyList(), doubts: List<String> = emptyList()) =
        Measure(n, 0, 0, Box(0, 0, 100, 10), 10f, Clef.TREBLE, Key(0), TimeSig(4, 4), events, directions = directions, doubts = doubts)

    private fun play(vararg bars: Measure) = Performance.play(bars.toList(), 60.0, rate, 0, Synth.BRASS)

    @Test
    fun `a pickup lasts as long as its notes, and the bar after starts when it ends`() {
        val p = play(bar(1, listOf(note(10f))), bar(2, listOf(note(10f), note(20f), note(30f), note(40f))))
        assertEquals(listOf(0L, 1000L), p.barStarts.toList())
        // A bar read short and in doubt is still a whole bar long.
        val q = play(bar(1, listOf(note(10f)), doubts = listOf("1 beats found, 4 expected")), bar(2, listOf(note(10f))))
        assertEquals(4000L, q.barStarts[1])
    }

    @Test
    fun `tied notes are one note held, across the barline too`() {
        val p = play(bar(1, listOf(note(10f), note(20f), note(30f), note(40f, tie = true))), bar(2, listOf(note(10f, base = 2), note(50f, base = 2))))
        // Four in bar 1, the last held into bar 2's first: four tones in all, not six... one of them 3 beats long.
        assertEquals(5, p.tones.size)
        assertTrue(p.tones.any { it.start == 3000L && it.length >= 2500L })
    }

    @Test
    fun `staccato short, slurred joined, a fermata held and the music waits for it`() {
        val p = play(bar(1, listOf(note(10f, marks = listOf("staccato")), note(20f), note(30f, marks = listOf("fermata")), note(40f))))
        val byStart = p.tones.sortedBy { it.start }
        assertTrue(byStart[0].length <= 500L)
        assertTrue(byStart[1].length in 750L..900L)
        assertTrue(byStart[2].length > 1500L)
        assertTrue("the note after a fermata comes late: ${byStart[3].start}", byStart[3].start > 3500L)
        val slurred = play(bar(1, listOf(note(10f), note(20f), note(30f), note(40f)), directions = listOf(Direction("slur", 5f, 45f))))
        assertEquals(1000L, slurred.tones.minBy { it.start }.length)
    }

    @Test
    fun `dynamics and accents set how loud, a ritardando slows`() {
        val p = play(bar(1, listOf(note(10f), note(20f, marks = listOf("accent")), note(30f), note(40f)), directions = listOf(Direction("dynamic", 35f, 38f, "p"))))
        val v = p.tones.sortedBy { it.start }.map { it.velocity }
        assertTrue("mf, then accented, then p: $v", v[1] > v[0] && v[2] < v[0])
        val rit = play(bar(1, listOf(note(10f), note(20f), note(30f), note(40f)), directions = listOf(Direction("text", 5f, 8f, "rit."))), bar(2, listOf(note(10f))))
        assertTrue("slower: bar 2 at ${rit.barStarts[1]}", rit.barStarts[1] > 4000L)
    }

    @Test
    fun `a breath mark ends the note before it early, and only that one`() {
        val plain = play(bar(1, listOf(note(0f, tie = false, marks = listOf("tenuto")), note(10f, marks = listOf("tenuto")), note(20f), note(30f))))
        val breathed = play(bar(1, listOf(note(0f, marks = listOf("tenuto")), note(10f, marks = listOf("tenuto")), note(20f), note(30f)), listOf(Direction("breath", 15f, above = true))))
        val a = plain.tones.sortedBy { it.start }; val b = breathed.tones.sortedBy { it.start }
        assertEquals("the first note as it was", a[0].length, b[0].length)
        assertTrue("the note before the breath shorter", b[1].length < a[1].length - rate * 0.1)
        assertEquals("the next note on its beat", a[2].start, b[2].start)
    }

    @Test
    fun `a double flat sounds two semitones down and is drawn as one`() {
        val n = Note(listOf(4), listOf(Pitch(6, 4)), Duration(4), 0f, accidentals = mapOf(4 to -2))
        val out = Signatures.repitch(listOf(n), Clef.TREBLE, Key(0))
        assertEquals(Pitch(6, 4).midi - 2, (out[0] as Note).pitches[0].midi)
        val m = bar(1, out + listOf(note(10f, base = 2), note(20f)))
        assertTrue(Engraver.line(listOf(m)).marks.any { it is Engraver.Symbol && it.name == "accidentalDoubleFlat" })
    }
}
