package com.inksheets.core.omr

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A black-and-white picture: true is ink. */
class Ink(val width: Int, val height: Int, val bits: BooleanArray = BooleanArray(width * height)) {
    operator fun get(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height && bits[y * width + x]
    operator fun set(x: Int, y: Int, v: Boolean) { if (x in 0 until width && y in 0 until height) bits[y * width + x] = v }
    fun copy() = Ink(width, height, bits.copyOf())

    /** Ink as ARGB pixels (black on white), for looking at. */
    fun argb(): IntArray = IntArray(width * height) { if (bits[it]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }

    companion object {
        /**
         * Ink from grey levels (0 black - 255 white), each pixel against the light around it
         * (Sauvola), so a scan darker at one side than the other still comes out clean.
         */
        fun fromGrey(width: Int, height: Int, grey: IntArray, window: Int = 31, k: Double = 0.2): Ink {
            val w1 = width + 1
            val sum = LongArray(w1 * (height + 1))
            val sq = LongArray(w1 * (height + 1))
            for (y in 0 until height) {
                var rs = 0L; var rq = 0L
                for (x in 0 until width) {
                    val g = grey[y * width + x].toLong()
                    rs += g; rq += g * g
                    sum[(y + 1) * w1 + x + 1] = sum[y * w1 + x + 1] + rs
                    sq[(y + 1) * w1 + x + 1] = sq[y * w1 + x + 1] + rq
                }
            }
            val r = window / 2
            val out = Ink(width, height)
            for (y in 0 until height) {
                val y0 = max(0, y - r); val y1 = min(height, y + r + 1)
                for (x in 0 until width) {
                    val x0 = max(0, x - r); val x1 = min(width, x + r + 1)
                    val n = ((x1 - x0) * (y1 - y0)).toDouble()
                    val s = sum[y1 * w1 + x1] - sum[y0 * w1 + x1] - sum[y1 * w1 + x0] + sum[y0 * w1 + x0]
                    val q = sq[y1 * w1 + x1] - sq[y0 * w1 + x1] - sq[y1 * w1 + x0] + sq[y0 * w1 + x0]
                    val mean = s / n
                    val sd = kotlin.math.sqrt(max(0.0, q / n - mean * mean))
                    val threshold = mean * (1 + k * (sd / 128.0 - 1))
                    // Paper stays paper: nothing lighter than mid-grey is ink.
                    out.bits[y * width + x] = grey[y * width + x] < threshold && grey[y * width + x] < 200
                }
            }
            return out
        }

        /** Ink from ARGB pixels. */
        fun fromArgb(width: Int, height: Int, argb: IntArray, window: Int = 31): Ink =
            fromGrey(width, height, IntArray(argb.size) { i ->
                val p = argb[i]
                val a = (p ushr 24) and 0xFF
                val lum = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                // Transparent is paper.
                (lum * a + 255 * (255 - a)) / 255
            }, window)
    }
}

/** Filled shapes drawn into [Ink]: glyph outlines, beams, lines - for templates and tests. */
object Fill {
    /** Fill closed polygons (non-zero winding) given in pixels. */
    fun polygons(ink: Ink, polys: List<FloatArray>, value: Boolean = true) {
        if (polys.isEmpty()) return
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in polys) for (i in 1 until p.size step 2) { minY = min(minY, p[i]); maxY = max(maxY, p[i]) }
        val y0 = max(0, floor(minY).toInt()); val y1 = min(ink.height - 1, ceil(maxY).toInt())
        val xs = ArrayList<Pair<Float, Int>>()
        for (y in y0..y1) {
            val cy = y + 0.5f
            xs.clear()
            for (p in polys) {
                val n = p.size / 2
                for (i in 0 until n) {
                    val ax = p[2 * i]; val ay = p[2 * i + 1]
                    val bx = p[2 * ((i + 1) % n)]; val by = p[2 * ((i + 1) % n) + 1]
                    if ((ay <= cy && by > cy) || (by <= cy && ay > cy)) {
                        val t = (cy - ay) / (by - ay)
                        xs += (ax + t * (bx - ax)) to (if (by > ay) 1 else -1)
                    }
                }
            }
            xs.sortBy { it.first }
            var wind = 0
            for (i in xs.indices) {
                val before = wind
                wind += xs[i].second
                if (before == 0 && wind != 0 && i + 1 < xs.size) {
                    // Runs until winding returns to zero.
                    var j = i + 1; var w = wind
                    while (j < xs.size) { w += xs[j].second; if (w == 0) break; j++ }
                    val from = (xs[i].first + 0.5f).roundToInt().coerceAtLeast(0)
                    val to = (xs[min(j, xs.size - 1)].first - 0.5f).roundToInt().coerceAtMost(ink.width - 1)
                    for (x in from..to) ink.bits[y * ink.width + x] = value
                }
            }
        }
    }

    /** A thick straight line, as a polygon. */
    fun line(ink: Ink, x1: Float, y1: Float, x2: Float, y2: Float, width: Float, value: Boolean = true) {
        val dx = x2 - x1; val dy = y2 - y1
        val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
        val nx = -dy / len * width / 2; val ny = dx / len * width / 2
        polygons(ink, listOf(floatArrayOf(x1 + nx, y1 + ny, x2 + nx, y2 + ny, x2 - nx, y2 - ny, x1 - nx, y1 - ny)), value)
    }
}
