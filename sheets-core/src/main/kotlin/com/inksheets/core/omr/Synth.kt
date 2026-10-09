package com.inksheets.core.omr

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * A small instrument of its own, the same on every device: notes played as a sum of harmonics
 * shaped like the instrument's family - brass bright and rising into the note, a clarinet's odd
 * harmonics, a flute's breath, strings' saw, mallets and plucked bass dying away - with an
 * envelope and a little vibrato. Enough to hear what a bar is meant to sound like, not to stand in
 * for the player.
 */
class Synth(val sampleRate: Int) {

    /** How an instrument family sounds. */
    class Patch(
        /** Each harmonic's strength, the fundamental first. */
        val harmonics: FloatArray,
        val attack: Double, val decay: Double, val sustain: Float, val release: Double,
        /** A note that dies away however long it is held (a mallet, a plucked string): its half-life, s. */
        val fade: Double = 0.0,
        val vibratoHz: Double = 0.0, val vibratoCents: Double = 0.0,
        /** Breath noise, as a share of the note. */
        val breath: Float = 0f,
        /** How much duller the upper harmonics start, brightening over the attack (brass). */
        val bloom: Float = 0f,
        val gain: Float = 0.22f,
        /** Drums: each tone's number a General MIDI drum (see [DrumKind]), struck and left to ring; tuned by [tune]. */
        val drum: Boolean = false,
        val tune: Double = 1.0
    )

    companion object {
        val BRASS = Patch(floatArrayOf(1f, 0.85f, 0.7f, 0.55f, 0.45f, 0.35f, 0.28f, 0.2f, 0.15f, 0.1f), 0.05, 0.1, 0.85f, 0.08, vibratoHz = 5.0, vibratoCents = 6.0, bloom = 0.7f)
        val LOW_BRASS = Patch(floatArrayOf(1f, 0.75f, 0.5f, 0.35f, 0.25f, 0.15f, 0.1f, 0.06f), 0.06, 0.12, 0.85f, 0.1, bloom = 0.6f, gain = 0.26f)
        val HORN = Patch(floatArrayOf(1f, 0.55f, 0.3f, 0.18f, 0.1f, 0.06f), 0.07, 0.1, 0.85f, 0.12, vibratoHz = 4.5, vibratoCents = 4.0, bloom = 0.4f)
        val CLARINET = Patch(floatArrayOf(1f, 0.04f, 0.65f, 0.05f, 0.4f, 0.04f, 0.25f, 0.03f, 0.12f), 0.03, 0.08, 0.9f, 0.06, vibratoHz = 4.5, vibratoCents = 3.0, breath = 0.02f)
        val SAX = Patch(floatArrayOf(1f, 0.9f, 0.65f, 0.55f, 0.45f, 0.35f, 0.25f, 0.18f, 0.12f), 0.03, 0.08, 0.85f, 0.07, vibratoHz = 5.5, vibratoCents = 10.0, breath = 0.03f)
        val DOUBLE_REED = Patch(floatArrayOf(0.6f, 1f, 0.8f, 0.6f, 0.45f, 0.3f, 0.2f, 0.12f), 0.03, 0.06, 0.85f, 0.06, vibratoHz = 5.0, vibratoCents = 6.0)
        val FLUTE = Patch(floatArrayOf(1f, 0.35f, 0.12f, 0.06f, 0.03f), 0.05, 0.08, 0.9f, 0.08, vibratoHz = 5.0, vibratoCents = 8.0, breath = 0.06f)
        val STRINGS = Patch(FloatArray(12) { 1f / (it + 1) }, 0.08, 0.1, 0.9f, 0.12, vibratoHz = 5.5, vibratoCents = 12.0)
        val MALLET = Patch(floatArrayOf(1f, 0.1f, 0.35f, 0.05f, 0.12f), 0.004, 0.2, 0.0f, 0.25, fade = 0.35)
        val PIANO = Patch(floatArrayOf(1f, 0.55f, 0.3f, 0.2f, 0.12f, 0.08f, 0.05f), 0.004, 0.3, 0.2f, 0.2, fade = 0.8)
        val BASS = Patch(floatArrayOf(1f, 0.6f, 0.3f, 0.15f, 0.08f), 0.006, 0.25, 0.3f, 0.08, fade = 0.9, gain = 0.3f)
        val VOICE = Patch(floatArrayOf(1f, 0.5f, 0.25f, 0.1f, 0.05f), 0.08, 0.1, 0.85f, 0.12, vibratoHz = 5.5, vibratoCents = 15.0)
        val DRUMS = Patch(floatArrayOf(1f), 0.001, 0.0, 0f, 0.0, gain = 0.32f, drum = true)
        /** A drum line's bass drums: toms' sound tuned well down. */
        val BASS_DRUMS = Patch(floatArrayOf(1f), 0.001, 0.0, 0f, 0.0, gain = 0.4f, drum = true, tune = 0.5)
        /** Tenors: toms' sound tuned up, tight. */
        val TENORS = Patch(floatArrayOf(1f), 0.001, 0.0, 0f, 0.0, gain = 0.3f, drum = true, tune = 1.45)

        /** The patch for a General MIDI program (see [Midi.program]). */
        fun patchFor(program: Int): Patch = when (program) {
            in 0..7 -> PIANO
            in 8..15 -> MALLET
            in 32..39 -> BASS
            in 40..51 -> STRINGS
            in 52..55 -> VOICE
            56, 59 -> BRASS
            57, 58 -> LOW_BRASS
            60 -> HORN
            61, 62, 63 -> BRASS
            in 64..67 -> SAX
            68, 69, 70 -> DOUBLE_REED
            71 -> CLARINET
            in 72..79 -> FLUTE
            else -> PIANO
        }

        fun frequency(midi: Double) = 440.0 * 2.0.pow((midi - 69) / 12.0)

        /** A sine wave looked up rather than worked out: every harmonic of every voice wants one a sample. */
        private const val TABLE = 4096
        private val SINE = FloatArray(TABLE + 1) { kotlin.math.sin(2 * PI * it / TABLE).toFloat() }
        private const val PER_RADIAN = TABLE / (2 * PI)

        /** sin([phase]) for a phase in 0..2π (a little past either way is fine), to a part in ten thousand. */
        fun sine(phase: Double): Double {
            var p = phase * PER_RADIAN
            if (p < 0) p += TABLE
            val i = p.toInt().coerceIn(0, TABLE - 1)
            val f = p - i
            return SINE[i] + (SINE[i + 1] - SINE[i]) * f
        }
    }

