package com.inkslate.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Recognises the deliberate back-and-forth scratch used to erase ink in drawing applications.
 *
 * It intentionally accepts much less than a generic "messy stroke": the gesture must travel over
 * the same span several times and reverse direction repeatedly. The caller also requires it to hit
 * existing ink before discarding the newly drawn stroke, so an uncertain gesture remains ordinary
 * handwriting rather than disappearing.
 */
object ScribbleErase {
    fun isScribble(points: List<InkPoint>): Boolean {
        if (points.size < 16) return false
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
        val horizontal = maxX - minX >= maxY - minY
        val span = max(maxX - minX, maxY - minY)
        if (span < 10f || length < span * 4.5f) return false

        // Ignore tiny digitiser tremors. Only a change that travelled a visible share of the
        // gesture span counts as turning back across it.
        val epsilon = max(1.5f, span * 0.035f)
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
        return reversals >= 5
    }
}
