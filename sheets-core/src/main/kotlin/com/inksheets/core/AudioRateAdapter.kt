package com.inksheets.core

/**
 * Streams audio made at [sourceRate] into an output running at [outputRate]. Dense ensembles can
 * be synthesised at 16 or 24 kHz and linearly brought to the device's 48 kHz rate. This cuts the
 * expensive per-voice work by two or three without changing pitch, tempo, parts, or the device
 * output contract. State and buffers are retained; [fill] allocates nothing on the audio path.
 */
class AudioRateAdapter(
    val sourceRate: Int,
    val outputRate: Int,
    private val sourceFill: (FloatArray) -> Unit
) {
    init {
        require(sourceRate > 0 && outputRate > 0)
        require(sourceRate <= outputRate) { "Realtime adapter only upsamples" }
    }

    private val source = FloatArray((sourceRate / 200).coerceAtLeast(32)) // about 5 ms
    private var sourceAt = source.size
    private var fraction = 0.0
    private var a = 0f
    private var b = 0f
    private var ready = false
    private val step = sourceRate.toDouble() / outputRate

    private fun next(): Float {
        if (sourceAt >= source.size) {
            java.util.Arrays.fill(source, 0f)
            sourceFill(source)
            sourceAt = 0
        }
        return source[sourceAt++]
    }

    fun fill(out: FloatArray) {
        if (!ready) {
            a = next()
            b = next()
            ready = true
        }
        for (i in out.indices) {
            out[i] = a + (b - a) * fraction.toFloat()
            fraction += step
            while (fraction >= 1.0) {
                a = b
                b = next()
                fraction -= 1.0
            }
        }
    }
}
