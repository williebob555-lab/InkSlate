package com.inksheets.core.sampler

import com.inksheets.core.omr.Synth
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * One sounding line of a [SampledInstrument]: a note, and the notes slurred on to it. Each note
 * plays a recording of its zone, resampled to its pitch (cubic), through its group's envelope.
 * Under a slur the next note does not start afresh: the sound crossfades (equal power, about 40
 * ms) into the next note's zone, picked up past its attack, so the line neither dips nor re-attacks.
 * Where the instrument has a legato group, that group's recording is used for the move instead.
 */
class SampledVoice(private val inst: SampledInstrument, private val rate: Int, first: Synth.Tone, private val gain: Float = 0.3f) {
    var cur: Synth.Tone = first
        private set
    val queue = ArrayList<Synth.Tone>()
    private val layers = ArrayList<Layer>(4)
    private var released = false

    fun last(): Synth.Tone = queue.lastOrNull() ?: cur

    init { layers += layerFor(first, false) }

    /** The tone being played and those joined on to it, moved in time by [moveCur] / [moveQueued] (tempo changes). */
    fun retime(moveCur: (Synth.Tone) -> Synth.Tone, moveQueued: (Synth.Tone) -> Synth.Tone) {
        cur = moveCur(cur)
        for (k in queue.indices) queue[k] = moveQueued(queue[k])
    }

    private fun velOf(t: Synth.Tone): Int = ((if (t.layer >= 0f) t.layer else t.velocity) * 107f).toInt().coerceIn(1, 127)

    private fun preferred(t: Synth.Tone, move: Boolean): List<Art> {
        val out = ArrayList<Art>(3)
        if (move) out += Art.LEGATO
        if (t.art and Synth.ART_STACCATO != 0) out += Art.STACCATO
        if (t.accent > 0.4f || t.art and (Synth.ART_ACCENT or Synth.ART_MARCATO) != 0) out += Art.ACCENT
        if (t.art and Synth.ART_TENUTO != 0) out += Art.TENUTO
        return out
    }

    private fun layerFor(t: Synth.Tone, move: Boolean): Layer {
        val seed = t.start * 131 + t.midi
        var pick = if (move) inst.pick(t.midi, velOf(t), listOf(Art.LEGATO), Trigger.LEGATO, seed) else null
        val viaLegatoGroup = pick != null || (move && inst.articulations.contains(Art.LEGATO))
        if (pick == null) pick = inst.pick(t.midi, velOf(t), preferred(t, move), Trigger.ATTACK, seed)
        // No sound for the note at all (an empty preset): a silent layer that ends at once.
        val p = pick ?: return Layer(null, t, 0.0, false, false)
        val soft = move && !(viaLegatoGroup && p.group.art == Art.LEGATO)
        return Layer(p.zone, t, 0.0, false, soft)
    }

