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
        val gain: Float = 0.22f
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
    }

    /**
     * One note to play: from sample [start] for [length] samples. [accent] (0-1): struck harder - a
     * quicker, brighter start that stands out from the notes round it and falls back at once.
     */
    class Tone(val midi: Int, val start: Long, val length: Long, val velocity: Float, val patch: Patch, val accent: Float = 0f)

    private class Voice(val tone: Tone, val freq: Double) {
        var phase = DoubleArray(tone.patch.harmonics.size)
        var released = -1L
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
            voices += Voice(t, frequency(t.midi.toDouble()))
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
        val p = v.tone.patch
        val sr = sampleRate.toDouble()
        val accent = v.tone.accent.toDouble()
        // An accent speaks at once: its attack a fraction of the patch's own.
        val attack = p.attack * sr * (1.0 - 0.75 * accent); val decay = p.decay * sr; val release = p.release * sr
        val punch = 0.07 * sr
        val stop = v.tone.start + v.tone.length
        val nyquist = sr / 2 * 0.9
        for (i in buf.indices) {
            val s = from + i
            if (s < v.tone.start) continue
            val t = (s - v.tone.start).toDouble()
            // The envelope: up, down to the held level, and away after the note ends.
            var env = when {
                t < attack -> t / attack
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
            val vib = if (p.vibratoHz > 0 && t > attack * 2) p.vibratoCents / 1200.0 * sin(2 * PI * p.vibratoHz * t / sr) * min(1.0, (t - attack * 2) / (0.3 * sr)) else 0.0
            val f = v.freq * (1.0 + vib * 0.693)
            // Brass blooms: its upper harmonics come in over the attack.
            // (An accent is bright from its very start.)
            val bright = if (p.bloom > 0f && accent < 0.5) min(1.0, 0.3 + t / (attack * 3 + 1)) else 1.0
            var x = 0.0
            for (h in p.harmonics.indices) {
                val fh = f * (h + 1)
                if (fh >= nyquist) break
                val amp = p.harmonics[h] * (if (h == 0) 1.0 else bright.pow(1.0 + h * p.bloom))
                v.phase[h] += 2 * PI * fh / sr
                if (v.phase[h] > 2 * PI) v.phase[h] -= 2 * PI
                x += amp * sin(v.phase[h])
            }
            if (p.breath > 0f) {
                noise = noise * 6364136223846793005L + 1442695040888963407L
                x += p.breath * ((noise ushr 33).toDouble() / (1L shl 31) - 1.0) * 4
            }
            buf[i] += (x * env * p.gain * v.tone.velocity).toFloat()
        }
        return from + buf.size >= stop + release
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
    private val rampStep: Double = 0.0
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

    private val bars = score.measures.filter { it.number + it.bars - 1 >= from && it.number <= to }

    init { schedule(0L) }

    /** One pass through the bars, from sample [at]: played as a player would ([Performance]). */
    private fun schedule(at: Long) {
        passStart = at
        val played = Performance.play(bars, bpm, synth.sampleRate, transpose, patch, at)
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
    guide: Pair<Voice, Float>? = null
) {
    /** One part: its notes, how far it is written above where it sounds, how it sounds. */
    class Voice(val score: Score, val transpose: Int, val patch: Synth.Patch)

    @Volatile var bar: Int = from
        private set
    @Volatile var finished = false
        private set

    private val starts: LongArray
    private val numbers: IntArray
    private val length: Long

    init {
        val order = PlayOrder.unrolled(mine).measures.filter { it.number + it.bars - 1 >= from && it.number <= to }
        val loud = 0.75f / kotlin.math.sqrt(others.size.coerceAtLeast(1).toFloat())
        val tones = ArrayList<Synth.Tone>()
        val s = ArrayList<Long>(); val n = ArrayList<Int>()
        var t = 0L
        fun samples(q: Double) = (q * 60.0 / bpm * synth.sampleRate).toLong()
        fun lay(v: Voice, m: Measure, at: Long, velocity: Float) {
            if (m.bars > 1) return
            var q = 0.0
            for (e in m.events) {
                if (e is Note && q < m.time.quarters) {
                    val len = max(1L, samples(min(e.duration.quarters, m.time.quarters - q)) - synth.sampleRate / 60)
                    for (p in e.pitches) tones += Synth.Tone((p.midi - v.transpose).coerceIn(12, 115), at + samples(q), len, velocity, v.patch)
                }
                q += e.duration.quarters
            }
        }
        for (mm in order) {
            for (k in 0 until mm.bars) {
                val number = mm.number + k
                s += t; n += number
                val barLen = samples(if (mm.bars > 1) mm.time.quarters else mm.playedQuarters)
                for (v in others) v.score.measures.firstOrNull { number >= it.number && number < it.number + it.bars }?.let { lay(v, it, t, loud) }
                guide?.let { (v, g) -> if (g > 0f && k == 0) lay(v, mm, t, g) }
                t += barLen
            }
        }
        starts = s.toLongArray(); numbers = n.toIntArray(); length = t
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
