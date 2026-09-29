package com.inksheets.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class TempoTrackerTest {
    /** Drum-like hits at [bpm], a bass note under each, noise over all. */
    private fun beats(bpm: Double, seconds: Double, rate: Int = 44_100, offBeats: Boolean = true): FloatArray {
        val random = java.util.Random(1)
        val out = FloatArray((rate * seconds).toInt())
        val period = 60.0 / bpm
        var t = 0.0
        var k = 0
        while (t < seconds) {
            val start = (t * rate).toInt()
            val loud = if (k % 4 == 0) 1f else 0.7f
            for (i in 0 until (rate * 0.12).toInt()) {
                if (start + i >= out.size) break
                val env = exp(-i / (rate * 0.02)).toFloat()
                out[start + i] += loud * env * ((random.nextFloat() - 0.5f) + 0.6f * sin(2 * PI * 110 * i / rate).toFloat())
            }
            // Quieter notes between the beats, as a band plays.
            if (offBeats) {
                val mid = ((t + period / 2) * rate).toInt()
                for (i in 0 until (rate * 0.08).toInt()) {
                    if (mid + i >= out.size) break
                    out[mid + i] += 0.25f * exp(-i / (rate * 0.03)).toFloat() * sin(2 * PI * 660 * i / rate).toFloat()
                }
            }
            t += period; k++
        }
        for (i in out.indices) out[i] = out[i] * 0.5f + (random.nextGaussian() * 0.01).toFloat()
        return out
    }

    private fun track(samples: FloatArray, rate: Int = 44_100, expected: Double = 110.0): TempoTracker {
        val t = TempoTracker(rate).apply { this.expected = expected }
        var at = 0
        while (at < samples.size) {
            t.feed(samples.copyOfRange(at, minOf(samples.size, at + 1024)))
            at += 1024
        }
        return t
    }

    @Test
    fun `finds the tempo of a steady beat`() {
        for (bpm in listOf(72.0, 96.0, 120.0, 144.0, 176.0)) {
            val t = track(beats(bpm, 12.0), expected = bpm * 1.1)
            assertNotNull(t.bpm)
            println("$bpm bpm heard as ${"%.1f".format(t.bpm)} (confidence ${"%.2f".format(t.confidence)})")
            assertEquals(bpm, t.bpm!!, bpm * 0.02)
        }
    }

    @Test
    fun `knows where the last beat fell`() {
        val rate = 44_100
        val bpm = 120.0
        val s = beats(bpm, 10.25, rate, offBeats = false)
        val t = track(s, rate, expected = 120.0)
        // Beats every 0.5 s from 0: the last before 10.25 s was at 10.0 s. The last full frame
        // fed ends a little before the end of the sound, so allow for it.
        val ago = t.lastBeatMsAgo!!
        println("last beat ${"%.0f".format(ago)} ms ago")
        assertEquals(250.0, ago, 60.0)
    }
}
