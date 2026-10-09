package com.inksheets.core.omr

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
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
        val tune: Double = 1.0,
        /**
         * A wind or brass voice made the way the instrument makes its tone: every harmonic up to
         * [SPECTRAL_TOP] Hz, falling away [slopeSoft] (played softly) to [slopeLoud] (loud) per
         * harmonic - so a loud note is brighter, not only louder - and lifted where the
         * instrument's body resonates ([formantHz], by [formantGain]), whatever note is played.
         * [harmonics] is then not used.
         */
        val spectral: Boolean = false,
        val slopeSoft: Double = 2.0,
        val slopeLoud: Double = 1.0,
        val formantHz: Double = 1000.0,
        val formantGain: Double = 1.0,
        /** Below this frequency the even harmonics are faint (a clarinet's low register). */
        val oddBelow: Double = 0.0,
        /** How far under the note a brass player's lips start it, cents, falling in over 30 ms. */
        val scoop: Double = 0.0,
        /** The buzz (or chiff) of a note's start, as a share of the note. */
        val chiff: Float = 0f
    )

    companion object {
        // Winds and brass by how each makes its tone (see Patch.spectral): body resonance, how
        // fast the upper harmonics fall away soft and loud, the start of the note.
        val BRASS = Patch(FloatArray(1), 0.035, 0.12, 0.88f, 0.12, vibratoHz = 5.2, vibratoCents = 5.0, bloom = 0.55f, gain = 0.2f,
            spectral = true, slopeSoft = 2.4, slopeLoud = 0.95, formantHz = 1250.0, formantGain = 2.2, scoop = 22.0)
        val LOW_BRASS = Patch(FloatArray(1), 0.045, 0.14, 0.88f, 0.14, vibratoHz = 4.8, vibratoCents = 4.0, bloom = 0.5f, gain = 0.24f,
            spectral = true, slopeSoft = 2.6, slopeLoud = 1.05, formantHz = 520.0, formantGain = 2.4, scoop = 28.0)
        val HORN = Patch(FloatArray(1), 0.06, 0.12, 0.9f, 0.16, vibratoHz = 4.5, vibratoCents = 3.0, bloom = 0.35f, gain = 0.22f,
            spectral = true, slopeSoft = 3.0, slopeLoud = 1.5, formantHz = 420.0, formantGain = 2.0, scoop = 15.0)
        val CLARINET = Patch(FloatArray(1), 0.03, 0.08, 0.92f, 0.08, vibratoHz = 4.5, vibratoCents = 2.0, breath = 0.012f, gain = 0.21f,
            spectral = true, slopeSoft = 2.3, slopeLoud = 1.4, formantHz = 1500.0, formantGain = 1.4, oddBelow = 1700.0, chiff = 0.03f)
        val SAX = Patch(FloatArray(1), 0.03, 0.08, 0.88f, 0.09, vibratoHz = 5.3, vibratoCents = 9.0, breath = 0.02f, gain = 0.2f,
            spectral = true, slopeSoft = 1.9, slopeLoud = 0.9, formantHz = 1700.0, formantGain = 1.8, chiff = 0.04f)
        val DOUBLE_REED = Patch(FloatArray(1), 0.03, 0.06, 0.88f, 0.07, vibratoHz = 5.0, vibratoCents = 5.0, gain = 0.2f,
            spectral = true, slopeSoft = 1.8, slopeLoud = 1.1, formantHz = 1100.0, formantGain = 2.6, chiff = 0.03f)
        val FLUTE = Patch(FloatArray(1), 0.05, 0.08, 0.92f, 0.1, vibratoHz = 5.0, vibratoCents = 7.0, breath = 0.035f, gain = 0.24f,
            spectral = true, slopeSoft = 3.6, slopeLoud = 2.4, formantHz = 800.0, formantGain = 1.2, chiff = 0.08f)

        /** How high a spectral voice's harmonics go: above this a wind instrument has little to say. */
        const val SPECTRAL_TOP = 9000.0
        private const val MAX_HARMONICS = 32
        private val LN2 = ln(2.0)
        private const val TWO_PI = 2 * PI
        /** Samples between the points loudness and pitch are worked out at (the sound runs straight between them). */
        private const val CONTROL = 16

        /**
         * Loudness to amplitude, as ears hear it: a step in dynamic is a step in decibels, not a
         * share - pianissimo to fortissimo about fifteen of them, as a band plays it.
         */
        fun amplitude(level: Float): Float = (0.68 * kotlin.math.exp(2.8 * (level - 0.68))).toFloat()
        val STRINGS = Patch(FloatArray(12) { 1f / (it + 1) }, 0.08, 0.1, 0.9f, 0.12, vibratoHz = 5.5, vibratoCents = 12.0)
        /** The metronome's click inside playback: short and bright, a wood block's tick. */
        val CLICK = Patch(floatArrayOf(1f, 0.0f, 0.5f, 0.0f, 0.25f), 0.0008, 0.025, 0.0f, 0.015, fade = 0.02, gain = 0.3f)
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
        val legato: Boolean = false,
        /** Slurred from this note (MIDI): the pitch glides over from it, as a slurred line does. */
        val from: Int? = null
    )

    private class Voice(var tone: Tone) {
        var freq = frequency(tone.midi.toDouble())
        /** A spectral voice's harmonics at the note's start and end loudness; how many are worth making. */
        var amp0: DoubleArray? = null
        var amp1: DoubleArray? = null
        var count = 1
        /** How many harmonics are being made now: more than [count] while a joined note's last ones fade out. */
        var active = 1
        /** Tones joined on to this voice (a slur): it carries on from one to the next, never stops and starts. */
        val queue = ArrayList<Tone>()
        /** Where the tone being played began, and whether it was joined on (so: no attack). */
        var segStart = tone.start
        var continued = false
        /** The voice's own start, for vibrato and drift: they go on across joined notes. */
        val born = tone.start
        var vibOk = tone.length > 0
        /** How far (octaves) the pitch started from the note's, closing over a few tens of ms. */
        var glideOct = 0.0
        /** The loudness the voice had when the tone being played took over from the one before. */
        var gainFrom = 0.0
        var lastGain = 0.0
        /** A slow drift of a couple of cents, each voice its own: a held note never stands quite still. */
        val drift = ((tone.start * 2654435761L + tone.midi * 40503L) and 0xFFFF) / 65536.0 * 2 * PI
        val phase = DoubleArray(MAX_HARMONICS)
        /** Each harmonic's strength now: worked out every few dozen samples, not every sample. */
        val now = DoubleArray(MAX_HARMONICS)
        var nowAt = -1_000_000L
        /** Loudness and pitch are worked out every [CONTROL] samples and run straight between. */
        var ctlAt = Long.MIN_VALUE
        var gA = 0.0; var gB = 0.0; var fA = 1.0; var fB = 1.0
        /** Breath and chiff: noise with the highs taken out. */
        var lo1 = 0.0; var lo2 = 0.0
        /** A drum's own sound, where the tone is one. */
        var drum: DrumVoice? = null

        init { setup(tone) }

        fun last(): Tone = queue.lastOrNull() ?: tone

        fun setup(t: Tone) {
            val p = t.patch
            freq = frequency(t.midi.toDouble())
            val most = if (p.spectral) max(1, kotlin.math.floor(SPECTRAL_TOP / freq).toInt().coerceAtMost(MAX_HARMONICS)) else p.harmonics.size
            amp0 = if (p.spectral) Spectra.spectrum(p, freq, t.velocity, most) else null
            amp1 = if (p.spectral) Spectra.spectrum(p, freq, t.endVelocity, most) else null
            val a0 = amp0; val a1 = amp1
            count = if (a0 == null || a1 == null) most else (most - 1 downTo 0).firstOrNull { maxOf(a0[it], a1[it]) > 0.004 }?.plus(1) ?: 1
            active = max(active, count)
        }
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

    /**
     * The tempo changed while playing: everything still to sound - and the rest of what is
     * sounding - [ratio] times as long from now (2: half as fast). Nothing stops or starts again;
     * the music carries straight on at the new pace, at the same pitch.
     */
    @Synchronized
    fun retime(ratio: Double) {
        if (ratio <= 0.0 || kotlin.math.abs(ratio - 1.0) < 1e-6) return
        val now = position
        fun at(t: Long) = if (t <= now) t else now + ((t - now) * ratio).toLong()
        fun moved(t: Tone) = Tone(t.midi, at(t.start), (at(t.start + t.length) - at(t.start)).coerceAtLeast(1L), t.velocity, t.patch, t.accent, t.endVelocity, t.legato, t.from)
        val shifted = pending.map { moved(it) }
        pending.clear(); pending += shifted
        for (v in voices) {
            val t = v.tone
            val end = t.start + t.length
            if (end > now) v.tone = Tone(t.midi, t.start, (at(end) - t.start).coerceAtLeast(1L), t.velocity, t.patch, t.accent, t.endVelocity, t.legato, t.from)
            for (k in v.queue.indices) v.queue[k] = moved(v.queue[k])
        }
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
    /** The level the whole is turned down to now (1: as it is). */
    private var level = 1f

    /**
     * The next [buf].size samples, added into [buf] - levelled: where many voices together would
     * go past full scale (a whole band, a big chord), the whole is turned down smoothly across
     * the block (never in one step, which is a click), and back up slowly after.
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
        // Headroom: the loudest sample at nine tenths of full scale at most. Turned down over the
        // block when it would go over; back up towards as it is over about a second.
        val want = if (peak * level > 0.9f) 0.9f / peak else 1f
        val to = if (want < level) want else level + (want - level) * min(1f, n / sampleRate.toFloat())
        // The change of level is spread over a few milliseconds at most (a long block is no excuse for a slow one).
        val ramp = min(n, sampleRate / 200)
        for (i in 0 until n) {
            val g = if (i >= ramp) to else level + (to - level) * (i + 1) / ramp
            buf[i] += softClip(mix[i] * g)
        }
        level = to
    }

    /** Smooth past 0.95 (only a sliver ever gets there): no hard edge on a peak. */
    private fun softClip(x: Float): Float {
        val a = kotlin.math.abs(x)
        if (a <= 0.95f) return x
        val y = 0.95f + 0.05f * kotlin.math.tanh((a - 0.95f) / 0.05f)
        return if (x < 0) -y else y
    }

    private fun render(buf: FloatArray) {
        val n = buf.size
        val end = position + n
        // Notes starting in this block begin at their own sample.
        while (pending.isNotEmpty() && pending.first().start < end) {
            val t = pending.removeAt(0)
            if (t.legato && !t.patch.drum && t.patch.fade <= 0.0) {
                // Joined to the note before: carried on by the voice that is playing it.
                val want = (t.from ?: t.midi).toDouble()
                val tol = (sampleRate * 0.06).toLong()
                val carrier = voices.filter { v ->
                    v.drum == null && v.tone.patch === t.patch && v.last().start < t.start &&
                        kotlin.math.abs(v.last().start + v.last().length - t.start) <= tol && v.queue.none { it.start == t.start }
                }.minByOrNull { kotlin.math.abs(it.last().midi - want) * 1000.0 + kotlin.math.abs(it.last().start + it.last().length - t.start) / sampleRate }
                if (carrier != null) { carrier.queue += t; continue }
            }
            voices += Voice(t).apply {
                if (t.patch.drum) drum = DrumVoice(t.midi, t.start, t.velocity, t.accent, t.patch.gain, t.patch.tune, t.start * 31 + t.midi)
                else if (t.legato && t.from != null) glideOct = ln(frequency(t.from.toDouble()) / freq) / LN2
            }
        }
        val it = voices.iterator()
        while (it.hasNext()) {
            val v = it.next()
            if (render(v, buf, position)) it.remove()
        }
        position = end
    }

    private fun smooth(u: Double): Double { val x = u.coerceIn(0.0, 1.0); return x * x * (3 - 2 * x) }

    private fun noiseSample(): Double {
        noise = noise * 6364136223846793005L + 1442695040888963407L
        return (noise ushr 33).toDouble() / (1L shl 31) - 1.0
    }

    /**
     * One voice into [buf] from [from]; true when it has finished. A voice is one sound that goes
     * on from note to note under a slur: the next note of the slur takes it over - its pitch
     * gliding, its loudness moving, its phase carrying straight on, no new attack.
     */
    private fun render(v: Voice, buf: FloatArray, from: Long): Boolean {
        v.drum?.let { return it.render(buf, from, sampleRate) }
        val sr = sampleRate.toDouble()
        val glideTime = 0.035 * sr
        val punch = 0.07 * sr
        val nyquist = sr / 2 * 0.9
        val twoPiOverSr = 2 * PI / sr
        for (i in buf.indices) {
            val s = from + i
            if (s < v.tone.start) continue
            // Joined on: the next tone takes over at its own sample.
            while (v.queue.isNotEmpty() && s >= v.queue[0].start) {
                val next = v.queue.removeAt(0)
                val oldLog = ln(v.freq) / LN2 + v.glideOct * (1 - smooth((s - v.segStart) / glideTime))
                v.gainFrom = v.lastGain
                v.tone = next
                v.setup(next)
                v.glideOct = oldLog - ln(v.freq) / LN2
                v.segStart = next.start
                v.continued = true
                v.ctlAt = Long.MIN_VALUE
                if (next.length > sr * 0.45) v.vibOk = true
            }
            val tone = v.tone
            val p = tone.patch
            val release = p.release * sr
            val length = max(1L, tone.length)
            val stop = tone.start + length
            val last = v.queue.isEmpty()
            // Letting go: a smooth fall to nothing, shorter on a short note (a staccato does not ring on).
            val relLen = max(0.015 * sr, min(release * 0.8, 0.25 * length))
            if (last && s >= stop + relLen) return true

            // Loudness and pitch, worked out every few samples and run straight between.
            if (v.ctlAt == Long.MIN_VALUE) {
                v.ctlAt = s
                v.gA = if (v.continued) v.gainFrom else gainAt(v, s, stop, relLen, last, sr, glideTime, punch)
                v.fA = pitchAt(v, s, sr, glideTime)
                v.gB = gainAt(v, s + CONTROL, stop, relLen, last, sr, glideTime, punch)
                v.fB = pitchAt(v, s + CONTROL, sr, glideTime)
            } else if (s >= v.ctlAt + CONTROL) {
                v.ctlAt += CONTROL
                if (v.ctlAt < s) v.ctlAt = s
                v.gA = v.gB; v.fA = v.fB
                v.gB = gainAt(v, v.ctlAt + CONTROL, stop, relLen, last, sr, glideTime, punch)
                v.fB = pitchAt(v, v.ctlAt + CONTROL, sr, glideTime)
            }
            val u = ((s - v.ctlAt).toDouble() / CONTROL).coerceIn(0.0, 1.0)
            val g = v.gA + (v.gB - v.gA) * u
            v.lastGain = g
            val fMul = v.fA + (v.fB - v.fA) * u
            val t = (s - v.segStart).toDouble()
            val f = v.freq * fMul

            // The harmonics' strengths change slowly (a swell, a bloom): every 32 samples is enough.
            if (s - v.nowAt >= 32) {
                val slew = if (v.nowAt < 0) 1.0 else if (v.continued) 1 - exp(-32.0 / (0.02 * sr)) else 1.0
                v.nowAt = s
                if (v.active > v.count && t > 0.2 * sr) v.active = v.count
                // Where its loudness has got to: a swell or a taper across the note.
                val prog = (t / length).coerceIn(0.0, 1.0)
                val soft = v.continued || tone.legato
                // Brass blooms: its upper harmonics come in over the attack - the same for an accent as for any note.
                val attack = max(0.004 * sr, p.attack * sr)
                val bloom = if (p.bloom > 0f && !soft) min(1.0, 0.3 + t / (attack * 3 + 1)) else 1.0
                val vel = tone.velocity + (tone.endVelocity - tone.velocity) * prog.toFloat()
                val loud = (0.45 + 0.55 * min(1.2, vel.toDouble())).coerceIn(0.3, 1.15)
                val a0 = v.amp0; val a1 = v.amp1
                for (h in 0 until v.active) {
                    val target = if (h >= v.count) 0.0 else if (a0 != null && a1 != null) (a0[h] + (a1[h] - a0[h]) * prog) * (if (h == 0 || bloom >= 1.0) 1.0 else bloom.pow(1.0 + h * p.bloom))
                        else p.harmonics[h] * (if (h == 0) 1.0 else bloom.pow(1.0 + h * p.bloom) * loud.pow(h * 0.45))
                    v.now[h] += (target - v.now[h]) * slew
                }
            }
            var x = 0.0
            val w0 = twoPiOverSr * f
            val top = min(v.active, (nyquist / f).toInt())
            val phase = v.phase; val now = v.now
            var w = w0
            for (h in 0 until top) {
                var ph = phase[h] + w
                if (ph > TWO_PI) ph -= TWO_PI
                phase[h] = ph
                x += now[h] * sine(ph)
                w += w0
            }
            // The start of the note: a reed's or a flute's chiff, and breath - faint soft noise
            // with the highs taken out, nothing like a click or a hiss.
            if (p.chiff > 0f || p.breath > 0f) {
                v.lo1 += (noiseSample() - v.lo1) * 0.12
                v.lo2 += (v.lo1 - v.lo2) * 0.12
                val nz = v.lo2 * 5
                if (p.chiff > 0f && !v.continued && !tone.legato && t < 0.1 * sr)
                    x += p.chiff * tone.velocity * exp(-t / (0.03 * sr)) * nz * 2
                if (p.breath > 0f) {
                    // A little more at the start of a note (the tongue and the air), a little under it after.
                    val b = p.breath * (if (v.continued || tone.legato) 0.6 else 1.0 + 1.0 * exp(-t / (0.06 * sr)))
                    x += b * nz * 0.6
                }
            }
            buf[i] += (x * g).toFloat()
        }
        return false
    }

    /** What a voice's output is multiplied by at sample [s]: the note's envelope, its loudness, its accent. */
    private fun gainAt(v: Voice, s: Long, stop: Long, relLen: Double, last: Boolean, sr: Double, glideTime: Double, punch: Double): Double {
        val tone = v.tone
        val p = tone.patch
        val accent = tone.accent.toDouble()
        val t = (s - v.segStart).toDouble()
        val length = max(1L, tone.length)
        val soft = v.continued || tone.legato
        val att = if (tone.legato && !v.continued) 0.012 * sr else max(if (p.fade > 0) 0.0008 * sr else 0.004 * sr, p.attack * sr * (1.0 - 0.5 * accent))
        val decay = p.decay * sr
        val sustain = p.sustain.toDouble()
        val env = when {
            v.continued -> sustain
            t < 0 -> 0.0
            t < att -> 0.5 * (1 - kotlin.math.cos(PI * t / att))
            p.fade > 0 -> exp(-(t - att) / (p.fade * sr) * 0.693)
            soft -> sustain
            t < att + decay -> 1.0 - (1.0 - sustain) * (t - att) / decay
            else -> sustain
        }
        // An accent: firmer - the same sound, a few decibels louder at the front, settling back a little.
        val boost = 1.0 + accent * (0.3 + 0.35 * exp(-t / punch))
        val prog = (t / length).coerceIn(0.0, 1.0)
        val vel = tone.velocity + (tone.endVelocity - tone.velocity) * prog.toFloat()
        var g = env * boost * p.gain * (if (p.spectral) amplitude(vel) else vel)
        // Taken over from the note before: loudness moves over to this one's, never dips.
        if (v.continued) {
            val w = smooth(t / glideTime)
            g = v.gainFrom * (1 - w) + g * w
        }
        if (last && s >= stop) {
            val r = (s - stop).toDouble()
            g *= if (r >= relLen) 0.0 else 0.5 * (1 + kotlin.math.cos(PI * r / relLen))
        }
        return g
    }

    /** What the voice's pitch is multiplied by at sample [s]: vibrato, the lips settling, a glide from the note before, a drift. */
    private fun pitchAt(v: Voice, s: Long, sr: Double, glideTime: Double): Double {
        val tone = v.tone
        val p = tone.patch
        val t = (s - v.segStart).toDouble()
        val age = (s - v.born).toDouble()
        val vibDelay = 0.28 * sr
        val vib = if (p.vibratoHz > 0 && v.vibOk && age > vibDelay)
            p.vibratoCents / 1200.0 * sin(2 * PI * p.vibratoHz * age / sr) * min(1.0, (age - vibDelay) / (0.4 * sr)) else 0.0
        // Lips settling onto the note (brass): only on a note that is struck.
        val scoop = if (p.scoop > 0 && !v.continued && !tone.legato) -p.scoop / 1200.0 * exp(-t / (0.03 * sr)) else 0.0
        val drift = 2.0 / 1200.0 * sin(2 * PI * 0.31 * age / sr + v.drift)
        val glide = if (v.glideOct != 0.0) v.glideOct * (1 - smooth(t / glideTime)) else 0.0
        val m = 1.0 + (vib + scoop + drift) * 0.693
        return if (glide != 0.0) m * 2.0.pow(glide) else m
    }

    /**
     * A spectral voice's harmonics at [level] loudness: falling away the faster the softer it is
     * played, lifted near the body's resonance, the even ones faint low in a clarinet's range -
     * normalised so the whole is as loud however bright.
     */
    private object Spectra {
        fun spectrum(p: Patch, f0: Double, level: Float, count: Int): DoubleArray {
            val loud = ((level - 0.25) / 0.85).coerceIn(0.0, 1.0)
            val slope = p.slopeSoft + (p.slopeLoud - p.slopeSoft) * loud
            val a = DoubleArray(count)
            var sum = 0.0
            for (h in 0 until count) {
                val n = h + 1
                val fh = f0 * n
                var v = 1.0 / n.toDouble().pow(slope)
                // The body's resonance: about an octave wide, on a log scale.
                val oct = ln(fh / p.formantHz) / 0.693
                v *= 1.0 + (p.formantGain - 1.0) * exp(-oct * oct * 2.0)
                if (p.oddBelow > 0 && n % 2 == 0 && fh < p.oddBelow) v *= 0.12
                a[h] = v; sum += v * v
            }
            val norm = 1.0 / kotlin.math.sqrt(sum.coerceAtLeast(1e-9))
            for (h in 0 until count) a[h] *= norm * 1.6
            return a
        }
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
        private val wet = 0.07f

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
    /** The metronome's click with the music, on its beats. */
    private val click: Boolean = false,
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
        val played = Performance.play(bars, bpm, synth.sampleRate, transpose, patch, at, drums, click)
        starts = played.barStarts; numbers = played.barNumbers
        passLength = played.length
        synth.add(played.tones)
    }

    /** The tempo set to [to] while playing: the music goes on from where it is, at that pace. */
    fun setTempo(to: Double) {
        if (to <= 0 || kotlin.math.abs(to - bpm) < 1e-6) return
        val ratio = bpm / to
        val into = synth.position - passStart
        synth.retime(ratio)
        for (i in starts.indices) if (starts[i] > into) starts[i] = into + ((starts[i] - into) * ratio).toLong()
        if (passLength > into) passLength = into + ((passLength - into) * ratio).toLong()
        bpm = to
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
    order: List<Measure>? = null,
    /** The metronome's click with the band, on the beats of [mine]. */
    click: Boolean = false
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
    private var length: Long
    /** The tempo now: changed while playing by [setTempo]. */
    @Volatile var tempo: Double = bpm
        private set

    /** The tempo set to [to] while playing: the band goes on from where it is, at that pace. */
    fun setTempo(to: Double) {
        if (to <= 0 || kotlin.math.abs(to - tempo) < 1e-6) return
        val ratio = tempo / to
        val now = synth.position
        synth.retime(ratio)
        for (i in starts.indices) if (starts[i] > now) starts[i] = now + ((starts[i] - now) * ratio).toLong()
        if (length > now) length = now + ((length - now) * ratio).toLong()
        tempo = to
    }

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
        if (click) tones += Performance.clicks(mineBars, lead.barQ, map, synth.sampleRate)
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
