package com.inkslate.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The filled shape of a variable-width freehand stroke, as plain polygons.
 *
 * ## Why polygons and not one smooth ribbon
 *
 * The obvious way to draw tapered ink is to offset the centreline left and right and join the two
 * edges into a single closed contour. It looks right, and for a stroke that never crosses itself
 * it is right - but it quietly depends on the fill rule to do something sensible where the stroke
 * loops back over itself, which is exactly what handwriting does. A `0`, a `D`, a looped `l`: the
 * ribbon closes a region that is not ink, and whether that region is painted comes down to
 * accumulated winding numbers through a contour hundreds of points long. Renderers disagreed, and
 * the export disagreed with the screen: closed letters came back from a save filled in solid.
 *
 * So the ribbon is not one contour. It is a run of small, *individually closed, individually
 * convex, identically wound* pieces - one per sample interval. Under the nonzero rule the union
 * of such pieces is exactly their union, whatever order they come in and however often the stroke
 * crosses itself, because no piece can ever contribute negative winding to another piece's
 * interior. A hole in a letter is simply a place no piece covers. There is nothing left for a
 * fill rule to get wrong, on any renderer, in any file format.
 *
 * ## Why it lives in :core
 *
 * The screen and the exported PDF must agree to the point. They now agree by construction: both
 * ask this file for the same numbers, rather than each building a path of their own.
 */
object StrokeOutline {

    /** Longest gap between generated cross-sections, in page points. */
    private const val MAX_STEP_PT = 0.55f

    /** Ceiling on generated cross-sections, so a pathological stroke cannot melt the renderer. */
    private const val MAX_SAMPLES = 6000

    /** Sides in a round cap. Enough that a nib end never reads as a polygon. */
    private const val CAP_SIDES = 14

    /** How far an edge may move when a cross-section is dropped, in page points. */
    private const val FLATNESS = 0.05f

    /**
     * One cross-section of the stroke: the two points the nib's ends occupy at this spot.
     *
     * For a round nib these are the centreline offset along its normal. For a broad nib they are
     * the two ends of the nib itself, which is held at a fixed angle and simply dragged - the
     * model that gives calligraphy its thick and thin.
     */
    private class Rib(
        val lx: Float, val ly: Float,
        val rx: Float, val ry: Float,
        val cx: Float, val cy: Float,
        val half: Float
    )

    /**
     * The stroke's area, as closed polygons wound the same way.
     *
     * Each entry is a flat `x0, y0, x1, y1, ...` list, implicitly closed. Fill them all with the
     * nonzero rule and the result is the ink.
     */
    fun contours(s: Stroke): List<FloatArray> {
        val pts = s.points
        if (pts.isEmpty()) return emptyList()
        if (pts.size == 1) {
            return listOf(circle(pts[0].x, pts[0].y, max(0.05f, pts[0].width / 2f)))
        }

        val dense = resample(pts)
        if (dense.size < 2) {
            val p = dense.firstOrNull() ?: pts[0]
            return listOf(circle(p.x, p.y, max(0.05f, p.width / 2f)))
        }

        val ribs = decimate(ribsFor(s.brush, dense))
        val out = ArrayList<FloatArray>(ribs.size + 2)
        for (i in 0 until ribs.size - 1) {
            addSlab(out, ribs[i], ribs[i + 1])
        }

        // Round nibs get round ends. A broad nib does not: its end is the nib, and squaring it
        // off is part of what makes calligraphy look like calligraphy.
        if (!s.brush.flatTip) {
            val a = ribs.first()
            val b = ribs.last()
            out.add(circle(a.cx, a.cy, max(0.05f, a.half)))
            out.add(circle(b.cx, b.cy, max(0.05f, b.half)))
        }
        return out
    }

    // ---- centreline ----------------------------------------------------------

