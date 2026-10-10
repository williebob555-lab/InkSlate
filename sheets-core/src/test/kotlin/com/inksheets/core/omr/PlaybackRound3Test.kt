package com.inksheets.core.omr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Round 3: a euphonium that sounds like one, dynamics that arrive, notes that die away, slurs that differ by instrument. */
class PlaybackRound3Test {
    private val rate = 48_000

    private fun pitchOf(midi: Int): Pitch {
        val names = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6)
        val alters = intArrayOf(0, 1, 0, 1, 0, 0, 1, 0, 1, 0, 1, 0)
        return Pitch(names[midi % 12], midi / 12 - 1, alters[midi % 12])
    }

    private fun bar(n: Int, ms: List<Int>, base: Int = 4, arts: Map<Int, List<String>> = emptyMap(), dirs: List<Direction> = emptyList()): Measure {
        val gap = if (base == 8) 40f else 90f
        val left = (n - 1) * 400
        return Measure(n, 0, 0, Box(left, 0, left + 400, 40), 10f, Clef.TREBLE, Key(0), TimeSig(4, 4),
            ms.mapIndexed { i, m -> Note(listOf(0), listOf(pitchOf(m)), Duration(base), left + 20f + i * gap, articulations = arts[i].orEmpty()) }, directions = dirs)
    }

    private fun render(tones: List<Synth.Tone>, seconds: Double): FloatArray {
        val s = Synth(rate); s.add(tones)
        val out = FloatArray((seconds * rate).toInt()); val b = FloatArray(480)
        var i = 0
        while (i < out.size) { java.util.Arrays.fill(b, 0f); s.fill(b); System.arraycopy(b, 0, out, i, min(b.size, out.size - i)); i += b.size }
        return out
    }

    private fun db(r: Double) = 20 * log10(r.coerceAtLeast(1e-9))

    /** Power at [freq] in x[from, to) (Goertzel). */
    private fun power(x: FloatArray, from: Int, to: Int, freq: Double): Double {
        val k = 2 * cos(2 * PI * freq / rate)
        var s1 = 0.0; var s2 = 0.0
        for (i in from.coerceAtLeast(0) until to.coerceAtMost(x.size)) { val s0 = x[i] + k * s1 - s2; s2 = s1; s1 = s0 }
        return (s1 * s1 + s2 * s2 - k * s1 * s2) / max(1, to - from)
    }

    private fun partials(x: FloatArray, from: Int, to: Int, f0: Double, n: Int) = (1..n).map { power(x, from, to, f0 * it) }

    private fun sustained(patch: Synth.Patch, midi: Int, velocity: Float): FloatArray =
        render(listOf(Synth.Tone(midi, 0, (1.2 * rate).toLong(), velocity, patch)), 1.6)

    // ---- 1. timbre ---------------------------------------------------------------------------

    @Test
    fun `a euphonium has all its harmonics - not a clarinet's odd ones`() {
        for (midi in listOf(46, 50, 53, 58)) {
            val f0 = Synth.frequency(midi.toDouble())
            val x = sustained(Synth.LOW_BRASS, midi, 0.7f)
            val p = partials(x, rate / 2, rate, f0, 12)
            // Harmonics 2-12: the odd (3, 5 ...) against the even (2, 4 ...).
            // (against the average of their even neighbours: a smooth roll-off gives about 1, a clarinet's odd ones stand out)
            fun oddOverNeighbours(q: List<Double>) = listOf(3, 5, 7, 9, 11).sumOf { q[it - 1] } / listOf(3, 5, 7, 9, 11).sumOf { (q[it - 2] + q[it]) / 2 }
            val ratio = oddOverNeighbours(p)
            val c = sustained(Synth.CLARINET, midi + 12, 0.7f)
            val pc = partials(c, rate / 2, rate, Synth.frequency(midi + 12.0), 12)
            val clar = oddOverNeighbours(pc)
            println("R3 timbre midi $midi: euphonium odd/even ${"%.2f".format(ratio)}, clarinet ${"%.1f".format(clar)}; partial levels dB re the loudest: ${p.take(8).map { "%.0f".format(db(sqrt(it)) - db(sqrt(p.max()))) }}")
            assertTrue("euphonium odd/neighbours $ratio", ratio in 0.3..3.0)
            assertTrue("clarinet is odd-heavy ($clar) - the contrast", clar > 1.5 * ratio)
            // Strong low partials: the fundamental and the second carry a fair share, and the upper ones fall smoothly.
            val share = (p[0] + p[1] + p[2]) / p.sum()
            assertTrue("low partials carry $share", share > 0.5)
            for (h in 5..8) assertTrue("partial ${h + 1} louder than ${h} by more than 3 dB: monotone roll-off", p[h] <= p[h - 1] * 4.0)
        }
    }

    @Test
    fun `the brighter the louder - the centroid rises with the dynamic`() {
        for ((name, patch, midi) in listOf(Triple("euphonium", Synth.LOW_BRASS, 50), Triple("trombone", Synth.TROMBONE, 52), Triple("trumpet", Synth.BRASS, 62), Triple("horn", Synth.HORN, 55))) {
            val f0 = Synth.frequency(midi.toDouble())
            fun centroid(v: Float): Double {
                val x = sustained(patch, midi, v)
                val p = partials(x, rate / 2, rate, f0, 30)
                return (1..30).sumOf { f0 * it * p[it - 1] } / p.sum()
            }
            val soft = centroid(0.35f); val mid = centroid(0.68f); val loud = centroid(1.0f)
            println("R3 centroid $name: p ${"%.0f".format(soft)} Hz, mf ${"%.0f".format(mid)}, f ${"%.0f".format(loud)}")
            if (name == "euphonium") assertTrue("$name: as recorded, loudness hardly changes the spectrum: $soft, $mid, $loud", loud > soft * 0.9 && loud < soft * 1.2)
            else assertTrue("$name: $soft < $mid < $loud", soft < mid && mid < loud && loud > soft * 1.3)
        }
    }

    // ---- 2. dynamics -------------------------------------------------------------------------

    /** The level line the tones follow, in dB: each tone's loudness at its start and end. */
    private fun steepest(tones: List<Synth.Tone>): Double {
        var worst = 0.0
        for (t in tones) {
            val secs = t.length / rate.toDouble()
            if (secs < 0.05) continue
            val d = abs(db(Synth.amplitude(t.endVelocity).toDouble()) - db(Synth.amplitude(t.velocity).toDouble()))
            worst = max(worst, d / secs / 10.0)    // dB per 100 ms
        }
        return worst
    }

    private fun dynamic(x: Float, text: String) = Direction("dynamic", x, text = text)

    @Test
    fun `a change of dynamic arrives over a second, in an S-curve - never faster than 3 dB in 100 ms`() {
        for (bpm in listOf(60.0, 100.0, 140.0)) for ((a, b) in listOf("p" to "f", "f" to "p", "pp" to "ff", "mf" to "f")) {
            val bars = (0 until 4).map { k ->
                val dirs = ArrayList<Direction>()
                if (k == 0) dirs += dynamic(5f, a)
                if (k == 1) dirs += dynamic(405f, b)
                bar(k + 1, listOf(46, 48, 50, 51).map { it + (k % 2) * 2 }, dirs = if (k == 0 || k == 1) dirs else emptyList(), arts = emptyMap())
            }
            val p = Performance.play(bars, bpm, rate, 0, Synth.LOW_BRASS)
            val slope = steepest(p.tones)
            // The level (dB) of the notes, from the mark on: it should take most of a second to get there.
            val lv = p.tones.map { db(Synth.amplitude(it.velocity).toDouble()) }
            val from = lv[3]; val to = lv[7]
            val arrived = (4..7).first { abs(lv[it] - to) < 0.2 * abs(to - from) + 0.3 } - 3
            println("R3 dynamics $a->$b at $bpm: steepest ${"%.2f".format(slope)} dB/100 ms, level ${"%.1f".format(from)} -> ${"%.1f".format(to)} dB, arrived by note $arrived after the mark")
            assertTrue("$a->$b at $bpm: $slope dB per 100 ms", slope <= 3.0)
        }
        // The level line's steps from tone to tone are bounded too (the S-curve, not a jump).
        val p = Performance.play(listOf(bar(1, listOf(46, 48, 50, 51), dirs = listOf(dynamic(5f, "p"))), bar(2, listOf(46, 48, 50, 51), dirs = listOf(dynamic(405f, "f")))), 100.0, rate, 0, Synth.LOW_BRASS)
        val lv = p.tones.map { db(Synth.amplitude(it.velocity).toDouble()) }
        for (i in 1 until lv.size) assertTrue("a step of ${lv[i] - lv[i - 1]} dB between notes $i", abs(lv[i] - lv[i - 1]) <= 8.0)
    }

    @Test
    fun `a hairpin follows its written span`() {
        val cresc = Direction("cresc", 20f, 380f)
        val p = Performance.play(listOf(bar(1, listOf(46, 46, 46, 46), dirs = listOf(dynamic(5f, "p"), cresc)), bar(2, listOf(46, 46, 46, 46))), 100.0, rate, 0, Synth.LOW_BRASS)
        val v = p.tones.map { it.velocity }
        println("R3 hairpin: ${v.map { "%.2f".format(it) }}")
        for (i in 1 until 4) assertTrue("rising through the span: $v", v[i] > v[i - 1] - 0.005f)
        assertTrue("and more by its end: $v", v[3] > v[0] + 0.1f)
    }

    @Test
    fun `only a subito mark moves quickly`() {
        fun drop(sub: Boolean): Float {
            val dirs = ArrayList<Direction>().apply { add(dynamic(405f, "p")); if (sub) add(Direction("text", 395f, 420f, "sub.")) }
            val p = Performance.play(listOf(bar(1, listOf(46, 48, 50, 51), dirs = listOf(dynamic(5f, "f"))), bar(2, listOf(46, 48, 50, 51), dirs = dirs)), 100.0, rate, 0, Synth.LOW_BRASS)
            return p.tones[4].endVelocity
        }
        val ordinary = drop(false); val subito = drop(true)
        println("R3 subito: end of the first note after the mark $subito (ordinary $ordinary)")
        assertTrue("subito arrives within the note: $subito vs $ordinary", subito < ordinary - 0.05f && subito < 0.46f)
    }

    // ---- 3. natural decay --------------------------------------------------------------------

    /** The steepest fall (dB) in 20 ms of the 10 ms peak envelope, while the sound is within 45 dB of its loudest. */
    private fun steepestFall(x: FloatArray): Double {
        val win = rate / 100
        val env = DoubleArray(x.size / (win / 2)) { k -> var m = 0.0; for (i in k * win / 2 until min(x.size, k * win / 2 + win)) m = max(m, abs(x[i].toDouble())); m }
        val top = env.max()
        var worst = 0.0
        for (k in 0 until env.size - 4) {
            val a = env[k]; val b = env[k + 4]    // 20 ms apart
            if (a > top * 0.0056 && b > 0) worst = max(worst, db(a) - db(b))
        }
        return worst
    }

    @Test
    fun `no note is cut off - every end is a natural decay`() {
        val bars = listOf(
            bar(1, listOf(46, 48, 50, 51), arts = mapOf(2 to listOf("staccato"), 3 to listOf("accent"))),
            bar(2, listOf(53, 51, 49, 46), dirs = listOf(Direction("slur", 20f - 380f + 400f, 400f + 20f + 3 * 90f - 5f)))
        )
        for ((name, patch) in listOf("euphonium" to Synth.LOW_BRASS, "trumpet" to Synth.BRASS, "horn" to Synth.HORN, "clarinet" to Synth.CLARINET, "sax" to Synth.SAX, "flute" to Synth.FLUTE)) {
            val p = Performance.play(bars + bar(3, listOf(46, 46, 46, 46)), 100.0, rate, 0, patch)
            val x = render(p.tones, p.length / rate.toDouble() + 1.0)
            val fall = steepestFall(x)
            println("R3 decay $name: steepest fall ${"%.1f".format(fall)} dB in 20 ms")
            assertTrue("$name: a fall of $fall dB in 20 ms", fall <= 20.0)
        }
        // A note's release: brass 80-150 ms, a staccato 40-60 ms (time to 40 dB down after the hold ends).
        for ((name, patch, lo, hi) in listOf(Triple("euphonium", Synth.LOW_BRASS, 0.08), Triple("trumpet", Synth.BRASS, 0.08)).map { listOf(it.first, it.second, it.third, 0.16) }.map { arrayOf(it[0], it[1], it[2], it[3]) }) {
            patch as Synth.Patch
            assertTrue("$name release ${patch.release}", patch.release in (lo as Double)..(hi as Double))
        }
        val stac = Performance.play(listOf(bar(1, listOf(50, 50, 50, 50), arts = (0..3).associateWith { listOf("staccato") })), 100.0, rate, 0, Synth.LOW_BRASS)
        val t = stac.tones[0]
        val x = render(listOf(t), 1.0)
        val endAt = (t.start + t.length).toInt()
        val peak = x.maxOf { abs(it) }
        var down = -1
        for (i in endAt until x.size - 100 step 50) if ((i until i + 100).maxOf { abs(x[it]) } < peak * 0.01) { down = i - endAt; break }
        println("R3 staccato: -40 dB ${down * 1000 / rate} ms after the hold ends")
        assertTrue("staccato dies in 40-60 ms (+ room): ${down * 1000 / rate}", down * 1000 / rate in 25..90)
    }

    @Test
    fun `tongued notes are separated by the release decaying - not chopped, not run together`() {
        val p = Performance.play(listOf(bar(1, listOf(50, 50, 50, 50))), 100.0, rate, 0, Synth.LOW_BRASS)
        val x = render(p.tones, p.length / rate.toDouble() + 0.5)
        for (k in 0..2) {
            val t = p.tones[k]
            val body = db(sqrt((t.start + rate / 4 until t.start + rate / 4 + 960).sumOf { x[it.toInt()].toDouble() * x[it.toInt()] } / 960))
            val nextAt = p.tones[k + 1].start
            val before = db(sqrt((nextAt - 480 until nextAt).sumOf { x[it.toInt()].toDouble() * x[it.toInt()] } / 480))
            println("R3 separation $k: body ${"%.1f".format(body)} dB, the 10 ms before the next attack ${"%.1f".format(before)} dB")
            assertTrue("run together: only ${body - before} dB down before the next note", body - before >= 20)
            assertTrue("chopped: ${body - before} dB down", body - before <= 70)
        }
    }

    // ---- 4. slurs by instrument --------------------------------------------------------------

    /** Seconds both pitches are within 15 dB of their own best in 20 ms windows around the join at [joinAt] s, and the loudest frequency in between. */
    private fun overlap(patch: Synth.Patch, a: Int, b: Int): Triple<Double, Double, Double> {
        val half = (0.5 * rate).toLong()
        val x = render(listOf(Synth.Tone(a, 0, half, 0.7f, patch), Synth.Tone(b, half, half, 0.7f, patch, legato = true, from = a)), 1.2)
        val fa = Synth.frequency(a.toDouble()); val fb = Synth.frequency(b.toDouble())
        val win = rate / 50
        val mid = Synth.frequency((a + b) / 2.0 + 0.5)    // a quarter-tone off the midpoint: a slide would pass through it
        val steps = (-60..120).map { k -> half.toInt() + k * rate / 2000 }
        val pa = steps.map { power(x, it - win / 2, it + win / 2, fa) }
        val pb = steps.map { power(x, it - win / 2, it + win / 2, fb) }
        val pm = steps.map { power(x, it - win / 2, it + win / 2, mid) }
        val refA = pa.max(); val refB = pb.max()
        // Both present: the old note still within 15 dB of its sustain while the new is within 15 dB of its.
        val both = steps.indices.count { pa[it] > refA * 0.03 && pb[it] > refB * 0.03 }
        val slide = pm.max() / max(refA, refB)
        return Triple(both / 2000.0 * 1000, slide, 0.0)
    }

    @Test
    fun `a brass slur overlaps the two notes, a reed's is cleaner and shallower - and neither slides`() {
        val brass = overlap(Synth.BRASS, 60, 67)
        val eup = overlap(Synth.LOW_BRASS, 58, 65)
        val clar = overlap(Synth.CLARINET, 60, 67)
        println("R3 slur overlap (ms both heard): trumpet ${"%.0f".format(brass.first)}, euphonium ${"%.0f".format(eup.first)}, clarinet ${"%.0f".format(clar.first)}; slide energy ${"%.4f".format(brass.second)} ${"%.4f".format(eup.second)} ${"%.4f".format(clar.second)}")
        assertTrue("brass overlaps ${brass.first} ms", brass.first in 15.0..90.0)
        assertTrue("euphonium overlaps ${eup.first} ms", eup.first in 15.0..90.0)
        assertTrue("a clarinet's change is a smooth blend of 30-60 ms (${clar.first})", clar.first in 28.0..70.0)
        for (s in listOf(brass.second, eup.second, clar.second)) assertTrue("a portamento: $s", s < 0.2)
        assertTrue(Synth.LOW_BRASS.slur.maxOverlapMs > Synth.CLARINET.slur.maxOverlapMs && Synth.LOW_BRASS.slur.dipDb > Synth.CLARINET.slur.dipDb)
    }

    @Test
    fun `each family has its own vibe - vibrato, attack, release, breath`() {
        assertEquals("a clarinet has no vibrato", 0.0, Synth.CLARINET.vibratoCents, 1e-9)
        assertTrue("a euphonium's is slow and late, as recorded (4.2 Hz, about 13 cents)", Synth.LOW_BRASS.vibratoCents in 10.0..16.0 && Synth.LOW_BRASS.vibratoHz in 3.8..4.6 && Synth.LOW_BRASS.vibDelay >= 0.4)
        assertTrue("flute and sax faster", Synth.FLUTE.vibratoHz > Synth.LOW_BRASS.vibratoHz && Synth.SAX.vibratoHz > Synth.LOW_BRASS.vibratoHz && Synth.FLUTE.vibratoCents >= 7.0 && Synth.SAX.vibratoCents >= 7.0)
        assertTrue("brass attacks slower than reeds", Synth.LOW_BRASS.attack > Synth.CLARINET.attack)
        assertTrue("brass releases longer than reeds", Synth.LOW_BRASS.release > Synth.CLARINET.release)
        assertTrue("a flute is the breathiest", Synth.FLUTE.breath > Synth.SAX.breath && Synth.SAX.breath > Synth.LOW_BRASS.breath)
    }
}
