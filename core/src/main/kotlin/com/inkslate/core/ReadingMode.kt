package com.inkslate.core

/**
 * A colour treatment applied to the page and everything on it.
 *
 * Applied to the ink as well as the paper, which is the part that matters: inverting only the
 * page would turn it black and leave black handwriting invisible on top of it.
 *
 * The matrices live here rather than beside either renderer because "night mode" has to be the
 * same night on both machines. They are 4x5 colour matrices in the convention both platforms use -
 * `R' = a*R + b*G + c*B + d*A + e`, with components in 0..255 - so each build wraps the same
 * numbers in its own filter type and neither owns the answer.
 */
enum class ReadingMode(val label: String) {
    NONE("Normal"),
    NIGHT("Night"),
    SEPIA("Sepia"),
    GRAY("Greyscale"),
    HIGH_CONTRAST("High contrast");

    /** The transform, or null when the page is shown as it is. */
    val matrix: FloatArray?
        get() = when (this) {
            NONE -> null

            // A straight inversion turns coloured highlighter into lurid complementary shades
            // that are harder to read past, so a little saturation comes out with it.
            NIGHT -> ColorMatrices.concat(
                ColorMatrices.saturation(0.72f),
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                )
            )

            SEPIA -> ColorMatrices.concat(
                floatArrayOf(
                    1.0f, 0f, 0f, 0f, 22f,
                    0f, 0.92f, 0f, 0f, 12f,
                    0f, 0f, 0.78f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                ),
                ColorMatrices.saturation(0f)
            )

            GRAY -> ColorMatrices.saturation(0f)

            HIGH_CONTRAST -> floatArrayOf(
                1.7f, 0f, 0f, 0f, -90f,
                0f, 1.7f, 0f, 0f, -90f,
                0f, 0f, 1.7f, 0f, -90f,
                0f, 0f, 0f, 1f, 0f
            )
        }

    /** Background behind the pages, so the surround does not glare in a dark room. */
    val backdropColor: Int
        get() = when (this) {
            NIGHT -> Palette.opaque("0A0C0E")
            SEPIA -> Palette.opaque("2A2418")
            else -> Palette.opaque("20242A")
        }
}

/**
 * The two colour-matrix operations the reading modes are built from.
 *
 * Written out rather than taken from a graphics library so the composed matrices can be shared:
 * the alternative is each platform composing its own with its own library, which is two chances
 * to end up with a different sepia.
 */
object ColorMatrices {

    /**
     * Desaturate towards luminance. 1 leaves the colours alone, 0 is greyscale.
     *
     * The weights are the usual luminance coefficients, and are what makes a grey page look
     * evenly grey rather than dark where it was blue.
     */
    fun saturation(s: Float): FloatArray {
        val inv = 1f - s
        val r = 0.213f * inv
        val g = 0.715f * inv
        val b = 0.072f * inv
        return floatArrayOf(
            r + s, g, b, 0f, 0f,
            r, g + s, b, 0f, 0f,
            r, g, b + s, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    }

    /**
     * Apply [b] first, then [a] - the order `postConcat` gives.
     *
     * The fifth column is a translation rather than a coefficient, so it accumulates through the
     * multiply and then picks up the outer matrix's own offset.
     */
    fun concat(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(20)
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += a[i * 5 + k] * b[k * 5 + j]
                out[i * 5 + j] = sum
            }
            var offset = a[i * 5 + 4]
            for (k in 0 until 4) offset += a[i * 5 + k] * b[k * 5 + 4]
            out[i * 5 + 4] = offset
        }
        return out
    }
}