    /**
     * One note to play: from sample [start] for [length] samples. [accent] (0-1): struck harder - a
     * quicker, brighter start that stands out from the notes round it and falls back at once.
     */
    class Tone(
        val midi: Int, val start: Long, val length: Long, val velocity: Float, val patch: Patch, val accent: Float = 0f,
        /** How loud it has become by its end: a swell into a high point, a taper away from it. */
        val endVelocity: Float = velocity,
        /** Joined to the note before it (a slur): no new attack - the sound moves on to it. */
        val legato: Boolean = false
    )

    private class Voice(val tone: Tone, val freq: Double) {
        var phase = DoubleArray(tone.patch.harmonics.size)
        var released = -1L
        /** A drum's own sound, where the tone is one. */
        var drum: DrumVoice? = null
    }

    private val pending = ArrayList<Tone>()
    private val voices = ArrayList<Voice>()
    private var noise = 12345L

    /** Where the music is: samples played since the start. */
    @Volatile var position: Long = 0L
        private set

    @Synchronized
    fun add(tones: List<Tone>) {
        pending += tones
        pending.sortBy { it.start }
    }

    /** Everything stopped at once, and the clock back to [to]. */
    @Synchronized
    fun reset(to: Long = 0L) {
        pending.clear(); voices.clear(); position = to
    }

    /**
     * The room: a small hall's reverberation under everything, so notes bloom and decay into a
     * space rather than stopping dead in a box - what most makes a synthesised band sound played.
     */
    private val room = Room(sampleRate)

    /** What the voices make, before it is levelled and added in. */
    private var mix = FloatArray(0)
    /** The level the whole is turned down to now (1: as it is), and the gain per sample towards where it is going. */
    private var level = 1f

    /**
     * The next [buf].size samples, added into [buf] - levelled: where many voices together would
     * go past full scale (a whole band, a big chord), the whole is turned down at once, smoothly,
     * and back up slowly after - never clipped, never pumping.
     */
    @Synchronized
    fun fill(buf: FloatArray) {
        val n = buf.size
        if (mix.size != n) mix = FloatArray(n)
        java.util.Arrays.fill(mix, 0f)
        render(mix)
        room.process(mix)
        var peak = 0f
        for (v in mix) peak = max(peak, kotlin.math.abs(v))
        // Headroom: the loudest sample at nine tenths of full scale at most. Turned down at once
        // when it would go over; back up towards as it is over about a second.
        val want = if (peak > 0.9f) 0.9f / peak else 1f
        val to = if (want < level) want else level + (want - level) * min(1f, n / sampleRate.toFloat())
        for (i in 0 until n) {
            val g = if (want < level) want else level + (to - level) * (i + 1) / n
            buf[i] += mix[i] * g
        }
        level = to
    }

