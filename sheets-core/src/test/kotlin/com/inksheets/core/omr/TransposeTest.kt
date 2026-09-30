package com.inksheets.core.omr

import org.junit.Assert.assertEquals
import org.junit.Test

/** Re-written for another instrument, music sounds the same, spelled in the new key. */
class TransposeTest {
    private fun note(p: Pitch, clef: Clef = Clef.TREBLE) = Note(listOf(clef.topLine - p.diatonic), listOf(p), Duration(4), 0f)
    private fun bar(key: Int, vararg ps: Pitch) = Measure(1, 0, 0, Box(0, 0, 10, 10), 10f, Clef.TREBLE, Key(key), TimeSig(4, 4), ps.map { note(it) })

    @Test
    fun `a trumpet part as an alto sax reads it`() {
        // B-flat trumpet (written a tone above sounding) to E-flat alto (a major sixth above): up a fifth.
        val m = Transpose.measure(bar(0, Pitch(1, 5), Pitch(3, 5, 1), Pitch(0, 5)), 9 - 2)
        assertEquals(1, m.key.fifths)                                        // C major -> G major
        assertEquals(listOf("A5", "C#6", "G5"), m.events.map { (it as Note).pitches.single().toString() })
        assertEquals(listOf(emptyMap<Int, Int>(), mapOf(-4 to 1), emptyMap()), m.events.map { (it as Note).accidentals })
    }

    @Test
    fun `down a tone into flats, and a key that wraps round`() {
        val m = Transpose.measure(bar(0, Pitch(1, 5), Pitch(3, 5, 1), Pitch(6, 4)), -2)
        assertEquals(-2, m.key.fifths)                                       // B-flat major
        assertEquals(listOf("C5", "E5", "A4"), m.events.map { (it as Note).pitches.single().toString() })
        // B major up a major third would be D-sharp major - nine sharps - so E-flat major.
        val w = Transpose.measure(bar(5, Pitch(6, 4), Pitch(3, 5, 1)), 4)
        assertEquals(-3, w.key.fifths)
        assertEquals(listOf("Eb5", "Bb5"), w.events.map { (it as Note).pitches.single().toString() })
    }

    @Test
    fun `every note sounds where it did`() {
        val ps = listOf(Pitch(0, 4), Pitch(2, 4, -1), Pitch(4, 4, 1), Pitch(6, 5), Pitch(1, 3))
        for (semis in -12..12) for (key in -4..4) {
            val m = Transpose.measure(bar(key, *ps.toTypedArray()), semis)
            m.events.forEachIndexed { i, e -> assertEquals("$semis from key $key", ps[i].midi + semis, (e as Note).pitches.single().midi) }
            // Each head where its letter sits on the staff.
            m.events.forEach { e -> val n = e as Note; assertEquals(Clef.TREBLE.topLine - n.pitches.single().diatonic, n.steps.single()) }
        }
    }
}
