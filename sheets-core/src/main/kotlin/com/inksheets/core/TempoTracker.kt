package com.inksheets.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The tempo of music being heard, and where its beats fall: for a metronome that follows the band
 * instead of leading it.
 *
 * Every 12 ms the sound is compared with the moment before - how much new sound started, across
 * the frequencies (spectral flux) - which peaks on every note and drum hit. Over the last eight
 * seconds those peaks repeat most strongly at the beat: the gap with the strongest repetition
 * (autocorrelation), preferring tempos near the one expected so it does not jump to double or
 * half, is the beat. The beats' place is then read off the same peaks.
 */
class TempoTracker(private val rate: Int) {
    private val stream = Resampler(rate)
    private val window = FloatArray(SIZE) { i -> (0.5 - 0.5 * cos(2 * PI * i / (SIZE - 1))).toFloat() }
    private var buffer = FloatArray(0)
    private var previous = FloatArray(SIZE / 2)
    /** The onset strength, newest last, [KEEP] frames at most. */
    private val envelope = FloatArray(KEEP)
    private var filled = 0
    private var frames = 0L
    private var sinceEstimate = 0

    /** The tempo heard, once there is enough to tell; null before. */
    var bpm: Double? = null
        private set

    /** How clearly the beat stands out, 0 to 1. */
    var confidence: Double = 0.0
        private set

    /** The onset frame of a beat, and the beat's length in frames. */
    private var beatFrame: Double? = null
    private var beatPeriod = 0.0

    /** Where a beat fell last: this many ms before the latest sound fed. */
    val lastBeatMsAgo: Double?
        get() {
            val b = beatFrame ?: return null
            if (beatPeriod <= 0) return null
            val since = ((frames - 1) - b).mod(beatPeriod)
            return since * 1000.0 / FPS
        }

    /** The tempo expected - the metronome's now: estimates near it are preferred. */
    @Volatile var expected: Double = 110.0

    /** Feed sound as it arrives; true when [bpm] was worked out again. */
    fun feed(samples: FloatArray): Boolean {
        buffer += stream.feed(samples)
        var at = 0
        var updated = false
        while (at + SIZE <= buffer.size) {
            push(flux(buffer, at))
            at += HOP
            if (++sinceEstimate >= ESTIMATE_EVERY && filled >= MIN_FRAMES) {
                sinceEstimate = 0
                estimate()
                updated = true
            }
        }
        if (at > 0) buffer = buffer.copyOfRange(at, buffer.size)
        return updated
    }

    private fun flux(s: FloatArray, at: Int): Float {
        val re = DoubleArray(SIZE) { i -> (s[at + i] * window[i]).toDouble() }
        val im = DoubleArray(SIZE)
        Fft.transform(re, im)
        var sum = 0f
        for (k in 1 until SIZE / 2) {
            val mag = ln(1f + 1000f * sqrt((re[k] * re[k] + im[k] * im[k]).toFloat()))
            val rise = mag - previous[k]
            if (rise > 0) sum += rise
            previous[k] = mag
        }
        return sum
    }

    private fun push(v: Float) {
        System.arraycopy(envelope, 1, envelope, 0, KEEP - 1)
        envelope[KEEP - 1] = v
        if (filled < KEEP) filled++
        frames++
    }

