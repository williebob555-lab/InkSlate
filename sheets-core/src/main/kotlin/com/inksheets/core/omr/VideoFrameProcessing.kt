package com.inksheets.core.omr

import kotlin.math.max
import kotlin.math.sqrt

/** Allocation-free measurements over one decoded greyscale frame. */
object VideoFrameAnalysis {
    /** 8x8 difference hash sampled across the complete frame. */
    fun fingerprint(frame: ScoreVideoFrame): Long {
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) {
            val sy = if (frame.height == 1) 0 else y * (frame.height - 1) / 7
            for (x in 0 until 8) {
                val ax = if (frame.width == 1) 0 else x * (frame.width - 1) / 8
                val bx = if (frame.width == 1) 0 else (x + 1) * (frame.width - 1) / 8
                val a = frame.grey[sy * frame.width + ax].toInt() and 0xff
                val b = frame.grey[sy * frame.width + bx].toInt() and 0xff
                if (a > b) hash = hash or (1L shl bit)
                bit++
            }
        }
        return hash
    }

    /** Coarse edge energy, bounded to roughly 50k samples even for a full-resolution frame. */
    fun sharpness(frame: ScoreVideoFrame): Float {
        if (frame.width < 2 || frame.height < 2) return 0f
        val stride = max(1, sqrt(frame.grey.size / 50_000.0).toInt())
        var squaredDifference = 0L
        var comparisons = 0L
        var y = 0
        while (y < frame.height - 1) {
            var x = 0
            while (x < frame.width - 1) {
                val at = y * frame.width + x
                val value = frame.grey[at].toInt() and 0xff
                val horizontal = value - (frame.grey[at + stride.coerceAtMost(frame.width - 1 - x)].toInt() and 0xff)
                val vertical = value - (frame.grey[(y + stride.coerceAtMost(frame.height - 1 - y)) * frame.width + x].toInt() and 0xff)
                squaredDifference += horizontal.toLong() * horizontal + vertical.toLong() * vertical
                comparisons += 2
                x += stride
            }
            y += stride
        }
        return (sqrt(squaredDifference.toDouble() / comparisons.coerceAtLeast(1)) / 255.0).toFloat().coerceIn(0f, 1f)
    }

    fun evidence(frame: ScoreVideoFrame, previousFingerprint: Long? = null): VideoFrameEvidence {
        val fingerprint = fingerprint(frame)
        val change = previousFingerprint?.let { java.lang.Long.bitCount(it xor fingerprint) / 64f } ?: 1f
        return VideoFrameEvidence(frame.timestampMs, fingerprint, sharpness(frame), change)
    }
}

/** Removes transient obstructions by keeping the median value of each pixel across nearby frames.
 * The caller supplies already-stabilized, equal-size frames. Inputs, output, and one tiny median
 * scratch buffer must fit the job's resident-frame budget before any output is allocated.
 */
object TemporalPageMerge {
    fun merge(frames: List<ScoreVideoFrame>, budget: VideoImportBudget = VideoImportBudget(),
              maxFrames: Int =  nineFrameLimit): ScoreVideoFrame {
        require(frames.isNotEmpty()) { "At least one frame is required" }
        require(maxFrames > 0 && frames.size <= maxFrames) { "Too many frames in one temporal merge" }
        val first = frames.first()
        require(frames.all { it.width == first.width && it.height == first.height }) {
            "Temporal merge frames must be stabilized to equal dimensions"
        }
        val required = frames.sumOf { it.grey.size.toLong() } + first.grey.size + frames.size * Int.SIZE_BYTES.toLong()
        require(required <= budget.maxResidentFrameBytes) { "Temporal merge exceeds the resident-frame budget" }
        val output = ByteArray(first.grey.size)
        val values = IntArray(frames.size)
        for (pixel in output.indices) {
            for (i in frames.indices) values[i] = frames[i].grey[pixel].toInt() and 0xff
            java.util.Arrays.sort(values)
            output[pixel] = values[values.size / 2].toByte()
        }
        val times = frames.map { it.timestampMs }.sorted()
        return ScoreVideoFrame(times[times.size / 2], first.width, first.height, output)
    }

    private const val nineFrameLimit = 9
}
