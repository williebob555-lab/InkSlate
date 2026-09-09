package com.inkslate.ink

import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter

/**
 * A colour treatment applied to the page and everything on it.
 *
 * The modes and their matrices live in `:core`, shared with the Windows build - "night mode" has
 * to be the same night on both machines, and two libraries composing their own saturation and
 * inversion is two chances to end up with a different one. This end only turns the numbers into
 * the filter type Android's canvas takes.
 */
typealias PageFilter = com.inkslate.core.ReadingMode

val PageFilter.filter: ColorFilter?
    get() = matrix?.let { ColorMatrixColorFilter(ColorMatrix(it)) }
