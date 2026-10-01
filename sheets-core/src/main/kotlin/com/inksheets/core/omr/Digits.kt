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

    /**
     * The trained digit reader (resources omr/digitnet.bin, made by train/digits.py from the
     * library's printed digits, clean and scanned): a figure's grid, its height in staff spaces
     * and its width over its height, and whether it is in the staff (a time signature's) - which
     * digit, or none. Judged on songs it never saw: 89% of digits right where the masks got 65%,
     * and 2% of other marks taken for digits where the masks took 28%.
     */
    private class Trained(val inputs: Int, val hidden: Int, val aw: FloatArray, val ab: FloatArray, val bw: FloatArray, val bb: FloatArray) {
        /** (digit 0-9 or 10 for none, its odds). */
        fun classify(x: FloatArray): Pair<Int, Float> {
            val h = FloatArray(hidden)
            for (j in 0 until hidden) { var v = ab[j]; val o = j * inputs; for (i in 0 until inputs) v += aw[o + i] * x[i]; h[j] = if (v > 0f) v else 0f }
            val out = FloatArray(11)
            for (c in 0 until 11) { var v = bb[c]; val o = c * hidden; for (j in 0 until hidden) v += bw[o + j] * h[j]; out[c] = v }
            val top = out.max(); var sum = 0f; for (c in 0 until 11) sum += kotlin.math.exp(out[c] - top)
            val best = out.indices.maxBy { out[it] }
            return best to 1f / sum
        }
    }

    private val trained: Trained? by lazy {
        runCatching {
            val bytes = Digits::class.java.getResourceAsStream("/omr/digitnet.bin")?.use { it.readBytes() } ?: return@lazy null
            val b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val inputs = b.int; val hidden = b.int
            fun floats(n: Int) = FloatArray(n) { b.float }
            Trained(inputs, hidden, floats(hidden * inputs), floats(hidden), floats(11 * hidden), floats(11))
        }.getOrNull()
    }

    /** The trained reader's digit for figure [m], [heightSp] staff spaces tall, [aspect] wide over tall: null when it says none. */
    fun readTrained(m: BooleanArray, heightSp: Float, aspect: Float, inStaff: Boolean = false): Pair<Int, Float>? {
        val t = trained ?: return null
        if (t.inputs != W * H + 3) return null
        val x = FloatArray(t.inputs)
        for (i in m.indices) x[i] = if (m[i]) 1f else 0f
        x[W * H] = heightSp / 3f; x[W * H + 1] = aspect; x[W * H + 2] = if (inStaff) 1f else 0f
        val (d, p) = t.classify(x)
        return if (d == 10 || p < 0.5f) null else d to p
    }

    /** Whether the trained reader is shipped (else the masks are all there is). */
    val hasTrained: Boolean get() = trained != null

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

    fun number(ink: Ink, x0: Int, x1: Int, y0: Int, y1: Int, minH: Int, maxH: Int, bottomFrom: Int = y0, space: Float = 0f,
               /** Set in the music font, as a time signature's digits are (a multi-bar rest's count): read as those. */
               musicFont: Boolean = false): Pair<Int, Int>? {
        // Each shape in the band, followed no further than a figure's height outside it.
        val rx0 = max(0, x0 - maxH); val ry0 = max(0, y0 - maxH)
        val region = Outline.Region(rx0, ry0, min(ink.width, x1 + maxH + 1) - rx0, min(ink.height, y1 + maxH + 1) - ry0)
        val figures = ArrayList<Triple<Int, Int, Int>>()   // digit, middle x, bottom
        for (y in y0..y1) for (x in x0..x1) {
            if (!ink[x, y] || !region.inside(x, y) || region.seen[region.index(x, y)]) continue
            val px = Outline.component(ink, x, y, region, 20_000)
            if (px.isEmpty()) continue
            var l = Int.MAX_VALUE; var r = Int.MIN_VALUE; var t = Int.MAX_VALUE; var b = Int.MIN_VALUE
            for (i in px.indices step 2) { l = min(l, px[i]); r = max(r, px[i]); t = min(t, px[i + 1]); b = max(b, px[i + 1]) }
            val h = b - t + 1; val w = r - l + 1
            // A number in a box (a rehearsal number), its figures touching the box on a scan: the
            // box's sides taken away and what is inside read.
            if (h in minH..maxH * 2 && w in minH..maxH * 5 && (l + r) / 2 in x0..x1) framed(ink, l, t, r, b)?.let { inside ->
                number(inside, l, r, t, b, minH * 2 / 3, maxH, space = space, musicFont = musicFont)?.let { return it }
            }
            // A figure: a digit's height, over the place asked about, ending near enough the line asked.
            if (h < minH || h > maxH || w > h * 1.3f || (l + r) / 2 !in x0..x1 || b < bottomFrom) continue
            val m = mask(ink, l, t, r, b) ?: continue
            // Read by the trained reader where the staff's size is known; else by the masks.
            // (Over a multi-bar rest a number is all but sure to be there: where the trained reader sees
            // none in a figure, the masks are asked too.)
            val (d, _) = (if (space > 0f && hasTrained) readTrained(m, h / space, w.toFloat() / h, inStaff = musicFont) ?: (if (musicFont) read(m) else null)
                else read(m)) ?: continue
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
