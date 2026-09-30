package com.inksheets.core.omr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiTest {
    @Test
    fun `writes a playable file, sounding pitch, bars kept in step`() {
        val c4 = Pitch(0, 4)
        val m1 = Measure(1, 0, 0, Box(0, 0, 0, 0), 10f, Clef.TREBLE, Key(0), TimeSig(4, 4),
            listOf(Note(listOf(8), listOf(c4), Duration(4), 0f), Note(listOf(7), listOf(Pitch(1, 4)), Duration(2, 1), 0f)))
        // Read wrongly: five beats in a four-beat bar - cut to the bar.
        val m2 = m1.copy(number = 2, events = m1.events + Note(listOf(6), listOf(Pitch(2, 4)), Duration(4), 0f))
        val rest = m1.copy(number = 3, events = listOf(Rest(Duration(1), 0f)), bars = 4)
        val bytes = Midi.write(Score(listOf(m1, m2, rest), 1), bpm = 120.0, transpose = 2, program = 56)
        assertEquals("MThd", String(bytes, 0, 4))
        // Read back with Java Sound: notes sounding a tone below written (B-flat trumpet).
        val seq = javax.sound.midi.MidiSystem.getSequence(java.io.ByteArrayInputStream(bytes))
        val track = seq.tracks[0]
        val ons = (0 until track.size()).map { track[it] }.filter { (it.message as? javax.sound.midi.ShortMessage)?.let { m -> m.command == 0x90 && m.data2 > 0 } == true }
        assertEquals(listOf(58, 60, 58, 60), ons.map { (it.message as javax.sound.midi.ShortMessage).data1 })
        assertEquals(listOf(0L, 480L, 1920L, 2400L), ons.map { it.tick })
        // Two bars, then four bars' rest: 6 bars of 4/4.
        assertTrue(seq.tickLength >= 6 * 1920L)
    }
}
