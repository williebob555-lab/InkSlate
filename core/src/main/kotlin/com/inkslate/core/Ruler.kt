package com.inkslate.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A straightedge that ink snaps to.
 *
 * Held in the coordinates of the page it sits on, so it stays put relative to the work rather
 * than to the screen when the view is scrolled or zoomed.
 *
 * The geometry is shared because it decides what gets written into the document: a stroke drawn
 * against the ruler is stored projected onto it, so a ruler that snapped differently on the two
 * builds would produce visibly different ink from the same movement.
 */
data class Ruler(
    val ax: Float,
    val ay: Float,
    val bx: Float,
    val by: Float,
    /** Which page it is lying on. A straightedge belongs to a sheet of paper, not to the view. */
    val page: Int = 0
) {
    val centerX: Float get() = (ax + bx) / 2f
    val centerY: Float get() = (ay + by) / 2f
    val length: Float get() = hypot(bx - ax, by - ay)

    /** Angle in degrees, 0 to 360, for the protractor readout. */
    val angleDegrees: Float
        get() {
            var deg = Math.toDegrees(atan2(by - ay, bx - ax).toDouble()).toFloat()
            if (deg < 0f) deg += 360f
            return deg
        }

    fun movedBy(dx: Float, dy: Float) = copy(ax = ax + dx, ay = ay + dy, bx = bx + dx, by = by + dy)

    /** Spin about the middle, which is what a hand on a straightedge does. */
    fun rotatedBy(degrees: Float): Ruler {
        val cx = centerX
        val cy = centerY
        val rad = Math.toRadians(degrees.toDouble())
        fun spinX(x: Float, y: Float) = cx + ((x - cx) * cos(rad) - (y - cy) * sin(rad)).toFloat()
        fun spinY(x: Float, y: Float) = cy + ((x - cx) * sin(rad) + (y - cy) * cos(rad)).toFloat()
        return copy(
            ax = spinX(ax, ay), ay = spinY(ax, ay),
            bx = spinX(bx, by), by = spinY(bx, by)
        )
    }

    /** Snap to the nearest [step] degrees, for clean angles. */
    fun snappedToAngle(step: Float = 15f): Ruler {
        val current = angleDegrees
        val target = (current / step).roundToInt() * step
        return rotatedBy(target - current)
    }

    /**
     * Project a page-space point onto the line, if it is close enough to count.
     *
     * [tolerance] is how far the pen may be from the edge and still be ruled - a physical ruler
     * guides the pen without needing the pen to be exactly against it. A negative tolerance means
     * "no distance test", which is what the samples after the first want: once a stroke is being
     * ruled it stays ruled to the end, the same way the pen stays against the edge.
     */
    fun project(x: Float, y: Float, tolerance: Float): Pair<Float, Float>? {
        val vx = bx - ax
        val vy = by - ay
        val len2 = vx * vx + vy * vy
        if (len2 < 1f) return null
        val t = ((x - ax) * vx + (y - ay) * vy) / len2
        val px = ax + vx * t
        val py = ay + vy * t
        if (tolerance < 0f) return px to py
        return if (hypot(x - px, y - py) <= tolerance) px to py else null
    }

    /**
     * Which part of the ruler a point has taken hold of.
     *
     * The ends rotate it and the middle slides it, which is how anyone uses a straightedge - and
     * why the ends win the tie: an end is a smaller target, so it has to be tested first.
     */
    fun grabAt(x: Float, y: Float, endRadius: Float, barTolerance: Float): Grab = when {
        hypot(x - ax, y - ay) <= endRadius -> Grab.END_A
        hypot(x - bx, y - by) <= endRadius -> Grab.END_B
        project(x, y, barTolerance) != null && withinSpan(x, y, barTolerance) -> Grab.BAR
        else -> Grab.NONE
    }

    /** True when a point lies alongside the bar rather than off past one of its ends. */
    private fun withinSpan(x: Float, y: Float, slack: Float): Boolean {
        val vx = bx - ax
        val vy = by - ay
        val len2 = vx * vx + vy * vy
        if (len2 < 1f) return false
        val t = ((x - ax) * vx + (y - ay) * vy) / len2
        val margin = slack / hypot(vx, vy).coerceAtLeast(1f)
        return t >= -margin && t <= 1f + margin
    }

    enum class Grab { NONE, END_A, END_B, BAR }

    /** Move one end, for a drag that is rotating or lengthening the bar. */
    fun withEnd(grab: Grab, x: Float, y: Float): Ruler = when (grab) {
        Grab.END_A -> copy(ax = x, ay = y)
        Grab.END_B -> copy(bx = x, by = y)
        else -> this
    }

    companion object {
        /** How far from the edge ink is still ruled, in page points at 1:1. */
        const val SNAP_POINTS = 22f

        /** A ruler laid across the middle of a box, which is where a new one appears. */
        fun across(box: Box, page: Int = 0): Ruler {
            val half = (box.width * 0.4f).coerceAtLeast(60f)
            return Ruler(
                box.centerX - half, box.centerY,
                box.centerX + half, box.centerY,
                page
            )
        }

        /** Degrees between two angles, taking the shorter way round. */
        fun angleBetween(a: Float, b: Float): Float {
            val d = abs(a - b) % 360f
            return if (d > 180f) 360f - d else d
        }
    }
}
