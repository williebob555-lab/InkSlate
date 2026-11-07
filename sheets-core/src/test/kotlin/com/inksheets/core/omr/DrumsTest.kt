package com.inksheets.core.omr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

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
            assertTrue("drum $key begins continuously instead of popping (${buf[0]})", abs(buf[0]) < 1e-6f)
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

    @Test
    fun `dense band keeps pitched section above percussion without clipping`() {
        val rate = 16_000
        fun measure(step: Int, midi: Int) = Measure(
            1, 0, 0, Box(0, 0, 100, 40), 10f, Clef.TREBLE, Key(0), TimeSig(4, 4),
            List(4) { i -> Note(listOf(step), listOf(Pitch(midi % 7, 4)), Duration(4), i * 10f) }
        )
        val lead = Score(listOf(measure(0, 0)), 1, listOf(1000))
        val pitchedScore = Score(listOf(measure(0, 2)), 1, listOf(1000))
        val drumScore = Score(listOf(measure(3, 0)), 1, listOf(1000))
        fun render(voices: List<EnsemblePlayer.Voice>): FloatArray {
            val player = EnsemblePlayer(Synth(rate), lead, voices, 1, 1, 120.0)
            val out = FloatArray(rate * 2)
            val block = FloatArray(160)
            var at = 0
            while (at < out.size) {
                block.fill(0f); player.fill(block)
                System.arraycopy(block, 0, out, at, minOf(block.size, out.size - at)); at += block.size
            }
            return out
        }
        fun rms(x: FloatArray) = sqrt(x.sumOf { it.toDouble() * it } / x.size)
        val pitched = render(List(8) { EnsemblePlayer.Voice(pitchedScore, 0, if (it % 2 == 0) Synth.BRASS else Synth.CLARINET) })
        val drums = render(listOf(
            EnsemblePlayer.Voice(drumScore, 0, Synth.DRUMS, DrumKind.SNARE),
            EnsemblePlayer.Voice(drumScore, 0, Synth.BASS_DRUMS, DrumKind.BASS_DRUMS)
        ))

        assertTrue("pitched RMS ${rms(pitched)} should exceed percussion ${rms(drums)}", rms(pitched) > rms(drums))
        assertTrue("percussion remains below output ceiling", drums.maxOf { abs(it) } < .98f)
        assertTrue("a drum strike begins continuously", abs(drums[0]) < 1e-6f)
    }

    @Test
    fun `concert band mix keeps static headroom instead of living on limiter`() {
        val rate = 16_000
        val notes = List(4) { i -> Note(listOf(i % 7), listOf(Pitch(i % 7, 4)), Duration(4), i * 20f) }
        val bar = Measure(1, 0, 0, Box(0, 0, 120, 40), 10f, Clef.TREBLE, Key(0), TimeSig(4, 4), notes)
        val score = Score(listOf(bar), 1, listOf(1000))
        val voices = List(20) { i ->
            val low = i % 4 == 0
            EnsemblePlayer.Voice(score, i % 12, if (low) Synth.LOW_BRASS else Synth.BRASS,
                balance = if (low) 1.5f else 1f)
        }
        val synth = Synth(rate)
        val player = EnsemblePlayer(synth, score, voices, 1, 1, 120.0)
        val block = FloatArray(160)
        var peak = 0f
        repeat(300) {
            block.fill(0f); player.fill(block)
            peak = maxOf(peak, block.maxOf { abs(it) })
        }

        assertTrue("static pitched gain ${player.pitchedGain} leaves section headroom", player.pitchedGain < .18f)
        assertTrue("limiter duty ${synth.limitedBlocks}/${synth.renderedBlocks}",
            synth.limitedBlocks * 100 < synth.renderedBlocks * 60)
        assertTrue("soft output ceiling $peak", peak < .98f)
    }
}
