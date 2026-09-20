package com.inkslate.core

/**
 * Selecting by drawing a ring round things.
 *
 * A box is the wrong shape for most of what gets selected on a marked-up page: one line of working
 * among five, a diagram with a margin note beside it, an answer written at an angle. A ring round
 * what you mean takes the same gesture and asks for far less tidying afterwards.
 *
 * Shared rather than written twice: which marks a ring catches has to be the same on the tablet
 * and on the laptop, or the same gesture over the same document picks up different things.
 */
object Lasso {

    /** How much of a mark has to be inside the ring for it to count as caught. */
    const val ENOUGH = 0.6f

    /**
     * Whether a point is inside the ring, by counting crossings of a ray cast from it.
     *
     * The ring is closed implicitly - the last point joins back to the first - so an unfinished
     * loop still selects what it encircles rather than nothing at all, which is what a hand drawing
     * quickly actually produces.
     */
    fun contains(ring: List<Float>, x: Float, y: Float): Boolean {
        val n = ring.size / 2
        if (n < 3) return false
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = ring[i * 2]
            val yi = ring[i * 2 + 1]
            val xj = ring[j * 2]
            val yj = ring[j * 2 + 1]
            if ((yi > y) != (yj > y)) {
                val at = (xj - xi) * (y - yi) / (yj - yi) + xi
                if (x < at) inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * Whether [stroke] is caught by the ring.
     *
     * Judged on the marks a stroke is made of rather than on its bounding box: a long diagonal
     * line has a box covering half the page, and selecting it by its box would sweep up everything
     * the ring was drawn to avoid. Most of it has to be inside, not all - a ring drawn at speed
     * usually clips the end of a letter, and losing the word for it would be maddening.
     */
    fun catches(stroke: Stroke, ring: List<Float>): Boolean {
        val samples = samplesOf(stroke)
        if (samples.isEmpty()) return false
        var inside = 0
        for (i in samples.indices step 2) {
            if (contains(ring, samples[i], samples[i + 1])) inside++
        }
        return inside.toFloat() / (samples.size / 2) >= ENOUGH
    }

    /** The points a stroke is judged by: its own for a drawn mark, its corners for a box. */
    private fun samplesOf(stroke: Stroke): FloatArray {
        val box = stroke.boundsBox()
        return when (stroke.kind) {
            Stroke.Kind.FREEHAND, Stroke.Kind.LINE, Stroke.Kind.ARROW -> {
                // Thinned, because a long stroke can carry thousands of points and a ring drawn
                // round it should not cost a sweep of all of them.
                val points = stroke.points
                val stride = (points.size / 24).coerceAtLeast(1)
                val out = ArrayList<Float>()
                var i = 0
                while (i < points.size) {
                    out.add(points[i].x); out.add(points[i].y)
                    i += stride
                }
                val last = points.last()
                out.add(last.x); out.add(last.y)
                out.toFloatArray()
            }
            else -> floatArrayOf(
                box.left, box.top, box.centerX, box.top, box.right, box.top,
                box.right, box.centerY, box.right, box.bottom, box.centerX, box.bottom,
                box.left, box.bottom, box.left, box.centerY, box.centerX, box.centerY
            )
        }
    }
}
