package com.inkslate.core

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Where the lines on a piece of paper go.
 *
 * Three places now have to draw ruled paper and agree exactly: the PDF writer, which puts it into
 * the document; the drawing surface, which paints the part of a growing canvas the document does
 * not cover yet; and the Windows build, which does both again. Separate implementations of "graph
 * paper" would line up on the day they were written and drift apart afterwards, and the drift
 * would show as a visible seam straight across the middle of a page.
 *
 * So there is one description of each pattern, emitted to whichever [Sink] is listening. It lives
 * here, with the document model, for the same reason [StrokeOutline] does: the two platforms are
 * allowed to differ in how they paint, never in what the shape is.
 */
object PaperPattern {

    /**
     * The patterns paper can be ruled with.
     *
     * Named rather than numbered because that is how [InkCanvas.background] stores it - a
     * document carries the name of its ruling, so a canvas made on the tablet is ruled the same
     * way when it opens on the laptop, whatever either build happens to offer in its own menus.
     */
    enum class Pattern { PLAIN, RULED, GRID, DOTS, GRAPH, CORNELL, MUSIC, ISOMETRIC }

    /**
     * The pattern [name] refers to, or [Pattern.PLAIN] when it names nothing known.
     *
     * Unknown names are plain paper on purpose. A document ruled by a later build that invented a
     * pattern this one has never heard of should open as a blank page with all its handwriting on
     * it, not fail to open at all.
     */
    fun patternOf(name: String): Pattern =
        Pattern.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Pattern.PLAIN

    /** Somewhere to draw. Coordinates are page points, y downwards, as ink is stored. */
    interface Sink {
        /** Fill the paper itself. */
        fun paper(left: Float, top: Float, right: Float, bottom: Float)
        fun lineWidth(width: Float)
        fun line(x0: Float, y0: Float, x1: Float, y1: Float)
        /** A dot of radius [r]. Drawn as a tiny square by writers that have no circle. */
        fun dot(x: Float, y: Float, r: Float)
    }

    /**
     * Draw [background] across the rectangle given.
     *
     * The ruling is laid out from [anchorX], [anchorY] rather than from the rectangle's own
     * corner, which is what lets a canvas that has grown past its page keep its lines in step
     * with the ones already printed on that page. Ask for a region left of the anchor and you get
     * lines continuing backwards through it, exactly where they would have been.
     */
    fun emit(
        background: Pattern,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        spacing: Float,
        anchorX: Float = 0f,
        anchorY: Float = 0f,
        sink: Sink
    ) {
        sink.paper(left, top, right, bottom)
        if (background == Pattern.PLAIN) return

        val step = spacing.coerceIn(4f, 200f)
        val h = bottom - top
        val w = right - left
        if (w <= 0f || h <= 0f) return

        /** Every multiple of [by] from [anchor] that lands inside [from]..[to]. */
        fun ticks(anchor: Float, by: Float, from: Float, to: Float): Iterable<Float> {
            if (by <= 0f) return emptyList()
            val first = anchor + ceil((from - anchor) / by) * by
            val count = floor((to - first) / by).toInt()
            if (count < 0) return emptyList()
            // A pathological zoom-out could ask for millions of lines; there is no sense in more
            // than a screenful of ruling however far the canvas has been dragged.
            val capped = minOf(count, MAX_LINES)
            return (0..capped).map { first + it * by }
        }

        when (background) {
            Pattern.RULED -> {
                sink.lineWidth(0.5f)
                for (y in ticks(anchorY, step, top, bottom)) sink.line(left, y, right, y)
            }

            Pattern.GRID, Pattern.GRAPH -> {
                val s = if (background == Pattern.GRAPH) step / 2f else step
                sink.lineWidth(0.4f)
                for (x in ticks(anchorX, s, left, right)) sink.line(x, top, x, bottom)
                for (y in ticks(anchorY, s, top, bottom)) sink.line(left, y, right, y)
            }

            Pattern.DOTS -> {
                for (y in ticks(anchorY, step, top, bottom)) {
                    for (x in ticks(anchorX, step, left, right)) sink.dot(x, y, 0.7f)
                }
            }

            // Cornell rules and music staves are page furniture: they belong to a sheet of paper
            // and have no meaning repeated across open space. They are drawn once, against the
            // page the anchor describes, and the rest of a grown canvas is left plain.
            Pattern.CORNELL -> {
                val pageBottom = anchorY + (bottom - anchorY).coerceAtMost(h)
                sink.lineWidth(0.5f)
                val ruleTo = pageBottom - step * 3f
                for (y in ticks(anchorY, step, anchorY, ruleTo)) sink.line(left, y, right, y)
                sink.lineWidth(1.2f)
                val cue = anchorX + (right - anchorX) * 0.28f
                sink.line(cue, anchorY, cue, ruleTo)
                sink.line(left, ruleTo, right, ruleTo)
            }

            Pattern.MUSIC -> {
                sink.lineWidth(0.6f)
                val staffGap = step / 4f
                val systemGap = step * 2.4f
                var y = anchorY + systemGap
                var guard = 0
                while (y + staffGap * 4 < bottom && guard++ < MAX_LINES) {
                    if (y >= top) {
                        val inset = (right - left) * 0.06f
                        for (i in 0..4) {
                            sink.line(left + inset, y + staffGap * i, right - inset, y + staffGap * i)
                        }
                    }
                    y += systemGap
                }
            }

            Pattern.ISOMETRIC -> {
                sink.lineWidth(0.4f)
                val run = h / 1.732f                  // 60 degrees: tan(60) = sqrt(3)
                val dx = step * 1.732f
                for (x in ticks(anchorX, dx, left - h * 1.2f, right + h * 1.2f)) {
                    sink.line(x, top, x + run, bottom)
                    sink.line(x, bottom, x + run, top)
                }
                for (y in ticks(anchorY, step, top, bottom)) sink.line(left, y, right, y)
            }

            Pattern.PLAIN -> Unit
        }
    }

    /** Above this many lines in one pass the paper is a solid block anyway. */
    private const val MAX_LINES = 4000
}
