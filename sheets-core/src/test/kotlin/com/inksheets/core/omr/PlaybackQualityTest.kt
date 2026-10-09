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
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What the playback sounds like, measured, not listened to: pops and hiss at note starts,
 * legato that is one voice, ties that are one tone, line breaks that cost nothing, accents that
 * are the same instrument, phrases that follow slurs, articulation and dynamics that are heard.
 */
class PlaybackQualityTest {
    private val rate = 48_000
    /** The slur's brief softening, and a little for the measuring. */
    private val dipLimit get() = Feel.now.dipDb + 0.8

    // ---- building music ----------------------------------------------------------------------

    private fun pitchOf(midi: Int): Pitch {
        val names = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6)
        val alters = intArrayOf(0, 1, 0, 1, 0, 0, 1, 0, 1, 0, 1, 0)
        return Pitch(names[midi % 12], midi / 12 - 1, alters[midi % 12])
    }

    private fun note(midi: Int, x: Float, base: Int = 4, arts: List<String> = emptyList(), tie: Boolean = false) =
        Note(listOf(0), listOf(pitchOf(midi)), Duration(base), x, articulations = arts, tie = tie)

    private fun bar(n: Int, events: List<Event>, left: Int = 0, width: Int = 400, dirs: List<Direction> = emptyList(),
                    staff: Int = 0, page: Int = 0, showsKey: Boolean = false, key: Key = Key(0)) =
        Measure(n, page, staff, Box(left, 0, left + width, 40), 10f, Clef.TREBLE, key, TimeSig(4, 4), events, showsKey = showsKey, directions = dirs)

    /** Notes of one bar, [base] the note value, spaced 40 px (eighths: 20 px) from the bar's left. */
    private fun notes(left: Int, midis: List<Int>, base: Int = 4, arts: Map<Int, List<String>> = emptyMap(), ties: Set<Int> = emptySet()): List<Event> {
        val gap = if (base == 8) 40f else 90f
        return midis.mapIndexed { i, m -> note(m, left + 20f + i * gap, base, arts[i].orEmpty(), i in ties) }
    }

    private fun played(bars: List<Measure>, patch: Synth.Patch = Synth.LOW_BRASS, bpm: Double = 120.0) =
        Performance.play(bars, bpm, rate, 0, patch)

    private fun render(tones: List<Synth.Tone>, seconds: Double, block: Int = 480): FloatArray {
        val s = Synth(rate)
        s.add(tones)
        val out = FloatArray((seconds * rate).toInt())
        val b = FloatArray(block)
        var i = 0
        while (i < out.size) {
            java.util.Arrays.fill(b, 0f)
            s.fill(b)
            System.arraycopy(b, 0, out, i, min(b.size, out.size - i))
            i += b.size
        }
        return out
    }

    // ---- measuring ---------------------------------------------------------------------------

    private fun peak(x: FloatArray) = x.maxOf { abs(it) }

    /** The biggest step between two samples, as a share of the loudest sample. */
    private fun maxJump(x: FloatArray): Double {
        var m = 0.0
        for (i in 1 until x.size) m = max(m, abs((x[i] - x[i - 1]).toDouble()))
        return m / peak(x)
    }

    /**
     * The steepest step anywhere in [x] against the steepest of any note's own sustain (150-300 ms in):
     * above 1 means somewhere the sound steps harder than any note ever does by itself - a click or pop.
     */
    private fun popRatio(x: FloatArray, tones: List<Synth.Tone>): Double {
        var own = 1e-9
        for (t in tones) {
            val at = t.start.toInt()
            for (i in max(1, at + rate * 150 / 1000) until min(x.size, at + rate * 300 / 1000)) own = max(own, abs((x[i] - x[i - 1]).toDouble()))
        }
        return maxJump(x) * peak(x) / own
    }

    /** [x] with everything under [hz] taken out (a biquad high-pass). */
    private fun highpass(x: FloatArray, hz: Double): FloatArray {
        val w = 2 * PI * hz / rate; val alpha = sin(w) / (2 * 0.707)
        val b0 = (1 + cos(w)) / 2; val b1 = -(1 + cos(w)); val b2 = b0
        val a0 = 1 + alpha; val a1 = -2 * cos(w); val a2 = 1 - alpha
        val y = FloatArray(x.size)
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in x.indices) {
            val v = (b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2) / a0
            x2 = x1; x1 = x[i].toDouble(); y2 = y1; y1 = v; y[i] = v.toFloat()
        }
        return y
    }

    private fun rms(x: FloatArray, from: Int, to: Int): Double {
        val a = from.coerceIn(0, x.size); val b = to.coerceIn(a, x.size)
        if (b == a) return 0.0
        var s = 0.0
        for (i in a until b) s += x[i].toDouble() * x[i]
        return sqrt(s / (b - a))
    }

    private fun db(r: Double) = 20 * log10(r.coerceAtLeast(1e-9))

    /** The loudness (dB) of a 30 ms window centred on second [t]. */
    private fun level(x: FloatArray, t: Double) = db(rms(x, ((t - 0.015) * rate).toInt(), ((t + 0.015) * rate).toInt()))

    /** How far (dB) the level falls between [t]'s neighbours at its lowest - 0 where it never dips below the quieter of them. */
    private fun dipAt(x: FloatArray, t: Double): Double {
        val ref = min(level(x, t - 0.07), level(x, t + 0.09))
        var low = Double.MAX_VALUE
        var k = t - 0.05
        while (k <= t + 0.06) { low = min(low, level(x, k)); k += 0.005 }
        return max(0.0, ref - low)
    }

    private fun centroid(x: FloatArray, from: Int, to: Int, f0: Double): Double {
        val w = x.copyOfRange(from, to)
        var num = 0.0; var den = 0.0
        for (h in 1..40) {
            val f = f0 * h
            if (f > 9000) break
            val k = 2 * cos(2 * PI * f / rate)
            var s1 = 0.0; var s2 = 0.0
            for (v in w) { val s0 = v + k * s1 - s2; s2 = s1; s1 = s0 }
            val p = s1 * s1 + s2 * s2 - k * s1 * s2
            num += f * p; den += p
        }
        return num / den
    }

    // ---- music used --------------------------------------------------------------------------

    /** An euphonium line: eight quarter notes, pairs slurred, a staccato, an accent, a long last note. */
    private fun melody(): List<Measure> {
        val run = Direction("slur", 290f, 425f)
        val b1 = bar(1, notes(0, listOf(46, 49, 53, 51), arts = mapOf(2 to listOf("staccato"))), dirs = listOf(Direction("slur", 20f, 115f), run))
        val b2 = bar(2, notes(400, listOf(53, 51, 49, 46), arts = mapOf(0 to listOf("accent"))), left = 400, dirs = listOf(run, Direction("slur", 510f, 695f)))
        return listOf(b1, b2)
    }

    // ---- 1. pops and hiss --------------------------------------------------------------------

    @Test
    fun `no pop and no hiss at the start of a note`() {
        for ((name, patch) in listOf("low brass" to Synth.LOW_BRASS, "brass" to Synth.BRASS, "clarinet" to Synth.CLARINET, "flute" to Synth.FLUTE, "sax" to Synth.SAX)) {
            val p = played(melody(), patch)
            val x = render(p.tones, p.length / rate + 1.0)
            val pop = popRatio(x, p.tones)
            val hf = highpass(x, 6000.0)
            // High-frequency energy in the 30 ms after each onset against the sustain (150-300 ms after).
            var worst = 0.0
            for (t in p.tones.filter { !it.legato }) {
                val at = t.start.toInt()
                // The share of the sound that is up there, at the start against in the sustain (a quieter sustain is no excuse).
                val onset = rms(hf, at, at + rate * 30 / 1000) / max(rms(x, at, at + rate * 30 / 1000), 1e-6)
                val sustain = rms(hf, at + rate * 150 / 1000, at + rate * 300 / 1000) / max(rms(x, at + rate * 150 / 1000, at + rate * 300 / 1000), 1e-6)
                worst = max(worst, onset / max(sustain, 0.003))
            }
            println("PQ pops $name: step at a note's start ${"%.2f".format(pop)} times the steepest in its sustain, onset/sustain HF share worst ${"%.2f".format(worst)}, max jump ${"%.3f".format(maxJump(x))} of peak")
            assertTrue("$name: a step at a note start ${pop} times the steepest of the sustain", pop <= 1.35)
            assertTrue("$name: hiss at note starts, ${"%.2f".format(worst)} times the sustain's", worst <= 1.3)
        }
    }

    @Test
    fun `a note starts from silence and stops into silence`() {
        val tone = Synth.Tone(46, 4800, (0.5 * rate).toLong(), 0.9f, Synth.LOW_BRASS, accent = 1f)
        val x = render(listOf(tone), 1.6)
        val first = (4800 until 4800 + rate / 250).maxOf { abs(x[it]) }      // the first 4 ms
        println("PQ accent first 4 ms ${"%.4f".format(first)} of peak ${"%.3f".format(peak(x))}")
        assertTrue("an accent's first 4 ms are ramped up from zero", first < 0.25f * peak(x))
        assertTrue("the end goes to nothing", rms(x, (1.55 * rate).toInt(), (1.6 * rate).toInt()) < 0.002 * peak(x))
    }

    // ---- 2. legato ---------------------------------------------------------------------------

    @Test
    fun `a slurred line is one voice - no dips at the joins`() {
        // Eight quarters in two bars, one slur over the lot.
        val s = listOf(Direction("slur", 20f, 400f + 20f + 3 * 90f + 5f))
        val b1 = bar(1, notes(0, listOf(46, 48, 50, 51)), dirs = s)
        val b2 = bar(2, notes(400, listOf(53, 51, 50, 48)), left = 400, dirs = s)
        for ((name, patch) in listOf("low brass" to Synth.LOW_BRASS, "clarinet" to Synth.CLARINET, "strings" to Synth.STRINGS)) {
            val p = played(listOf(b1, b2), patch)
            val x = render(p.tones, p.length / rate + 1.0)
            val dips = p.tones.drop(1).map { dipAt(x, it.start.toDouble() / rate) }
            println("PQ legato $name: dips dB ${dips.map { "%.2f".format(it) }}, legato ${p.tones.map { it.legato }}")
            assertTrue("$name: every joined note is legato", p.tones.drop(1).all { it.legato })
            assertTrue("$name: dips ${dips}", dips.all { it <= dipLimit })
        }
    }

    @Test
    fun `separated notes are tongued - they come again, gently`() {
        val b = bar(1, notes(0, listOf(46, 46, 46, 46)))
        val p = played(listOf(b))
        val x = render(p.tones, p.length / rate + 1.0)
        val dips = p.tones.drop(1).map { dipAt(x, it.start.toDouble() / rate) }
        println("PQ tongued dips dB ${dips.map { "%.2f".format(it) }}, jump ${"%.4f".format(maxJump(x))}")
        assertTrue("each note is struck again: $dips", dips.all { it > 1.5 })
        assertTrue("a pop: ${popRatio(x, p.tones)}", popRatio(x, p.tones) <= 1.35)
    }

    // ---- 3. ties -----------------------------------------------------------------------------

    private fun onsets(p: Performance.Played) = p.tones.size

    @Test
    fun `tied notes are one tone across a barline`() {
        val b1 = bar(1, notes(0, listOf(46, 48, 50, 53), ties = setOf(3)))
        val b2 = bar(2, notes(400, listOf(53, 51, 50, 48)), left = 400)
        val p = played(listOf(b1, b2))
        val held = p.tones.filter { it.midi == 53 }
        println("PQ tie tones ${p.tones.size}, held length ${held.firstOrNull()?.length}")
        assertEquals(7, onsets(p))
        assertEquals(1, held.size)
        assertTrue("one held tone of two beats", held[0].length > rate * 0.9)
        val x = render(p.tones, p.length / rate + 1.0)
        val beat = p.tones.first { it.midi == 53 }.start.toDouble() / rate
        val dip = dipAt(x, beat + 0.5)   // where the second note would be struck
        println("PQ tie dip at the barline ${"%.2f".format(dip)} dB")
        assertTrue(dip <= 1.5)
    }

    @Test
    fun `a slur joining two notes of one pitch is a tie, over a barline and over a line break`() {
        // Over a barline: the slur runs on past the first bar's edge.
        val run = Direction("slur", 20f + 3 * 90f, 400f + 20f + 5f)
        val b1 = bar(1, notes(0, listOf(46, 48, 50, 53)), dirs = listOf(run))
        val b2 = bar(2, notes(400, listOf(53, 51, 50, 48)), left = 400, dirs = listOf(run))
        val p = played(listOf(b1, b2))
        assertEquals("a slur between two like notes is a tie", 7, onsets(p))
        // Over a line break: the next bar is on another staff of the page, key shown again.
        val key = Key(-2)
        val c1 = bar(1, notes(0, listOf(46, 48, 50, 53), ties = setOf(3)), key = key)
        val c2 = bar(2, notes(0, listOf(53, 51, 50, 48)), staff = 1, showsKey = true, key = key)
        val q = played(listOf(c1, c2))
        assertEquals("a tie over a line break", 7, onsets(q))
        // A slur of three notes with a repeated pitch inside is not a tie: the repeat is joined, not held.
        val three = Direction("slur", 20f, 20f + 2 * 90f + 5f)
        val d = bar(1, notes(0, listOf(53, 53, 55, 53)), dirs = listOf(three))
        assertEquals(4, onsets(played(listOf(d))))
    }

    // ---- 4. line transitions -----------------------------------------------------------------

    @Test
    fun `a new line is no new section - the music goes straight on`() {
        val key = Key(-2)
        val line1 = (1..4).map { bar(it, notes(0, listOf(46, 48, 50, 51)), left = (it - 1) * 400, key = key) }
        // Two lines of four bars: the second starts a new staff with its key printed again.
        val line2 = (5..8).map { bar(it, notes(0, listOf(46, 48, 50, 51)), left = (it - 5) * 400, staff = 1, showsKey = it == 5, key = key) }
        val one = (1..8).map { bar(it, notes(0, listOf(46, 48, 50, 51)), left = (it - 1) * 400, key = key) }
        val broken = played(line1 + line2)
        val straight = played(one)
        val beat = rate * 60 / 120
        // Last note of the line and first of the next: a beat apart, as the tempo says (bar 4's last to bar 5's first).
        val last = broken.tones[15]; val first = broken.tones[16]
        val ref = straight.tones[15].let { straight.tones[16].start - it.start }
        println("PQ line break: gap ${(first.start - last.start)} samples, straight ${ref}, beat $beat, lengths ${broken.length} vs ${straight.length}")
        assertEquals("the tempo is kept across the break", ref, first.start - last.start)
        assertTrue("on the beat", abs((first.start - last.start) - beat) <= beat * 0.06)
        assertEquals("nothing slower for the break", straight.length.toDouble(), broken.length.toDouble(), 2.0)
        // And no breath taken: the last note of the line sounds as long as any other.
        assertTrue("the last note of the line is not cut short (${last.length} vs ${straight.tones[15].length})", last.length >= straight.tones[15].length * 0.98)
        assertEquals("loudness as before", straight.tones[16].velocity.toDouble(), first.velocity.toDouble(), 0.01)
    }

    @Test
    fun `a slur over a line break is joined`() {
        val key = Key(-2)
        val s1 = Direction("slur", 20f + 2 * 90f, 400f + 20f)
        val s2 = Direction("slur", 20f - 380f, 20f + 5f)
        val a = bar(1, notes(0, listOf(46, 48, 50, 51)), dirs = listOf(s1.copy(x2 = 395f)), key = key)
        val b = bar(2, notes(0, listOf(53, 51, 50, 48)), dirs = listOf(Direction("slur", -20f, 25f)), staff = 1, showsKey = true, key = key)
        val p = played(listOf(a, b))
        println("PQ slur over line: ${p.tones.map { it.legato }}, lengths ${p.tones.map { it.length }}, starts ${p.tones.map { it.start }}")
        assertTrue("the first note of the next line is joined to the last of this", p.tones[4].legato)
        assertEquals("no gap", p.tones[3].start + p.tones[3].length, p.tones[4].start)
    }

    // ---- 5. accents --------------------------------------------------------------------------

    @Test
    fun `an accent is the same instrument, louder`() {
        for ((name, patch) in listOf("low brass" to Synth.LOW_BRASS, "brass" to Synth.BRASS, "clarinet" to Synth.CLARINET)) {
            val plain = render(listOf(Synth.Tone(58, 0, (0.7 * rate).toLong(), 0.7f, patch)), 1.5)
            val acc = render(listOf(Synth.Tone(58, 0, (0.7 * rate).toLong(), 0.7f, patch, accent = 1f)), 1.5)
            val f0 = Synth.frequency(58.0)
            val cp = centroid(plain, 0, rate / 2, f0); val ca = centroid(acc, 0, rate / 2, f0)
            // The accent is a gentle weight: its loudest tenth of a second against the plain note's, 2 dB at most.
            val louder = (5 until 40).maxOf { k -> level(acc, k * 0.01 + 0.015) - level(plain, k * 0.01 + 0.015) }
            println("PQ accent $name: centroid plain ${"%.0f".format(cp)} accent ${"%.0f".format(ca)} Hz, ${"%.1f".format(louder)} dB louder")
            assertTrue("$name: timbre ${ca / cp}", abs(ca / cp - 1) <= 0.15)
            assertTrue("$name: $louder dB", louder in 0.8..2.2)
        }
        // Planned from the page: an accent mark leaves the pitch's tone alone but louder.
        val b = bar(1, notes(0, listOf(46, 46, 46, 46), arts = mapOf(1 to listOf("accent"))))
        val p = played(listOf(b))
        val ratio = p.tones[1].velocity / p.tones[2].velocity
        println("PQ accent planned: level ${p.tones[1].velocity} vs ${p.tones[2].velocity}, accent ${p.tones[1].accent}")
        assertTrue(p.tones[1].accent > 0f && ratio < 1.15f)
    }

    // ---- 6. phrases --------------------------------------------------------------------------

    @Test
    fun `a slur over several bars is one phrase - no dips, no gaps, at the barlines`() {
        val slur = Direction("slur", 20f, 3 * 400f + 20f + 3 * 90f + 5f)
        val bars = (0 until 4).map { k -> bar(k + 1, notes(k * 400, listOf(46, 48, 50, 51)), left = k * 400, dirs = listOf(slur)) }
        val p = played(bars)
        val x = render(p.tones, p.length / rate + 1.0)
        val joins = (1 until p.tones.size).map { p.tones[it].start.toDouble() / rate }
        val dips = joins.map { dipAt(x, it) }
        val barlines = (1 until 4).map { it * 4 }
        println("PQ phrase: dips at barlines ${barlines.map { "%.2f".format(dips[it - 1]) }}, max any ${"%.2f".format(dips.max())}")
        for (i in 0 until p.tones.size - 1) assertEquals("no gap after note $i", p.tones[i].start + p.tones[i].length, p.tones[i + 1].start)
        assertTrue("dips $dips", dips.all { it <= dipLimit })
        // Loudness across the barlines changes smoothly: no bump at the bar's first note.
        val lv = p.tones.map { it.velocity }
        for (b in barlines) {
            val step = abs(lv[b] - lv[b - 1])
            assertTrue("a jump in loudness of $step at barline $b: $lv", step < 0.06f)
        }
    }

    // ---- Copprasch: alternating articulations, dynamics --------------------------------------

    @Test
    fun `a bar of slurred pairs, tongued and staccato notes sounds as written`() {
        val arts = mapOf(3 to listOf("staccato"), 4 to listOf("staccato"), 7 to listOf("accent"))
        // eighths: [slur e0 e1] e2 e3. e4. [slur e5 e6] e7>
        val dirs = listOf(Direction("slur", 20f, 20f + 40f + 5f), Direction("slur", 20f + 5 * 40f, 20f + 6 * 40f + 5f))
        val b = bar(1, notes(0, listOf(50, 52, 53, 52, 50, 48, 50, 53), base = 8, arts = arts), dirs = dirs)
        val p = played(listOf(b), Synth.LOW_BRASS, 100.0)
        val nominal = (rate * 60 / 100 / 2).toLong()
        val t = p.tones
        println("PQ copprasch: lengths/nominal ${t.map { "%.2f".format(it.length.toDouble() / nominal) }}, legato ${t.map { it.legato }}")
        assertEquals(8, t.size)
        assertTrue("the second of a slurred pair is joined, not struck", t[1].legato && t[1].from == t[0].midi)
        assertTrue(t[6].legato && t[6].from == t[5].midi)
        assertTrue("and the others are struck", !t[0].legato && !t[2].legato && !t[3].legato && !t[4].legato && !t[5].legato && !t[7].legato)
        assertTrue("a slurred note holds to the next (${t[0].length})", t[0].length >= nominal * 0.98)
        val rel = rate * Feel.now.releaseMs.toLong() / 1000
        val slot = t[3].start - t[2].start
        assertTrue("a tongued note leaves a gap of at least 40 ms (${t[2].length + rel} of $slot)", t[2].length + rel in (slot * 0.75).toLong()..(slot - rate * 40 / 1000))
        for (i in listOf(3, 4)) assertTrue("staccato is short: ${(t[i].length + rel).toDouble() / nominal}", t[i].length + rel in (nominal * 0.35).toLong()..(nominal * 0.55).toLong())
        assertTrue("the accent is marked", t[7].accent > 0f)
        // Heard: the staccato note's second half is much quieter than its first.
        val x = render(t, p.length / rate + 1.0)
        val s = t[3].start.toInt()
        val first = rms(x, s, s + (nominal * 0.4).toInt()); val second = rms(x, s + (nominal * 0.6).toInt(), s + nominal.toInt())
        println("PQ staccato first/second half ${"%.1f".format(db(first) - db(second))} dB; joined dip ${"%.2f".format(dipAt(x, t[1].start.toDouble() / rate))} dB; tongued dip ${"%.2f".format(dipAt(x, t[2].start.toDouble() / rate))} dB")
        assertTrue("staccato dies away: ${db(first) - db(second)}", db(first) - db(second) > 6.0)
        assertTrue(dipAt(x, t[1].start.toDouble() / rate) <= dipLimit)
    }

    @Test
    fun `piano and then forte a bar later are far apart`() {
        fun two(first: String, second: String): Performance.Played {
            val b1 = bar(1, notes(0, listOf(50, 50, 50, 50)), dirs = listOf(Direction("dynamic", 5f, text = first)))
            val b2 = bar(2, notes(400, listOf(50, 50, 50, 50)), left = 400, dirs = listOf(Direction("dynamic", 405f, text = second)))
            return played(listOf(b1, b2))
        }
        val p = two("p", "f")
        val x = render(p.tones, p.length / rate + 1.0)
        val a = p.tones.slice(1..3); val b = p.tones.slice(5..7)
        val pa = a.map { rms(x, it.start.toInt() + rate / 10, it.start.toInt() + rate / 4) }.average()
        val fb = b.map { rms(x, it.start.toInt() + rate / 10, it.start.toInt() + rate / 4) }.average()
        println("PQ dynamics: p to f ${"%.1f".format(db(fb) - db(pa))} dB (tones ${a.map { "%.2f".format(it.velocity) }} vs ${b.map { "%.2f".format(it.velocity) }})")
        assertTrue("p to f only ${db(fb) - db(pa)} dB", db(fb) - db(pa) in 10.0..16.0)
        // A dynamic holds until the next one: the bar after f with nothing written is still f.
        val b3 = bar(3, notes(800, listOf(50, 50, 50, 50)), left = 800)
        val q = played(listOf(
            bar(1, notes(0, listOf(50, 50, 50, 50)), dirs = listOf(Direction("dynamic", 5f, text = "p"))),
            bar(2, notes(400, listOf(50, 50, 50, 50)), left = 400, dirs = listOf(Direction("dynamic", 405f, text = "f"))), b3))
        val v2 = q.tones.slice(4..7).map { it.velocity }.average(); val v3 = q.tones.slice(8..11).map { it.velocity }.average()
        assertEquals(v2, v3, 0.15)
        assertTrue("still forte, not piano", v3 - q.tones.slice(0..3).map { it.velocity }.average() > 0.4)
    }
}