    private fun estimate() {
        val n = filled
        val e = DoubleArray(n) { envelope[KEEP - n + it].toDouble() }
        // Only the peaks: each value above the average around it.
        val m = DoubleArray(n)
        val half = (FPS * 0.15).toInt()
        for (i in 0 until n) {
            var s = 0.0; var c = 0
            for (j in maxOf(0, i - half)..minOf(n - 1, i + half)) { s += e[j]; c++ }
            m[i] = maxOf(0.0, e[i] - s / c)
        }
        val minLag = (60.0 * FPS / MAX_BPM).toInt()
        val maxLag = (60.0 * FPS / MIN_BPM).toInt() + 1
        val acf = DoubleArray(maxLag * 2 + 2)
        var zero = 0.0
        for (i in 0 until n) zero += m[i] * m[i]
        if (zero <= 1e-9) return
        for (lag in 1 until acf.size) {
            var s = 0.0
            for (i in lag until n) s += m[i] * m[i - lag]
            acf[lag] = s / (n - lag) / (zero / n)
        }
        var bestLag = -1
        var bestScore = 0.0
        for (lag in minLag..maxLag) {
            val bpmHere = 60.0 * FPS / lag
            val prior = exp(-0.5 * (log2(bpmHere / expected) / PRIOR_OCTAVES).let { it * it })
            // A beat repeats at twice its gap too: that helps it over its own half.
            val score = (acf[lag] + 0.5 * acf[lag * 2]) * prior
            if (score > bestScore) { bestScore = score; bestLag = lag }
        }
        if (bestLag <= minLag || bestLag >= maxLag) return
        // Between frames: the peak of a parabola through the best and its neighbours.
        val a = acf[bestLag - 1]; val b = acf[bestLag]; val c = acf[bestLag + 1]
        val shift = (a - c) / (2 * (a - 2 * b + c)).takeIf { it != 0.0 }.let { if (it == null) 0.0 else it }
        val lag = bestLag + shift.coerceIn(-0.5, 0.5)
        bpm = 60.0 * FPS / lag
        confidence = (acf[bestLag] / (acf.drop(minLag).take(maxLag - minLag).average() + 1e-9) - 1).coerceIn(0.0, 3.0) / 3.0
        // Where the beats fall: the offset whose comb through the last few seconds catches most.
        val period = lag
        var bestOffset = 0.0
        var bestSum = -1.0
        val steps = period.toInt().coerceAtLeast(1)
        for (o in 0 until steps) {
            var s = 0.0
            var k = 0
            while (true) {
                val i = (n - 1 - o - k * period).toInt()
                if (i < 0 || k > 12) break
                s += m[i]; k++
            }
            if (s > bestSum) { bestSum = s; bestOffset = o.toDouble() }
        }
        beatFrame = (frames - 1) - bestOffset
        beatPeriod = period
    }

    /** The sound at [Chroma.RATE], as it arrives. */
    private class Resampler(private val rate: Int) {
        private var carry = 0.0
        private var last = 0f
        fun feed(samples: FloatArray): FloatArray {
            if (rate == Chroma.RATE || samples.isEmpty()) return samples
            val step = rate.toDouble() / Chroma.RATE
            val out = FloatArray((samples.size / step).toInt() + 2)
            var count = 0
            var at = carry
            while (at < samples.size - 1) {
                val j = kotlin.math.floor(at).toInt()
                val t = (at - j).toFloat()
                val a = if (j < 0) last else samples[j]
                out[count++] = a + (samples[j + 1] - a) * t
                at += step
            }
            carry = at - samples.size
            last = samples[samples.size - 1]
            return out.copyOf(count)
        }
    }

    companion object {
        private const val SIZE = 512
        private const val HOP = 128
        /** Onset frames a second. */
        const val FPS = Chroma.RATE.toDouble() / HOP
        /** Eight seconds of onsets. */
        private val KEEP = (FPS * 8).toInt()
        private val MIN_FRAMES = (FPS * 4).toInt()
        /** Worked out again every half second. */
        private val ESTIMATE_EVERY = (FPS / 2).toInt()
        const val MIN_BPM = 50.0
        const val MAX_BPM = 220.0
        /** How far from the tempo expected an estimate may go before it is doubted. */
        private const val PRIOR_OCTAVES = 0.6
    }
}

/** A radix-2 FFT, in place. */
internal object Fft {
    fun transform(re: DoubleArray, im: DoubleArray) {
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
            val half = len / 2
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until half) {
                    val vr = re[i + k + half] * cr - im[i + k + half] * ci
                    val vi = re[i + k + half] * ci + im[i + k + half] * cr
                    re[i + k + half] = re[i + k] - vr; im[i + k + half] = im[i + k] - vi
                    re[i + k] += vr; im[i + k] += vi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
