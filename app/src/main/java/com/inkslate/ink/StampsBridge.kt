package com.inkslate.ink

import android.graphics.RectF
import com.inkslate.core.Box
import com.inkslate.core.Stamps
import com.inkslate.core.Stroke

/**
 * Build a stamp inside a platform rectangle.
 *
 * The stamps themselves live in `:core`, shared with the Windows build - a stamp is geometry, and
 * a number line built one way here and another way there would be two drawings wearing one name.
 * All this end does is hand over the rectangle in the shared form.
 */
fun buildStamp(
    kind: Stamps.Kind,
    bounds: RectF,
    page: Int,
    color: Int,
    width: Float,
    options: Stamps.StampOptions = kind.defaults,
    nextId: () -> String
): List<Stroke> = Stamps.build(
    kind,
    Box(bounds.left, bounds.top, bounds.right, bounds.bottom),
    page,
    color,
    width,
    options,
    nextId
)
