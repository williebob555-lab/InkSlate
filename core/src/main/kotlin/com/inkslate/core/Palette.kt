package com.inkslate.core

/**
 * The pens themselves: which colours are offered, which widths a slider can land on, and the
 * starting kit of presets.
 *
 * Shared for the same reason the document format is. A width ladder that differed between the
 * tablet and the laptop would mean a stroke drawn at "2.0" on one could not be reproduced on the
 * other, and a palette that differed would mean a document marked up in the app's own red opened
 * somewhere with no way to match it. These are part of what a document can contain, not part of
 * how one platform happens to draw a toolbar.
 */
object Palette {

    /**
     * Tuned for marking up printed worksheets: readable on white, and distinct from print.
     *
     * The dark half first, because that is what most marks are; the light half exists for
     * writing on dark paper and on photographs.
     */
    val COLORS: List<Int> = listOf(
        "000000", "374151", "DC2626", "EA580C", "CA8A04", "16A34A",
        "0891B2", "1D4ED8", "7C3AED", "DB2777", "FFFFFF", "FDE047",
        "86EFAC", "93C5FD", "F9A8D4", "FCA5A5"
    ).map { opaque(it) }

    /**
     * Stroke widths a slider can land on.
     *
     * A ladder of fixed stops rather than a continuous range, which is what the first version of
     * this got wrong: a hairline and a fine liner are less than a tenth of a point apart, so a
     * linear pixel-per-point slider could not address them at all, and nothing you picked could
     * be picked again. Stops are dense where nibs actually live and coarse where the difference
     * stops mattering.
     */
    val WIDTHS: FloatArray = floatArrayOf(
        0.05f, 0.08f, 0.12f, 0.16f, 0.2f, 0.25f, 0.3f, 0.35f, 0.4f, 0.45f, 0.5f,
        0.6f, 0.7f, 0.8f, 0.9f, 1.0f, 1.1f, 1.2f, 1.4f, 1.6f, 1.8f, 2.0f, 2.25f,
        2.5f, 2.75f, 3.0f, 3.5f, 4.0f, 4.5f, 5.0f, 6f, 7f, 8f, 9f, 10f, 12f, 14f,
        16f, 18f, 20f, 24f, 28f, 32f, 40f, 48f, 56f, 64f
    )

    /** Eraser radii, same idea. */
    val ERASER_SIZES: FloatArray = floatArrayOf(
        1f, 1.5f, 2f, 3f, 4f, 5f, 6f, 8f, 10f, 12f, 15f, 18f, 22f, 28f, 34f, 42f, 52f, 64f
    )

    /** Index of the ladder stop nearest [value]. */
    fun stopIndex(ladder: FloatArray, value: Float): Int {
        var best = 0
        var bestD = Float.MAX_VALUE
        for (i in ladder.indices) {
            val d = kotlin.math.abs(ladder[i] - value)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /** `RRGGBB` to an opaque ARGB int, without any platform's colour parser. */
    fun opaque(hex: String): Int = (0xFF000000L or hex.toLong(16)).toInt()

    const val BLACK: Int = 0xFF000000.toInt()
}

/**
 * A saved pen: everything you would otherwise have to set four controls to get back.
 *
 * Held here rather than beside either toolbar so the starting kit is the same on both, and so a
 * preset could be synced later without the two builds disagreeing about what one is.
 */
data class PenPreset(
    val brush: BrushType,
    val color: Int,
    val width: Float,
    val opacity: Float = 1f
) {
    companion object {
        /** Starting kit: black pen, blue, red for corrections, yellow highlighter, pencil. */
        val defaults: List<PenPreset> = listOf(
            PenPreset(BrushType.BALLPOINT, Palette.BLACK, 2.0f),
            PenPreset(BrushType.GEL, Palette.opaque("1D4ED8"), 2.6f),
            PenPreset(BrushType.BALLPOINT, Palette.opaque("DC2626"), 2.0f),
            PenPreset(BrushType.HIGHLIGHTER, Palette.opaque("FDE047"), 15f),
            PenPreset(BrushType.PENCIL, Palette.opaque("4B5563"), 2.0f)
        )
    }
}
