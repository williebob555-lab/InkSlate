package com.inkslate.desktop

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix

/**
 * A colour treatment applied to the page and everything on it.
 *
 * The modes and their matrices come from `:core`, shared with the tablet. This end only turns the
 * numbers into the filter type Compose takes - which happens to use the same 4x5 convention
 * Android's canvas does, so the same matrix serves both without translation.
 */
typealias PageFilter = com.inkslate.core.ReadingMode

val PageFilter.colorFilter: ColorFilter?
    get() = matrix?.let { ColorFilter.colorMatrix(ColorMatrix(it)) }
