package com.inkslate.core

import kotlinx.serialization.Serializable

/** Three annotation layers represented as bits, so one stroke can belong to several without copies. */
object AnnotationLayers {
    const val ONE = 1
    const val TWO = 2
    const val THREE = 4
    const val ALL = ONE or TWO or THREE

    fun normalise(mask: Int): Int = (mask and ALL).takeIf { it != 0 } ?: ONE
    /** New ink records into exactly one channel, including when loading a legacy multi-record mask. */
    fun recordingLayer(mask: Int): Int {
        val valid = mask and ALL
        return if (valid == 0) ONE else valid and -valid
    }
    /** Mixer masks may be completely muted; stroke membership may not. */
    fun mixerMask(mask: Int): Int = mask and ALL

    /** Remove only one channel from legacy shared ink; zero means no memberships remain. */
    fun withoutLayer(membership: Int, target: Int): Int =
        normalise(membership) and recordingLayer(target).inv() and ALL

    /** Effective fader level for a stroke in one or more channels, or null when fully muted. */
    fun mixedOpacity(membership: Int, visibleMask: Int, levels: List<Float>): Float? {
        val shown = normalise(membership) and mixerMask(visibleMask)
        if (shown == 0) return null
        var level = 0f
        if ((shown and ONE) != 0) level = maxOf(level, levels.getOrElse(0) { 1f })
        if ((shown and TWO) != 0) level = maxOf(level, levels.getOrElse(1) { 1f })
        if ((shown and THREE) != 0) level = maxOf(level, levels.getOrElse(2) { 1f })
        return level.coerceIn(0f, 1f)
    }

    /** Filters choose one recording channel; overlapping filters prefer the viewport's active one. */
    fun writeMask(viewportMask: Int, filters: Iterable<LayerFilter>, page: Int, x: Float, y: Float): Int {
        val active = recordingLayer(viewportMask)
        var inside = 0
        for (filter in filters) {
            if (filter.pageIndex == page && filter.contains(x, y)) inside = inside or mixerMask(filter.layerMask)
        }
        return when {
            inside == 0 || (inside and active) != 0 -> active
            else -> recordingLayer(inside)
        }
    }

    /** Stable UI colours for every possible combination; independent of drawing-ink colour. */
    fun colour(mask: Int): Int = when (normalise(mask)) {
        ONE -> 0xFF2563EB.toInt()                  // blue
        TWO -> 0xFFF59E0B.toInt()                  // amber/yellow
        THREE -> 0xFFEF4444.toInt()                // red
        ONE or TWO -> 0xFF22C55E.toInt()           // green
        TWO or THREE -> 0xFFF97316.toInt()          // orange
        ONE or THREE -> 0xFF8B5CF6.toInt()          // violet
        else -> 0xFF64748B.toInt()                  // all three: neutral slate
    }
}

/** A temporary, page-bound reveal/write viewport. Geometry is in page coordinates. */
@Serializable
data class LayerFilter(
    val pageIndex: Int,
    val layerMask: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    fun moved(dx: Float, dy: Float): LayerFilter = copy(
        left = left + dx, top = top + dy, right = right + dx, bottom = bottom + dy
    )

    /** One two-finger frame: resize around the original centre, move with the finger midpoint. */
    fun pinched(
        focusDx: Float,
        focusDy: Float,
        scale: Float,
        pageWidth: Float,
        pageHeight: Float,
        minimum: Float = 24f,
        bounded: Boolean = true
    ): LayerFilter {
        if (!bounded) {
            val newWidth = (width * scale).coerceAtLeast(minimum)
            val newHeight = (height * scale).coerceAtLeast(minimum)
            val newLeft = left + width / 2f + focusDx - newWidth / 2f
            val newTop = top + height / 2f + focusDy - newHeight / 2f
            return copy(left = newLeft, top = newTop, right = newLeft + newWidth, bottom = newTop + newHeight)
        }
        val pageW = pageWidth.coerceAtLeast(minimum)
        val pageH = pageHeight.coerceAtLeast(minimum)
        val newWidth = (width * scale).coerceIn(minimum, pageW)
        val newHeight = (height * scale).coerceIn(minimum, pageH)
        val centreX = left + width / 2f + focusDx
        val centreY = top + height / 2f + focusDy
        val newLeft = (centreX - newWidth / 2f).coerceIn(0f, (pageW - newWidth).coerceAtLeast(0f))
        val newTop = (centreY - newHeight / 2f).coerceIn(0f, (pageH - newHeight).coerceAtLeast(0f))
        return copy(
            left = newLeft,
            top = newTop,
            right = newLeft + newWidth,
            bottom = newTop + newHeight
        )
    }

    fun withBounds(l: Float, t: Float, r: Float, b: Float, minimum: Float = 24f): LayerFilter {
        val nl = minOf(l, r - minimum)
        val nt = minOf(t, b - minimum)
        return copy(left = nl, top = nt, right = maxOf(r, nl + minimum), bottom = maxOf(b, nt + minimum))
    }
}
