package com.inksheets.core.omr

import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * A page scanned or photographed turned: how far, the page turned upright to read, and what was
 * read on it turned back onto the page as it is - so the clean view, Fix's pictures and the
 * readings kept all line up with the page shown. (Held-out scans turned 3 degrees: a sixth of the
 * bars were lost, whole staves not found - the staff finder looks for lines near level.)
 */
object Skew {
    /**
     * How many degrees the page's staff lines run aslant (positive: falling to the right, y growing
     * downward): the angle along which the page's ink, summed in rows, gathers most
     * sharply into lines - tried from -6 to 6 degrees, then finer about the best.
     */
    fun of(ink: Ink): Float {
        val xs = ArrayList<Int>(); val ys = ArrayList<Int>()
        for (y in 0 until ink.height) for (x in 0 until ink.width step 2) if (ink.bits[y * ink.width + x]) { xs += x; ys += y }
        if (xs.size < 1000) return 0f
        val cx = ink.width / 2.0
        val pad = (ink.width * tan(Math.toRadians(7.0))).toInt() + 2
        val rows = IntArray(ink.height + 2 * pad)
        fun sharpness(deg: Double): Double {
            java.util.Arrays.fill(rows, 0)
            val t = tan(Math.toRadians(deg))
            for (i in xs.indices) {
                val r = (ys[i] - (xs[i] - cx) * t).roundToInt() + pad
                if (r in rows.indices) rows[r]++
            }
            var s = 0.0
            for (v in rows) s += v.toDouble() * v
            return s
        }
        var best = 0.0; var bestS = sharpness(0.0)
        for (k in -24..24) { val d = k * 0.25; val s = sharpness(d); if (s > bestS) { bestS = s; best = d } }
        var fine = best
        for (k in -5..5) { val d = best + k * 0.05; val s = sharpness(d); if (s > bestS) { bestS = s; fine = d } }
        return fine.toFloat()
    }

    /** [ink] turned [deg] degrees about its middle (the way that brings lines aslant by -[deg] level), its size kept. */
    fun turn(ink: Ink, deg: Float): Ink {
        val out = Ink(ink.width, ink.height)
        val a = Math.toRadians(deg.toDouble()); val c = cos(a); val s = sin(a)
        val cx = ink.width / 2.0; val cy = ink.height / 2.0
        for (y in 0 until ink.height) for (x in 0 until ink.width) {
            // The pixel of the page that lands here.
            val dx = x - cx; val dy = y - cy
            val sx = (c * dx - s * dy + cx).roundToInt(); val sy = (s * dx + c * dy + cy).roundToInt()
            if (ink[sx, sy]) out.bits[y * ink.width + x] = true
        }
        return out
    }

