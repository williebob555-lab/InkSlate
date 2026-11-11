package com.inkslate.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

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
        val minimumSamples = lerpInt(24, 4, s)
        if (points.size < minimumSamples) return false
        val sampled = resample(points, 72) ?: return false

        // Rotate into the gesture's own coordinate system. This treats diagonal scratches like
        // horizontal ones, while uniform arc-length samples stop slow pen motion receiving more
        // votes merely because Android delivered more events there.
        val centreX = sampled.sumOf { it.x.toDouble() }.toFloat() / sampled.size
        val centreY = sampled.sumOf { it.y.toDouble() }.toFloat() / sampled.size
        var xx = 0f
        var xy = 0f
        var yy = 0f
        sampled.forEach {
            val x = it.x - centreX
            val y = it.y - centreY
            xx += x * x
            xy += x * y
            yy += y * y
        }
        if (xx + yy < 1f) return false
        val angle = .5f * atan2(2f * xy, xx - yy)
        val majorX = cos(angle)
        val majorY = sin(angle)
        val minorX = -majorY
        val minorY = majorX
        val major = sampled.map { (it.x - centreX) * majorX + (it.y - centreY) * majorY }
        val minor = sampled.map { (it.x - centreX) * minorX + (it.y - centreY) * minorY }

        // Ignore a single noisy touch-down/up sample when defining the shared corridor.
        val majorLow = percentile(major, .04f)
        val majorHigh = percentile(major, .96f)
        val majorSpan = majorHigh - majorLow
        if (majorSpan < lerp(16f, 4f, s)) return false
        val minorSpan = percentile(minor, .96f) - percentile(minor, .04f)

        // Dense fill advances its return strokes across an area. A scratch keeps returning through
        // one corridor. This scale-free ratio closes the old loophole where every fill smaller
        // than an absolute 14 document units bypassed the protection.
        if (minorSpan / majorSpan > lerp(.40f, .58f, s)) return false

        // Count completed end-to-end trips, not every local direction change. Hooks, letters and
        // digitiser jitter may reverse often, but they do not repeatedly cross opposite zones of
        // the whole mark. Maximum sensitivity relaxes genuine trip length/count without accepting
        // arbitrary wiggle.
        val traverseShare = lerp(.72f, .42f, s)
        val middle = (majorLow + majorHigh) / 2f
        val lowZone = middle - majorSpan * traverseShare / 2f
        val highZone = middle + majorSpan * traverseShare / 2f
        var side = 0
        var traversals = 0
        major.forEach { value ->
            val reached = when {
                value <= lowZone -> -1
                value >= highZone -> 1
                else -> 0
            }
            if (reached != 0) {
                if (side != 0 && reached != side) traversals++
                side = reached
            }
        }
        return traversals >= lerpInt(6, 2, s)
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

    private data class Point(val x: Float, val y: Float)

    /** Uniform points along the path, so recognition measures geometry rather than event rate. */
    private fun resample(points: List<InkPoint>, count: Int): List<Point>? {
        val distances = FloatArray(points.size)
        var length = 0f
        for (i in 1 until points.size) {
            length += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
            distances[i] = length
        }
        if (length < 4f) return null
        val result = ArrayList<Point>(count)
        var segment = 1
        for (sample in 0 until count) {
            val target = length * sample / (count - 1f)
            while (segment < distances.lastIndex && distances[segment] < target) segment++
            val before = segment - 1
            val segmentLength = distances[segment] - distances[before]
            val amount = if (segmentLength <= .0001f) 0f
            else (target - distances[before]) / segmentLength
            result += Point(
                points[before].x + (points[segment].x - points[before].x) * amount,
                points[before].y + (points[segment].y - points[before].y) * amount
            )
        }
        return result
    }

    private fun percentile(values: List<Float>, at: Float): Float {
        val sorted = values.sorted()
        val position = at.coerceIn(0f, 1f) * sorted.lastIndex
        val low = position.toInt()
        val high = (low + 1).coerceAtMost(sorted.lastIndex)
        val amount = position - low
        return sorted[low] + (sorted[high] - sorted[low]) * amount
    }
}
