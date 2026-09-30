package com.inksheets.core.omr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/** The instrument plays the notes asked, at the pitch they sound, when they are due. */
class SynthTest {
    private val rate = 48_000

    /** Strength of [freq] in [x] (Goertzel). */
    private fun power(x: FloatArray, freq: Double): Double {
        val k = 2 * cos(2 * PI * freq / rate)
        var s1 = 0.0; var s2 = 0.0
        for (v in x) { val s0 = v + k * s1 - s2; s2 = s1; s1 = s0 }
        return s1 * s1 + s2 * s2 - k * s1 * s2
    }

    private fun render(p: ScorePlayer, seconds: Double): FloatArray {
        val out = FloatArray((seconds * rate).toInt())
        val block = FloatArray(480)
        var i = 0
        while (i < out.size) {
            java.util.Arrays.fill(block, 0f)
            p.fill(block)
            System.arraycopy(block, 0, out, i, minOf(block.size, out.size - i))
            i += block.size
        }
        return out
    }

    private fun bar(number: Int, vararg midis: Int, base: Int = 4) = Measure(
        number, 0, 0, Box(0, 0, 100, 40), 10f, Clef.TREBLE, Key(0), TimeSig(4, 4),
        midis.mapIndexed { i, m -> Note(listOf(0), listOf(pitchOf(m)), Duration(base), i * 10f) }
    )

    private fun pitchOf(midi: Int): Pitch {
        val names = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6)
        val alters = intArrayOf(0, 1, 0, 1, 0, 0, 1, 0, 1, 0, 1, 0)
        return Pitch(names[midi % 12], midi / 12 - 1, alters[midi % 12])
    }

    @Test
    fun `a note sounds at its pitch, transposed as the instrument sounds`() {
        // A B-flat trumpet's written D5 sounds C5.
        val score = Score(listOf(bar(1, 74, 74, 74, 74)), 1, listOf(1000))
        val p = ScorePlayer(Synth(rate), score, 1, 1, 120.0, transpose = 2, patch = Synth.BRASS)
        val x = render(p, 1.5)
        val c5 = power(x, 523.25); val d5 = power(x, 587.33); val b4 = power(x, 493.88)
        println("C5 $c5, D5 $d5, B4 $b4")
        assertTrue(c5 > d5 * 20 && c5 > b4 * 20)
        for (patch in listOf(Synth.CLARINET, Synth.FLUTE, Synth.STRINGS, Synth.MALLET, Synth.BASS, Synth.SAX, Synth.PIANO)) {
            val y = render(ScorePlayer(Synth(rate), score, 1, 1, 120.0, 2, patch), 1.0)
            assertTrue("$patch", power(y, 523.25) > power(y, 587.33) * 10)
            assertTrue("loud enough, never clipping", y.maxOf { abs(it) } in 0.05f..1f)
        }
    }

    @Test
    fun `notes come on the beat at the tempo`() {
        // Four quarter notes, C5 D5 E5 F5, at 120: a note every half second.
        val score = Score(listOf(bar(1, 72, 74, 76, 77)), 1, listOf(1000))
        val x = render(ScorePlayer(Synth(rate), score, 1, 1, 120.0, 0, Synth.PIANO), 2.2)
        // Which note is loudest in each quarter second.
        val names = listOf(72, 74, 76, 77)
        val heard = (0 until 8).map { k ->
            val w = x.copyOfRange(k * rate / 4, (k + 1) * rate / 4)
            names.maxByOrNull { power(w, Synth.frequency(it.toDouble())) }
        }
        println("heard $heard")
        assertEquals(listOf(72, 72, 74, 74, 76, 76, 77, 77), heard)
    }

    @Test
    fun `a loop goes round, faster each time up to the tempo wanted`() {
        val score = Score(listOf(bar(1, 72, 72, 72, 72), bar(2, 74, 74, 74, 74)), 1, listOf(1000))
        val p = ScorePlayer(Synth(rate), score, 1, 2, 60.0, 0, Synth.PIANO, loop = true, rampTo = 70.0, rampStep = 5.0)
        val seen = ArrayList<Pair<Int, Int>>()
        val block = FloatArray(480)
        repeat(rate * 30 / 480) {
            p.fill(block)
            if (seen.lastOrNull() != p.round to p.bar) seen += p.round to p.bar
        }
        println("rounds and bars $seen, bpm ${p.bpm}")
        assertEquals(listOf(0 to 1, 0 to 2, 1 to 1, 1 to 2, 2 to 1, 2 to 2, 3 to 1), seen.take(7))
        assertEquals(70.0, p.bpm, 1e-9)
    }
}
