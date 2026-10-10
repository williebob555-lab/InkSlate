package com.inksheets.core

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class WindowFollowerTest {
    /** A minute of "music": a few chords held a second or two each, a chorus that comes back. */
    private fun music(): List<Chroma.Frame> {
        val random = java.util.Random(3)
        val chords = List(12) { FloatArray(12) { if (random.nextFloat() < 0.3f) 1f else 0.05f } }
        val plan = listOf(0, 1, 2, 3, 4, 5, 6, 7, 2, 3, 4, 5, 8, 9, 10, 11, 0, 1, 2, 3, 4, 5, 6, 7, 9, 11, 10, 8)
        val out = ArrayList<Chroma.Frame>()
        for ((i, c) in plan.withIndex()) repeat(15 + (i % 3) * 5) { out += frame(chords[c]) }
        return out
    }

    private fun frame(v: FloatArray, noise: java.util.Random? = null): Chroma.Frame {
        val n = FloatArray(12) { v[it] + (noise?.nextGaussian()?.toFloat()?.times(0.15f) ?: 0f) }.map { it.coerceAtLeast(0f) }.toFloatArray()
        val norm = sqrt(n.sumOf { (it * it).toDouble() }).toFloat()
        for (k in 0 until 12) n[k] /= norm
        return Chroma.Frame(n, 0.1f)
    }

    /** The music played at [speed], noisily: how far behind or ahead the follower is at each moment once going. */
    private fun errors(speed: Double): List<Double> {
        val ref = music()
        val follower = WindowFollower(ref)
        val noise = java.util.Random(9)
        val out = ArrayList<Double>()
        var t = 0.0
        while (t < ref.size - 1) {
            val f = frame(ref[t.toInt()].notes, noise)
            val at = follower.hear(f, quiet = false) / 100.0
            if (t > 60) out += abs(at - t) / 10.0
            t += speed
        }
        return out
    }

    @Test
    fun `follows music played slower and faster than written, and is not drawn to the chorus that comes back`() {
        for (speed in listOf(0.75, 1.0, 1.3)) {
            val e = errors(speed).sorted()
            val median = e[e.size / 2]; val worst = e.last()
            assertTrue("at $speed: median $median s", median < 0.6)
            assertTrue("at $speed: worst $worst s", worst < 3.0)
        }
    }

    @Test
    fun `starting part way through the page locks within a few seconds, stays locked, and does not flip back to searching`() {
        val ref = music()
        for (from in listOf(100, 250, 400)) {
            // The page in front is the whole stretch; the band comes in at [from] and plays a little slower.
            val follower = WindowFollower(ref, 0, startEndMs = (ref.size - 1) * 100L)
            val noise = java.util.Random(5)
            var t = from.toDouble()
            var heardFrames = 0
            var lockedAt = -1
            var flips = 0
            var was = false
            while (t < ref.size - 1) {
                val at = follower.hear(frame(ref[t.toInt()].notes, noise), quiet = false)
                heardFrames++
                if (follower.locked && lockedAt < 0) lockedAt = heardFrames
                if (was && !follower.locked) flips++
                was = follower.locked
                if (follower.locked && heardFrames > 150) assertTrue("from $from at heard $heardFrames: ${at / 100.0} vs $t", abs(at / 100.0 - t) < 60)
                t += 0.9
            }
            assertTrue("from $from: locked after $lockedAt frames", lockedAt in 1..120)
            assertTrue("from $from: flipped $flips times", flips == 0)
            assertTrue(follower.searching.not())
        }
    }

    @Test
    fun `a rest holds the place and does not drop the lock`() {
        val ref = music()
        val follower = WindowFollower(ref, 0, startEndMs = (ref.size - 1) * 100L)
        val noise = java.util.Random(5)
        var t = 100.0
        repeat(150) { follower.hear(frame(ref[t.toInt()].notes, noise), quiet = false); t += 1.0 }
        assertTrue(follower.locked)
        val before = follower.positionMs
        repeat(60) { follower.hear(frame(ref[t.toInt()].notes, noise), quiet = true) }
        assertTrue(follower.locked)
        assertTrue("held: ${follower.positionMs} from $before", follower.positionMs - before in 0..7_000)
    }
}
