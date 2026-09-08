package com.inkslate.ink

import com.inkslate.core.Stroke.Kind as StrokeKind
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turns a rough freehand stroke into the shape it was clearly meant to be.
 *
 * The bar for replacing what someone drew is deliberately high. A wrong correction is far more
 * annoying than a missed one, because it destroys work that was already correct - so every test
 * here errs toward leaving the stroke alone, and anything ambiguous is left as ink.
 */
object ShapeRecogniser {

    /** Below this straightness, a stroke is not a line. Ratio of end-to-end to path length. */
    private const val LINE_STRAIGHTNESS = 0.94f

    /** How closely a closed stroke must match a circle, as a radius variation ratio. */
    private const val CIRCLE_TOLERANCE = 0.19f

    /** How close the ends must be, relative to size, for a stroke to count as closed. */
    private const val CLOSE_RATIO = 0.22f

    /**
     * Return a corrected stroke, or null to keep the original.
     *
     * Only freehand pen strokes are considered: correcting a highlighter sweep or an eraser path
     * would be meaningless, and shapes drawn with the shape tools are already exact.
     */
    fun recognise(stroke: Stroke): Stroke? {
        if (stroke.kind != StrokeKind.FREEHAND) return null
        if (stroke.brush.isHighlighter) return null
        val pts = stroke.points
        if (pts.size < 6) return null

        val bounds = stroke.rawBounds()
        val size = max(bounds.width(), bounds.height())
        if (size < 12f) return null      // too small to have meant anything in particular

        val pathLength = pathLength(pts)
        if (pathLength < 1f) return null

        val start = pts.first()
        val end = pts.last()
        val endToEnd = hypot(end.x - start.x, end.y - start.y)
        val closed = endToEnd < size * CLOSE_RATIO

        // ---- straight line ----
        if (!closed && endToEnd / pathLength > LINE_STRAIGHTNESS) {
            return stroke.copy(
                kind = StrokeKind.LINE,
                points = listOf(
                    InkPoint(start.x, start.y, stroke.baseWidth),
                    InkPoint(end.x, end.y, stroke.baseWidth)
                ),
                updatedUtc = System.currentTimeMillis()
            )
        }

        if (!closed) return null

        // ---- circle or ellipse ----
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val radii = pts.map { hypot(it.x - cx, it.y - cy) }
        val meanR = radii.average().toFloat()
        if (meanR > 1f) {
            val variation = sqrt(radii.sumOf { val d = it - meanR; (d * d).toDouble() } / radii.size)
                .toFloat() / meanR
            if (variation < CIRCLE_TOLERANCE) {
                return stroke.copy(
                    kind = StrokeKind.ELLIPSE,
                    points = listOf(
                        InkPoint(bounds.left, bounds.top, stroke.baseWidth),
                        InkPoint(bounds.right, bounds.bottom, stroke.baseWidth)
                    ),
                    updatedUtc = System.currentTimeMillis()
                )
            }
        }

        // ---- rectangle ----
        // Four dominant corners near 90 degrees, with the outline hugging its bounding box.
        val corners = countCorners(pts)
        if (corners == 4 && fillsBounds(pts, bounds)) {
            return stroke.copy(
                kind = StrokeKind.RECT,
                points = listOf(
                    InkPoint(bounds.left, bounds.top, stroke.baseWidth),
                    InkPoint(bounds.right, bounds.bottom, stroke.baseWidth)
                ),
                updatedUtc = System.currentTimeMillis()
            )
        }

        return null
    }

    private fun pathLength(pts: List<InkPoint>): Float {
        var total = 0f
        for (i in 1 until pts.size) {
            total += hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
        }
        return total
    }

    /**
     * Count direction reversals sharp enough to be deliberate corners.
     *
     * Sampling across a span rather than between adjacent points keeps hand tremor from reading
     * as a corner on every third sample.
     */
    private fun countCorners(pts: List<InkPoint>): Int {
        val span = max(2, pts.size / 16)
        var corners = 0
        var i = span
        var lastCornerAt = -span
        while (i < pts.size - span) {
            val a = pts[i - span]
            val b = pts[i]
            val c = pts[i + span]
            val angle1 = atan2(b.y - a.y, b.x - a.x)
            val angle2 = atan2(c.y - b.y, c.x - b.x)
            var delta = abs(angle2 - angle1)
            if (delta > Math.PI) delta = (2 * Math.PI - delta).toFloat()
            if (delta > Math.toRadians(55.0) && i - lastCornerAt >= span) {
                corners++
                lastCornerAt = i
            }
            i++
        }
        return corners
    }

    /**
     * Does the outline actually run along its bounding box?
     *
     * A triangle and a rectangle have similar corner counts from some angles; this separates them
     * by checking that most of the stroke lies near an edge of the box.
     */
    private fun fillsBounds(pts: List<InkPoint>, bounds: RectF): Boolean {
        val tolerance = max(3f, min(bounds.width(), bounds.height()) * 0.16f)
        val nearEdge = pts.count { p ->
            val dx = min(abs(p.x - bounds.left), abs(p.x - bounds.right))
            val dy = min(abs(p.y - bounds.top), abs(p.y - bounds.bottom))
            dx < tolerance || dy < tolerance
        }
        return nearEdge.toFloat() / pts.size > 0.82f
    }
}
