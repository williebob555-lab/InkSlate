package com.inksheets.core.omr

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Rounds 7-8: a vibrato planned from the music, controlled - nothing random in it; straight, then a smooth bloom; phase-continuous. */
class PlaybackRound7Test {
    private val rate = 48_000

    @After
    fun restore() { Feel.expression = 1.0; Feel.drift = 0.0 }

    private fun plans(bars: List<Measure>, bpm: Double = 66.0, patch: Synth.Patch = Synth.LOW_BRASS) =
        Performance.play(bars, bpm, rate, 0, patch).tones.map { it.vib }

    private fun distinct(ps: List<Vibrato?>) = ps.filterNotNull().distinctBy { System.identityHashCode(it) }

    private fun sd(v: List<Double>): Double { val m = v.average(); return sqrt(v.sumOf { (it - m) * (it - m) } / v.size) }

    private fun Double.format() = "%.2f".format(this)

    /** The lengths of the cycles (s) the plan makes from [from] to [to] seconds, adding up the rate as the synth does. */
    private fun cycles(v: Vibrato, from: Double, to: Double): List<Double> {
        val out = ArrayList<Double>()
        var t = 0.0; var phase = 0.0; var last = 0.0
        val dt = 0.0005
        while (t < to) {
            phase += 2 * Math.PI * v.rateAt(t) * dt
            t += dt
            if (phase >= 2 * Math.PI) { phase -= 2 * Math.PI; if (last >= from) out += t - last; last = t }
        }
        return out
    }

    @Test
    fun `a slurred line has one plan, lines differ only as the music does, and the same render repeats`() {
        val a = plans(DemoPassages.lyrical()); val b = plans(DemoPassages.lyrical())
        val lines = distinct(a)
        println("R7 lines: ${lines.size}; depth ${lines.map { it.depth.format() }}; rate ${lines.map { it.rate.format() }}; knot depth ranges ${lines.map { "%.2f-%.2f".format(it.knotDepth.min(), it.knotDepth.max()) }}; taper to ${lines.map { it.taperTo.format() }}")
        assertTrue("planned over the slurs, not note by note (${a.size} notes)", lines.size in 2..5)
        for (i in a.indices) {
            assertEquals(a[i]!!.depth, b[i]!!.depth, 1e-9)
            for (t in listOf(0.7, 1.3, 2.1, 3.0)) { assertEquals(a[i]!!.depthAt(t), b[i]!!.depthAt(t), 1e-9); assertEquals(a[i]!!.rateAt(t), b[i]!!.rateAt(t), 1e-9) }
        }
        // The last note dies away to straight; the lines differ because the music does (levels, pitches, where in the phrase).
        assertEquals("the last line eases to straight", 0.0, lines.last().taperTo, 1e-9)
        assertTrue("lines differ with the music", lines.map { it.knotDepth.average() }.let { (it.max() - it.min()) > 0.05 })
        // Modest: 5-18 cents at the widest, 4.2-5.3 Hz.
        for (l in lines) {
            val widest = (0..200).maxOf { l.depthAt(it / 200.0 * l.span * 0.8) }
            val rates = (0..50).map { l.rateAt(it / 50.0 * l.span) }
            println("R7 line: widest ${widest.format()} cents, rate ${rates.min().format()}-${rates.max().format()} Hz")
            assertTrue("widest $widest", widest in 5.0..19.0)
            assertTrue("rate ${rates.min()}-${rates.max()}", rates.min() >= 4.0 && rates.max() <= 5.4)
        }
    }

    @Test
    fun `no jitter - a steady note's cycles all but the same length, smooth trends, a start that is straight`() {
        for (l in distinct(plans(DemoPassages.lyrical()))) {
            val cs = cycles(l, 0.9, l.span - 0.5)
            if (cs.size < 4) continue
            var worst = 0.0
            for (k in 1 until cs.size) worst = max(worst, abs(cs[k] / cs[k - 1] - 1))
            println("R7 steadiness: ${cs.size} cycles, longest/shortest ${(cs.max() / cs.min()).format()}, largest change between consecutive cycles ${"%.4f".format(worst)}")
            assertTrue("cycle to cycle change $worst", worst < 0.03)
            // The rate only ever quickens or slows smoothly: sampled every 50 ms, the second difference is tiny.
            val r = (0..(l.span * 20).toInt()).map { l.rateAt(it * 0.05) }
            for (k in 1 until r.size - 1) assertTrue("rate wobbles at ${k * 0.05} s", abs(r[k + 1] - 2 * r[k] + r[k - 1]) < 0.12)
            // Straight at the start, then it blooms evenly.
            assertEquals(0.0, l.depthAt(0.0), 0.3)
            assertTrue("starts straight: ${l.depthAt(0.1)} of ${l.depthAt(1.5)}", l.depthAt(0.1) < 0.25 * l.depthAt(1.5))
            val rise = (0..14).map { l.depthAt(0.2 + it * 0.04) }
            for (k in 1 until rise.size) assertTrue("the bloom is not even: $rise", rise[k] >= rise[k - 1] - 0.05)
        }
    }

