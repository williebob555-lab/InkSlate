package com.inkslate.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Recognises the deliberate back-and-forth scratch used to erase ink in drawing applications.
 *
 * It intentionally accepts much less than a generic "messy stroke": the gesture must travel over
 * the same span several times and reverse direction repeatedly. The caller also requires it to hit
 * existing ink before discarding the newly drawn stroke, so an uncertain gesture remains ordinary
 * handwriting rather than disappearing.
 */
object ScribbleErase {
    const val DEFAULT_SENSITIVITY = 0.82f

    /**
     * [sensitivity] deliberately spans a very wide range. At zero this requires a long,
     * unmistakable seven-reversal scratch. At one, a short two-reversal scratch is enough when
     * it actually crosses existing ink (the caller performs that final safety check).
     */
    fun isScribble(
        points: List<InkPoint>,
        sensitivity: Float = DEFAULT_SENSITIVITY
    ): Boolean {
        val s = sensitivity.coerceIn(0f, 1f)
        val minimumSamples = lerpInt(28, 5, s)
        if (points.size < minimumSamples) return false
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var length = 0f
        for (i in points.indices) {
            val p = points[i]
            minX = minOf(minX, p.x); maxX = maxOf(maxX, p.x)
            minY = minOf(minY, p.y); maxY = maxOf(maxY, p.y)
            if (i > 0) length += hypot(p.x - points[i - 1].x, p.y - points[i - 1].y)
        }
        val width = maxX - minX
        val height = maxY - minY
        val horizontal = width >= height
        val span = max(width, height)
        val minimumSpan = lerp(18f, 6f, s)
        val minimumTravel = lerp(7.5f, 1.8f, s)
        if (span < minimumSpan || length < span * minimumTravel) return false

        // A scratch keeps crossing a narrow band. Dense shading/filling advances across a broad
        // area as it reverses, and must stay ink even though it contains many more reversals.
        val minorSpan = min(width, height)
        if (minorSpan > max(14f, span * 0.65f)) return false

        // Ignore tiny digitiser tremors. Only a change that travelled a visible share of the
        // gesture span counts as turning back across it.
        val epsilon = max(lerp(2.2f, 0.8f, s), span * lerp(0.06f, 0.018f, s))
        var lastDirection = 0
        var reversals = 0
        var anchor = if (horizontal) points.first().x else points.first().y
        for (i in 1 until points.size) {
            val value = if (horizontal) points[i].x else points[i].y
            val delta = value - anchor
            if (abs(delta) < epsilon) continue
            val direction = if (delta > 0f) 1 else -1
            if (lastDirection != 0 && direction != lastDirection) reversals++
            lastDirection = direction
            anchor = value
        }
        return reversals >= lerpInt(7, 2, s)
    }

    /** Lowest working setting, plus a little margin for the next less-perfect attempt. */
    fun calibratedSensitivity(points: List<InkPoint>): Float? {
        if (!isScribble(points, 1f)) return null
        var low = 0f
        var high = 1f
        repeat(12) {
            val middle = (low + high) / 2f
            if (isScribble(points, middle)) high = middle else low = middle
        }
        return (high + 0.12f).coerceAtMost(1f)
    }

    private fun lerp(from: Float, to: Float, amount: Float): Float =
        from + (to - from) * amount

    private fun lerpInt(from: Int, to: Int, amount: Float): Int =
        kotlin.math.round(lerp(from.toFloat(), to.toFloat(), amount)).toInt()
}