    private inner class Layer(val zone: Zone?, val tone: Synth.Tone, startAt: Double, val oneShot: Boolean, val skipAttack: Boolean) {
        val group = zone?.group
        private val d = zone?.pcm?.data ?: FloatArray(0)
        private val start = zone?.start ?: 0
        private val end = zone?.end ?: 0
        private val loop = zone?.loop == true
        private val ls = zone?.loopStart ?: 0
        private val le = zone?.loopEnd ?: 0
        private val ll = le - ls
        private val xf = zone?.crossfade ?: 0
        private val xfStart = le - xf
        /** Where the fast path (four plain points) stops being safe. */
        private val safe = if (loop) xfStart - 3 else end - 3
        val step = if (zone == null) 0.0 else zone.pcm.rate.toDouble() / rate * 2.0.pow((tone.midi + zone.tune - zone.root) / 12.0)
        var pos: Double = if (zone == null) 0.0 else if (startAt > 0) startAt else if (skipAttack) {
            if (loop) ls.toDouble() else (start + min(0.08 * zone.pcm.rate, (end - start) * 0.3))
        } else start.toDouble()
        private val level: Float = if (zone == null || group == null) 0f else
            (dbToLinear(inst.masterDb + group.gainDb + zone.gainDb) * Synth.amplitude(tone.velocity) * gain).toFloat()
        var stage = 0
        var env = 0f
        private val sus = (group?.sustain ?: 1.0).toFloat()
        private val atkStep = 1f / max(1.0, max(group?.attack ?: 0.0, 0.004) * rate).toFloat()
        private val decStep = (1f - sus) / max(1.0, (group?.decay ?: 0.0) * rate).toFloat()
        private var relStep = 0f
        var fadeInLen = 0; private var fadeInPos = 0
        var fadeOutLen = 0; private var fadeOutPos = 0
        var dead = zone == null

        init {
            if (skipAttack) { stage = 2; env = sus }
            if (oneShot) { stage = 2; env = 1f; fadeInLen = 32 }
        }

        fun release() {
            if (oneShot || stage == 3) return
            // A short clean release: the recording own tail would fill the silence between tongued notes.
            val len = max(0.02, min(group?.release ?: 0.1, 0.06)) * rate
            relStep = env / len.toFloat()
            stage = 3
        }

        fun crossfadeOut(len: Int) { if (!oneShot && fadeOutLen == 0) { fadeOutLen = max(1, len); fadeOutPos = 0 } }

        private fun swell(s: Long): Float {
            // The one continuous line of loudness (a new dynamic arrives over a moment) and, on an accent, a
            // gentle weight - 2 dB at most, rising and falling back over about a tenth of a second.
            val t = (s - tone.start).toDouble()
            var w = 1f
            if (tone.endVelocity != tone.velocity || tone.ramp > 0) w = Synth.amplitude(tone.velocityAt(t)) / Synth.amplitude(tone.velocity)
            if (tone.accent > 0f) {
                val x = t / (0.11 * rate)
                if (x > 0 && x < 1) { val h = sin(PI * x); w *= (1.0 + tone.accent * 0.25 * h * h).toFloat() }
            }
            return w
        }

        private fun get(i: Int): Float {
            var j = i
            if (loop) {
                while (j >= le) j -= ll
                if (j >= xfStart) {
                    val a = (j - xfStart).toFloat() / xf
                    return d[j] * (1f - a) + d[j - ll] * a
                }
            }
            return if (j < 0 || j >= end) 0f else d[j]
        }

        fun render(buf: FloatArray, i0: Int, i1: Int, from: Long) {
            if (dead || i1 <= i0) return
            var sw = swell(from + i0)
            val dsw = (swell(from + i1) - sw) / (i1 - i0)
            var k = i0
            var p = pos
            while (k < i1) {
                when (stage) {
                    0 -> { env += atkStep; if (env >= 1f) { env = 1f; stage = 1 } }
                    1 -> { env -= decStep; if (env <= sus) { env = sus; stage = 2 } }
                    3 -> { env -= relStep; if (env <= 0f) { dead = true; break } }
                }
                var amp = env * level * sw
                sw += dsw
                if (fadeInLen > 0 && fadeInPos < fadeInLen) { amp *= sin(PI / 2 * fadeInPos / fadeInLen).toFloat(); fadeInPos++ }
                if (fadeOutLen > 0) {
                    amp *= cos(PI / 2 * fadeOutPos / fadeOutLen).toFloat()
                    if (++fadeOutPos >= fadeOutLen) dead = true
                }
                if (!loop) { val rem = end - p; if (rem < 48) amp *= (rem / 48.0).toFloat().coerceAtLeast(0f) }
                val i = p.toInt(); val t = (p - i).toFloat()
                val x = if (i >= 1 && i < safe)
                    Wav.hermite(d[i - 1], d[i], d[i + 1], d[i + 2], t)
                else Wav.hermite(get(i - 1), get(i), get(i + 1), get(i + 2), t)
                buf[k] += x * amp
                p += step
                if (loop) { if (p >= le) p -= ll } else if (p >= end - 1) dead = true
                k++
                if (dead) break
            }
            pos = p
        }
    }

    private fun startRelease(at: Long) {
        released = true
        val t = cur
        inst.pick(t.midi, velOf(t), preferred(t, false), Trigger.RELEASE, t.start * 131 + t.midi + 7)?.let { layers += Layer(it.zone, t, 0.0, true, false) }
        for (l in layers) l.release()
    }

    /** One block into [buf] from sample [from]; true when the voice has finished. */
    fun render(buf: FloatArray, from: Long): Boolean {
        val n = buf.size
        // The move from one note to the next under a slur: a quick crossfade, no slide.
        val xf = (cur.patch.slur.let { (it.minOverlapMs + it.maxOverlapMs) / 2 }.coerceIn(10.0, 45.0) / 1000 * rate).toInt()
        var i = max(0, (cur.start - from).toInt())
        while (i < n) {
            val s = from + i
            while (queue.isNotEmpty() && s >= queue[0].start) {
                val next = queue.removeAt(0)
                for (l in layers) l.crossfadeOut(xf)
                cur = next
                val nl = layerFor(next, true)
                nl.fadeInLen = xf
                layers += nl
            }
            if (!released && queue.isEmpty() && s >= cur.start + cur.length) startRelease(s)
            var end = n
            if (queue.isNotEmpty()) end = min(end, (queue[0].start - from).toInt())
            else if (!released) end = min(end, (cur.start + cur.length - from).toInt())
            if (end <= i) end = i + 1
            for (l in layers) l.render(buf, i, end, from)
            layers.removeAll { it.dead }
            i = end
            if (released && layers.isEmpty()) return true
        }
        return released && layers.isEmpty()
    }
}
