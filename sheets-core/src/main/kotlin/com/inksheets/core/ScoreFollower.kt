package com.inksheets.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What sound is doing, a tenth of a second at a time: how much of each of the twelve notes
 * (C, C#, ... B, any octave) there is, and how loud it is. Two performances of the same music -
 * the band's recording and the band in the room - have much the same notes at the same places,
 * whatever the instruments, the room or the tempo, which is what makes following possible.
 */
object Chroma {
    /** Everything is worked out at this rate: enough for the notes that matter, and cheap. */
    const val RATE = 11_025
    /** A frame every tenth of a second. */
    const val HOP = RATE / 10
    const val FRAME_MS = 100L
    private const val SIZE = 4096
    private val window = FloatArray(SIZE) { i -> (0.5 - 0.5 * cos(2 * PI * i / (SIZE - 1))).toFloat() }
    /** Which note each frequency bin counts towards (-1 for none): 55 Hz to 2.5 kHz. */
    private val noteOfBin = IntArray(SIZE / 2) { k ->
        val f = k.toDouble() * RATE / SIZE
        if (f < 55.0 || f > 2_500.0) -1 else ((12 * log2(f / 440.0)).roundToInt() + 69).mod(12)
    }

    class Frame(val notes: FloatArray, val loudness: Float)

    /** [samples] at [rate], at [RATE]: linear, which is enough for finding notes. */
    fun resample(samples: FloatArray, rate: Int): FloatArray {
        if (rate == RATE || samples.isEmpty()) return samples
        val n = (samples.size.toLong() * RATE / rate).toInt()
        val step = rate.toDouble() / RATE
        return FloatArray(n) { i ->
            val at = i * step
            val j = at.toInt()
            val t = (at - j).toFloat()
            val a = samples[j.coerceAtMost(samples.size - 1)]
            val b = samples[(j + 1).coerceAtMost(samples.size - 1)]
            a + (b - a) * t
        }
    }

    /** Every frame of [samples] (at [RATE]). */
    fun frames(samples: FloatArray): List<Frame> {
        val out = ArrayList<Frame>(samples.size / HOP + 1)
        var at = 0
        while (at + SIZE <= samples.size) {
            out += frame(samples, at)
            at += HOP
        }
        return out
    }

    /** One frame from [SIZE] samples of [samples] starting at [at]. */
    fun frame(samples: FloatArray, at: Int): Frame {
        val re = DoubleArray(SIZE)
        val im = DoubleArray(SIZE)
        var power = 0.0
        for (i in 0 until SIZE) {
            val s = samples[at + i]
            power += s * s
            re[i] = (s * window[i]).toDouble()
        }
        fft(re, im)
        val notes = FloatArray(12)
        for (k in 1 until SIZE / 2) {
            val n = noteOfBin[k]
            if (n >= 0) notes[n] += (re[k] * re[k] + im[k] * im[k]).toFloat()
        }
        // Loudness squashed, so a loud passage and a quiet one of the same notes look alike.
        var norm = 0.0
        for (n in 0 until 12) { notes[n] = ln(1f + 100f * notes[n] / SIZE); norm += notes[n] * notes[n] }
        norm = sqrt(norm)
        if (norm > 1e-6) for (n in 0 until 12) notes[n] = (notes[n] / norm).toFloat()
        return Frame(notes, sqrt(power / SIZE).toFloat())
    }

    /** Frames of sound as it arrives, at any rate, in blocks of any size. */
    class Stream(private val rate: Int) {
        private var buffer = FloatArray(0)
        private var carry = 0.0
        private var last = 0f

        fun feed(samples: FloatArray): List<Frame> {
            buffer += resampleStreaming(samples)
            val out = ArrayList<Frame>()
            var at = 0
            while (at + SIZE <= buffer.size) { out += frame(buffer, at); at += HOP }
            if (at > 0) buffer = buffer.copyOfRange(at, buffer.size)
            return out
        }

        private fun resampleStreaming(samples: FloatArray): FloatArray {
            if (rate == RATE) return samples
            if (samples.isEmpty()) return samples
            val step = rate.toDouble() / RATE
            val out = FloatArray((samples.size / step).toInt() + 2)
            var count = 0
            // Positions relative to this block; -1 is the last sample of the block before.
            var at = carry
            while (at < samples.size - 1) {
                val j = kotlin.math.floor(at).toInt()
                val t = (at - j).toFloat()
                val a = if (j < 0) last else samples[j]
                val b = samples[j + 1]
                out[count++] = a + (b - a) * t
                at += step
            }
            carry = at - samples.size
            last = samples[samples.size - 1]
            return out.copyOf(count)
        }
    }

    /** In-place radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                val half = len / 2
                for (k in 0 until half) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + half] * cr - im[i + k + half] * ci
                    val vi = re[i + k + half] * ci + im[i + k + half] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + half] = ur - vr; im[i + k + half] = ui - vi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/**
 * Where in a recording the music being heard is: each tenth of a second heard moves the place in
 * the recording on by none, one or two tenths - so it keeps up with a band playing anywhere from
 * stopped to twice the recording's tempo - to wherever the notes match best along the way
 * (online dynamic time warping, Viterbi style: every place in the recording is a candidate each
 * step, and the best path to each is kept).
 */
class ScoreFollower(
    private val reference: List<Chroma.Frame>,
    startMs: Long = 0,
    /** Costs of holding still, and of going at double speed, over going with the reference. */
    private val stayCost: Float = STAY,
    private val skipCost: Float = SKIP
) {
    private val n = reference.size
    private val start = (startMs / Chroma.FRAME_MS).toInt()
    private var cost = FloatArray(n) { j -> START_SPREAD * kotlin.math.abs(j - start) }
    private var next = FloatArray(n)

    /** Where the music heard has got to in the recording, in ms. */
    var positionMs: Long = startMs
        private set

    /** The recording's length, in ms. */
    val lengthMs: Long get() = n * Chroma.FRAME_MS

    /** One frame heard. Quiet frames - a rest, a gap - hold the place rather than move it. */
    fun hear(frame: Chroma.Frame, quiet: Boolean): Long {
        if (n == 0 || quiet) return positionMs
        val live = frame.notes
        var best = Float.MAX_VALUE
        var bestAt = 0
        for (j in 0 until n) {
            val ref = reference[j].notes
            var dot = 0f
            for (k in 0 until 12) dot += live[k] * ref[k]
            val d = 1f - dot
            val stay = cost[j] + stayCost
            val one = if (j >= 1) cost[j - 1] else Float.MAX_VALUE
            val two = if (j >= 2) cost[j - 2] + skipCost else Float.MAX_VALUE
            val c = d + minOf(stay, one, two)
            next[j] = c
            if (c < best) { best = c; bestAt = j }
        }
        // Kept small: only the differences matter.
        for (j in 0 until n) next[j] -= best
        val t = cost; cost = next; next = t
        positionMs = bestAt * Chroma.FRAME_MS
        return positionMs
    }

    companion object {
        /** Costs of holding still, and of going at double speed, over going with the recording. */
        const val STAY = 0.12f
        const val SKIP = 0.12f
        /** How sure it is at first that the music starts where it was told. */
        const val START_SPREAD = 0.02f
    }
}

/**
 * Whether music is being played: heard once it has gone on a moment, and over once it has been
 * quiet for a while after - quiet against how loud the music was, so a soft passage is not an end.
 */
class MusicPresence(private val quietForMs: Long = 4_000, private val floor: Float = 0.004f) {
    private var peak = 0f
    private var playedMs = 0L
    private var quietMs = 0L

    /** Music has been heard since the start. */
    val heard: Boolean get() = playedMs >= 1_500

    /** It was heard, and has stopped. */
    var stopped = false
        private set

    /** Whether this frame is quiet - quiet frames do not move the place in the recording. */
    fun quiet(frame: Chroma.Frame): Boolean = frame.loudness < maxOf(floor, peak * 0.08f)

    fun hear(frame: Chroma.Frame): Boolean {
        // The peak falls slowly, so the level follows the music, not one loud moment.
        peak = maxOf(frame.loudness, peak * 0.998f)
        if (quiet(frame)) quietMs += Chroma.FRAME_MS else { playedMs += Chroma.FRAME_MS; quietMs = 0 }
        if (heard && quietMs >= quietForMs) stopped = true
        return stopped
    }
}

/**
 * When each page turn falls in a recording: [turnsMs] from playing along and turning, or, until
 * then, the pages spread evenly over it. Page i (0-based) is turned from at [turnAt] (i).
 */
class TurnPlan(private val turnsMs: List<Long>, private val pages: Int, private val lengthMs: Long) {
    val learned: Boolean get() = pages > 1 && turnsMs.size >= pages - 1

    fun turnAt(page: Int): Long? {
        if (page >= pages - 1) return null
        turnsMs.getOrNull(page)?.let { return it }
        return lengthMs * (page + 1) / pages
    }
}