    private fun render(buf: FloatArray) {
        val n = buf.size
        val end = position + n
        // Notes starting in this block begin at their own sample.
        while (pending.isNotEmpty() && pending.first().start < end) {
            val t = pending.removeAt(0)
            voices += Voice(t, frequency(t.midi.toDouble())).apply {
                if (t.patch.drum) drum = DrumVoice(t.midi, t.start, t.velocity, t.accent, t.patch.gain, t.patch.tune, t.start * 31 + t.midi)
            }
        }
        val it = voices.iterator()
        while (it.hasNext()) {
            val v = it.next()
            if (render(v, buf, position)) it.remove()
        }
        position = end
    }

    /** One voice into [buf] from [from]; true when it has finished. */
    private fun render(v: Voice, buf: FloatArray, from: Long): Boolean {
        v.drum?.let { return it.render(buf, from, sampleRate) }
        val p = v.tone.patch
        val sr = sampleRate.toDouble()
        val accent = v.tone.accent.toDouble()
        // Joined to the note before (a slur): no fresh attack, the tone simply moves on - a brief
        // glide in from nothing so it never clicks.
        val legato = v.tone.legato && p.fade <= 0.0
        // An accent speaks at once: its attack a fraction of the patch's own.
        val attack = if (legato) 0.012 * sr else p.attack * sr * (1.0 - 0.75 * accent)
        val decay = p.decay * sr; val release = p.release * sr
        val punch = 0.07 * sr
        val length = max(1L, v.tone.length)
        val stop = v.tone.start + length
        val nyquist = sr / 2 * 0.9
        // Louder is brighter: a soft note's upper harmonics fall away, a loud one's ring out - as
        // every wind and brass instrument does (its tone changes with its dynamic, not only its level).
        val v0 = v.tone.velocity; val v1 = v.tone.endVelocity
        // Vibrato only on a held note, and only once it has settled.
        val vibDelay = 0.28 * sr
        for (i in buf.indices) {
            val s = from + i
            if (s < v.tone.start) continue
            val t = (s - v.tone.start).toDouble()
            // The envelope: up, down to the held level, and away after the note ends.
            var env = when {
                t < attack -> t / attack
                legato -> p.sustain.toDouble() + (1.0 - p.sustain) * 0.25 * exp(-(t - attack) / (0.05 * sr))
                t < attack + decay -> 1.0 - (1.0 - p.sustain) * (t - attack) / decay
                else -> p.sustain.toDouble()
            }
            // Struck or plucked: up, then dying away, halving every [fade] seconds however long it is held.
            if (p.fade > 0 && t >= attack) env = exp(-(t - attack) / (p.fade * sr) * 0.693)
            // The accent's front: half as loud again and more, falling back to the note within a tenth of a second.
            if (accent > 0) env *= 1.0 + accent * 1.6 * exp(-t / punch)
            if (s >= stop) {
                val r = (s - stop).toDouble()
                if (r >= release) return true
                env *= 1.0 - r / release
            }
            if (env <= 0.0) continue
            // Where its loudness has got to: a swell or a taper across the note.
            val u = (t / length).coerceIn(0.0, 1.0)
            val vel = v0 + (v1 - v0) * u.toFloat()
            val vib = if (p.vibratoHz > 0 && t > vibDelay && length > sr * 0.45)
                p.vibratoCents / 1200.0 * sin(2 * PI * p.vibratoHz * t / sr) * min(1.0, (t - vibDelay) / (0.4 * sr)) else 0.0
            val f = v.freq * (1.0 + vib * 0.693)
            // Brass blooms: its upper harmonics come in over the attack. (An accent is bright from its very start.)
            val bloom = if (p.bloom > 0f && accent < 0.5 && !legato) min(1.0, 0.3 + t / (attack * 3 + 1)) else 1.0
            val loud = (0.45 + 0.55 * min(1.2, vel.toDouble())).coerceIn(0.3, 1.15)
            var x = 0.0
            for (h in p.harmonics.indices) {
                val fh = f * (h + 1)
                if (fh >= nyquist) break
                val amp = p.harmonics[h] * (if (h == 0) 1.0 else bloom.pow(1.0 + h * p.bloom) * loud.pow(h * 0.45))
                v.phase[h] += 2 * PI * fh / sr
                if (v.phase[h] > 2 * PI) v.phase[h] -= 2 * PI
                x += amp * sine(v.phase[h])
            }
            if (p.breath > 0f) {
                noise = noise * 6364136223846793005L + 1442695040888963407L
                // Breath: most at the start of a note (the tongue and the air), a little under it after.
                val b = p.breath * (if (legato) 0.6 else 1.0 + 2.5 * exp(-t / (0.06 * sr)))
                x += b * ((noise ushr 33).toDouble() / (1L shl 31) - 1.0) * 4
            }
            buf[i] += (x * env * p.gain * vel).toFloat()
        }
        return from + buf.size >= stop + release
    }

