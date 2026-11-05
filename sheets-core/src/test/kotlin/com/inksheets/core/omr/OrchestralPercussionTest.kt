package com.inksheets.core.omr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrchestralPercussionTest {
    @Test fun `named orchestral instruments do not fall back to a drum kit`() {
        assertEquals(DrumKind.TRIANGLE, DrumKind.of("percussion", "Triangle"))
        assertEquals(DrumKind.SUSPENDED_CYMBAL, DrumKind.of("percussion", "Suspended Cymbal"))
        assertEquals(DrumKind.CONCERT_BASS_DRUM, DrumKind.of("percussion", "Concert Bass Drum"))
        assertEquals(DrumKind.TAMBOURINE, DrumKind.of("percussion", "Tambourine"))
        assertEquals(DrumKind.TAM_TAM, DrumKind.of("percussion", "Tam-tam"))
    }

    @Test fun `pitched percussion keeps written pitch and gets its own voice`() {
        assertNull(DrumKind.of("percussion", "Timpani"))
        assertEquals("timpani", DrumKind.pitchedInstrument("percussion", "Timpani"))
        assertEquals(47, Midi.program("timpani"))
        assertEquals("marimba", DrumKind.pitchedInstrument("percussion", "Marimba 1"))
        assertEquals(12, Midi.program("marimba"))
    }

    @Test fun `ensemble midi writes a named triangle on the percussion channel`() {
        val measure = Measure(1, 0, 0, Box(0, 0, 100, 40), 10f, Clef.TREBLE, Key(0), TimeSig(4, 4),
            listOf(Note(listOf(3), listOf(Pitch(0, 4)), Duration(4), 20f)))
        val document = ScoreDocument(listOf(ScorePart("triangle", listOf(Score(listOf(measure), 1)),
            instrument = "percussion", label = "Triangle")))
        val bytes = Midi.write(document, 120.0)
        assertTrue(bytes.indices.any { i -> i + 2 < bytes.size && bytes[i].toInt() and 0xFF == 0x99 && bytes[i + 1].toInt() and 0xFF == 81 })
    }
}
