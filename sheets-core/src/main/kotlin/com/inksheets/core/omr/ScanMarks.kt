package com.inksheets.core.omr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The small marks of a heavy scan read from the picture's ink by their geometry, where the mark
 * reader's net never sees them whole (a 1-bit plate print resampled: a staccato dot is a sliver a few
 * pixels across; a slur's ends merge into heads and stems; a hairpin is two hair-thin lines):
 * staccato dots over or under a note's head, slurs (arcs followed round the heads they touch, pieces
 * joined), hairpins (a wedge of two thin lines, one end open). Used only on scans ([Recognizer.readMarks]).
 */
internal class ScanMarks(private val ink: Ink, private val staves: List<Recognizer.Staff>, private val measures: List<Measure>,
                         private val covered: (Int, Int, Int) -> Boolean) {

    private val DEBUG = System.getProperty("inksheets.omr.scanmarks") != null

    class Arc(val mi: Int, val x0: Float, val x1: Float, val above: Boolean)
    class Wedge(val mi: Int, val kind: String, val x0: Float, val x1: Float, val above: Boolean)

    /** Pixels (x, y interleaved) of the shapes in a window, ink within [reach] pixels taken as one. */
    private fun components(x0: Int, y0: Int, x1: Int, y1: Int, reach: Int, usable: (Int, Int) -> Boolean): List<IntArray> {
        val w = x1 - x0 + 1; val h = y1 - y0 + 1
        if (w <= 0 || h <= 0) return emptyList()
        val ok = BooleanArray(w * h); val seen = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) ok[y * w + x] = usable(x0 + x, y0 + y)
        val out = ArrayList<IntArray>()
        val stack = IntArray(w * h)
        for (s in 0 until w * h) {
            if (!ok[s] || seen[s]) continue
            var sp = 0; stack[sp++] = s; seen[s] = true
            val px = ArrayList<Int>()
            while (sp > 0) {
                val c = stack[--sp]; val cx = c % w; val cy = c / w
                px += cx + x0; px += cy + y0
                for (dy in -reach..reach) for (dx in -reach..reach) {
                    val nx = cx + dx; val ny = cy + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val n = ny * w + nx
                    if (ok[n] && !seen[n]) { seen[n] = true; stack[sp++] = n }
                }
            }
            out += px.toIntArray()
        }
        return out
    }

    private fun box(px: IntArray): IntArray {
        var l = Int.MAX_VALUE; var r = Int.MIN_VALUE; var t = Int.MAX_VALUE; var b = Int.MIN_VALUE
        for (i in px.indices step 2) { l = min(l, px[i]); r = max(r, px[i]); t = min(t, px[i + 1]); b = max(b, px[i + 1]) }
        return intArrayOf(l, t, r, b)
    }

    // ---- staccato dots ----------------------------------------------------------------------

    /** (measure index, event index) of each note a dot stands over or under. */
    fun staccatos(): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for ((mi, m) in measures.withIndex()) {
            if (m.bars > 1) continue
            val s = staves.getOrNull(m.staff) ?: continue
            val sp = m.space
            for ((ei, e) in m.events.withIndex()) {
                if (e !is Note || e.steps.isEmpty()) continue
                val cx = e.x + sp * 0.6f
                val yHi = s.y(e.steps.min(), e.x.toInt()); val yLo = s.y(e.steps.max(), e.x.toInt())
                for (above in listOf(true, false)) {
                    // On the side the stem is not.
                    if (above && e.stemUp == true && e.duration.base >= 2 || !above && e.stemUp == false && e.duration.base >= 2) continue
                    val a = if (above) yHi - sp * 3.4f else yLo + sp * 0.8f
                    val b = if (above) yHi - sp * 0.8f else yLo + sp * 3.4f
                    val rx0 = (cx - sp * 2.2f).toInt(); val rx1 = (cx + sp * 2.2f).toInt()
                    val ry0 = (a - sp * 1.2f).toInt(); val ry1 = (b + sp * 1.2f).toInt()
                    val found = components(rx0, ry0, rx1, ry1, 2) { x, y -> ink[x, y] }.any { px ->
                        val bx = box(px)
                        if (bx[0] <= rx0 || bx[2] >= rx1 || bx[1] <= ry0 || bx[3] >= ry1) return@any false
                        val w = bx[2] - bx[0] + 1; val h = bx[3] - bx[1] + 1
                        val least = max(2f, sp * 0.1f)
                        if (w < least || h < least || w > sp * 0.6f || h > sp * 0.6f) return@any false
                        if (px.size / 2 < w * h * 0.4f || w.toFloat() / h !in 0.4f..2.5f) return@any false
                        val dx = (bx[0] + bx[2]) / 2f; val dy = (bx[1] + bx[3]) / 2f
                        if (abs(dx - cx) > sp * 0.6f || dy < a || dy > b) return@any false
                        // This note's, not the next one's.
                        m.events.none { o -> o !== e && o is Note && abs(o.x + sp * 0.6f - dx) < abs(cx - dx) }
                    }
                    if (found) { out += mi to ei; break }
                }
            }
        }
        return out
    }

    // ---- slurs --------------------------------------------------------------------------------

    private class Piece(val cols: java.util.TreeMap<Int, Pair<Int, Int>>)   // x -> (min y, max y)

    private fun piece(px: IntArray): Piece {
        val t = java.util.TreeMap<Int, Pair<Int, Int>>()
        for (i in px.indices step 2) { val x = px[i]; val y = px[i + 1]; val o = t[x]; t[x] = if (o == null) y to y else min(o.first, y) to max(o.second, y) }
        return Piece(t)
    }

    private fun Piece.x0() = cols.firstKey()
    private fun Piece.x1() = cols.lastKey()
    private fun Piece.mid(x: Int): Float { val c = cols[x]!!; return (c.first + c.second) / 2f }
    private fun Piece.slope(atEnd: Boolean): Float {
        val xs = cols.keys.toList(); val n = max(2, xs.size / 4)
        val a = if (atEnd) xs.takeLast(n) else xs.take(n)
        val b = if (atEnd) xs.dropLast(n).takeLast(n) else xs.drop(n).take(n)
        if (b.isEmpty() || a.isEmpty()) return 0f
        val ya = a.map { mid(it) }.average().toFloat(); val yb = b.map { mid(it) }.average().toFloat()
        val dx = a.average().toFloat() - b.average().toFloat()
        return if (abs(dx) < 1f) 0f else (ya - yb) / dx
    }

    /** Slurs of each staff: thin arcs over (or under) the notes, their pieces joined across heads and stems. */
    fun slurs(): List<Arc> {
        val out = ArrayList<Arc>()
        for ((key, group) in groups()) {
            val s = staves.getOrNull(key.second) ?: continue
            val first = group.first(); val sp = measures[first].space
            val lo = group.minOf { measures[it].box.left }; val hi = group.maxOf { measures[it].box.right }
            val mid = (lo + hi) / 2
            val owner = { x: Int -> group.firstOrNull { x >= measures[it].box.left && x < measures[it].box.right } }
            val notes = group.flatMap { gi -> measures[gi].events.filterIsInstance<Note>().map { it.x + sp * 0.6f } }.sorted()
            for (above in listOf(true, false)) {
                val top = s.lineY(0, mid); val bottom = s.lineY(4, mid)
                val y0 = if (above) (top - sp * 5f).toInt() else (bottom + sp * 0.35f).toInt()
                val y1 = if (above) (top - sp * 0.35f).toInt() else (bottom + sp * 5f).toInt()
                val comps = components(lo, y0, hi, y1, 2) { x, y ->
                    ink[x, y] && (owner(x)?.let { !covered(it, x, y) } ?: true)
                }
                var pieces = comps.map { piece(it) }.filter { p ->
                    val w = p.x1() - p.x0() + 1
                    val thick = p.cols.values.map { it.second - it.first + 1 }.average()
                    val ys0 = p.cols.values.minOf { it.first }; val ys1 = p.cols.values.maxOf { it.second }
                    w >= sp * 0.5f && thick <= sp * 0.5f && (ys1 - ys0 + 1) <= w * 0.9f + sp * 0.3f
                }.sortedBy { it.x0() }.toMutableList()
                // Pieces of one arc, joined: each ends where the next begins, on the same curve.
                var merged = true
                while (merged) {
                    merged = false
                    loop@ for (i in pieces.indices) for (j in pieces.indices) {
                        if (i == j) continue
                        val a = pieces[i]; val b = pieces[j]
                        val gap = b.x0() - a.x1()
                        if (gap < -sp * 0.5f || gap > sp * 1.8f || a.x0() > b.x0()) continue
                        if (abs(a.mid(a.x1()) - b.mid(b.x0())) > sp * 0.6f) continue
                        val wa = a.x1() - a.x0() + 1; val wb = b.x1() - b.x0() + 1
                        if (wa >= sp * 0.8f && wb >= sp * 0.8f && abs(a.slope(true) - b.slope(false)) > 0.4f) continue
                        val c = java.util.TreeMap<Int, Pair<Int, Int>>(a.cols)
                        for ((x, v) in b.cols) { val o = c[x]; c[x] = if (o == null) v else min(o.first, v.first) to max(o.second, v.second) }
                        pieces = pieces.filterIndexed { k, _ -> k != i && k != j }.toMutableList().also { it += Piece(c) }
                        merged = true; break@loop
                    }
                }
                if (DEBUG) println("  ARCS staff ${key.second} above=$above band $y0..$y1 comps ${comps.size} pieces ${pieces.map { "${it.x0()}..${it.x1()}" }}")
                for (p in pieces) {
                    val x0 = p.x0(); val x1 = p.x1(); val w = x1 - x0 + 1
                    if (w < sp * 1.6f || w > sp * 16f) continue
                    val ys = p.cols.keys.map { p.mid(it) }
                    val n = max(1, ys.size / 6)
                    val ends = (ys.take(n).average() + ys.takeLast(n).average()).toFloat() / 2f
                    val middle = ys.drop(ys.size * 2 / 5).take(max(1, ys.size / 5)).average().toFloat()
                    val bow = if (above) ends - middle else middle - ends
                    if (bow < max(sp * 0.15f, w * 0.05f) || bow > w * 0.45f) continue
                    val under = notes.filter { it >= x0 - sp * 0.7f && it <= x1 + sp * 0.7f }
                    if (under.size < 2) continue
                    val mi = owner(((x0 + x1) / 2)) ?: first
                    out += Arc(mi, under.first(), under.last(), above)
                }
            }
        }
        return out
    }

    // ---- hairpins -------------------------------------------------------------------------------

    fun hairpins(): List<Wedge> {
        val out = ArrayList<Wedge>()
        for ((key, group) in groups()) {
            val s = staves.getOrNull(key.second) ?: continue
            val sp = measures[group.first()].space
            val lo = group.minOf { measures[it].box.left }; val hi = group.maxOf { measures[it].box.right }
            val mid = (lo + hi) / 2
            val owner = { x: Int -> group.firstOrNull { x >= measures[it].box.left && x < measures[it].box.right } }
            for (above in listOf(false, true)) {
                val top = s.lineY(0, mid); val bottom = s.lineY(4, mid)
                val y0 = if (above) (top - sp * 4.2f).toInt() else (bottom + sp * 0.4f).toInt()
                val y1 = if (above) (top - sp * 0.4f).toInt() else (bottom + sp * 4.2f).toInt()
                val comps = components(lo, y0, hi, y1, 2) { x, y -> ink[x, y] && (owner(x)?.let { !covered(it, x, y) } ?: true) }
                for (px in comps) {
                    val bx = box(px)
                    if (bx[2] - bx[0] + 1 < sp * 2.5f) continue
                    // Columns: ink count, top, bottom.
                    val n = bx[2] - bx[0] + 1
                    val cnt = IntArray(n); val mn = IntArray(n) { Int.MAX_VALUE }; val mx = IntArray(n) { Int.MIN_VALUE }
                    for (i in px.indices step 2) { val c = px[i] - bx[0]; cnt[c]++; mn[c] = min(mn[c], px[i + 1]); mx[c] = max(mx[c], px[i + 1]) }
                    // The longest run of thin columns (a letter beside it is heavier).
                    var best = 0 to 0; var start = -1
                    for (c in 0..n) {
                        val thin = c < n && cnt[c] > 0 && cnt[c] <= sp * 0.5f
                        if (thin && start < 0) start = c
                        if (!thin && start >= 0) { if (c - start > best.second - best.first) best = start to c; start = -1 }
                    }
                    val len = best.second - best.first
                    if (len < sp * 2.5f) continue
                    val cols = (best.first until best.second).filter { cnt[it] > 0 }
                    if (cols.size < len * 0.85f) continue
                    val e = cols.map { (mx[it] - mn[it] + 1).toFloat() }
                    val k = max(2, cols.size / 8)
                    val left = e.take(k).average().toFloat(); val right = e.takeLast(k).average().toFloat()
                    val open = max(left, right); val point = min(left, right)
                    if (open - point < sp * 0.45f || point > sp * 0.45f || open > sp * 3.2f) continue
                    // Spreading steadily: the extent follows the line from one end to the other.
                    val xs = cols.map { it.toFloat() }
                    val mxm = xs.average().toFloat(); val me = e.average().toFloat()
                    var sxy = 0f; var sxx = 0f; var syy = 0f
                    for (i in xs.indices) { sxy += (xs[i] - mxm) * (e[i] - me); sxx += (xs[i] - mxm) * (xs[i] - mxm); syy += (e[i] - me) * (e[i] - me) }
                    if (sxx == 0f || syy == 0f || abs(sxy) / kotlin.math.sqrt(sxx * syy) < 0.9f) continue
                    // Two strokes at the open end, a gap between.
                    val openCols = if (right > left) cols.takeLast(k) else cols.take(k)
                    if (openCols.any { cnt[it] > (mx[it] - mn[it] + 1) * 0.7f + 1 }) continue
                    val kind = if (right > left) "cresc" else "dim"
                    val xa = (bx[0] + cols.first()).toFloat(); val xb = (bx[0] + cols.last()).toFloat()
                    out += Wedge(owner((xa + xb).toInt() / 2) ?: group.first(), kind, xa, xb, above)
                }
            }
        }
        return out
    }

    /** Measures of each staff of each page (bars one by one), left to right: (page, staff) to measure indices. */
    private fun groups(): Map<Pair<Int, Int>, List<Int>> =
        measures.indices.filter { measures[it].bars <= 1 }.groupBy { measures[it].page to measures[it].staff }
            .mapValues { (_, v) -> v.sortedBy { measures[it].box.left } }
}