    /**
     * A small hall (Schroeder's reverberator: four combs in parallel, two all-passes after): mono,
     * the same on every device; about a second and a half to die away, mixed under the dry sound.
     */
    private class Room(rate: Int) {
        private val k = rate / 44100.0
        private val combs = intArrayOf(1557, 1617, 1491, 1422).map { FloatArray((it * k).toInt().coerceAtLeast(1)) }
        private val combAt = IntArray(4)
        private val combLow = FloatArray(4)
        private val passes = intArrayOf(556, 225).map { FloatArray((it * k).toInt().coerceAtLeast(1)) }
        private val passAt = IntArray(2)
        private val feedback = 0.76f
        private val damp = 0.3f
        private val wet = 0.16f

        fun process(buf: FloatArray) {
            for (i in buf.indices) {
                val input = buf[i] * 0.25f
                var out = 0f
                for (c in combs.indices) {
                    val line = combs[c]; val j = combAt[c]
                    val y = line[j]
                    combLow[c] = y * (1 - damp) + combLow[c] * damp
                    line[j] = input + combLow[c] * feedback
                    combAt[c] = if (j + 1 >= line.size) 0 else j + 1
                    out += y
                }
                for (a in passes.indices) {
                    val line = passes[a]; val j = passAt[a]
                    val y = line[j]
                    line[j] = out + y * 0.5f
                    out = y - out * 0.5f
                    passAt[a] = if (j + 1 >= line.size) 0 else j + 1
                }
                buf[i] = buf[i] + out * wet
            }
        }
    }
}

/**
 * Bars of a score played on a [Synth]: from bar [from] to bar [to] (numbers as read), at [bpm]
 * quarter notes a minute, as they sound ([transpose] semitones written above sounding), round
 * again if [loop] - each time a little faster, by [rampStep] bpm up to [rampTo], for working a
 * passage up to tempo.
 */
class ScorePlayer(
    val synth: Synth,
    private val score: Score,
    val from: Int,
    val to: Int,
    bpm: Double,
    private val transpose: Int,
    private val patch: Synth.Patch,
    val loop: Boolean = false,
    private val rampTo: Double? = null,
    private val rampStep: Double = 0.0,
    /** A drum part: played on the drums (see [DrumKind]). */
    private val drums: DrumKind? = null,
    /** The bars to play, in order, where given (from a bar on, repeats and all: [PlayOrder.from]) - else those numbered [from] to [to]. */
    order: List<Measure>? = null
) {
    /** The tempo now: goes up round each loop when ramping. */
    @Volatile var bpm: Double = bpm
        private set

    /** The bar being played now, and how many times round; for showing where it is. */
    @Volatile var bar: Int = from
        private set
    @Volatile var round: Int = 0
        private set
    @Volatile var finished: Boolean = false
        private set

    /** Where each bar of the pass starts, in samples from the pass's start, and how long the pass is. */
    private var starts = LongArray(0)
    private var numbers = IntArray(0)
    private var passStart = 0L
    private var passLength = 0L

    private val bars = order ?: score.measures.filter { it.number + it.bars - 1 >= from && it.number <= to }

    init { schedule(0L) }

    /** One pass through the bars, from sample [at]: played as a player would ([Performance]). */
    private fun schedule(at: Long) {
        passStart = at
        val played = Performance.play(bars, bpm, synth.sampleRate, transpose, patch, at, drums)
        starts = played.barStarts; numbers = played.barNumbers
        passLength = played.length
        synth.add(played.tones)
    }

    /** The next samples into [buf]; keeps [bar] up to date and goes round again when looping. */
    fun fill(buf: FloatArray) {
        if (finished) return
        synth.fill(buf)
        val into = synth.position - passStart
        val i = starts.indexOfLast { it <= into }
        if (i >= 0) bar = numbers[i]
        if (into >= passLength) {
            if (loop && passLength > 0) {
                rampTo?.let { top -> bpm = min(top, bpm + rampStep) }
                schedule(synth.position)
                bar = numbers.firstOrNull() ?: from
                round++
            } else if (into >= passLength + synth.sampleRate / 2) finished = true
        }
    }
}

