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
internal class ScanMarks(private val ink: Ink, private val grey: IntArray, private val staves: List<Recognizer.Staff>, private val measures: List<Measure>,
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

    // ---- dynamics -------------------------------------------------------------------------------

    class Dyn(val mi: Int, val text: String, val x0: Float, val x1: Float, val above: Boolean)

    /**
     * Dynamics' letters (p, f, m...) found as shapes of ink under or over each staff and asked of the mark
     * reader, whole - where the bar's kept shapes have them cut by a stem's box or run into a fingering.
     */
    fun dynamics(): List<Dyn> {
        val out = ArrayList<Dyn>()
        val sets = setOf("sf", "sfz", "sffz", "fz", "rfz", "rf", "sfp", "fp")
        for ((key, group) in groups()) {
            val s = staves.getOrNull(key.second) ?: continue
            val sp = measures[group.first()].space
            val lo = group.minOf { measures[it].box.left }; val hi = group.maxOf { measures[it].box.right }
            val mid = (lo + hi) / 2
            val owner = { x: Int -> group.firstOrNull { x >= measures[it].box.left && x < measures[it].box.right } }
            for (above in listOf(false, true)) {
                val top = s.lineY(0, mid); val bottom = s.lineY(4, mid)
                val y0 = if (above) (top - sp * 4.5f).toInt() else (bottom + sp * 0.3f).toInt()
                val y1 = if (above) (top - sp * 0.3f).toInt() else (bottom + sp * 4.5f).toInt()
                val comps = components(lo, y0, hi, y1, 2) { x, y -> ink[x, y] }.map { box(it) }
                class Letter(val bx: IntArray, val c: Char)
                val letters = ArrayList<Letter>()
                for (bx in comps) {
                    val w = (bx[2] - bx[0] + 1) / sp; val h = (bx[3] - bx[1] + 1) / sp
                    if (w !in 0.8f..2.8f || h !in 1.2f..3.2f) continue
                    val cx = (bx[0] + bx[2]) / 2
                    val (label, p) = MarkReader.read(grey, ink.width, ink.height, bx, sp, s.lineY(0, cx.coerceIn(s.left, s.right))) ?: continue
                    if (p < 0.9f || !label.startsWith("dyn_")) continue
                    letters += Letter(bx, label.removePrefix("dyn_")[0])
                }
                letters.sortBy { it.bx[0] }
                var i = 0
                while (i < letters.size) {
                    var j = i
                    while (j + 1 < letters.size && letters[j + 1].bx[0] - letters[j].bx[2] < sp * 0.7f && abs(letters[j + 1].bx[1] + letters[j + 1].bx[3] - letters[i].bx[1] - letters[i].bx[3]) < sp * 1.6f) j++
                    val word = (i..j).joinToString("") { letters[it].c.toString() }
                    val x0 = letters[i].bx[0]; val x1 = letters[j].bx[2]
                    val y0b = (i..j).minOf { letters[it].bx[1] }; val y1b = (i..j).maxOf { letters[it].bx[3] }
                    // Run on into other letters (a word's): another shape a hair away.
                    val runOn = comps.any { o -> (o[2] - o[0] + 1) * (o[3] - o[1] + 1) >= 6 && !(o[0] >= x0 && o[2] <= x1) &&
                        o[1] <= y1b && o[3] >= y0b && (o[0] in x1 + 1..x1 + (sp * 0.35f).toInt() || o[2] in x0 - (sp * 0.35f).toInt() until x0) && letters.none { it.bx.contentEquals(o) } }
                    if (DEBUG) println("  DYN? staff ${key.second} '$word' x $x0..$x1 y $y0b..$y1b runOn $runOn")
                    val cyL = (y0b + y1b) / 2f
                    val here = if (above) abs(cyL - s.lineY(0, mid)) else abs(cyL - s.lineY(4, mid))
                    val nearer = staves.indices.any { o -> o != key.second && staves[o].let { st -> minOf(abs(cyL - st.lineY(0, mid)), abs(cyL - st.lineY(4, mid))) } < here }
                    if (!runOn && !nearer && (word in Performance.LEVELS || word in sets)) out += Dyn(owner((x0 + x1) / 2) ?: group.first(), word, x0.toFloat(), x1.toFloat(), above)
                    i = j + 1
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
            val thinMost = sp * 0.45f
            for (above in listOf(false, true)) {
                val top = s.lineY(0, mid); val bottom = s.lineY(4, mid)
                val y0 = if (above) (top - sp * 4.2f).toInt() else (bottom + sp * 0.4f).toInt()
                val y1 = if (above) (top - sp * 0.4f).toInt() else (bottom + sp * 4.2f).toInt()
                // Each column's runs of ink in the band (a wedge's column: one or two thin runs).
                val n = hi - lo + 1
                val top1 = IntArray(n) { -1 }; val bot1 = IntArray(n) { -1 }; val runs = IntArray(n); val wedgy = BooleanArray(n); val blank = BooleanArray(n)
                for (c in 0 until n) {
                    val x = lo + c
                    var r = 0; var inRun = false; var runStart = 0; var ok = true; var first = -1; var last = -1; var gap = 0
                    for (y in y0..y1) {
                        val v = ink[x, y] && (owner(x)?.let { !covered(it, x, y) } ?: true)
                        if (v) {
                            if (!inRun) { if (!(r > 0 && gap <= 1)) r++; runStart = y; inRun = true }
                            if (first < 0) first = y
                            last = y; gap = 0
                            if (y - runStart + 1 > thinMost) ok = false
                        } else { inRun = false; gap++ }
                    }
                    runs[c] = r; top1[c] = first; bot1[c] = last
                    blank[c] = r == 0
                    wedgy[c] = r in 1..2 && ok
                }
                // Stretches of wedge-like columns (hair-line breaks of a few columns allowed), moving smoothly.
                var c = 0
                while (c < n) {
                    if (!wedgy[c]) { c++; continue }
                    var e = c; var lastGood = c
                    while (e + 1 < n) {
                        if (wedgy[e + 1] && abs((top1[e + 1] + bot1[e + 1]) / 2f - (top1[lastGood] + bot1[lastGood]) / 2f) <= 3f + (e + 1 - lastGood) && (e + 1 - lastGood) <= 4) { e++; lastGood = e }
                        else if (blank[e + 1] && e + 1 - lastGood <= 3) e++
                        else break
                    }
                    val cols = (c..lastGood).filter { wedgy[it] }
                    val from = c; val to = lastGood; c = lastGood + 1
                    if (to - from + 1 < sp * 2.5f || cols.size < (to - from + 1) * 0.7f) continue
                    val ext = cols.map { (bot1[it] - top1[it] + 1).toFloat() }
                    val k = max(2, cols.size / 8)
                    val left = ext.take(k).average().toFloat(); val right = ext.takeLast(k).average().toFloat()
                    val open = max(left, right); val point = min(left, right)
                    if (DEBUG) println("  WEDGE? staff ${key.second} above=$above x ${lo + from}..${lo + to} y ${top1[cols[0]]} sp ${sp.toInt()} left ${"%.1f".format(left)} right ${"%.1f".format(right)}")
                    if (open - point < sp * 0.45f || point > sp * 0.9f || open > sp * 3.2f) continue
                    val xs = cols.map { it.toFloat() }
                    val mxm = xs.average().toFloat(); val me = ext.average().toFloat()
                    var sxy = 0f; var sxx = 0f; var syy = 0f
                    for (i in xs.indices) { sxy += (xs[i] - mxm) * (ext[i] - me); sxx += (xs[i] - mxm) * (xs[i] - mxm); syy += (ext[i] - me) * (ext[i] - me) }
                    if (sxx == 0f || syy == 0f || abs(sxy) / kotlin.math.sqrt(sxx * syy) < 0.93f) continue
                    // Two strokes at the open end.
                    val openCols = if (right > left) cols.takeLast(k) else cols.take(k)
                    if (openCols.count { runs[it] == 2 } < openCols.size * 0.6f) continue
                    val kind = if (right > left) "cresc" else "dim"
                    val xa = (lo + cols.first()).toFloat(); val xb = (lo + cols.last()).toFloat()
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
