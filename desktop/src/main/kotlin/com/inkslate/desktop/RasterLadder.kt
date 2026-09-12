package com.inkslate.desktop

/**
 * How big to render a page, and - more to the point - when not to render it again.
 *
 * A page is rendered to a bitmap at whatever size it is being shown at. Zooming changes that size
 * continuously, so the sizes are rounded onto a ladder; the question is what the rungs are. Evenly
 * spaced ones look reasonable and are not: the step from one to the next is a smaller and smaller
 * fraction as they get bigger, so magnifying a page a few times crossed twenty of them, and each
 * crossing is a full page render and a bitmap of some tens of megabytes thrown away. That is felt
 * as the occasional dropped frame while zooming, from the collection of what was discarded.
 *
 * The rungs here are spaced by ratio, so there are nine of them over the whole range a page can be
 * shown at rather than twenty-four, and a given amount of zooming crosses the same number wherever
 * it starts.
 *
 * The second half is hysteresis. Landing exactly on a boundary and rocking across it would re-render
 * on every wobble, so a raster sharper than needed is kept until it is wastefully sharp - zooming
 * out re-renders nothing at all, and zooming back in finds the raster it had.
 */
object RasterLadder {

    /** Below this a page is a thumbnail and the cost of rendering is all overhead. */
    const val SMALLEST = 160

    /** Above this the memory is not worth the sharpness; the page is simply magnified. */
    const val LARGEST = 4_000

    /** The rung at or above [wanted]. */
    fun rungFor(wanted: Int): Int {
        if (wanted <= SMALLEST) return SMALLEST
        var rung = SMALLEST
        while (rung < wanted && rung < LARGEST) rung = rung * 3 / 2
        return rung.coerceAtMost(LARGEST)
    }

    /**
     * Whether a raster already made at [have] pixels should be made again for [wanted].
     *
     * Two reasons and no others: it is not sharp enough to be shown at this size, or it is so much
     * sharper than needed that it is holding memory for nothing.
     */
    fun shouldRemake(have: Int?, wanted: Int): Boolean {
        if (have == null) return true
        val target = rungFor(wanted)
        if (have < target) return true
        return have > target * 2
    }
}
