package com.inksheets.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

/**
 * Playing a recording slower or faster without changing its pitch, and changing its pitch without
 * changing its speed - practising a hard passage at 70%, or a recording in concert pitch shifted
 * to match a part.
 *
 * WSOLA (waveform-similarity overlap-add): the output is built from overlapping windows of the
 * input, each taken from wherever near its nominal place it best continues the waveform already
 * written, so the joins do not beat against each other. Pitch is then a stretch by the pitch ratio
 * followed by resampling back to the original length.
 *
 * Works on mono float samples from a position the caller controls, so seeking and A-B loops are the
 * caller's: [read] fills a block and returns where in the source it got to.
 */
class TimeStretch(private val source: FloatArray, sampleRate: Int) {

    private val frame = (sampleRate * 0.040).toInt()          // 40 ms windows
    private val overlap = frame / 2
    private val hop = frame - overlap
    private val search = (sampleRate * 0.012).toInt()         // look 12 ms either way for the best join
    private val window = FloatArray(frame) { i -> (0.5 - 0.5 * cos(2 * PI * i / (frame - 1))).toFloat() }

    /** Playback speed: 0.5 is half speed. */
    var speed: Double = 1.0

    /** Semitones up (positive) or down. */
    var pitch: Int = 0

    /** Where in the source playback is, in samples. */
    var position: Double = 0.0

    private var tail = FloatArray(overlap)       // the end of the last window, to cross-fade into
    private var pending = FloatArray(0)          // stretched output not yet handed out
    private var pendingAt = 0
    private var resampleAt = 0.0

    /** Start again at [sample], forgetting any half-built output. */
    fun seek(sample: Double) {
        position = sample.coerceIn(0.0, source.size.toDouble())
        tail = FloatArray(overlap)
        pending = FloatArray(0)
        pendingAt = 0
        resampleAt = 0.0
    }

    val atEnd: Boolean get() = position >= source.size - frame

    /** Fill [out] with the next samples; silence past the end. */
    fun read(out: FloatArray) {
        val ratio = 2.0.pow(pitch / 12.0)
        // Stretch by speed and pitch together, then resample by the pitch ratio: the result has
        // the requested speed and the requested pitch.
        val stretchSpeed = speed / ratio
        var i = 0
        while (i < out.size) {
            // Resample out of the stretched buffer at the pitch ratio.
            val need = resampleAt + ratio
            if (need + 1 >= pending.size - pendingAt) {
                if (atEnd) { while (i < out.size) out[i++] = 0f; return }
                grow(stretchSpeed)
                continue
            }
            val at = pendingAt + resampleAt
            val k = at.toInt()
            val f = (at - k).toFloat()
            out[i++] = pending[k] * (1 - f) + pending[k + 1] * f
            resampleAt += ratio
            val whole = resampleAt.toInt()
            pendingAt += whole
            resampleAt -= whole
        }
    }

    /** Add one hop of stretched output. */
    private fun grow(stretchSpeed: Double) {
        val nominal = position.toInt()
        // Find the start near [nominal] whose opening best matches the tail already written.
        var best = nominal
        var bestScore = Double.NEGATIVE_INFINITY
        val from = (nominal - search).coerceAtLeast(0)
        val to = (nominal + search).coerceAtMost(source.size - frame - 1)
        if (to >= from) {
            var s = from
            while (s <= to) {
                var score = 0.0
                var j = 0
                while (j < overlap) {
                    score += tail[j] * source[s + j]
                    j += 2      // every other sample: plenty for choosing, half the cost
                }
                if (score > bestScore) { bestScore = score; best = s }
                s += 1
            }
        }
        best = best.coerceIn(0, (source.size - frame).coerceAtLeast(0))

        val produced = FloatArray(hop)
        for (j in 0 until hop) {
            val fresh = if (best + j < source.size) source[best + j] else 0f
            produced[j] = if (j < overlap) {
                tail[j] * (1 - window[j]) + fresh * window[j]
            } else fresh
        }
        for (j in 0 until overlap) {
            val at = best + hop + j
            tail[j] = if (at < source.size) source[at] else 0f
        }
        // Keep the unread part of the buffer and add the new hop.
        val keep = pending.size - pendingAt
        val next = FloatArray(keep + hop)
        System.arraycopy(pending, pendingAt, next, 0, keep)
        System.arraycopy(produced, 0, next, keep, hop)
        pending = next
        pendingAt = 0
        position += hop * stretchSpeed
    }
}