    /**
     * Resample the centreline along the same quadratic-through-midpoints curve the thin-stroke
     * renderer draws, so a brush stroke and a pen stroke follow identical geometry.
     *
     * Samples arrive as far apart as a couple of points when the document is zoomed out. Building
     * the ribbon straight from them makes long strokes visibly faceted, which is precisely what
     * the single smooth contour used to hide.
     */
    private fun resample(pts: List<InkPoint>): List<InkPoint> {
        val out = ArrayList<InkPoint>(pts.size * 2)
        out.add(pts[0])
        var budget = MAX_SAMPLES
        for (i in 1 until pts.size) {
            val prev = pts[i - 1]
            val cur = pts[i]
            // control point is the sample itself; the curve runs midpoint to midpoint
            val startX = if (i == 1) prev.x else (pts[i - 2].x + prev.x) / 2f
            val startY = if (i == 1) prev.y else (pts[i - 2].y + prev.y) / 2f
            val endX = if (i == pts.size - 1) cur.x else (prev.x + cur.x) / 2f
            val endY = if (i == pts.size - 1) cur.y else (prev.y + cur.y) / 2f

            val span = hypot(endX - startX, endY - startY) +
                hypot(prev.x - startX, prev.y - startY)
            val steps = ceil(span / MAX_STEP_PT).toInt().coerceIn(1, 24)
            if (budget <= 0) break
            for (k in 1..steps) {
                val t = k / steps.toFloat()
                val u = 1f - t
                val x = u * u * startX + 2f * u * t * prev.x + t * t * endX
                val y = u * u * startY + 2f * u * t * prev.y + t * t * endY
                out.add(InkPoint(x, y, prev.width + (cur.width - prev.width) * t))
                if (--budget <= 0) break
            }
        }
        return out
    }

    // ---- cross-sections ------------------------------------------------------

    private fun ribsFor(brush: BrushType, pts: List<InkPoint>): List<Rib> {
        val n = pts.size
        val out = ArrayList<Rib>(n)

        // Taper is measured in arc length, not in sample count: a slow stroke has far more
        // samples than a fast one over the same distance, and counting samples would make the
        // taper depend on how fast the hand moved rather than on how far it went.
        val total = FloatArray(n)
        for (i in 1 until n) {
            total[i] = total[i - 1] + hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
        }
        val length = total[n - 1]

        val nibVec: FloatArray? = if (brush.isChisel) {
            val a = Math.toRadians(brush.nibAngleDeg.toDouble())
            floatArrayOf(cos(a).toFloat(), sin(a).toFloat())
        } else null

        var lastNx = 0f
        var lastNy = 1f
        for (i in 0 until n) {
            val prev = pts[max(0, i - 1)]
            val next = pts[min(n - 1, i + 1)]
            var tx = next.x - prev.x
            var ty = next.y - prev.y
            val len = hypot(tx, ty)
            var nx: Float
            var ny: Float
            var dir = 0f
            if (len < 1e-4f) {
                nx = lastNx; ny = lastNy
            } else {
                tx /= len; ty /= len
                nx = -ty; ny = tx
                dir = Math.atan2(ty.toDouble(), tx.toDouble()).toFloat()
            }
            lastNx = nx; lastNy = ny

            var half = max(0.02f, pts[i].width / 2f) * brush.directionFactor(dir)
            half *= taperAt(brush, total[i], length)

            if (nibVec != null) {
                // Sweep the nib: its two ends are a fixed distance apart in a fixed direction,
                // so a stroke along the nib narrows to the nib's own body and a stroke across it
                // is at full width. No width curve can produce that.
                val core = max(0.03f, half * brush.nibCore)
                val ex = nibVec[0] * half + nx * core
                val ey = nibVec[1] * half + ny * core
                out.add(
                    Rib(
                        pts[i].x + ex, pts[i].y + ey,
                        pts[i].x - ex, pts[i].y - ey,
                        pts[i].x, pts[i].y, core
                    )
                )
            } else {
                out.add(
                    Rib(
                        pts[i].x + nx * half, pts[i].y + ny * half,
                        pts[i].x - nx * half, pts[i].y - ny * half,
                        pts[i].x, pts[i].y, half
                    )
                )
            }
        }
        return out
    }

    /**
     * Drop cross-sections that add nothing.
     *
     * Resampling is deliberately generous, because a curve needs the density. A straight run of
     * unchanging width does not, and keeping it would put a hundred identical slabs into the
     * exported page for a single ruled line. A cross-section survives only if removing it would
     * move an edge by more than [FLATNESS], so the shape is unchanged to well under a pixel at
     * any sane zoom while long straight runs collapse to one slab.
     */
    private fun decimate(ribs: List<Rib>): List<Rib> {
        if (ribs.size < 3) return ribs
        val out = ArrayList<Rib>(ribs.size)
        out.add(ribs[0])
        var i = 0
        while (i < ribs.size - 1) {
            var best = i + 1
            // Bounded so this stays linear: an entirely straight stroke would otherwise be
            // quadratic in its own length.
            val limit = min(ribs.size - 1, i + 96)
            var j = i + 2
            while (j <= limit) {
                if (!flatBetween(ribs, i, j)) break
                best = j
                j++
            }
            out.add(ribs[best])
            i = best
        }
        return out
    }