    /** [grey] (0-255, [w] by [h]) turned as [turn] turns ink; paper where nothing lands. */
    fun turnGrey(grey: IntArray, w: Int, h: Int, deg: Float): IntArray {
        val out = IntArray(w * h) { 255 }
        val a = Math.toRadians(deg.toDouble()); val c = cos(a); val s = sin(a)
        val cx = w / 2.0; val cy = h / 2.0
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x - cx; val dy = y - cy
            val sx = (c * dx - s * dy + cx).roundToInt(); val sy = (s * dx + c * dy + cy).roundToInt()
            if (sx in 0 until w && sy in 0 until h) out[y * w + x] = grey[sy * w + sx]
        }
        return out
    }

    /** Where a point of the page turned by [deg] (as [turn] does) is on the page as it was. */
    class Back(deg: Float, w: Int, h: Int) {
        private val a = Math.toRadians(deg.toDouble()); private val c = cos(a); private val s = sin(a)
        private val cx = w / 2.0; private val cy = h / 2.0
        fun x(x: Float, y: Float) = (c * (x - cx) - s * (y - cy) + cx).toFloat()
        fun y(x: Float, y: Float) = (s * (x - cx) + c * (y - cy) + cy).toFloat()
    }

    /** [r], read on the page turned by [deg], put back on the page as it was ([w] by [h]). */
    fun back(r: Recognizer.PageReading, deg: Float, w: Int, h: Int): Recognizer.PageReading {
        val b = Back(deg, w, h)
        fun box(bx: Box): Box {
            val xs = listOf(b.x(bx.left.toFloat(), bx.top.toFloat()), b.x(bx.right.toFloat(), bx.top.toFloat()), b.x(bx.left.toFloat(), bx.bottom.toFloat()), b.x(bx.right.toFloat(), bx.bottom.toFloat()))
            val ys = listOf(b.y(bx.left.toFloat(), bx.top.toFloat()), b.y(bx.right.toFloat(), bx.top.toFloat()), b.y(bx.left.toFloat(), bx.bottom.toFloat()), b.y(bx.right.toFloat(), bx.bottom.toFloat()))
            return Box(xs.min().roundToInt(), ys.min().roundToInt(), xs.max().roundToInt(), ys.max().roundToInt())
        }
        fun event(e: Event, midY: Float): Event = when (e) {
            is Note -> e.copy(x = b.x(e.x, midY))
            is Rest -> e.copy(x = b.x(e.x, midY))
        }
        val measures = r.measures.map { m ->
            val midY = (m.box.top + m.box.bottom) / 2f
            m.copy(
                box = box(m.box),
                events = m.events.map { event(it, midY) },
                maybe = m.maybe.map { event(it, midY) },
                directions = m.directions.map { d -> d.copy(x = b.x(d.x, midY), x2 = b.x(d.x2, midY)) },
                // The five lines' heights at the left edge, then at the right: where those points are now.
                lines = if (m.lines.size == 10) List(10) { i -> b.y(if (i < 5) m.box.left.toFloat() else m.box.right.toFloat(), m.lines[i]) } else m.lines,
                kept = m.kept.map { loop -> IntArray(loop.size) { i -> if (i % 2 == 0) b.x(loop[i].toFloat(), loop[i + 1].toFloat()).roundToInt() else b.y(loop[i - 1].toFloat(), loop[i].toFloat()).roundToInt() } }
            )
        }
        val staves = r.staves.map { s ->
            // Each line's points put back, then read off again at each whole x.
            val lines = Array(5) { l -> (s.left..s.right).map { x -> b.x(x.toFloat(), s.lineY(l, x)) to b.y(x.toFloat(), s.lineY(l, x)) } }
            val left = lines.maxOf { it.first().first }.roundToInt(); val right = lines.minOf { it.last().first }.roundToInt()
            if (right <= left) s else Recognizer.Staff(left, right, Array(5) { l ->
                val pts = lines[l]; var k = 0
                FloatArray(right - left + 1) { i ->
                    val x = (left + i).toFloat()
                    while (k + 1 < pts.size - 1 && pts[k + 1].first < x) k++
                    val (x0, y0) = pts[k]; val (x1, y1) = pts[min(k + 1, pts.size - 1)]
                    if (x1 - x0 < 1e-3f) y0 else y0 + (y1 - y0) * ((x - x0) / (x1 - x0)).coerceIn(0f, 1f)
                }
            }, s.space)
        }
        val barlines = r.barlines.mapIndexed { si, xs ->
            val s = r.staves.getOrNull(si)
            xs.map { x -> if (s == null) x else b.x(x.toFloat(), (s.lineY(0, x) + s.lineY(4, x)) / 2f).roundToInt() }
        }
        val dropped = r.dropped.map { (hd, why) ->
            Recognizer.Head(b.x(hd.x.toFloat(), hd.y.toFloat()).roundToInt(), hd.step, b.y(hd.x.toFloat(), hd.y.toFloat()).roundToInt(), hd.kind, hd.score) to why
        }
        return Recognizer.PageReading(staves, barlines, measures, r.thickness, r.space, dropped)
    }

    /**
     * Below this many degrees a page is read as it is: the staff finder and tracing take it in their
     * stride, and turning the picture softens it. (Held-out scans: at 0.6 degrees turning cost 0.3%
     * of bars right; at 3 degrees it brought them from 88.9% to 94.2%, trust 96.9 -> 98.8%.)
     */
    const val LEAST = 1.0f
}
