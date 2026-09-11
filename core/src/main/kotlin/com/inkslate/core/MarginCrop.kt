package com.inkslate.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Finding the printed area of a page, so its margins can be hidden.
 *
 * Textbooks give up a quarter of the page to margins, and on a screen that is a large amount of
 * room spent on nothing. This decides where the content actually is.
 *
 * Shared, because cropping changes the page's *visible* size, which is what the page arrangement
 * lays out and what ink is hit-tested against. Two builds trimming the same scan to two different
 * boxes would lay the same document out two different ways. Reading the pixels is each platform's
 * own job; the rule for what counts as content, and when a crop is worth making, is here.
 */
object MarginCrop {

    /** How far a pixel must differ from the background, summed across red, green and blue. */
    private const val THRESHOLD = 26

    /** Padding around the detected content, as a fraction of the page's shorter side. */
    private const val PADDING = 0.015f

    /** Above this much of the page kept, the crop is not worth the visual jump. */
    private const val NOT_WORTH_IT = 0.94f

    /** Below this much kept, the detection has almost certainly found a stray mark. */
    private const val IMPLAUSIBLE = 0.25f

    /**
     * The printed area of a rendered page, in page coordinates, or null to leave it alone.
     *
     * [pixelAt] returns a packed ARGB pixel. Sampled rather than read exhaustively: the box only
     * has to be approximately right, it is padded afterwards regardless, and reading every pixel
     * of a rendered page is real work on the critical path of showing one.
     *
     * Null is returned for a page not worth cropping - one that is almost all content already, or
     * one where the "content" found is so small that it is far likelier to be a speck of scanner
     * noise than the text. Both cases matter: a crop that saves nothing is a jump on screen for
     * no gain, and a crop that saves too much hides the page.
     */
    fun detect(
        rasterWidth: Int,
        rasterHeight: Int,
        pageWidth: Float,
        pageHeight: Float,
        pixelAt: (x: Int, y: Int) -> Int
    ): Box? {
        if (rasterWidth < 4 || rasterHeight < 4) return null
        if (pageWidth <= 0f || pageHeight <= 0f) return null

        val step = max(1, min(rasterWidth, rasterHeight) / 200)

        // The corner, taken as the paper. A scan with a tinted or grey background is still found
        // correctly this way, where assuming white would report the whole page as content.
        val background = pixelAt(1, 1)
        val bgR = (background shr 16) and 0xFF
        val bgG = (background shr 8) and 0xFF
        val bgB = background and 0xFF

        var minX = rasterWidth
        var minY = rasterHeight
        var maxX = -1
        var maxY = -1

        var y = 0
        while (y < rasterHeight) {
            var x = 0
            while (x < rasterWidth) {
                val px = pixelAt(x, y)
                val diff = abs(((px shr 16) and 0xFF) - bgR) +
                    abs(((px shr 8) and 0xFF) - bgG) +
                    abs((px and 0xFF) - bgB)
                if (diff > THRESHOLD) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                x += step
            }
            y += step
        }
        if (maxX < 0 || maxY < 0) return null

        val sx = pageWidth / rasterWidth
        val sy = pageHeight / rasterHeight
        val pad = min(pageWidth, pageHeight) * PADDING
        val box = Box(
            (minX * sx - pad).coerceAtLeast(0f),
            (minY * sy - pad).coerceAtLeast(0f),
            (maxX * sx + pad).coerceAtMost(pageWidth),
            (maxY * sy + pad).coerceAtMost(pageHeight)
        )

        if (box.width > pageWidth * NOT_WORTH_IT && box.height > pageHeight * NOT_WORTH_IT) {
            return null
        }
        if (box.width < pageWidth * IMPLAUSIBLE || box.height < pageHeight * IMPLAUSIBLE) {
            return null
        }
        return box
    }
}
