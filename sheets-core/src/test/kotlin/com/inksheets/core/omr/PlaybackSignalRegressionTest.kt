package com.inksheets.core.omr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/** Signal regressions for a small, repeatable phrase; timbre and musicality still require listening. */
class PlaybackSignalRegressionTest {
    private val rate = 48_000
    // A clean, deterministic tone makes an abrupt transition visible without breath noise masking it.
    private val patch = Synth.Patch(floatArrayOf(1f), 0.03, 0.05, 0.8f, 0.15, gain = 0.22f)
    private val beat = rate / 3

    private fun render(): FloatArray {
        val synth = Synth(rate)
        synth.add(listOf(
            Synth.Tone(60, 0, beat.toLong(), 0.7f, patch),
            Synth.Tone(60, beat.toLong(), beat.toLong(), 0.7f, patch),
            Synth.Tone(62, (2 * beat).toLong(), beat.toLong(), 0.7f, patch, legato = true, from = 60)
        ))
        val output = FloatArray(rate * 3 / 2)
        val block = FloatArray(480)
        for (offset in output.indices step block.size) {
            java.util.Arrays.fill(block, 0f)
            synth.fill(block)
            System.arraycopy(block, 0, output, offset, minOf(block.size, output.size - offset))
        }
        return output
    }

    private fun rms(x: FloatArray, from: Int, to: Int): Double {
        var sum = 0.0
        for (i in from until to) sum += x[i].toDouble() * x[i]
        return sqrt(sum / (to - from))
    }

    private fun maxStep(x: FloatArray, from: Int, to: Int): Double {
        var largest = 0.0
        for (i in maxOf(1, from) until to) largest = maxOf(largest, abs((x[i] - x[i - 1]).toDouble()))
        return largest
    }

    @Test
    fun `phrase is finite has headroom and little DC and renders identically`() {
        val x = render()
        assertArrayEquals(x, render(), 0f)
        assertTrue("all samples must be finite", x.all { it.isFinite() })
        assertTrue("expected audible output with headroom", x.maxOf { abs(it) } in 0.01f..0.97f)
        assertTrue("DC offset exceeds baseline", abs(x.map { it.toDouble() }.average()) < 0.005)
    }

    @Test
    fun `repeated and legato transitions do not introduce large sample jumps`() {
        val x = render()
        // Compare each 80 ms transition window to a steady portion of the first note. The
        // ratio is a coarse click detector, not a claim that all clicks are perceptually absent.
        val reference = maxStep(x, rate / 10, rate / 5)
        for (join in listOf(beat, 2 * beat)) {
            val jump = maxStep(x, join - rate / 25, join + rate / 25)
            assertTrue("jump $jump at $join exceeds steady step $reference", jump < reference * 3.0)
        }
    }

    @Test
    fun `release remains audible after note off and dies away`() {
        val x = render()
        val end = 3 * beat
        val tail = rms(x, end, end + rate / 20)
        val later = rms(x, end + rate / 3, end + rate * 2 / 5)
        assertTrue("release tail is missing: $tail", tail > 0.0001)
        assertTrue("release should decay: $tail then $later", later < tail * 0.25)
    }
}
