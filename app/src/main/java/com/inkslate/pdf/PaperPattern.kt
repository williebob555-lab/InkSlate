package com.inkslate.pdf

import com.inkslate.pdf.BlankDocumentFactory.Background
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Where the lines on a piece of paper go.
 *
 * There are now two places that have to draw ruled paper and agree exactly: the PDF writer, which
 * puts it into the document, and the drawing surface, which paints the part of a growing canvas
 * the document does not cover yet. Two implementations of "graph paper" would line up on the day
 * they were written and drift apart afterwards, and the drift would show as a visible seam right
 * across the middle of the page.
 *
 * So there is one description of each pattern, emitted to whichever [PaperSink] is listening.
 */
object PaperPattern {

    /** Somewhere to draw. Coordinates are page points, y downwards, as ink is stored. */
    interface PaperSink {
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
        background: Background,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        spacing: Float,
        anchorX: Float = 0f,
        anchorY: Float = 0f,
        sink: PaperSink
    ) {
        sink.paper(left, top, right, bottom)
        if (background == Background.PLAIN) return

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
            Background.RULED -> {
                sink.lineWidth(0.5f)
                for (y in ticks(anchorY, step, top, bottom)) sink.line(left, y, right, y)
            }

            Background.GRID, Background.GRAPH -> {
                val s = if (background == Background.GRAPH) step / 2f else step
                sink.lineWidth(0.4f)
                for (x in ticks(anchorX, s, left, right)) sink.line(x, top, x, bottom)
                for (y in ticks(anchorY, s, top, bottom)) sink.line(left, y, right, y)
            }

            Background.DOTS -> {
                for (y in ticks(anchorY, step, top, bottom)) {
                    for (x in ticks(anchorX, step, left, right)) sink.dot(x, y, 0.7f)
                }
            }

            // Cornell rules and music staves are page furniture: they belong to a sheet of paper
            // and have no meaning repeated across open space. They are drawn once, against the
            // page the anchor describes, and the rest of a grown canvas is left plain.
            Background.CORNELL -> {
                val pageBottom = anchorY + (bottom - anchorY).coerceAtMost(h)
                sink.lineWidth(0.5f)
                val ruleTo = pageBottom - step * 3f
                for (y in ticks(anchorY, step, anchorY, ruleTo)) sink.line(left, y, right, y)
                sink.lineWidth(1.2f)
                val cue = anchorX + (right - anchorX) * 0.28f
                sink.line(cue, anchorY, cue, ruleTo)
                sink.line(left, ruleTo, right, ruleTo)
            }

            Background.MUSIC -> {
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

            Background.ISOMETRIC -> {
                sink.lineWidth(0.4f)
                val run = h / 1.732f                  // 60 degrees: tan(60) = sqrt(3)
                val dx = step * 1.732f
                for (x in ticks(anchorX, dx, left - h * 1.2f, right + h * 1.2f)) {
                    sink.line(x, top, x + run, bottom)
                    sink.line(x, bottom, x + run, top)
                }
                for (y in ticks(anchorY, step, top, bottom)) sink.line(left, y, right, y)
            }

            Background.PLAIN -> Unit
        }
    }

    /** Above this many lines in one pass the paper is a solid block anyway. */
    private const val MAX_LINES = 4000
}
