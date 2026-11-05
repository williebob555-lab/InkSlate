package com.inksheets.core.omr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh

/** One independently gainable mono render placed in a stereo field. */
data class StereoStem(
    val partId: String,
    val samples: FloatArray,
    val gain: Float = 1f,
    /** -1 is left, 0 centre, +1 right. */
    val pan: Float = 0f,
    val delaySamples: Int = 0
)

/** An inter-channel-aligned stereo render. */
data class StereoSignal(val left: FloatArray, val right: FloatArray) {
    init { require(left.size == right.size) { "Stereo channels must have the same length" } }
    val frames: Int get() = left.size
}

/**
 * Lightweight direct ensemble mix. It has no score, stage, decoder or platform dependency and is
 * initialized only when ensemble audio is requested. A later stage renderer can feed its computed
 * gains, pans and delays into the same stem contract.
 */
object DirectStereoMix {
    fun render(stems: List<StereoStem>, sampleRate: Int, levelOutput: Boolean = true): StereoSignal {
        require(sampleRate > 0) { "Sample rate must be positive" }
        stems.forEach {
            require(it.partId.isNotBlank()) { "A stem must retain its part id" }
            require(it.gain.isFinite() && it.pan.isFinite()) { "Stem gain and pan must be finite" }
            require(it.delaySamples >= 0) { "Stem delay cannot be negative" }
            require(it.samples.all(Float::isFinite)) { "Stem samples must be finite" }
        }
        val frames = stems.maxOfOrNull { it.delaySamples + it.samples.size } ?: 0
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        for (stem in stems) {
            val angle = (stem.pan.coerceIn(-1f, 1f) + 1f) * (PI / 4.0)
            val leftGain = stem.gain * cos(angle).toFloat()
            val rightGain = stem.gain * sin(angle).toFloat()
            for (i in stem.samples.indices) {
                val frame = stem.delaySamples + i
                left[frame] += stem.samples[i] * leftGain
                right[frame] += stem.samples[i] * rightGain
            }
        }
        if (levelOutput) level(left, right, sampleRate)
        return StereoSignal(left, right)
    }

    /** Linked-channel level control: the stereo image never wanders because one side got louder. */
    private fun level(left: FloatArray, right: FloatArray, sampleRate: Int) {
        val block = max(1, sampleRate / 200) // 5 ms; also bounds the fastest gain change.
        var level = 1f
        var from = 0
        while (from < left.size) {
            val until = min(left.size, from + block)
            var peak = 0f
            for (i in from until until) peak = max(peak, max(abs(left[i]), abs(right[i])))
            val wanted = if (peak * level > .9f) .9f / peak else 1f
            val frames = until - from
            val target = if (wanted < level) wanted
            else level + (wanted - level) * min(1f, frames / sampleRate.toFloat())
            for (i in from until until) {
                val gain = level + (target - level) * (i - from + 1) / frames
                left[i] = softClip(left[i] * gain)
                right[i] = softClip(right[i] * gain)
            }
            level = target
            from = until
        }
    }

    private fun softClip(value: Float): Float {
        val magnitude = abs(value)
        if (magnitude <= .9f) return value
        val shaped = .9f + .07f * tanh((magnitude - .9f) / .07f)
        return if (value < 0f) -shaped else shaped
    }
}
