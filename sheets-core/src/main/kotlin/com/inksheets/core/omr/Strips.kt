package com.inksheets.core.omr

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A staff cut out of its page and straightened: its lines made level along their traced course,
 * drawn so a space is [SPACE] pixels and the top line is at row [TOP] - the same for every page,
 * scan or print, so the trained reader always sees music the same size and shape. Grey, 0 ink to
 * 1 paper. Pages are read a strip at a time, so a phone holds only one in memory.
 */
class Strip(val width: Int, val pixels: FloatArray, private val staff: Recognizer.Staff, private val x0: Float, private val step: Float) {
    /** Where column [c] is on the page. */
    fun pageX(c: Float): Float = x0 + c * step

    /** The column of page x [x]. */
    fun column(x: Float): Float = (x - x0) / step

    /** Where row [r] of column [c] is on the page. */
    fun pageY(c: Float, r: Float): Float {
        val x = pageX(c).roundToInt()
        val top = staff.lineY(0, x); val space = (staff.lineY(4, x) - top) / 4f
        return top + (r - Strips.TOP) / Strips.SPACE * space
    }

    /** The row of page point ([x], [y]). */
    fun row(x: Float, y: Float): Float {
        val xi = x.roundToInt()
        val top = staff.lineY(0, xi); val space = (staff.lineY(4, xi) - top) / 4f
        return Strips.TOP + (y - top) / space * Strips.SPACE
    }

    operator fun get(c: Int, r: Int): Float = if (c in 0 until width && r in 0 until Strips.H) pixels[r * width + c] else 1f
}

object Strips {
    /** Pixels a staff space is drawn in a strip. */
    const val SPACE = 10f
    /** Rows in a strip: five spaces above the staff, its four, five below, and a little. */
    const val H = 144
    /** The row of the top line. */
    const val TOP = 52f

    /**
     * Staff [s] out of the page [grey] ([w] by [h], 0 black - 255 white), from a space before its
     * start to its end; each strip pixel the average of the page under it (a page drawn larger than
     * the strip is shrunk without losing thin lines to the gaps between samples).
     */
    fun cut(grey: IntArray, w: Int, h: Int, s: Recognizer.Staff): Strip {
        val step = s.space / SPACE
        val x0 = max(0f, s.left - s.space * 1.5f)
        val x1 = min(w - 1f, s.right + s.space * 0.5f)
        // A width the network's halvings divide evenly.
        val width = (((x1 - x0) / step).toInt() + 15) / 16 * 16
        val px = FloatArray(width * H)
        fun at(x: Float, y: Float): Float {
            val xi = floor(x).toInt(); val yi = floor(y).toInt()
            if (xi < 0 || yi < 0 || xi >= w - 1 || yi >= h - 1) return 1f
            val fx = x - xi; val fy = y - yi
            val a = grey[yi * w + xi]; val b = grey[yi * w + xi + 1]; val c = grey[(yi + 1) * w + xi]; val d = grey[(yi + 1) * w + xi + 1]
            return ((a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy) / 255f
        }
        // Samples under each strip pixel, each way: enough to cover it.
        val n = max(1, step.roundToInt())
        for (c in 0 until width) {
            val xc = x0 + c * step
            val xi = xc.roundToInt().coerceIn(s.left, s.right)
            val top = s.lineY(0, xi); val space = (s.lineY(4, xi) - top) / 4f
            val rowStep = space / SPACE
            for (r in 0 until H) {
                val yc = top + (r - TOP) / SPACE * space
                var sum = 0f
                for (j in 0 until n) for (i in 0 until n) sum += at(xc + (i + 0.5f) / n * step - step / 2, yc + (j + 0.5f) / n * rowStep - rowStep / 2)
                px[r * width + c] = sum / (n * n)
            }
        }
        return Strip(width, px, s, x0, step)
    }

    /** Grey levels (0-255) from ARGB pixels. */
    fun grey(argb: IntArray): IntArray = IntArray(argb.size) { i ->
        val p = argb[i]
        val a = (p ushr 24) and 0xFF
        val lum = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        (lum * a + 255 * (255 - a)) / 255
    }
}
