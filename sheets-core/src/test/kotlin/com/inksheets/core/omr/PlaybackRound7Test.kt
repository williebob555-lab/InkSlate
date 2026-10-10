package com.inksheets.core.omr

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Round 7: vibrato that is planned and wanders - not a steady wobble. */
class PlaybackRound7Test {
    private val rate = 48_000

    @After
    fun restore() { Feel.expression = 1.0 }

    private fun plans(bars: List<Measure>, bpm: Double = 66.0, patch: Synth.Patch = Synth.LOW_BRASS) =
        Performance.play(bars, bpm, rate, 0, patch).tones.map { it.vib }

    private fun distinct(ps: List<Vibrato?>) = ps.filterNotNull().distinctBy { System.identityHashCode(it) }

    private fun sd(v: List<Double>): Double { val m = v.average(); return sqrt(v.sumOf { (it - m) * (it - m) } / v.size) }

    @Test
    fun `a slurred line has one plan, different lines differ, and the same render repeats`() {
        val a = plans(DemoPassages.lyrical()); val b = plans(DemoPassages.lyrical())
        val lines = distinct(a)
        println("R7 lines: ${lines.size}; depth ${lines.map { "%.1f".format(it.depth) }}; rate ${lines.map { "%.2f".format(it.rate) }}; growth ${lines.map { "%.2f".format(it.growth) }}; taper to ${lines.map { "%.2f".format(it.taperTo) }}")
        assertTrue("planned over the slurs, not note by note (${a.size} notes)", lines.size in 2..5)
        for (i in 0 until lines.size - 1) assertTrue("two lines in a row with the same vibrato", abs(lines[i].depth - lines[i + 1].depth) > 0.3 || abs(lines[i].rate - lines[i + 1].rate) > 0.05)
        // The same every time.
        assertEquals(a.size, b.size)
        for (i in a.indices) { assertEquals(a[i]!!.depth, b[i]!!.depth, 1e-9); assertEquals(a[i]!!.depthAt(1.3), b[i]!!.depthAt(1.3), 1e-9); assertEquals(a[i]!!.rateAt(2.1), b[i]!!.rateAt(2.1), 1e-9) }
        // The last note dies away: its line tapers hard; the phrase ends ease.
        assertTrue("the last line tapers to ${lines.last().taperTo}", lines.last().taperTo <= 0.4)
        assertTrue("not all lines the same: depth sd ${sd(lines.map { it.depth })}", sd(lines.map { it.depth }) > 0.8)
    }

    @Test
    fun `depth and rate wander smoothly - no steps, a start that swells, an end that eases`() {
        val line = distinct(plans(DemoPassages.lyrical())).first()
        var worstDepth = 0.0; var worstRate = 0.0
        var t = 0.0
        while (t < line.span - 0.001) {
            worstDepth = max(worstDepth, abs(line.depthAt(t + 0.001) - line.depthAt(t)))
            worstRate = max(worstRate, abs(line.rateAt(t + 0.001) - line.rateAt(t)))
            t += 0.001
        }
        println("R7 smoothness: depth moves at most ${"%.3f".format(worstDepth)} cents per ms, rate ${"%.4f".format(worstRate)} Hz per ms; depth at 0 ${"%.1f".format(line.depthAt(0.0))}, at 0.6 s ${"%.1f".format(line.depthAt(0.6))}")
        assertTrue("depth steps $worstDepth cents in a ms", worstDepth < 0.5)
        assertTrue("rate steps $worstRate Hz in a ms", worstRate < 0.05)
        assertEquals("starts from nothing", 0.0, line.depthAt(0.0), 0.5)
        assertTrue("and swells in", line.depthAt(0.7) > 3 * max(0.1, line.depthAt(0.1)))
        // It wanders: over a few seconds the rate and depth are not constant.
        val rates = (0..40).map { line.rateAt(1.0 + it * 0.1) }; val deps = (0..40).map { line.depthAt(1.0 + it * 0.1) }
        println("R7 wandering: rate sd ${"%.2f".format(sd(rates))} Hz (mean ${"%.2f".format(rates.average())}), depth sd ${"%.1f".format(sd(deps))} cents (mean ${"%.1f".format(deps.average())})")
        assertTrue(sd(rates) > 0.15 && sd(deps) > 1.5)
    }

