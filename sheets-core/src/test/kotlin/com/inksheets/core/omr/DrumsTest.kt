package com.inksheets.core.omr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Drum parts known by their names, and each drum sounding - struck, then rung out. */
class DrumsTest {
    @Test
    fun `which drums a part is`() {
        assertEquals(DrumKind.SNARE, DrumKind.of("drumline", "Dancing Queen - DL - Snare Drum"))
        assertEquals(DrumKind.BASS_DRUMS, DrumKind.of("drumline", "Barbie Girl - DL - Bass Drums"))
        assertEquals(DrumKind.TENORS, DrumKind.of("drumline", "Diva- DL Tenor Drums"))
        assertEquals(DrumKind.CYMBALS, DrumKind.of("drumline", "Diva- DL Cymbals"))
        assertEquals(DrumKind.KIT, DrumKind.of("drums", "Hot Hot Hot - Drum Set"))
        assertEquals(DrumKind.KIT, DrumKind.of(null, "Crab_Rave-Drumset"))
        assertNull("a tenor sax is no drum", DrumKind.of("tenor-sax", "24K Magic - Tenor Sax"))
        assertNull("mallets have pitches", DrumKind.of("percussion", "Mallets"))
        assertNull(DrumKind.of("trumpet", "Trumpet 1"))
    }

    @Test
    fun `a drum set's notes by where they sit`() {
        assertEquals(38, DrumKind.KIT.key(3))     // the third space: snare
        assertEquals(36, DrumKind.KIT.key(7))     // the bottom space: bass drum
        assertEquals(42, DrumKind.KIT.key(-1))    // over the staff: hi-hat
        assertEquals(49, DrumKind.KIT.key(-2))    // a ledger line over it: crash
        assertEquals(38, DrumKind.SNARE.key(8))   // a snare's part: the snare, wherever
    }

    @Test
    fun `each drum sounds and rings out`() {
        val rate = 44100
        for (key in listOf(36, 38, 42, 46, 49, 51, 41, 45, 50)) for (patch in listOf(Synth.DRUMS, Synth.BASS_DRUMS, Synth.TENORS)) {
            val synth = Synth(rate)
            synth.add(listOf(Synth.Tone(key, 0L, rate / 8L, 1f, patch)))
            val buf = FloatArray(rate * 3)
            synth.fill(buf)
            val early = buf.copyOfRange(0, rate / 10).maxOf { abs(it) }
            val late = buf.copyOfRange(rate * 5 / 2, rate * 3).maxOf { abs(it) }
            assertTrue("drum $key heard ($early)", early > 0.02f)
            assertTrue("drum $key within full scale", buf.all { abs(it) <= 1f })
            assertTrue("drum $key rung out ($late)", late < 1e-4f)
        }
    }

    @Test
    fun `a drum part played - drums struck, not pitches`() {
        val n = Note(listOf(3), listOf(Pitch(0, 5)), Duration(4), 0f)
        val bar = Measure(1, 0, 0, Box(0, 0, 100, 40), 10f, Clef.TREBLE, Key(0), TimeSig(4, 4), List(4) { n.copy(x = it.toFloat()) })
        val played = Performance.play(listOf(bar), 120.0, 44100, 0, Synth.PIANO, drums = DrumKind.KIT)
        assertEquals(4, played.tones.size)
        assertTrue(played.tones.all { it.midi == 38 && it.patch.drum })
    }
}