/**
 * The band without you: every other part's bars played together on a [Synth], each on its own
 * instrument, laid on [mine]'s timeline bar by bar - by bar number, so repeats and time changes
 * follow the part being played from. From bar [from] to [to] of [mine] (in playing order), at [bpm].
 */
class EnsemblePlayer(
    val synth: Synth,
    mine: Score,
    others: List<Voice>,
    val from: Int,
    val to: Int,
    val bpm: Double,
    /** Your own part, quietly, to play along with: its loudness (0 for none). */
    guide: Pair<Voice, Float>? = null,
    /** [mine]'s bars to play, in order, where given (see [PlayOrder.from]) - else those numbered [from] to [to]. */
    order: List<Measure>? = null
) {
    /** One part: its notes, how far it is written above where it sounds, how it sounds. */
    class Voice(val score: Score, val transpose: Int, val patch: Synth.Patch,
                /** Drums: the drums each note is (see [DrumKind]), in place of its pitches. */
                val drums: DrumKind? = null)

    @Volatile var bar: Int = from
        private set
    @Volatile var finished = false
        private set

    private val starts: LongArray
    private val numbers: IntArray
    private val length: Long

    init {
        val order = order ?: PlayOrder.unrolled(mine).measures.filter { it.number + it.bars - 1 >= from && it.number <= to }
        // The timeline: every bar of the part played from, in playing order (a rest of many bars
        // as that many bars), each as long as it is played.
        val numbers = ArrayList<Int>(); val lengths = ArrayList<Double>(); val mineBars = ArrayList<Measure?>()
        for (mm in order) for (k in 0 until mm.bars) {
            numbers += mm.number + k
            lengths += if (mm.bars > 1) mm.time.quarters else mm.playedQuarters
            mineBars += if (mm.bars > 1) null else mm
        }
        val len = lengths.toDoubleArray()
        fun along(v: Voice): List<Measure?> = numbers.map { n -> v.score.measures.firstOrNull { n >= it.number && n < it.number + it.bars }?.takeIf { it.bars == 1 } }
        // Every part planned on the one timeline; one tempo for all, led by the part played from.
        val plans = others.map { v -> Interpretation.analyse(along(v), len, v.drums) }
        val lead = Interpretation.analyse(mineBars, len)
        val map = Interpretation.tempoMap(listOf(lead) + plans, bpm, ensemble = true)
        // Balanced as a band is: the tune on top a little forward, the inner parts back, the bass firm.
        val means = plans.map { p -> p.notes.flatMap { it.keys.toList() }.takeIf { it.isNotEmpty() }?.average() }
        val pitched = others.indices.filter { others[it].drums == null && means[it] != null }
        val top = pitched.maxByOrNull { means[it]!! - others[it].transpose }
        val bottom = pitched.minByOrNull { means[it]!! - others[it].transpose }
        val base = 0.8f / kotlin.math.sqrt(others.size.coerceAtLeast(1).toFloat())
        val tones = ArrayList<Synth.Tone>()
        for ((i, v) in others.withIndex()) {
            val weight = when (i) { top -> 1.2f; bottom -> 1.05f; else -> if (v.drums != null) 1f else 0.88f }
            tones += Interpretation.tones(plans[i], map, synth.sampleRate, v.transpose, v.patch, 0L, base * weight, v.drums)
        }
        // Your own part as well, where wanted - at the loudness asked for.
        guide?.let { (v, g) -> if (g > 0f) tones += Interpretation.tones(lead, map, synth.sampleRate, v.transpose, v.patch, 0L, base * g, v.drums) }
        starts = LongArray(numbers.size) { (map.seconds(lead.barQ[it]) * synth.sampleRate).toLong() }
        this.numbers = numbers.toIntArray()
        length = (map.seconds(lead.totalQ) * synth.sampleRate).toLong()
        synth.add(tones)
    }

    fun fill(buf: FloatArray) {
        if (finished) return
        synth.fill(buf)
        val i = starts.indexOfLast { it <= synth.position }
        if (i >= 0) bar = numbers[i]
        if (synth.position >= length + synth.sampleRate / 2) finished = true
    }
}
