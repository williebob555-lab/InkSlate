package com.inksheets.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class MusicToolsTest {

    private val rate = 48_000

    /** A tone with a strong second harmonic, the way a trombone sounds - the case that fools FFTs. */
    private fun brassy(hz: Double, seconds: Double = 0.1): FloatArray {
        val n = (rate * seconds).toInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() / rate
            (0.3 * sin(2 * PI * hz * t) + 0.5 * sin(2 * PI * 2 * hz * t) + 0.15 * sin(2 * PI * 3 * hz * t)).toFloat()
        }
    }

    @Test
    fun `the tuner finds fundamentals from a bass guitar's low E to a trombone's high B-flat`() {
        for (hz in listOf(41.2, 58.27, 116.54, 233.08, 466.16)) {
            val window = Tuner.windowFor(rate)
            val reading = Tuner.detect(brassy(hz, window.toDouble() / rate), rate)
            assertNotNull("nothing heard at $hz", reading)
            val cents = 1200 * kotlin.math.log2(reading!!.hz / hz)
            assertEquals("at $hz Hz", 0.0, cents, 3.0)
        }
    }

    @Test
    fun `silence is not a note`() {
        assertNull(Tuner.detect(FloatArray(4000), rate))
    }

    @Test
    fun `notes are named in the player's key`() {
        val concertBb = Tuner.hzOf(58) // B-flat 3... midi 58 = A#3/Bb3
        assertEquals("B♭", Tuner.note(concertBb).name)
        // A treble-clef baritone reads that concert B-flat as a C, a ninth up.
        val tc = Tuner.note(concertBb, transpose = Instruments.byId.getValue("baritone-tc").transpose)
        assertEquals("C", tc.name)
        assertEquals("B♭", tc.concertName)
        assertEquals(-20.0, Tuner.note(440.0 * Math.pow(2.0, -20.0 / 1200)).cents, 0.01)
    }

    @Test
    fun `clicks land on the exact sample, bar after bar`() {
        val m = Metronome(rate)
        m.settings = Metronome.Settings(bpm = 120.0, beatsPerBar = 3)
        val beats = ArrayList<Int>()
        m.onBeat = { beats += it }
        val out = FloatArray(rate * 3)   // three seconds at 120 bpm is six beats
        // In small pieces, as an audio callback would ask.
        var at = 0
        val piece = FloatArray(441)
        while (at < out.size) {
            m.fill(piece)
            piece.copyInto(out, at, 0, minOf(piece.size, out.size - at))
            at += piece.size
        }
        assertEquals(listOf(0, 1, 2, 0, 1, 2), beats.take(6))
        // Each click starts exactly half a second after the last.
        val starts = (0 until 6).map { beat -> beat * rate / 2 }
        for (s in starts) {
            assertEquals(0f, out[s], 1e-6f)            // sin(0)
            assert(abs(out[s + 5]) > 0.01f) { "no click at sample $s" }
            if (s > 10) assertEquals(0f, out[s - 10], 1e-3f)
        }
    }

    @Test
    fun `tap tempo averages the recent taps`() {
        assertNull(Metronome.tapTempo(listOf(0L)))
        assertEquals(120.0, Metronome.tapTempo(listOf(0L, 500L, 1000L, 1500L))!!, 0.01)
        // A pause and a fresh start does not drag the tempo down.
        assertEquals(60.0, Metronome.tapTempo(listOf(0L, 500L, 9000L, 10000L, 11000L))!!, 0.01)
    }
}
