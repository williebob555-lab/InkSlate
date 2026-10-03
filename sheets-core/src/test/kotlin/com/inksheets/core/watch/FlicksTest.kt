package com.inksheets.core.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Flicks among playing, made up: an arm that never stops (slow swings on every axis, and sharp
 * jerks the other way round, as a slide stops or a hand plucks), with flicks at cues - out fast,
 * back slower, as a wrist does. What the trainer learns from one recording must hold on another.
 */
class FlicksTest {
    /** The way a flick for the next page turns the watch, worn on the right wrist; back is the other way. */
    private val way = norm(floatArrayOf(0.25f, 0.9f, -0.35f))
    private val jerkWay = norm(floatArrayOf(0.9f, -0.2f, 0.4f))

    private fun norm(v: FloatArray): FloatArray { val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); return FloatArray(3) { v[it] / n } }

    /**
     * [seconds] of playing at 100 readings a second, with flicks at [cues] (after [react] ms, at
     * [speed] rad/s) and, at [doubles], two flicks for the next page [gap] ms apart.
     */
    private fun recording(
        seed: Long, seconds: Int, cues: List<Cue>, speed: Float = 8f, react: Long = 350,
        jerks: Float = 3.5f, jerkAlongFlick: Float = 0f, doubles: List<Long> = emptyList(), gap: Long = 220
    ): Session {
        val r = java.util.Random(seed)
        val n = seconds * 100
        val g = Array(n) { FloatArray(3) }
        // Playing: slow swings on every axis (up to ~1.5 rad/s), a little noise.
        val phases = DoubleArray(9) { r.nextDouble() * 2 * PI }
        val freqs = DoubleArray(9) { 0.3 + r.nextDouble() * 2.5 }
        for (i in 0 until n) for (a in 0..2) {
            val t = i / 100.0
            g[i][a] = (0.6 * sin(freqs[a] * 2 * PI * t + phases[a]) + 0.5 * sin(freqs[a + 3] * 2 * PI * t + phases[a + 3]) +
                0.4 * sin(freqs[a + 6] * 2 * PI * t + phases[a + 6]) + r.nextGaussian() * 0.15).toFloat()
        }
        // Jerks: short sharp turns another way, every second or two.
        var i = 50
        while (i < n - 30) {
            val amp = jerks * (0.6f + 0.4f * r.nextFloat()) * if (r.nextBoolean()) 1 else -1
            pulse(g, i, 6, amp, jerkWay)
            if (jerkAlongFlick > 0f && r.nextInt(3) == 0) pulse(g, i + 10, 6, jerkAlongFlick, way)
            i += 100 + r.nextInt(120)
        }
        for (c in cues) flick(g, ((c.t + react) / 10).toInt(), if (c.next) speed else -speed)
        for (d in doubles) { flick(g, (d / 10).toInt(), speed); flick(g, ((d + gap) / 10).toInt(), speed) }
        return Session(List(n) { k -> Sample(k * 10L, g[k][0], g[k][1], g[k][2]) }, cues)
    }

    /** A flick: out at [speed] for ~80 ms, back at 60% for ~120 ms. */
    private fun flick(g: Array<FloatArray>, at: Int, speed: Float) {
        pulse(g, at, 8, speed, way)
        pulse(g, at + 8, 12, -speed * 0.6f, way)
    }

    private fun pulse(g: Array<FloatArray>, at: Int, len: Int, amp: Float, dir: FloatArray) {
        for (k in 0 until len) {
            val i = at + k
            if (i !in g.indices) return
            val s = (amp * sin(PI * (k + 0.5) / len)).toFloat()
            for (a in 0..2) g[i][a] += s * dir[a]
        }
    }

    /** The calibration the app gives: 30 s of playing, then cues every ~11 s. */
    private fun cues(seed: Long): List<Cue> {
        val r = java.util.Random(seed)
        val kinds = listOf(true, true, false, true, false, true, true, false)
        var t = 30_000L
        return kinds.map { k -> Cue(t, k).also { t += 8_000 + r.nextInt(6_000) } }
    }

    private fun cos(a: FlickWay, b: FloatArray) = a.x * b[0] + a.y * b[1] + a.z * b[2]

    @Test
    fun `a calibration learns the way each flick turns the watch, and a speed between the playing and the flicks`() {
        val s = recording(1, 130, cues(1))
        val report = FlickTrainer.train(listOf(s), "bass")
        val model = assertNotNull(report.model).let { report.model!! }
        assertNull(report.problem)
        assertTrue("next way ${model.next}", cos(model.next, way) > 0.97f)
        val back = assertNotNull(model.back).let { model.back!! }
        assertTrue("back way $back", cos(back, way) < -0.97f)
        assertTrue("threshold ${model.next.threshold} above playing ${report.nextPlaying}", model.next.threshold > report.nextPlaying)
        assertTrue("threshold ${model.next.threshold} below flicks ${report.nextSlowest}", model.next.threshold < report.nextSlowest)
        assertEquals(8, report.right)
        assertEquals(0, report.wrong + report.missed + report.falseTurns)
    }

    @Test
    fun `what one calibration learned holds on another recording - every flick turns, nothing else does`() {
        val model = FlickTrainer.train(listOf(recording(2, 130, cues(2))), "bass").model!!
        val other = recording(99, 200, cues(99), speed = 7f)
        val r = FlickTrainer.replay(listOf(other), model)
        assertEquals(Triple(8, 0, 0), Triple(r.right, r.wrong, r.missed))
        assertEquals(0, r.falseTurns)
    }

    @Test
    fun `two quick flicks are two turns, and the swing back from either is not a turn back`() {
        val model = FlickTrainer.train(listOf(recording(3, 130, cues(3))), "bass").model!!
        for (gap in listOf(180L, 250L, 400L)) {
            val s = recording(4, 10, emptyList(), doubles = listOf(3_000L), gap = gap)
            val d = FlickDetector(model)
            val turns = s.samples.map { d.feed(it) }.filter { it != 0 }
            assertEquals("gap $gap", listOf(FlickDetector.NEXT, FlickDetector.NEXT), turns)
        }
    }

    @Test
    fun `a back flick straight after a next is still taken`() {
        val model = FlickTrainer.train(listOf(recording(5, 130, cues(5))), "bass").model!!
        val s = recording(6, 10, listOf(Cue(2_000, true), Cue(2_700, false)), react = 300)
        val d = FlickDetector(model)
        assertEquals(listOf(FlickDetector.NEXT, FlickDetector.BACK), s.samples.map { d.feed(it) }.filter { it != 0 })
    }

    @Test
    fun `playing as fast as a flick along the same way is never taken for one - and it says so`() {
        val s = recording(7, 130, cues(7), speed = 6f, jerkAlongFlick = 7f)
        val report = FlickTrainer.train(listOf(s), "trombone")
        assertNotNull(report.problem)
        assertEquals(0, report.falseTurns)
    }

    @Test
    fun `too few cues, or no flicks after them, gives no model and a reason`() {
        assertNull(FlickTrainer.train(listOf(recording(8, 40, listOf(Cue(30_000, true)))), "x").model)
        val still = recording(9, 130, emptyList()).copy(cues = cues(9))
        val r = FlickTrainer.train(listOf(still), "x")
        assertTrue(r.model == null || r.problem != null)
    }

    @Test
    fun `readings, cues, sessions, models and hellos survive the trip`() {
        val samples = listOf(Sample(5, 1f, -2f, 3.5f, 0.1f, 9.8f, -0.2f), Sample(15, 0f, 0f, 0f))
        val cues = listOf(Cue(10, true), Cue(12, false))
        assertEquals(samples to cues, WatchWire.unpackBatch(WatchWire.packBatch(samples, cues)))
        assertNull(WatchWire.unpackBatch(byteArrayOf(1, 2)))
        val session = Session(samples, cues)
        assertEquals(session, WatchWire.readSession(WatchWire.writeSession(session)))
        val model = FlickModel(FlickWay(0.1f, 0.9f, 0.2f, 5f, 0.8f), null, "bass", 42)
        assertEquals(model, FlickModel.decode(model.encode()))
        assertNull(FlickModel.decode("nonsense"))
        val hello = WatchWire.Hello(true, "bass", "1.2.3", calibrating = true)
        assertEquals(hello, WatchWire.Hello.decode(hello.encode()))
    }
}