    @Test
    fun `short notes get little or none, long notes full`() {
        fun note(x: Float, base: Int, m: Int = 50) = Note(listOf(0), listOf(Pitch(0, 3)), Duration(base), x)
        fun bar(n: Int, base: Int, count: Int) = Measure(n, 0, 0, Box((n - 1) * 400, 0, n * 400, 40), 10f, Clef.TREBLE, Key(0), TimeSig(4, 4),
            List(count) { note((n - 1) * 400 + 20f + it * 40f, base) })
        val quick = plans(listOf(bar(1, 16, 16), bar(2, 16, 16)), 100.0)    // sixteenths: 0.15 s each, detached
        val medium = plans(listOf(bar(1, 8, 8), bar(2, 8, 8)), 100.0)       // eighths: 0.3 s
        val long = plans(listOf(bar(1, 2, 2), bar(2, 2, 2)), 100.0)         // halves: 1.2 s
        println("R7 note lengths: sixteenths ${quick.count { it != null }} planned, eighths max depth ${medium.filterNotNull().maxOfOrNull { it.depth }}, halves max depth ${long.filterNotNull().maxOfOrNull { it.depth }}")
        assertTrue("sixteenths have no vibrato", quick.all { it == null })
        assertTrue("eighths hardly any", medium.all { it == null || it.depth < 2.0 })
        assertTrue("held notes have it", long.all { it != null && it.depth > 3.0 })
    }

    @Test
    fun `vibrato goes on through a slur - the phase never jumps`() {
        // A pure tone patch with the planned vibrato across a slurred pair: the pitch (zero-crossing period) moves smoothly.
        val sine = Synth.Patch(floatArrayOf(1f), 0.004, 0.0, 1f, 0.02, gain = 0.3f)
        val plan = Vibrato(0, 77, 20.0, 5.0, delay = 0.05, swell = 0.1, span = 3.0, growth = 0.0, rateTrend = 0.1, taperSecs = 0.0, taperTo = 1.0, depthNoise = 0.3, rateNoise = 0.15)
        val half = (1.2 * rate).toLong()
        val tones = listOf(Synth.Tone(57, 0, half, 0.7f, sine, vib = plan), Synth.Tone(61, half, half, 0.7f, sine, legato = true, from = 57, vib = plan))
        val s = Synth(rate); s.add(tones)
        val x = FloatArray((2.6 * rate).toInt()); val b = FloatArray(480)
        var i = 0
        while (i < x.size) { java.util.Arrays.fill(b, 0f); s.fill(b); System.arraycopy(b, 0, x, i, min(480, x.size - i)); i += 480 }
        // Periods between rising zero crossings; the pitch change itself (a major third) is at the join: compare each note's own trend.
        val times = ArrayList<Double>()
        for (k in 1 until x.size) if (x[k - 1] < 0f && x[k] >= 0f) times += (k - 1 + (-x[k - 1].toDouble() / (x[k] - x[k - 1]))) / rate
        val periods = times.zipWithNext { a, c -> c - a }
        var worst = 0.0; var worstAt = 0.0
        for (k in 1 until periods.size) {
            val at = times[k]
            if (abs(at - 1.2) < 0.08 || at < 0.1 || at > 2.3) continue    // the join itself (a new pitch), the first moments, the tail
            val change = abs(periods[k] / periods[k - 1] - 1)
            if (change > worst) { worst = change; worstAt = at }
        }
        println("R7 continuity: largest change between consecutive periods ${"%.4f".format(worst)} (20 cents peak vibrato at 5 Hz moves a period by up to ${"%.4f".format(20.0 / 1200 * 0.693 * 2 * Math.PI * 5 / 220)} per cycle step)")
        assertTrue("a jump of $worst in the pitch at $worstAt s", worst < 0.02)
        // And the vibrato is there on both sides: the pitch wobbles by roughly the planned cents.
        val first = periods.indices.filter { times[it] in 0.6..1.1 }.map { periods[it] }
        val wob = 1200 * Math.log((first.max() / first.min())) / Math.log(2.0) / 2
        assertTrue("planned 20 cents, measured $wob", wob in 8.0..40.0)
    }

    @Test
    fun `expression scales the swings - none is steady, more is wider`() {
        fun spread(e: Double): Pair<Double, Double> {
            Feel.expression = e
            val ps = distinct(plans(DemoPassages.lyrical()))
            val t = (0..60).map { 1.0 + it * 0.05 }
            val dev = ps.map { p -> sd(t.map { p.depthAt(it) }) / p.depth.coerceAtLeast(1.0) }.average()
            return sd(ps.map { it.depth }) / ps.map { it.depth }.average() to dev
        }
        val steady = spread(0.0); val normal = spread(1.0); val more = spread(1.5)
        println("R7 expression: between-line depth CV / within-line CV  x0 ${steady.first.format()} ${steady.second.format()}   x1 ${normal.first.format()} ${normal.second.format()}   x1.5 ${more.first.format()} ${more.second.format()}")
        assertTrue("steady between lines at 0: ${steady.first}", steady.first < 0.05)
        assertTrue("more at 1 than 0", normal.first > steady.first + 0.05 && normal.second > steady.second + 0.03)
        assertTrue("more still at 1.5", more.first > normal.first && more.second > normal.second)
    }

    private fun Double.format() = "%.2f".format(this)

    @Test
    fun `a clarinet has none, a sampled instrument is left to its recordings`() {
        assertTrue(plans(DemoPassages.lyrical(), patch = Synth.CLARINET).all { it == null })
    }
}
