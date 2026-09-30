package com.inksheets.core.omr

import kotlin.math.max
import kotlin.math.min

/**
 * Printed numbers read in any typeface: a multi-bar rest's count, a tuplet's 3. Each figure on
 * the page is cut out by its own outline, shrunk to a small grid keeping its shape (a "1" stays
 * narrow), and matched against the digits of the typefaces parts are printed in
 * (resources omr/digits.txt, made by DigitMasksTool) - so a figure is read whole, not by whichever
 * part of it a thin digit happens to fit.
 */
object Digits {
    const val W = 12
    const val H = 18

    private val known: List<Pair<Int, BooleanArray>> by lazy {
        val text = Digits::class.java.getResourceAsStream("/omr/digits.txt")?.bufferedReader()?.readText() ?: return@lazy emptyList()
        text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { line ->
            val parts = line.trim().split(' ')
            val d = parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: return@mapNotNull null
            val bits = parts.last()
            if (bits.length != W * H) null else d to BooleanArray(W * H) { bits[it] == '1' }
        }
    }

    /**
     * The ink between ([l], [t]) and ([r], [b]) cut to its outline and shrunk into the grid,
     * fitted by its height (or width, if wider) and centred; null when there is no ink.
     */
    fun mask(ink: Ink, l: Int, t: Int, r: Int, b: Int): BooleanArray? {
        var x0 = Int.MAX_VALUE; var x1 = -1; var y0 = Int.MAX_VALUE; var y1 = -1
        for (y in t..b) for (x in l..r) if (ink[x, y]) { x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y) }
        if (x1 < 0) return null
        val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
        val scale = min(H.toFloat() / bh, W.toFloat() / bw)
        val gw = max(1, Math.round(bw * scale)); val gh = max(1, Math.round(bh * scale))
        val ox = (W - gw) / 2; val oy = (H - gh) / 2
        val out = BooleanArray(W * H)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            // The share of ink under this cell.
            val sx0 = x0 + (gx * bw / gw.toFloat()).toInt(); val sx1 = max(sx0, x0 + ((gx + 1) * bw / gw.toFloat()).toInt() - 1)
            val sy0 = y0 + (gy * bh / gh.toFloat()).toInt(); val sy1 = max(sy0, y0 + ((gy + 1) * bh / gh.toFloat()).toInt() - 1)
            var n = 0; var c = 0
            for (yy in sy0..sy1) for (xx in sx0..sx1) { n++; if (ink[xx, yy]) c++ }
            out[(oy + gy) * W + ox + gx] = c * 3 >= n
        }
        return out
    }

    /** The digit [m] is most like, and how alike (1 the same): null when nothing is close. */
    fun read(m: BooleanArray): Pair<Int, Float>? {
        var best: Pair<Int, Int>? = null
        for ((d, k) in known) {
            var diff = 0
            for (i in m.indices) if (m[i] != k[i]) diff++
            if (best == null || diff < best.second) best = d to diff
        }
        val (d, diff) = best ?: return null
        val alike = 1f - diff.toFloat() / (W * H)
        return if (alike >= 0.8f) d to alike else null
    }

    /**
     * The number printed in the band [x0]..[x1], [y0]..[y1]: its figures - shapes [minH] to [maxH]
     * pixels tall - read left to right. (value, middle x), or null when none are figures.
     */
    /**
     * Whether the shape in ([l], [t])..([r], [b]) is a box drawn round something - each of its four
     * sides a line of ink - and if so, the page with only what is inside it, the sides cleared.
     */
    private fun framed(ink: Ink, l: Int, t: Int, r: Int, b: Int): Ink? {
        val w = r - l + 1; val h = b - t + 1
        fun row(y: Int) = (l..r).count { ink[it, y] } >= w * 0.75f
        fun col(x: Int) = (t..b).count { ink[x, it] } >= h * 0.75f
        val reach = max(2, min(w, h) / 6)
        val top = (t until t + reach).lastOrNull { row(it) } ?: return null
        val bottom = (b downTo b - reach + 1).lastOrNull { row(it) } ?: return null
        val left = (l until l + reach).lastOrNull { col(it) } ?: return null
        val right = (r downTo r - reach + 1).lastOrNull { col(it) } ?: return null
        if (bottom - top < h / 2 || right - left < w / 2) return null
        val out = Ink(ink.width, ink.height)
        for (y in top + 1 until bottom) for (x in left + 1 until right) if (ink[x, y]) out[x, y] = true
        return out
    }

    fun number(ink: Ink, x0: Int, x1: Int, y0: Int, y1: Int, minH: Int, maxH: Int, bottomFrom: Int = y0): Pair<Int, Int>? {
        val seen = HashSet<Long>()
        val figures = ArrayList<Triple<Int, Int, Int>>()   // digit, middle x, bottom
        for (y in y0..y1) for (x in x0..x1) {
            if (!ink[x, y]) continue
            val key = x.toLong() shl 32 or (y.toLong() and 0xffffffffL)
            if (key in seen) continue
            // This shape: its extent, eight ways connected, not far outside the band.
            val stack = ArrayDeque<Long>(); stack += key
            var l = x; var r = x; var t = y; var b = y; var n = 0
            while (stack.isNotEmpty() && n < 20_000) {
                val k = stack.removeLast()
                if (!seen.add(k)) continue
                val px = (k shr 32).toInt(); val py = k.toInt()
                if (!ink[px, py]) continue
                n++; l = min(l, px); r = max(r, px); t = min(t, py); b = max(b, py)
                for (dy in -1..1) for (dx in -1..1) if (dx != 0 || dy != 0) {
                    val nx = px + dx; val ny = py + dy
                    if (nx < x0 - maxH || nx > x1 + maxH || ny < y0 - maxH || ny > y1 + maxH) continue
                    stack += nx.toLong() shl 32 or (ny.toLong() and 0xffffffffL)
                }
            }
            val h = b - t + 1; val w = r - l + 1
            // A number in a box (a rehearsal number), its figures touching the box on a scan: the
            // box's sides taken away and what is inside read.
            if (h in minH..maxH * 2 && w in minH..maxH * 5 && (l + r) / 2 in x0..x1) framed(ink, l, t, r, b)?.let { inside ->
                number(inside, l, r, t, b, minH * 2 / 3, maxH)?.let { return it }
            }
            // A figure: a digit's height, over the place asked about, ending near enough the line asked.
            if (h < minH || h > maxH || w > h * 1.3f || (l + r) / 2 !in x0..x1 || b < bottomFrom) continue
            val m = mask(ink, l, t, r, b) ?: continue
            val (d, _) = read(m) ?: continue
            figures += Triple(d, (l + r) / 2, b)
        }
        if (figures.isEmpty()) return null
        // The figures of one number stand on one line, side by side: those nearest the line asked.
        val lowest = figures.maxOf { it.third }
        val one = figures.filter { lowest - it.third <= maxH / 4 }.sortedBy { it.second }
        // A bar number or a rest's count is a figure or three, side by side: more is a line of text.
        if (one.size > 3 || one.zipWithNext().any { (a, b) -> b.second - a.second > maxH * 1.2f }) return null
        val value = one.fold(0) { v, (d, _, _) -> v * 10 + d }
        return value to (one.first().second + one.last().second) / 2
    }
}