    private fun flatBetween(ribs: List<Rib>, i: Int, j: Int): Boolean {
        val a = ribs[i]
        val b = ribs[j]
        for (k in i + 1 until j) {
            val m = ribs[k]
            if (distToSegment(m.lx, m.ly, a.lx, a.ly, b.lx, b.ly) > FLATNESS) return false
            if (distToSegment(m.rx, m.ry, a.rx, a.ry, b.rx, b.ry) > FLATNESS) return false
        }
        return true
    }

    private fun distToSegment(
        px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float
    ): Float {
        val vx = bx - ax
        val vy = by - ay
        val len2 = vx * vx + vy * vy
        if (len2 < 1e-9f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * vx + (py - ay) * vy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + vx * t), py - (ay + vy * t))
    }

    /** Multiplier from the entry and exit tapers at arc position [at] along a stroke of [length]. */
    private fun taperAt(brush: BrushType, at: Float, length: Float): Float {
        if (length < 1e-3f) return 1f
        var f = 1f
        if (brush.taperIn > 0f) {
            val span = length * brush.taperIn
            if (at < span) f *= ease(at / span)
        }
        if (brush.taperOut > 0f) {
            val span = length * brush.taperOut
            val left = length - at
            if (left < span) f *= ease(left / span)
        }
        // Never quite to nothing: a stroke that ends in a zero-width point ends in an invisible
        // spike rather than a tapered tip.
        return 0.12f + 0.88f * f
    }

    private fun ease(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    // ---- polygon emission ----------------------------------------------------

    /**
     * One interval of the ribbon, as convex, positively wound pieces.
     *
     * Adjacent slabs share their whole boundary rib, so there is no gap to fill at a join however
     * sharply the stroke turns. A slab whose two ribs cross - which happens when the stroke turns
     * inside its own half-width - is split into triangles and each is wound the right way round,
     * because a bow-tie quadrilateral would otherwise cancel part of itself away and punch a
     * notch out of the ink.
     */
    private fun addSlab(out: MutableList<FloatArray>, a: Rib, b: Rib) {
        val quad = floatArrayOf(a.lx, a.ly, a.rx, a.ry, b.rx, b.ry, b.lx, b.ly)
        val t1 = area(a.lx, a.ly, a.rx, a.ry, b.rx, b.ry)
        val t2 = area(a.lx, a.ly, b.rx, b.ry, b.lx, b.ly)
        if (abs(t1) < 1e-7f && abs(t2) < 1e-7f) return
        if (t1 >= 0f == t2 >= 0f) {
            out.add(if (t1 + t2 >= 0f) quad else reversed(quad))
            return
        }
        addTriangle(out, a.lx, a.ly, a.rx, a.ry, b.rx, b.ry)
        addTriangle(out, a.lx, a.ly, b.rx, b.ry, b.lx, b.ly)
    }

    private fun addTriangle(
        out: MutableList<FloatArray>,
        x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float
    ) {
        if (abs(area(x0, y0, x1, y1, x2, y2)) < 1e-7f) return
        val tri = floatArrayOf(x0, y0, x1, y1, x2, y2)
        out.add(if (area(x0, y0, x1, y1, x2, y2) >= 0f) tri else reversed(tri))
    }

    /** Twice the signed area; positive is the same handedness a [circle] is generated with. */
    private fun area(
        x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float
    ): Float = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)

    private fun reversed(poly: FloatArray): FloatArray {
        val n = poly.size / 2
        val out = FloatArray(poly.size)
        for (i in 0 until n) {
            out[i * 2] = poly[(n - 1 - i) * 2]
            out[i * 2 + 1] = poly[(n - 1 - i) * 2 + 1]
        }
        return out
    }

    private fun circle(cx: Float, cy: Float, r: Float): FloatArray {
        val out = FloatArray(CAP_SIDES * 2)
        for (i in 0 until CAP_SIDES) {
            val a = i * 2.0 * Math.PI / CAP_SIDES
            out[i * 2] = cx + (cos(a) * r).toFloat()
            out[i * 2 + 1] = cy + (sin(a) * r).toFloat()
        }
        return out
    }
}
