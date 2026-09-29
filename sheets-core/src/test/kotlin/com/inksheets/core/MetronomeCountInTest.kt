package com.inksheets.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetronomeCountInTest {

    private val rate = 48_000

    /** The sample numbers where a click starts, across [seconds] of output written in [block]s. */
    private fun clicks(m: Metronome, seconds: Double, block: Int = 480): List<Int> {
        val out = ArrayList<Int>()
        var at = 0
        var quiet = 1_000_000
        val buf = FloatArray(block)
        while (at < seconds * rate) {
            m.fill(buf)
            for (i in buf.indices) {
                // A click starts where sound follows a good stretch of silence (its sine crosses zero).
                if (buf[i] != 0f) { if (quiet > 2_000) out += at + i; quiet = 0 } else quiet++
            }
            at += block
        }
        // The first sample of a click is sin(0) = 0: its start is the sample before.
        return out.map { it - 1 }
    }

    @Test
    fun `a count-in cue fires at the first beat after the bars`() {
        val m = Metronome(rate).apply { settings = Metronome.Settings(bpm = 120.0, beatsPerBar = 4) }
        m.reset()
        var firedAt = -1L
        var written = 0L
        m.cueAt(m.samplesFor(1)) { firedAt = written }
        val buf = FloatArray(100)
        repeat(1000) { m.fill(buf); written += buf.size }
        // One bar of 4/4 at 120 is 2 s = 96 000 samples; the cue is seen in the block holding it.
        assertEquals(96_000.0, firedAt.toDouble(), 100.0)
        assertEquals(2000.0, m.msFor(1), 0.001)
    }

    @Test
    fun `a first beat still to come is silence, then clicks on the beat`() {
        val m = Metronome(rate).apply { settings = Metronome.Settings(bpm = 120.0) }
        m.reset()
        m.phaseTo(-250.0)
        val c = clicks(m, 1.3)
        // 250 ms of silence, then every half second.
        assertEquals(listOf(12_000, 36_000, 60_000), c.take(3))
    }

    @Test
    fun `mid-beat the next click lands where the grid says`() {
        val m = Metronome(rate).apply { settings = Metronome.Settings(bpm = 120.0) }
        m.reset()
        m.phaseTo(1_200.0)          // 200 ms into the third beat
        val c = clicks(m, 1.0)
        assertEquals(14_400.0, c.first().toDouble(), 2.0)   // 300 ms on: the fourth beat, to a sample or two
    }

    @Test
    fun `muted it keeps time but is silent`() {
        val m = Metronome(rate).apply { settings = Metronome.Settings(bpm = 120.0) }
        m.reset()
        var beats = 0
        m.onBeat = { beats++ }
        m.muted = true
        assertTrue(clicks(m, 2.0).isEmpty())
        assertEquals(4, beats)
    }
}
