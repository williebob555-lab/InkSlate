package com.inksheets.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log2
import kotlin.math.sin

class TimeStretchTest {

    private val rate = 44_100

    private fun tone(hz: Double, seconds: Double) =
        FloatArray((rate * seconds).toInt()) { i -> (0.5 * sin(2 * PI * hz * i / rate)).toFloat() }

    /** Read until the source runs out; returns everything produced. */
    private fun drain(ts: TimeStretch): FloatArray {
        val out = ArrayList<Float>()
        val block = FloatArray(512)
        var guard = 0
        while (!ts.atEnd && guard++ < 10_000) {
            ts.read(block)
            block.forEach { out += it }
        }
        return out.toFloatArray()
    }

    private fun centsOff(samples: FloatArray, from: Int, expected: Double): Double {
        val window = samples.copyOfRange(from, from + Tuner.windowFor(rate, 200.0))
        val hz = Tuner.detect(window, rate, lowestHz = 200.0)!!.hz
        return 1200 * log2(hz / expected)
    }

    @Test
    fun `half speed takes twice as long and keeps its pitch`() {
        val ts = TimeStretch(tone(440.0, 2.0), rate).apply { speed = 0.5 }
        val out = drain(ts)
        assertEquals(4.0, out.size.toDouble() / rate, 0.15)
        assertEquals(0.0, centsOff(out, rate, 440.0), 10.0)
    }

    @Test
    fun `an octave up keeps its length`() {
        val ts = TimeStretch(tone(220.0, 2.0), rate).apply { pitch = 12 }
        val out = drain(ts)
        assertEquals(2.0, out.size.toDouble() / rate, 0.15)
        assertEquals(0.0, centsOff(out, rate / 2, 440.0), 10.0)
    }

    @Test
    fun `seeking starts from the new place`() {
        val ts = TimeStretch(tone(440.0, 2.0), rate)
        ts.seek(rate * 1.5)
        val out = drain(ts)
        assertTrue("got ${out.size}", out.size < rate * 0.7)
    }
}
