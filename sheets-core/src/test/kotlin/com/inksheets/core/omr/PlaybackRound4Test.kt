package com.inksheets.core.omr

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Round 4: staccato, accent and marcato speak with a "ta" - fast, firm, bright from the first millisecond. */
class PlaybackRound4Test {
    private val rate = 48_000

    private fun render(t: Synth.Tone, seconds: Double): FloatArray {
        val s = Synth(rate); s.add(listOf(t))
        val out = FloatArray((seconds * rate).toInt()); val b = FloatArray(480)
        var i = 0
        while (i < out.size) { java.util.Arrays.fill(b, 0f); s.fill(b); System.arraycopy(b, 0, out, i, min(b.size, out.size - i)); i += b.size }
        return out
    }

    private fun rms(x: FloatArray, a: Int, b: Int): Double {
        var s = 0.0
        for (i in a.coerceAtLeast(0) until b.coerceAtMost(x.size)) s += x[i].toDouble() * x[i]
        return sqrt(s / max(1, b - a))
    }

    private fun centroid(x: FloatArray, from: Int, to: Int, f0: Double): Double {
        var num = 0.0; var den = 0.0
        for (h in 1..30) {
            val f = f0 * h
            if (f > 9000) break
            val k = 2 * cos(2 * PI * f / rate)
            var s1 = 0.0; var s2 = 0.0
            for (i in from until to) { val s0 = x[i] + k * s1 - s2; s2 = s1; s1 = s0 }
            val p = s1 * s1 + s2 * s2 - k * s1 * s2
            num += f * p; den += p
        }
        return num / den
    }

    /** Milliseconds from the start to the first 4 ms window within 3 dB of the loudest in the first 150 ms. */
    private fun toMinus3(x: FloatArray): Double {
        val win = rate / 250
        val env = (0 until 150 * rate / 1000 step rate / 2000).map { rms(x, it, it + win) }
        val peak = env.max()
        val k = env.indexOfFirst { it >= peak * 0.708 }
        return k * 0.5 + 2.0   // each step 0.5 ms; the window's centre
    }

    private fun tone(patch: Synth.Patch, midi: Int, kind: String): Synth.Tone {
        val length = if (kind == "staccato") (0.12 * rate).toLong() else (0.5 * rate).toLong()
        return when (kind) {
            "marcato" -> Synth.Tone(midi, 0, length, 0.7f, patch, accent = 1f, art = Synth.ART_MARCATO)
            "accent" -> Synth.Tone(midi, 0, length, 0.7f, patch, accent = 0.8f, art = Synth.ART_ACCENT)
            "staccato" -> Synth.Tone(midi, 0, length, 0.7f, patch, art = Synth.ART_STACCATO)
            else -> Synth.Tone(midi, 0, length, 0.7f, patch)
        }
    }

    @Test
    fun `an articulated note speaks at once and bright - a ta, not a wha`() {
        for ((name, patch, midi) in listOf(Triple("euphonium", Synth.LOW_BRASS, 58), Triple("trombone", Synth.TROMBONE, 58), Triple("trumpet", Synth.BRASS, 67), Triple("clarinet", Synth.CLARINET, 67))) {
            val f0 = Synth.frequency(midi.toDouble())
            val result = HashMap<String, Triple<Double, Double, Double>>()
            for (kind in listOf("plain", "staccato", "accent", "marcato")) {
                val x = render(tone(patch, midi, kind), 0.8)
                val t3 = toMinus3(x)
                val onset = centroid(x, 0, rate / 50, f0)                 // the first 20 ms
                val later = centroid(x, rate * 90 / 1000, rate * 110 / 1000, f0)  // around 100 ms
                result[kind] = Triple(t3, onset, later)
                println("R4 $name $kind: -3 dB at ${"%.1f".format(t3)} ms, centroid first 20 ms ${"%.0f".format(onset)} Hz vs at 100 ms ${"%.0f".format(later)} Hz")
            }
            for (kind in listOf("staccato", "accent", "marcato")) {
                val (t3, onset, later) = result.getValue(kind)
                assertTrue("$name $kind: full in $t3 ms", t3 <= 12.0)
                assertTrue("$name $kind: darker at the start ($onset) than at 100 ms ($later)", onset >= later * 0.97)
            }
            val plain = result.getValue("plain")
            assertTrue("$name: the tongued note is softer than an accent (${plain.first} vs ${result.getValue("accent").first})", plain.first > result.getValue("accent").first + 3.0)
            assertTrue("$name: but quick - a da (${plain.first} ms)", plain.first <= 40.0)
            assertTrue("$name: marcato is the firmest", result.getValue("marcato").first <= result.getValue("accent").first + 0.5 && result.getValue("marcato").first <= result.getValue("staccato").first + 0.5)
        }
    }

    @Test
    fun `a firm start is clean - no click, no noise burst`() {
        for (kind in listOf("staccato", "accent", "marcato")) {
            val x = render(tone(Synth.LOW_BRASS, 58, kind), 0.8)
            var jump = 0.0
            for (i in 1 until x.size) jump = max(jump, abs((x[i] - x[i - 1]).toDouble()))
            val peak = x.maxOf { abs(it) }
            // The first 3 ms are nearly nothing: it rises, it does not start with a step.
            val first = (0 until rate * 3 / 1000).maxOf { abs(x[it]) }
            println("R4 clean $kind: largest step ${"%.3f".format(jump / peak)} of peak, first 3 ms ${"%.3f".format(first / peak)} of peak")
            assertTrue("$kind: first 3 ms at ${first / peak} of the peak", first < 0.5 * peak)
            assertTrue("$kind: a step of ${jump / peak} of the peak", jump / peak < 0.2)
        }
    }

    @Test
    fun `a clarinet slur is a smooth blend, not a snap - and a register break a little rougher`() {
        assertTrue(Synth.CLARINET.slur.minOverlapMs >= 28.0 && Synth.CLARINET.slur.maxOverlapMs <= 46.0)
        assertTrue("tiny dip", Synth.CLARINET.slur.dipDb <= 0.5 && Synth.CLARINET.slur.roughDb in 0.5..1.2)
        for (p in listOf(Synth.SAX, Synth.DOUBLE_REED, Synth.FLUTE)) assertTrue(p.slur.minOverlapMs >= 28.0 && p.slur.dipDb <= 0.6)
    }
}
