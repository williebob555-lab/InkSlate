package com.inkslate.ink

import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter

/**
 * A colour treatment applied to the page and everything on it.
 *
 * Applied to the ink as well as the paper, which is the part that matters: inverting only the
 * page would turn it black and leave black handwriting invisible on top of it.
 */
enum class PageFilter(val label: String) {
    NONE("Normal"),
    NIGHT("Night"),
    SEPIA("Sepia"),
    GRAY("Greyscale"),
    HIGH_CONTRAST("High contrast");

    val filter: ColorFilter?
        get() = when (this) {
            NONE -> null
            NIGHT -> ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f
                    )
                ).also {
                    // Pull a little saturation out: a straight inversion turns coloured
                    // highlighter into lurid complementary shades that are harder to read past.
                    it.postConcat(ColorMatrix().apply { setSaturation(0.72f) })
                }
            )
            SEPIA -> ColorMatrixColorFilter(
                ColorMatrix().apply {
                    setSaturation(0f)
                    postConcat(
                        ColorMatrix(
                            floatArrayOf(
                                1.0f, 0f, 0f, 0f, 22f,
                                0f, 0.92f, 0f, 0f, 12f,
                                0f, 0f, 0.78f, 0f, 0f,
                                0f, 0f, 0f, 1f, 0f
                            )
                        )
                    )
                }
            )
            GRAY -> ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            HIGH_CONTRAST -> ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        1.7f, 0f, 0f, 0f, -90f,
                        0f, 1.7f, 0f, 0f, -90f,
                        0f, 0f, 1.7f, 0f, -90f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }

    /** Background behind the pages, so the surround does not glare in a dark room. */
    val backdropColor: Int
        get() = when (this) {
            NIGHT -> android.graphics.Color.parseColor("#0A0C0E")
            SEPIA -> android.graphics.Color.parseColor("#2A2418")
            else -> android.graphics.Color.parseColor("#20242A")
        }
}