    @Test
    fun `it follows the music - wider with a crescendo, narrower and slower into a phrase end, straight for the last note`() {
        fun pitchOf(midi: Int): Pitch { val names = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6); val alters = intArrayOf(0, 1, 0, 1, 0, 0, 1, 0, 1, 0, 1, 0); return Pitch(names[midi % 12], midi / 12 - 1, alters[midi % 12]) }
        // Four half notes under one slur, a crescendo through the first two bars.
        val slur = Direction("slur", 20f, 400f + 20f + 90f * 2 + 5f)
        fun bar(n: Int, ms: List<Int>, dirs: List<Direction>) = Measure(n, 0, 0, Box((n - 1) * 400, 0, n * 400, 40), 10f, Clef.TREBLE, Key(0), TimeSig(4, 4),
            ms.mapIndexed { i, m -> Note(listOf(0), listOf(pitchOf(m)), Duration(2), (n - 1) * 400 + 20f + i * 180f) }, directions = dirs)
        fun line(dyn: String, pin: Direction?): Vibrato =
            plans(listOf(bar(1, listOf(55, 55), listOfNotNull(slur, Direction("dynamic", 5f, text = dyn), pin)), bar(2, listOf(55, 55), listOf(slur))), 60.0).filterNotNull().first()
        val plain = line("mf", null)
        val cresc = line("mf", Direction("cresc", 20f, 380f))
        val dim = line("mf", Direction("dim", 20f, 380f))
        println("R7 hairpins: knot depth plain ${plain.knotDepth.map { it.format() }}, cresc ${cresc.knotDepth.map { it.format() }}, dim ${dim.knotDepth.map { it.format() }}; rate cresc ${cresc.knotRate.map { it.format() }} dim ${dim.knotRate.map { it.format() }}")
        // The same notes: a crescendo is wider and quicker at the end than none, a diminuendo narrower and slower.
        assertTrue("wider in a crescendo", cresc.knotDepth[cresc.knotDepth.size - 2] > plain.knotDepth[plain.knotDepth.size - 2] + 0.05)
        assertTrue("quicker in a crescendo", cresc.knotRate[cresc.knotRate.size - 2] >= plain.knotRate[plain.knotRate.size - 2] - 1e-9)
        assertTrue("narrower in a diminuendo", dim.knotDepth[dim.knotDepth.size - 2] < plain.knotDepth[plain.knotDepth.size - 2] - 0.05)
        assertTrue("slower in a diminuendo", dim.knotRate[dim.knotRate.size - 2] <= plain.knotRate[plain.knotRate.size - 2] + 1e-9)
        // The end of the last line: eases to straight before the release, and slows a little.
        assertTrue("eases to straight before the release", dim.depthAt(dim.span - 0.01) < 0.15 * dim.depthAt(dim.span - 0.6))
        assertTrue(dim.rateAt(dim.span - 0.01) < dim.rateAt(dim.span - 0.6) * 1.02)
    }

    @Test
    fun `short notes get little or none, long notes full`() {
        fun note(x: Float, base: Int) = Note(listOf(0), listOf(Pitch(0, 3)), Duration(base), x)
        fun bar(n: Int, base: Int, count: Int) = Measure(n, 0, 0, Box((n - 1) * 400, 0, n * 400, 40), 10f, Clef.TREBLE, Key(0), TimeSig(4, 4),
            List(count) { note((n - 1) * 400 + 20f + it * 40f, base) })
        val quick = plans(listOf(bar(1, 16, 16), bar(2, 16, 16)), 100.0)
        val medium = plans(listOf(bar(1, 8, 8), bar(2, 8, 8)), 100.0)
        val long = plans(listOf(bar(1, 2, 2), bar(2, 2, 2)), 100.0)
        println("R7 note lengths: sixteenths ${quick.count { it != null }} planned, eighths max depth ${medium.filterNotNull().maxOfOrNull { it.depth }}, halves max depth ${long.filterNotNull().maxOfOrNull { it.depth }}")
        assertTrue("sixteenths have no vibrato", quick.all { it == null })
        assertTrue("eighths hardly any", medium.all { it == null || it.depth < 2.0 })
        assertTrue("held notes have it", long.all { it != null && it.depth > 3.0 })
    }

    @Test
    fun `vibrato goes on through a slur - the phase never jumps`() {
        val sine = Synth.Patch(floatArrayOf(1f), 0.004, 0.0, 1f, 0.02, gain = 0.3f)
        val plan = Vibrato(0, 77, 20.0, 5.0, delay = 0.05, swell = 0.1, span = 3.0, growth = 0.0, rateTrend = 0.1, taperSecs = 0.0, taperTo = 1.0, depthNoise = 0.0, rateNoise = 0.0)
        val half = (1.2 * rate).toLong()
        val tones = listOf(Synth.Tone(57, 0, half, 0.7f, sine, vib = plan), Synth.Tone(61, half, half, 0.7f, sine, legato = true, from = 57, vib = plan))
        val s = Synth(rate); s.add(tones)
        val x = FloatArray((2.6 * rate).toInt()); val b = FloatArray(480)
        var i = 0
        while (i < x.size) { java.util.Arrays.fill(b, 0f); s.fill(b); System.arraycopy(b, 0, x, i, min(480, x.size - i)); i += 480 }
        val times = ArrayList<Double>()
        for (k in 1 until x.size) if (x[k - 1] < 0f && x[k] >= 0f) times += (k - 1 + (-x[k - 1].toDouble() / (x[k] - x[k - 1]))) / rate
        val periods = times.zipWithNext { a, c -> c - a }
        var worst = 0.0; var worstAt = 0.0
        for (k in 1 until periods.size) {
            val at = times[k]
            if (abs(at - 1.2) < 0.08 || at < 0.1 || at > 2.3) continue
            val change = abs(periods[k] / periods[k - 1] - 1)
            if (change > worst) { worst = change; worstAt = at }
        }
        println("R7 continuity: largest change between consecutive periods ${"%.4f".format(worst)}")
        assertTrue("a jump of $worst in the pitch at $worstAt s", worst < 0.02)
        val first = periods.indices.filter { times[it] in 0.6..1.1 }.map { periods[it] }
        val wob = 1200 * Math.log((first.max() / first.min())) / Math.log(2.0) / 2
        assertTrue("planned 20 cents, measured $wob", wob in 8.0..40.0)
    }

    @Test
    fun `expression scales the swings - none is plain, more is wider - and drift is a hair`() {
        fun spread(e: Double): Double {
            Feel.expression = e
            val ps = distinct(plans(DemoPassages.lyrical()))
            return ps.map { it.knotDepth.average() }.let { sd(it) }
        }
        val steady = spread(0.0); val normal = spread(1.0); val more = spread(1.5)
        println("R7 expression: spread of the lines' intensity x0 ${steady.format()}  x1 ${normal.format()}  x1.5 ${more.format()}")
        assertTrue("plain at 0: $steady", steady < 0.005)
        assertTrue(normal > steady + 0.01 && more > normal)
        Feel.expression = 1.0
        Feel.drift = 0.02
        val l = distinct(plans(DemoPassages.lyrical())).first()
        val rs = (0..100).map { l.rateAt(1.0 + it * 0.05) }
        // Drift on top of the rest stays within a couple of percent of the drift-free line.
        Feel.drift = 0.0
        val l0 = distinct(plans(DemoPassages.lyrical())).first()
        val r0 = (0..100).map { l0.rateAt(1.0 + it * 0.05) }
        val worst = rs.indices.maxOf { abs(rs[it] / r0[it] - 1) }
        println("R7 drift: rate differs from the drift-free by at most ${"%.4f".format(worst)}")
        assertTrue("drift $worst", worst in 0.0..0.045)
    }

    @Test
    fun `a clarinet has none`() {
        assertTrue(plans(DemoPassages.lyrical(), patch = Synth.CLARINET).all { it == null })
    }
}
