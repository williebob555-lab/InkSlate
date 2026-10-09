package com.inksheets.core.omr

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Round 2: level that moves gradually, slurs without portamento, notes that do not balloon, and real silence where there is space. */
class PlaybackRound2Test {
    private val rate = 48_000

    @After
    fun restore() { Feel.now = Feel.C }

    private fun pitchOf(midi: Int): Pitch {
        val names = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6)
        val alters = intArrayOf(0, 1, 0, 1, 0, 0, 1, 0, 1, 0, 1, 0)
        return Pitch(names[midi % 12], midi / 12 - 1, alters[midi % 12])
    }

    private fun bar(n: Int, ms: List<Int>, base: Int = 4, arts: Map<Int, List<String>> = emptyMap(), dirs: List<Direction> = emptyList(), rests: Set<Int> = emptySet()): Measure {
        val gap = if (base == 8) 40f else if (base == 1) 0f else 90f
        val left = (n - 1) * 400
        return Measure(n, 0, 0, Box(left, 0, left + 400, 40), 10f, Clef.TREBLE, Key(0), TimeSig(4, 4),
            ms.mapIndexed { i, m -> if (i in rests) Rest(Duration(base), left + 20f + i * gap) else Note(listOf(0), listOf(pitchOf(m)), Duration(base), left + 20f + i * gap, articulations = arts[i].orEmpty()) }, directions = dirs)
    }

    private fun play(bars: List<Measure>, patch: Synth.Patch = Synth.LOW_BRASS, bpm: Double = 100.0) = Performance.play(bars, bpm, rate, 0, patch)

    private fun render(tones: List<Synth.Tone>, seconds: Double): FloatArray {
        val s = Synth(rate); s.add(tones)
        val out = FloatArray((seconds * rate).toInt()); val b = FloatArray(480)
        var i = 0
        while (i < out.size) { java.util.Arrays.fill(b, 0f); s.fill(b); System.arraycopy(b, 0, out, i, min(b.size, out.size - i)); i += b.size }
        return out
    }

    private fun db(r: Double) = 20 * log10(r.coerceAtLeast(1e-9))
    private fun rms(x: FloatArray, a: Int, b: Int): Double {
        val from = a.coerceIn(0, x.size); val to = b.coerceIn(from, x.size)
        if (to == from) return 0.0
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return sqrt(s / (to - from))
    }
    /** Loudness (dB) of a 40 ms window (several periods of the lowest note) centred on second [t]. */
    private fun level(x: FloatArray, t: Double) = db(rms(x, ((t - 0.02) * rate).toInt(), ((t + 0.02) * rate).toInt()))

    /** The steepest change of loudness (dB per 10 ms) between [from] and [to] seconds. */
    private fun steepest(x: FloatArray, from: Double, to: Double): Double {
        var worst = 0.0; var t = from
        while (t + 0.01 <= to) { val d = abs(level(x, t + 0.01) - level(x, t)); if (d > worst) { worst = d; lastAt = t }; t += 0.005 }
        return worst
    }

    private var lastAt = 0.0

    private fun slurred(vararg b: Int): Direction = Direction("slur", 20f, (b[0] * 400 - 400) + 20f + 5f)

    /** Four bars under one slur, with a dynamic change a bar in. */
    private fun phrase(first: String, second: String): List<Measure> {
        val slur = Direction("slur", 20f, 3 * 400f + 20f + 3 * 90f + 5f)
        return (0 until 4).map { k ->
            val dirs = ArrayList<Direction>().apply {
                add(slur)
                if (k == 0) add(Direction("dynamic", 5f, text = first))
                if (k == 1) add(Direction("dynamic", 405f, text = second))
            }
            bar(k + 1, listOf(46, 48, 50, 51).map { it + (k % 2) * 2 }, dirs = dirs)
        }
    }

    @Test
    fun `the level of a slurred phrase moves gradually - dynamics, not spikes`() {
        for ((name, feel) in listOf("A" to Feel.A, "B" to Feel.B, "C" to Feel.C)) {
            Feel.now = feel
            for ((first, second) in listOf("mf" to "mf", "p" to "f", "f" to "p")) {
                val p = play(phrase(first, second))
                val x = render(p.tones, p.length / rate.toDouble() + 0.5)
                val end = p.tones.last().start / rate.toDouble()
                val slope = steepest(x, 0.25, end - 0.05)
                println("R2 slope $name $first->$second: steepest ${"%.2f".format(slope)} dB per 10 ms inside the phrase, at $lastAt s; tone starts ${p.tones.map { "%.3f".format(it.start / rate.toDouble()) }}")
                assertTrue("$name $first->$second: level moves ${slope} dB in 10 ms", slope <= (if (first == second) 1.0 else 1.5))
            }
        }
    }

    @Test
    fun `an accent is a gentle short weight, not a spike`() {
        val plain = bar(1, listOf(46, 46, 46, 46))
        val acc = bar(1, listOf(46, 46, 46, 46), arts = mapOf(1 to listOf("accent")))
        val a = play(listOf(plain)); val b = play(listOf(acc))
        val xa = render(a.tones, 3.0); val xb = render(b.tones, 3.0)
        val t1 = b.tones[1].start / rate.toDouble()
        // (after the start: an accent begins a little firmer, which is the point)
        val peak = (5 until 40).maxOf { k -> level(xb, t1 + k * 0.01 + 0.01) - level(xa, t1 + k * 0.01 + 0.01) }
        val steep = steepest(xb, t1 + 0.03, t1 + 0.4) - steepest(xa, t1 + 0.03, t1 + 0.4)
        println("R2 accent: peak ${"%.2f".format(peak)} dB over the plain note")
        assertTrue("accent ${peak} dB", peak <= 2.2)
        assertTrue("and no steeper than the plain note's own attack by much: $steep", steep <= 1.0)
    }

    @Test
    fun `a slur changes pitch at once - no glide`() {
        val sine = Synth.Patch(floatArrayOf(1f), 0.004, 0.0, 1f, 0.02, gain = 0.3f)
        for ((name, feel) in listOf("A" to Feel.A, "B" to Feel.B, "C" to Feel.C)) {
            Feel.now = feel
            val half = (0.5 * rate).toLong()
            val x = render(listOf(Synth.Tone(57, 0, half, 0.7f, sine), Synth.Tone(61, half, half, 0.7f, sine, legato = true, from = 57)), 1.2)
            val fOld = Synth.frequency(57.0); val fNew = Synth.frequency(61.0)
            // Rising zero crossings and the frequency each period says.
            var lastOld = 0.0; var firstNew = Double.MAX_VALUE; var prevT = -1.0
            for (i in 1 until x.size) if (x[i - 1] < 0f && x[i] >= 0f) {
                val t = (i - 1 + (-x[i - 1].toDouble() / (x[i] - x[i - 1]))) / rate
                if (prevT > 0) {
                    val f = 1 / (t - prevT)
                    if (t < 0.55 && abs(f / fOld - 1) < 0.03) lastOld = t
                    if (t > 0.49 && abs(f / fNew - 1) < 0.03 && firstNew == Double.MAX_VALUE) firstNew = t
                }
                prevT = t
            }
            val ms = (firstNew - lastOld) * 1000
            println("R2 pitch $name: ${"%.1f".format(ms)} ms from the old pitch to the new")
            assertTrue("$name: a glide of $ms ms", ms <= 16)
        }
    }

    @Test
    fun `a long note under a slur does not balloon`() {
        val slur = Direction("slur", 20f, 400f + 25f)
        val b1 = bar(1, listOf(46, 48, 50, 53), dirs = listOf(Direction("slur", 20f, 400f + 25f)))
        val b2 = bar(2, listOf(53), base = 1, dirs = listOf(slur))
        val p = play(listOf(b1, b2))
        val x = render(p.tones, p.length / rate.toDouble() + 0.6)
        val whole = p.tones.last()
        val a = whole.start / rate.toDouble(); val len = whole.length / rate.toDouble()
        val startL = level(x, a + 0.25); val endL = level(x, a + len - 0.1)
        val midL = (1..8).maxOf { k -> level(x, a + 0.25 + (len - 0.35) * k / 9) }
        println("R2 long note: start ${"%.2f".format(startL)} mid(max) ${"%.2f".format(midL)} end ${"%.2f".format(endL)} dB")
        assertTrue("a swell in the middle: ${midL - max(startL, endL)} dB over the ends", midL <= max(startL, endL) + 0.4)
    }

    @Test
    fun `where there is space it is silent - between separated notes, after a staccato, in a rest`() {
        for ((name, feel) in listOf("A" to Feel.A, "B" to Feel.B, "C" to Feel.C)) {
            Feel.now = feel
            // Four tongued quarters, four staccato quarters, then a bar of rest.
            val b1 = bar(1, listOf(46, 48, 50, 51))
            val b2 = bar(2, listOf(46, 48, 50, 51), arts = (0..3).associateWith { listOf("staccato") })
            val b3 = bar(3, listOf(0), base = 1, rests = setOf(0))
            val b4 = bar(4, listOf(46, 46, 46, 46))
            val p = play(listOf(b1, b2, b3, b4), bpm = 100.0)
            val x = render(p.tones, p.length / rate.toDouble() + 0.5)
            val beat = 0.6
            val ts = p.tones.map { it.start / rate.toDouble() }
            // Between tongued notes: the middle of the gap against the note's body.
            for (k in 0..2) {
                val body = level(x, ts[k] + 0.25)
                val gapMid = level(x, ts[k + 1] - Feel.now.gapMinMs / 2000.0 - 0.003)
                println("R2 gap $name tongued $k: body ${"%.1f".format(body)} dB, gap ${"%.1f".format(gapMid)} dB")
                assertTrue("$name: the gap after tongued note $k is only ${body - gapMid} dB down", body - gapMid >= 18)
            }
            // After a staccato: silent for the second half of its beat.
            for (k in 4..6) {
                val body = level(x, ts[k] + 0.03)
                val after = level(x, ts[k] + beat * 0.8)
                assertTrue("$name: after staccato $k only ${body - after} dB down", body - after >= 25)
                assertTrue("$name: a staccato is audibly shorter than half a beat plus a little", level(x, ts[k] + beat * 0.6) < body - 20)
            }
            // The rest bar: silent (from a third of the way in, once the last note's release and the room have gone).
            val restStart = ts[7] + beat
            val loud = level(x, ts[7] + 0.05)
            val inRest = (1..8).maxOf { k -> level(x, restStart + 0.25 + k * (2.4 - 0.35) / 9) }
            println("R2 rest $name: ${"%.1f".format(loud - inRest)} dB below the last note")
            assertTrue("$name: the rest is only ${loud - inRest} dB down", loud - inRest >= 40)
        }
    }
}
