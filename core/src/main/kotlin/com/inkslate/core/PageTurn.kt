package com.inkslate.core

/**
 * Turning a page, and taking its handwriting with it.
 *
 * A scanned handout that arrives sideways is not fixed by rotating the paper alone: the marks on
 * it are stored in the page's own display coordinates, so a page that turns and ink that does not
 * come apart immediately. Both halves are here, working off the same quarter-turn count, because
 * they are only ever correct together.
 */
object PageTurn {

    /** Normalise any turn count to 0..3 clockwise quarter turns. */
    fun normalise(quarterTurns: Int): Int = ((quarterTurns % 4) + 4) % 4

    /** True when the page's width and height swap. */
    fun swapsDimensions(quarterTurns: Int): Boolean = normalise(quarterTurns) % 2 == 1

    /**
     * Map a point through [quarterTurns] clockwise turns of a [width] x [height] page.
     *
     * One clockwise turn sends (x, y) to (height - y, x): the left edge becomes the top, and the
     * page comes out height-by-width. Applying that repeatedly, swapping the dimensions as it
     * goes, covers all four cases without four sets of formulae to get individually wrong.
     */
    fun point(x: Float, y: Float, width: Float, height: Float, quarterTurns: Int): FloatArray {
        var px = x
        var py = y
        var w = width
        var h = height
        repeat(normalise(quarterTurns)) {
            val nx = h - py
            val ny = px
            px = nx
            py = ny
            val t = w
            w = h
            h = t
        }
        return floatArrayOf(px, py)
    }
}

/**
 * Turn a stroke with the page it sits on.
 *
 * Two kinds of object need two treatments. A path - handwriting, a line, a box - *is* its points,
 * so turning the points is the whole job and the result is exact. An object with content of its
 * own - a text box, a table, a pasted image - cannot be turned that way: rotating the two corners
 * of a text box gives a box of the right shape containing sideways text. Those keep their own
 * width and height, move their centre to where the turn puts it, and carry the turn in
 * [Stroke.rotation], which the renderer applies about the object's centre.
 */
fun Stroke.turnedWithPage(quarterTurns: Int, pageWidth: Float, pageHeight: Float): Stroke {
    val q = PageTurn.normalise(quarterTurns)
    if (q == 0 || points.isEmpty()) return this

    fun map(x: Float, y: Float) = PageTurn.point(x, y, pageWidth, pageHeight, q)

    return when (kind) {
        Stroke.Kind.TEXT, Stroke.Kind.TABLE, Stroke.Kind.IMAGE -> {
            val box = rawBoundsBox()
            val c = map(box.centerX, box.centerY)
            val w = box.width
            val h = box.height
            val left = c[0] - w / 2f
            val top = c[1] - h / 2f
            val moved = if (kind == Stroke.Kind.TEXT) {
                // one anchor at the top-left; the box is derived from it
                listOf(InkPoint(left, top, points[0].width))
            } else {
                listOf(
                    InkPoint(left, top, points.first().width),
                    InkPoint(left + w, top + h, points.last().width)
                )
            }
            copy(points = moved, rotation = (rotation + 90f * q) % 360f)
        }
        else -> copy(
            points = points.map { p ->
                val m = map(p.x, p.y)
                InkPoint(m[0], m[1], p.width)
            }
        )
    }
}
