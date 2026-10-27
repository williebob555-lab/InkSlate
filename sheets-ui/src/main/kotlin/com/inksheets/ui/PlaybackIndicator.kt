package com.inksheets.ui

import com.inkslate.core.PageMark

/** Music remains untouched: a leading bracket and a fine progress rail sit outside the staff. */
object PlaybackIndicator {
    fun marks(left: Float, top: Float, right: Float, bottom: Float, space: Float,
              progress: Float, paused: Boolean = false): List<PageMark> {
        if (right <= left || space <= 0 || !progress.isFinite()) return emptyList()
        val ink = if (paused) 0xE5B87510.toInt() else 0xEB2878D4.toInt()
        val track = if (paused) 0x30B87510 else 0x302878D4
        val pad = space * .55f
        val y = bottom + space * 1.2f
        val x = left + (right - left) * progress.coerceIn(0f, 1f)
        return listOf(
            PageMark.line(left - pad, top - pad, left - pad, bottom + pad, space * .26f, ink),
            PageMark.line(left - pad, top - pad, left + space * .4f, top - pad, space * .26f, ink),
            PageMark.line(left, y, right, y, space * .22f, track),
            PageMark.line(left, y, x, y, space * .32f, ink),
            PageMark.rect(x - space * .2f, y - space * .32f, x + space * .2f, y + space * .32f, ink)
        )
    }
}
