package com.inksheets.core.omr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reading printed music off a page picture (optical music recognition).
 *
 * In order: how thick the staff lines are and how far apart (from the ink's vertical runs); where
 * each staff's five lines run, traced in slices so a skewed scan still reads; the lines taken out,
 * leaving the symbols; barlines, which divide staves into measures; then in each measure the
 * noteheads (matched against the music font drawn at the page's own staff size, at every line and
 * space), their stems, beams, flags and dots, accidentals and rests, and at a staff's start its
 * clef, key and time. Pitches follow from the clef, key and accidentals; a measure whose notes do
 * not add up to its time is marked as doubtful.
 */
/** [adapt]: fit the notehead's size to each page, and take borderline heads that carry a stem (off only to measure against). */
class Recognizer(private val debug: Boolean = false, private val adapt: Boolean = true) {

    /** One staff: its five lines' heights at every x from [left] to [right]. */
    class Staff(val left: Int, val right: Int, val lines: Array<FloatArray>, val space: Float) {
        fun lineY(line: Int, x: Int): Float = lines[line][(x - left).coerceIn(0, right - left)]
        /** The height of [step] half-spaces below the top line at [x]. */
        fun y(step: Int, x: Int): Float {
            val top = lineY(0, x); val bottom = lineY(4, x)
            return top + step * (bottom - top) / 8f
        }
        val top: Int get() = lines[0].average().roundToInt()
        val bottom: Int get() = lines[4].average().roundToInt()
    }

    /** What was found on a page, for drawing over it: staves, barlines, heads. */
    class PageReading(val staves: List<Staff>, val barlines: List<List<Int>>, val measures: List<Measure>, val thickness: Int, val space: Float,
                      /** Heads found and then let go, with why: for finding out what the reader gets wrong. */
                      val dropped: List<Pair<Head, String>> = emptyList())

    // ---- measuring ---------------------------------------------------------------------------

    /** The staff lines' thickness and the distance between them, in pixels; null when no staff. */
    fun metrics(ink: Ink): Pair<Int, Float>? {
        val black = IntArray(64); val white = IntArray(256)
        for (x in 0 until ink.width step 3) {
            var y = 0
            while (y < ink.height) {
                val v = ink[x, y]
                var n = 0
                while (y < ink.height && ink[x, y] == v) { n++; y++ }
                if (v && n < black.size) black[n]++ else if (!v && n < white.size) white[n]++
            }
        }
        val t = (1 until black.size).maxByOrNull { black[it] } ?: return null
        val w = (2 until white.size).maxByOrNull { white[it] } ?: return null
        if (white[w] < 20) return null
        return t to (w + t).toFloat()
    }

    // ---- staves ------------------------------------------------------------------------------

    /** Five lines found in one slice of the page: its middle, and their heights there. */
    private class Group(val x: Int, val ys: FloatArray)

    fun staves(ink: Ink, t: Int, space: Float): List<Staff> {
        val slice = max(48, (space * 6).toInt())
        val groups = ArrayList<Group>()
        var sx = 0
        while (sx + slice / 2 < ink.width) {
            val x1 = min(ink.width, sx + slice)
            val rows = FloatArray(ink.height)
            for (y in 0 until ink.height) {
                var c = 0
                for (x in sx until x1) if (ink.bits[y * ink.width + x]) c++
                rows[y] = c.toFloat() / (x1 - sx)
            }
            // Line candidates: runs of rows mostly ink, no thicker than a line and a bit - and how
            // much of the slice each fills.
            val lines = ArrayList<Float>()
            val fills = ArrayList<Float>()
            var y = 0
            while (y < ink.height) {
                if (rows[y] > 0.55f) {
                    val y0 = y
                    var most = 0f
                    while (y < ink.height && rows[y] > 0.55f) { most = max(most, rows[y]); y++ }
                    if (y - y0 <= t * 2 + 2) { lines += (y0 + y - 1) / 2f; fills += most }
                } else y++
            }
            // Fives, evenly spaced. Where a passage high or low sets its ledger lines in a row, six
            // or more line up: the five that run fullest are the staff's, the ledger row only
            // under its notes.
            fun even(from: Int) = from + 4 < lines.size && (from + 1..from + 4).all { abs(lines[it] - lines[it - 1] - space) <= space * 0.3f }
            var i = 0
            while (i + 4 < lines.size) {
                if (!even(i)) { i++; continue }
                var best = i
                var k = i + 1
                while (even(k) && k <= i + 2) {
                    if ((k until k + 5).minOf { fills[it] } > (best until best + 5).minOf { fills[it] }) best = k
                    k++
                }
                groups += Group((sx + x1) / 2, lines.subList(best, best + 5).toFloatArray())
                i = best + 5
            }
            sx += slice
        }
        // Slices joined into staves: the same staff is where the lines were in the slice before.
        val tracks = ArrayList<ArrayList<Group>>()
        for (g in groups.sortedBy { it.x }) {
            val home = tracks.firstOrNull { tr -> tr.last().x < g.x && abs(tr.last().ys[0] - g.ys[0]) < space * 1.2f }
            if (home != null) home += g else tracks += arrayListOf(g)
        }
        val staves = ArrayList<Staff>()
        for (tr in tracks) {
            if (tr.size < 3) continue
            // Where the lines really start and end: follow them out from the slices found.
            val centreY = { x: Int, l: Int -> interpolate(tr, x, l) }
            fun onLines(x: Int): Boolean = (0..4).count { l -> val yy = centreY(x, l).roundToInt(); (-1..1).any { ink[x, yy + it] } } >= 4
            var left = tr.first().x
            var miss = 0
            while (left > 0 && miss < space) { left--; if (onLines(left)) miss = 0 else miss++ }
            left += miss
            var right = tr.last().x
            miss = 0
            while (right < ink.width - 1 && miss < space) { right++; if (onLines(right)) miss = 0 else miss++ }
            right -= miss
            if (right - left < space * 10) continue
            val lines = Array(5) { l -> FloatArray(right - left + 1) { i -> centreY(left + i, l) } }
            staves += refine(ink, Staff(left, right, lines, space), t)
        }
        return staves.sortedBy { it.top }
    }

    /**
     * [s] fitted to the page's lines closely: between the slices it was found in, a scan's lines
     * bow and a slice's reading can be pulled off by the ink round them, a third of a space or
     * more - enough to put a note on the wrong line, leave lines unerased and hide barlines. Every
     * few pixels along, where the five lines really are near where they were thought to be - thin
     * ink running along, not a head or a beam - and the shift most of them agree on, smoothed.
     */
    private fun refine(ink: Ink, s: Staff, t: Int): Staff {
        val step = 4
        val reach = max(2, (s.space * 0.4f).roundToInt())
        val xs = (s.left..s.right step step).toList()
        val shift = FloatArray(xs.size) { Float.NaN }
        for ((k, x) in xs.withIndex()) {
            val offs = ArrayList<Float>()
            for (l in 0..4) {
                val want = s.lineY(l, x)
                val y0 = want.roundToInt()
                var best: Float? = null
                var dy = -reach
                while (dy <= reach) {
                    val y = y0 + dy
                    if (!(x - 3..x + 3).all { ink[it, y] }) { dy++; continue }
                    // The run of ink up and down here: a line is thin.
                    var a = y; while (ink[x, a - 1] && y - a <= t + 2) a--
                    var b = y; while (ink[x, b + 1] && b - y <= t + 2) b++
                    if (b - a + 1 <= t + 2) {
                        val off = (a + b) / 2f - want
                        if (best == null || abs(off) < abs(best)) best = off
                    }
                    dy = b - y0 + 1
                }
                best?.let { offs += it }
            }
            if (offs.size < 3) continue
            offs.sort()
            val mid = offs[offs.size / 2]
            // The lines move together: most of them within a couple of pixels of the same shift.
            if (offs.count { abs(it - mid) <= 2f } >= 3) shift[k] = mid
        }
        if (shift.all { it.isNaN() }) return s
        // Gaps (barlines, chords, beams across every line) from their neighbours; then smoothed.
        val known = shift.indices.filter { !shift[it].isNaN() }
        val filled = FloatArray(shift.size) { i ->
            if (!shift[i].isNaN()) shift[i] else {
                val before = known.lastOrNull { it < i }; val after = known.firstOrNull { it > i }
                when {
                    before == null -> shift[after!!]
                    after == null -> shift[before]
                    else -> shift[before] + (shift[after] - shift[before]) * (i - before) / (after - before).toFloat()
                }
            }
        }
        val smooth = FloatArray(filled.size) { i ->
            val w = (max(0, i - 3)..min(filled.size - 1, i + 3)).map { filled[it] }.sorted()
            w[w.size / 2]
        }
        val lines = Array(5) { l ->
            FloatArray(s.right - s.left + 1) { i ->
                val f = i / step.toFloat()
                val k = f.toInt().coerceAtMost(smooth.size - 1)
                val k2 = (k + 1).coerceAtMost(smooth.size - 1)
                s.lines[l][i] + smooth[k] + (smooth[k2] - smooth[k]) * (f - k)
            }
        }
        return Staff(s.left, s.right, lines, s.space)
    }

    /** A line's height at [x], between the slices it was found in. */
    private fun interpolate(pts: List<Group>, x: Int, line: Int): Float {
        if (x <= pts.first().x) return pts.first().ys[line]
        if (x >= pts.last().x) return pts.last().ys[line]
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            if (x <= b.x) { val f = (x - a.x).toFloat() / (b.x - a.x); return a.ys[line] + f * (b.ys[line] - a.ys[line]) }
        }
        return pts.last().ys[line]
    }

    /** [ink] with the staff lines (and ledger lines) taken out, keeping whatever crosses them. */
    fun withoutLines(ink: Ink, staves: List<Staff>, t: Int): Ink {
        val out = ink.copy()
        for (s in staves) for (x in s.left..s.right) {
            for (l in 0..4) eraseThin(out, x, s.lineY(l, x).roundToInt(), t)
            // Ledger lines, up to five either side.
            for (k in 1..5) {
                eraseLedger(out, s, x, s.y(-2 * k, x).roundToInt(), t)
                eraseLedger(out, s, x, s.y(8 + 2 * k, x).roundToInt(), t)
            }
        }
        return out
    }

    private fun eraseThin(ink: Ink, x: Int, y0: Int, t: Int) {
        val y = (-1..1).map { y0 + it }.firstOrNull { ink[x, it] } ?: return
        var a = y; while (ink[x, a - 1]) a--
        var b = y; while (ink[x, b + 1]) b++
        if (b - a + 1 <= t + 2) for (yy in a..b) ink[x, yy] = false
    }

    private fun eraseLedger(ink: Ink, s: Staff, x: Int, y0: Int, t: Int) {
        val y = (-1..1).map { y0 + it }.firstOrNull { ink[x, it] } ?: return
        // A ledger line is a short horizontal stroke: ink both sides along it.
        if (!(ink[x - 1, y] || ink[x + 1, y])) return
        var run = 0
        var xx = x; while (ink[xx, y] && run < s.space * 4) { xx--; run++ }
        xx = x; while (ink[xx, y] && run < s.space * 4) { xx++; run++ }
        if (run > s.space * 3.8f) return
        eraseThin(ink, x, y, t)
    }

    // ---- barlines ----------------------------------------------------------------------------

    /**
     * The x of each barline on [s], left to right. [stems] are the stems of the notes found, which
     * run the height of a staff as a barline can, and [heads] the heads - a barline runs through none.
     */
    fun barlines(ink: Ink, clean: Ink, s: Staff, t: Int, stems: List<Int> = emptyList(), heads: List<Head> = emptyList()): List<Int> {
        val found = ArrayList<Int>()
        var x = s.left
        while (x <= s.right) {
            val top = s.lineY(0, x).roundToInt(); val bottom = s.lineY(4, x).roundToInt()
            // Ink all the way down the staff - a scan's line may have a speck missing.
            fun down(xx: Int): Boolean {
                var gaps = 0
                for (y in top..bottom) if (!ink[xx, y] && !ink[xx - 1, y] && !ink[xx + 1, y]) { if (++gaps > (bottom - top) / 25 + 1) return false }
                return true
            }
            if (!down(x)) { x++; continue }
            // As wide as the run of such columns.
            var x2 = x
            while (x2 + 1 <= s.right && (top..bottom).count { ink[x2 + 1, it] } >= (bottom - top) * 0.95) x2++
            val width = x2 - x + 1
            val centre = (x + x2) / 2
            // Not a stem: a stem goes on past the staff, or has a head at its end.
            var above = 0; while (ink[centre, top - above - 1]) above++
            var below = 0; while (ink[centre, bottom + below + 1]) below++
            val thin = width <= max(3, (s.space * 0.6f).toInt())
            // A stem touches its head at one of its ends: ink right against it, a head's height.
            val runTop = top - above; val runBottom = bottom + below
            fun touching(side: Int, yEnd: Int): Boolean {
                // A head-sized patch of ink just beyond the line's own (perhaps soft) edge.
                val edge = if (side < 0) x - 2 else x2 + 2
                val w = (s.space * 0.6f).toInt().coerceAtLeast(3)
                val h = (s.space * 0.4f).toInt()
                // The stem's ink runs on through its head, so the head may be just short of the end.
                return listOf(-0.5f, 0f, 0.5f).any { off ->
                    val yc = yEnd + (off * s.space).toInt()
                    var c = 0; var n = 0
                    for (y in yc - h..yc + h) for (k in 0 until w) { n++; if (clean[edge + side * k, y]) c++ }
                    c > n * 0.5f
                }
            }
            val isStem = stems.any { abs(it - centre) <= t + 2 }
            // Through a head's middle half: a head found a few pixels off, or a wide whole note, may reach a barline at its edge.
            val throughHead = heads.any { h -> val w = tpl(h.kind, s.space).ink.width; centre in h.x + w / 4..h.x + 3 * w / 4 }
            val headNear = isStem || throughHead || (stems.isEmpty() && listOf(runTop, runBottom).any { yEnd -> touching(-1, yEnd) || touching(1, yEnd) })
            val staysOnStaff = above < s.space * 0.6f && below < s.space * 0.6f
            val reachesAnotherStaff = above > s.space * 3 || below > s.space * 3
            if (debug) println("bar? x=$centre w=$width above=$above below=$below thin=$thin head=$headNear")
            if (thin && (staysOnStaff || reachesAnotherStaff) && !headNear) {
                if (found.isEmpty() || centre - found.last() > s.space * 1.5f) found += centre
                else found[found.size - 1] = centre   // a double or final barline: its last stroke
            }
            x = x2 + 1
        }
        return found
    }

    private fun dense(ink: Ink, x: Int, y: Int, w: Int, h: Int): Boolean {
        var c = 0
        for (yy in y until y + h) for (xx in x until x + w) if (ink[xx, yy]) c++
        return c > w * h * 0.5f
    }

    // ---- matching symbols --------------------------------------------------------------------

    /**
     * How well [name] sits with its origin at ([x], [y]): the share of its ink found, less the
     * share of ink where it has none - 1 perfect, 0 or below nothing like it.
     */
    /**
     * The page with its thin strokes worn away (see [Ink.opened]): filled heads are matched here, so a
     * tie, slur or accent touching a head costs it nothing, and none of them can pass for a head.
     */
    private var solid: Ink? = null

    /** The filled notehead fitted to this page (see fitHeads); null for the font's own. */
    private var fitted: MusicGlyphs.Template? = null

    /** The template for [name]: the page's size of filled head once fitted, else the music font's. */
    private fun tpl(name: String, space: Float): MusicGlyphs.Template =
        if (name == "noteheadBlack") fitted ?: MusicGlyphs.template(name, space) else MusicGlyphs.template(name, space)

    /**
     * Fit the filled notehead to this page: engravers and scans print heads a little larger and
     * bolder than the music font's, so the font's head is tried larger too, and the size that
     * finds the most heads surely on the first staves is used for the whole page.
     */
    private fun fitHeads(clean: Ink, staves: List<Staff>, lines: Ink) {
        fitted = null
        if (staves.isEmpty()) return
        val sp = staves.first().space
        rings.remove("noteheadBlack" to (sp * 10).toInt())
        val sample = staves.take(4)
        var best: MusicGlyphs.Template? = null; var most = 0
        for (scale in listOf(1f, 1.12f, 1.25f)) {
            fitted = MusicGlyphs.template("noteheadBlack", sp * scale)
            val found = sample.sumOf { s -> heads(clean, s, s.left, s.right, lines, filledOnly = true).count { it.score > 0.8f && it.step in 0..8 } }
            if (found > most) { most = found; best = fitted }
        }
        fitted = if (best === MusicGlyphs.template("noteheadBlack", sp)) null else best
        if (debug) println("head size: ${fitted?.ink?.width ?: "font's"} ($most sure heads)")
    }
    fun score(ink: Ink, name: String, space: Float, x: Int, y: Int): Float = match(ink, tpl(name, space), x, y)

    /** [score] against the music font's own [name], whatever size of head the page was fitted to. */
    private fun fontScore(ink: Ink, name: String, space: Float, x: Int, y: Int): Float = match(ink, MusicGlyphs.template(name, space), x, y)

    private fun match(ink: Ink, tp: MusicGlyphs.Template, x: Int, y: Int): Float {
        val x0 = x - tp.ox; val y0 = y - tp.oy
        var hit = 0; var stray = 0; var empty = 0
        val w = tp.ink.width; val h = tp.ink.height
        for (j in 0 until h) {
            val row = (y0 + j)
            if (row < 0 || row >= ink.height) continue
            val base = row * ink.width
            for (i in 0 until w) {
                val px = x0 + i
                if (px < 0 || px >= ink.width) continue
                val want = tp.ink.bits[j * w + i]
                val got = ink.bits[base + px]
                if (want) { if (got) hit++ } else { empty++; if (got) stray++ }
            }
        }
        if (tp.count == 0) return 0f
        return hit.toFloat() / tp.count - 0.6f * stray.toFloat() / max(1, empty)
    }

    /** The share of the enclosed hole of a hollow notehead that is paper, at ([x], [y]). */
    private fun holeClear(ink: Ink, name: String, space: Float, x: Int, y: Int): Float {
        val tp = MusicGlyphs.template(name, space)
        val hole = holes.getOrPut(name to (space * 10).toInt()) { holeOf(tp.ink) }
        var n = 0; var clear = 0
        for (j in 0 until tp.ink.height) for (i in 0 until tp.ink.width) {
            if (!hole[j * tp.ink.width + i]) continue
            n++
            if (!ink[x - tp.ox + i, y - tp.oy + j]) clear++
        }
        return if (n == 0) 0f else clear.toFloat() / n
    }

    private val holes = HashMap<Pair<String, Int>, BooleanArray>()

    /** A filled head's outline, split into its outer ring and its core (its inside, a way in). */
    private class RingMask(val ring: BooleanArray, val core: BooleanArray, val ringCount: Int, val coreCount: Int)
    private val rings = HashMap<Pair<String, Int>, RingMask>()

    /**
     * How much ([x], [y]) looks like a hollow head shaped like [shape]: ink round the ring, paper in
     * the core - whatever the size and slant of the engraver's hole. 0 to 1.
     */
    private fun hollow(ink: Ink, space: Float, x: Int, y: Int, shape: String, font: Boolean = false): Float {
        val tp = if (font) MusicGlyphs.template(shape, space) else tpl(shape, space)
        val m = rings.getOrPut((if (font) "font " else "") + shape to (space * 10).toInt()) {
            val w = tp.ink.width; val h = tp.ink.height
            // Distance in from the outline, by repeated erosion.
            val depth = IntArray(w * h) { if (tp.ink.bits[it]) Int.MAX_VALUE else 0 }
            var layer = 0
            var changed = true
            while (changed) {
                changed = false
                for (j in 0 until h) for (i in 0 until w) {
                    val p = j * w + i
                    if (depth[p] != Int.MAX_VALUE) continue
                    val edge = listOf(i - 1 to j, i + 1 to j, i to j - 1, i to j + 1).any { (a, b) -> a !in 0 until w || b !in 0 until h || depth[b * w + a] == layer }
                    if (edge) { depth[p] = layer + 1; changed = true }
                }
                layer++
            }
            val ringDepth = max(1, (space * 0.16f).toInt())
            val coreDepth = max(ringDepth + 1, (space * 0.3f).toInt())
            val ring = BooleanArray(w * h) { depth[it] in 1..ringDepth }
            // A shape with a hole (a whole note's) is clear in its hole - its sides, however thick
            // this engraver's, are not asked to be; a solid one, a way in from its edge.
            val hole = holeOf(tp.ink)
            val core = if (hole.count { it } >= w * h / 20) BooleanArray(w * h) { hole[it] && !ring[it] }
                else BooleanArray(w * h) { depth[it] > coreDepth && depth[it] != Int.MAX_VALUE }
            RingMask(ring, core, ring.count { it }, core.count { it })
        }
        if (m.coreCount == 0 || m.ringCount == 0) return 0f
        var ringInk = 0; var coreClear = 0
        val w = tp.ink.width
        for (j in 0 until tp.ink.height) for (i in 0 until w) {
            val p = j * w + i
            if (m.ring[p]) { if (ink[x - tp.ox + i, y - tp.oy + j]) ringInk++ }
            else if (m.core[p]) { if (!ink[x - tp.ox + i, y - tp.oy + j]) coreClear++ }
        }
        val r = ringInk.toFloat() / m.ringCount
        val c = coreClear.toFloat() / m.coreCount
        // The ring need not be whole (a thin hollow head's sides), the middle must be clear.
        return if (c < 0.55f) 0f else (r * 0.6f + c * 0.4f)
    }

    private fun holeOf(t: Ink): BooleanArray {
        val outside = BooleanArray(t.width * t.height)
        val stack = ArrayDeque<Int>()
        for (i in 0 until t.width) { stack += i; stack += (t.height - 1) * t.width + i }
        for (j in 0 until t.height) { stack += j * t.width; stack += j * t.width + t.width - 1 }
        while (stack.isNotEmpty()) {
            val p = stack.removeLast()
            if (outside[p] || t.bits[p]) continue
            outside[p] = true
            val x = p % t.width; val y = p / t.width
            if (x > 0) stack += p - 1; if (x < t.width - 1) stack += p + 1
            if (y > 0) stack += p - t.width; if (y < t.height - 1) stack += p + t.width
        }
        return BooleanArray(t.width * t.height) { !outside[it] && !t.bits[it] }
    }

    /**
     * Whether a hollow head's outline at ([x], [y]), [w] wide, stands on its own as a note's does:
     * strokes leaving it through a frame a little outside it - above, right and below (an
     * accidental may sit close on the left) - at most two, a stem and a tie or dot. A digit's
     * or a letter's loop, or a clef's, has more running off it.
     */
    private fun standsAlone(clean: Ink, sp: Float, x: Int, y: Int, w: Int, besides: Head? = null): Boolean {
        val m = max(2, (sp * 0.3f).roundToInt())
        // Half the head's height - heads are about three quarters as tall as wide - whatever size this page's are.
        val halfH = max(sp * 0.5f, w * 0.38f).roundToInt() + 1
        // From a quarter in: an accidental's strokes reach past the head's left side, above and below.
        val left = x + w / 4; val right = x + w + m; val top = y - halfH - m; val bottom = y + halfH + m
        // Walked round: top edge left to right, down the right side, bottom edge right to left.
        val path = ArrayList<Boolean>()
        val topEnd = right - left
        // Ink of [besides] - the next head of a chord - taken as paper.
        fun at(xx: Int, yy: Int) = clean[xx, yy] && (besides == null || xx !in besides.x - 1..besides.x + w + 1 || yy !in besides.y - halfH..besides.y + halfH)
        for (xx in left..right) path += at(xx, top)
        for (yy in top + 1 until bottom) path += at(right, yy)
        val sideEnd = path.size - 1
        for (xx in right downTo left) path += at(xx, bottom)
        // Each run of ink crossed.
        var runs = 0
        var i = 0
        while (i < path.size) {
            if (!path[i]) { i++; continue }
            var j = i
            while (j + 1 < path.size && path[j + 1]) j++
            // Down the right side, a long way: the head's own stem, however thick, not a stroke off it.
            val stem = i > topEnd && j <= sideEnd && j - i + 1 >= sp * 0.5f
            // Along the top or bottom, a long way: a slur or tie passing, or a chord's next head - a
            // letter's or digit's strokes leave upright and cross short.
            val passing = (j <= topEnd || i > sideEnd) && j - i + 1 >= sp * 0.5f
            if (!stem && !passing) runs++
            i = j + 1
        }
        return runs <= 2
    }

    /** Whether the middle of a filled head at ([x], [y]) - its shape worn in a fifth of a space all round - is all ink. */
    private fun solidCore(ink: Ink, sp: Float, x: Int, y: Int): Boolean {
        val tp = tpl("noteheadBlack", sp)
        val w = tp.ink.width; val h = tp.ink.height
        val r = max(1, (sp * 0.2f).roundToInt())
        var n = 0; var got = 0
        for (j in 0 until h) for (i in 0 until w) {
            if ((-r..r).any { b -> (-r..r).any { a -> !tp.ink[i + a, j + b] } }) continue
            n++
            if (ink[x - tp.ox + i, y - tp.oy + j]) got++
        }
        return n > 0 && got >= n * 0.97f
    }

    /**
     * How far filled head [h] ([w] wide) runs up and down, measured down its middle columns on
     * [ink] (the page with thin strokes worn away, so a stem or ledger line is not its edge):
     * (top, bottom), or null where the columns disagree.
     */
    private fun extentOf(ink: Ink, h: Head, w: Int, sp: Float): Pair<Int, Int>? {
        val reach = (sp * 4.5f).toInt()
        val tops = ArrayList<Int>(); val bottoms = ArrayList<Int>()
        for (x in h.x + w / 3..h.x + 2 * w / 3) {
            if (!ink[x, h.y]) continue
            var top = h.y; while (ink[x, top - 1] && h.y - top < reach) top--
            var bottom = h.y; while (ink[x, bottom + 1] && bottom - h.y < reach) bottom++
            tops += top; bottoms += bottom
        }
        if (tops.size < 2) return null
        tops.sort(); bottoms.sort()
        return tops[tops.size / 2] to bottoms[bottoms.size / 2]
    }
    /**
     * Every test a hollow head is put to, at the best place within a few pixels of ([x], [y]) on
     * [s]: for finding out why a head was not read. [clean] as [withoutLines] gives it, after [read].
     */
    fun explainHollow(clean: Ink, s: Staff, x: Int, y: Int): String {
        val sp = s.space
        val headW = tpl("noteheadBlack", sp).ink.width - 2
        var best = ""; var bestRing = -1f
        for (dx in -4..4) for (dy in -2..2) {
            val xx = x + dx; val yy = y + dy
            val ring = hollow(clean, sp, xx, yy, "noteheadBlack")
            if (ring <= bestRing) continue
            bestRing = ring
            val half = score(clean, "noteheadHalf", sp, xx, yy)
            val whole = score(clean, "noteheadWhole", sp, xx, yy)
            best = "ring ${"%.2f".format(ring)} (font ${"%.2f".format(hollow(clean, sp, xx, yy, "noteheadBlack", font = true))}), " +
                "half ${"%.2f".format(half)} hole ${"%.2f".format(holeClear(clean, "noteheadHalf", sp, xx, yy))}, " +
                "whole ${"%.2f".format(whole)} hole ${"%.2f".format(holeClear(clean, "noteheadWhole", sp, xx, yy))}, " +
                "wide ${"%.2f".format(hollow(clean, sp, xx, yy, "noteheadWhole"))}, " +
                "accidental-like ${accidentalLike(clean, sp, xx, yy, headW)}, alone ${standsAlone(clean, sp, xx, yy, headW)}, " +
                "sides ${(xx..xx + max(2, headW / 4)).any { clean[it, yy] } && (xx + headW - max(2, headW / 4)..xx + headW).any { clean[it, yy] }} at ${dx},${dy}"
        }
        return best
    }

    /** The best eighth-rest match within a space of ([x], [y]) on [s], and where: for finding out why a rest was not read. */
    fun explainRest(clean: Ink, s: Staff, x: Int, y: Int): String {
        var best = -1f; var at = ""
        for (dx in -(s.space.toInt())..s.space.toInt()) for (step in 1..7) {
            val v = score(clean, "rest8th", s.space, x + dx, s.y(step, x + dx).roundToInt())
            if (v > best) { best = v; at = "dx $dx step $step" }
        }
        return "rest8th ${"%.2f".format(best)} at $at"
    }

    /** A notehead found: where, which kind, how sure. */
    class Head(val x: Int, val step: Int, val y: Int, val kind: String, var score: Float) {
        var stemX = -1
        var stemEnd = 0
        var up = false
        var flags = 0
        var dots = 0
        /** A hollow head with strokes running off it (see standsAlone): kept only in a chord. */
        var crowded = false
        /** Only just like a head (touched by an accent, a tie, a smudge): kept only if it has a stem. */
        var weak = false
    }

    /** Noteheads on [s] between [from] and [to]; [lines] is the page with its staff and ledger lines, to check notes off the staff by. */
    fun heads(clean: Ink, s: Staff, from: Int, to: Int, lines: Ink? = null, filledOnly: Boolean = false): List<Head> {
        val found = ArrayList<Head>()
        val sp = s.space
        val black = tpl("noteheadBlack", sp)
        val headW = black.ink.width - 2
        for (step in -8..16) {
            for (x in from until to - headW / 2) {
                val y = s.y(step, x + headW / 2).roundToInt()
                val cx = x + headW / 2
                // Quick look first: something at the head's middle, or its sides for a hollow head.
                val mid = clean[cx, y]
                val q = max(2, headW / 4)
                val sides = (x..x + q).any { clean[it, y] } && (x + headW - q..x + headW).any { clean[it, y] }
                if (!mid && !sides) continue
                if (mid) {
                    // The page's size of head or the font's, whichever fits this one better.
                    val body = solid ?: clean
                    val sc = if (fitted == null) score(body, "noteheadBlack", sp, x, y)
                        else max(score(body, "noteheadBlack", sp, x, y), fontScore(body, "noteheadBlack", sp, x, y))
                    // Ties, slurs and accents touch heads and cost them a little.
                    if (sc > 0.68f) found += Head(x, step, y, "noteheadBlack", sc)
                    else if (adapt && sc > 0.5f && !filledOnly && solidCore(body, sp, x, y) && !accidentalLike(clean, sp, x, y, headW) &&
                        // Not a piece of a beam: ink that runs on past both sides of the head.
                        !(-1..1).all { dy -> clean[x - (sp * 0.35f).toInt(), y + dy] && clean[x + headW + (sp * 0.35f).toInt(), y + dy] })
                        found += Head(x, step, y, "noteheadBlack", sc).also { it.weak = true }
                }
                if (sides && !filledOnly) {
                    for (kind in listOf("noteheadHalf", "noteheadWhole")) {
                        val sc = score(clean, kind, sp, x, y)
                        if (sc > 0.66f && holeClear(clean, kind, sp, x, y) > 0.6f) found += Head(x, step, y, kind, sc).also { it.crowded = !standsAlone(clean, sp, x, y, tpl(kind, sp).ink.width - 2) }
                    }
                    // Any engraver's hollow head: a head-shaped ring of ink round a clear middle -
                    // and not a flat's or natural's bowl, whose strokes rise on the left or fall on
                    // the right, as no note's stem does.
                    if (!accidentalLike(clean, sp, x, y, headW)) {
                        // The page's own head's shape, or the font's - a whole note is rounder than either.
                        val ring = max(hollow(clean, sp, x, y, "noteheadBlack"), if (fitted != null) hollow(clean, sp, x, y, "noteheadBlack", font = true) else 0f)
                        if (ring > 0.72f) found += Head(x, step, y, "noteheadHalf", ring).also { it.crowded = !standsAlone(clean, sp, x, y, headW) }
                        val wide = hollow(clean, sp, x, y, "noteheadWhole")
                        if (wide > 0.66f) found += Head(x, step, y, "noteheadWhole", wide).also { it.crowded = !standsAlone(clean, sp, x, y, tpl("noteheadWhole", sp).ink.width - 2) }
                    }
                }
            }
        }
        // A chord's heads a third apart touch, one over the other, and a single head's shape fits
        // across the two better than either: a filled "head" standing two spaces tall is two
        // heads (three spaces, three), each a space from the next.
        val stacked = ArrayList<Head>()
        for (h in found) {
            if (h.kind != "noteheadBlack") continue
            val w = tpl(h.kind, sp).ink.width - 2
            val (top, bottom) = extentOf(solid ?: clean, h, w, sp) ?: continue
            val n = Math.round((bottom - top + 1) / sp)
            if (n < 2 || n > 4) continue
            val centre = s.lineY(0, h.x + w / 2); val half = (s.lineY(4, h.x + w / 2) - centre) / 8f
            for (k in 0 until n) {
                val y = top + sp * (k + 0.5f)
                val step = Math.round((y - centre) / half)
                stacked += Head(h.x, step, s.y(step, h.x + w / 2).roundToInt(), h.kind, h.score)
            }
            h.weak = true; h.score = -1f   // replaced by the heads it stands for
        }
        found.removeAll { it.score < 0f }
        found += stacked
        // A note off the staff stands on ledger lines: without one at the staff's edge it is a
        // dynamic's loop, an accent, a word - not a note.
        if (lines != null) found.retainAll { h ->
            if (h.step in -1..9) return@retainAll true
            val w = tpl(h.kind, sp).ink.width - 2
            val ledger = if (h.step < 0) -2 else 10
            val ly = s.y(ledger, h.x + w / 2).roundToInt()
            // A horizontal line under (or through) the head, at least as long as it is wide.
            (-1..1).any { dy ->
                val y = ly + dy
                var a = h.x + w / 2; var b = a
                if (!lines[a, y]) return@any false
                while (lines[a - 1, y] && a > h.x - w) a--
                while (lines[b + 1, y] && b < h.x + 2 * w) b++
                b - a + 1 >= w * 0.9f
            }
        }
        // A hollow head with strokes running off it is a letter's, a digit's or a clef's loop - unless
        // another hollow head sits right above or below it: a chord's heads touch each other.
        found.removeAll { h -> h.crowded && found.none { o -> o !== h && o.kind != "noteheadBlack" && abs(o.x - h.x) <= headW / 3 && abs(o.step - h.step) in 2..3 &&
            standsAlone(clean, sp, h.x, h.y, tpl(h.kind, sp).ink.width - 2, besides = o) } }
        // A weak head beside a sure one is that head's smudge, or a beam's end, not another note.
        found.removeAll { h -> h.weak && found.any { o -> !o.weak && abs(o.x - h.x) < headW * 1.3f && abs(o.step - h.step) <= 3 } }
        // The best of each cluster: heads cannot overlap, except a second's two in a chord.
        found.sortByDescending { it.score }
        val kept = ArrayList<Head>()
        for (h in found) {
            val w = tpl(h.kind, sp).ink.width
            if (kept.none { k -> abs(k.x - h.x) < w * 0.7f && abs(k.step - h.step) < 2 }) kept += h
        }
        return kept.sortedBy { it.x }
    }

    /**
     * The best digit between [x0] and [x1] centred on one of [steps]: (digit, x), or null. Engravers'
     * digits differ in size and shape more than their noteheads, so each is tried a little smaller
     * and larger, and taken loosely.
     */
    private fun digit(clean: Ink, s: Staff, x0: Int, x1: Int, steps: List<Int>): Pair<Int, Int>? {
        // Each digit's best fit, at whatever size and place.
        class Fit(val d: Int, val x: Int, val y: Int, val sp: Float, val sc: Float)
        val fits = HashMap<Int, Fit>()
        for (size in listOf(1f, 0.85f, 1.15f, 0.7f)) {
            val sp = s.space * size
            for (d in 0..9) for (xx in x0..x1) for (step in steps) {
                val y = s.y(step, xx).roundToInt()
                val sc = score(clean, "timeSig$d", sp, xx, y)
                if (sc > 0.5f && sc > (fits[d]?.sc ?: 0f)) fits[d] = Fit(d, xx, y, sp, sc)
            }
        }
        val best = fits.values.maxByOrNull { it.sc } ?: return null
        return best.d to best.x
    }

    /** The end of something shaped like a time signature just after [x0] - ink in both halves of the staff. */
    private fun unreadTime(clean: Ink, s: Staff, x0: Int): Int? {
        val sp = s.space
        fun halves(x: Int): Pair<Boolean, Boolean> {
            val upper = (s.y(1, x).roundToInt()..s.y(3, x).roundToInt()).any { clean[x, it] }
            val lower = (s.y(5, x).roundToInt()..s.y(7, x).roundToInt()).any { clean[x, it] }
            return upper to lower
        }
        val start = (x0..x0 + (sp * 2.5f).toInt()).firstOrNull { halves(it).let { (u, l) -> u && l } } ?: return null
        var x = start
        while (x < start + sp * 3.5f && halves(x).let { (u, l) -> u || l }) x++
        val width = x - start
        return if (width >= sp * 0.8f && width <= sp * 3.2f) x + (sp * 0.4f).toInt() else null
    }

    private fun blockRests(clean: Ink, s: Staff, from: Int, to: Int, taken: List<Pair<Int, Int>>): List<Pair<Rest, Float>> {
        val sp = s.space
        val out = ArrayList<Pair<Rest, Float>>()
        var x = from
        while (x < to) {
            val mid = s.y(4, x).roundToInt()
            val second = s.y(2, x).roundToInt()
            // Sitting on the middle line: ink just above it; hanging from the second: just below.
            val kind = when {
                clean[x, mid - (sp * 0.25f).toInt()] -> 2
                clean[x, second + (sp * 0.25f).toInt()] -> 1
                else -> 0
            }
            if (kind == 0 || taken.any { (a, b) -> x in a..b }) { x++; continue }
            val y0 = if (kind == 2) mid - (sp * 0.55f).toInt() else second + 1
            val y1 = if (kind == 2) mid - 1 else second + (sp * 0.55f).toInt()
            val yc = (y0 + y1) / 2
            var x2 = x
            while (x2 < to && clean[x2 + 1, yc]) x2++
            val w = x2 - x + 1
            // Solid, a space or so wide, and nothing above or below the block (not part of a note).
            var filled = 0; var n = 0
            for (yy in y0..y1) for (xx in x..x2) { n++; if (clean[xx, yy]) filled++ }
            val clearAbove = (x..x2).count { clean[it, y0 - (sp * 0.35f).toInt()] } <= w / 5
            val clearBelow = (x..x2).count { clean[it, y1 + (sp * 0.35f).toInt()] } <= w / 5
            if (w >= sp * 0.8f && w <= sp * 1.7f && n > 0 && filled >= n * 0.8f && (if (kind == 2) clearAbove else clearBelow)) {
                out += Rest(Duration(kind), x.toFloat()) to 0.8f
            }
            x = x2 + 1
        }
        return out
    }

    /**
     * The hooks of a flag-like rest from [x], [w] wide, on [s]: each a small round blob on the
     * page with thin strokes worn away (its slanting stem gone).
     */
    private fun hooks(s: Staff, x: Int, w: Int): Int {
        val ink = solid ?: return 0
        val sp = s.space
        val x0 = x - (sp * 0.2f).toInt(); val x1 = x + w + (sp * 0.2f).toInt()
        val y0 = s.y(-1, x).roundToInt(); val y1 = s.y(9, x).roundToInt()
        val seen = HashSet<Int>()
        var n = 0
        for (yy in y0..y1) for (xx in x0..x1) {
            if (!ink[xx, yy] || (yy * ink.width + xx) in seen) continue
            // The blob here: how far it spreads.
            val stack = ArrayDeque<Int>(); stack += yy * ink.width + xx
            var l = xx; var r = xx; var t = yy; var b = yy; var size = 0
            while (stack.isNotEmpty() && size < 2_000) {
                val q = stack.removeLast()
                if (!seen.add(q)) continue
                val px = q % ink.width; val py = q / ink.width
                if (!ink[px, py]) continue
                size++; l = min(l, px); r = max(r, px); t = min(t, py); b = max(b, py)
                if (px > x0 - 5) stack += q - 1; if (px < x1 + 5) stack += q + 1
                stack += q - ink.width; stack += q + ink.width
            }
            val bw = r - l + 1; val bh = b - t + 1
            if (bw in (sp * 0.25f).toInt()..(sp * 0.75f).toInt() && bh in (sp * 0.25f).toInt()..(sp * 0.75f).toInt() && size >= bw * bh * 0.5f) n++
        }
        return n
    }

    /** A stroke rising from the left edge, or falling from the right, of a head-sized ring at ([x], [y]). */
    private fun accidentalLike(clean: Ink, sp: Float, x: Int, y: Int, w: Int): Boolean {
        fun stroke(from: Int, to: Int, dir: Int): Boolean = (from..to).any { xx ->
            var n = 0; var yy = y + dir * (sp * 0.5f).toInt()
            while (clean[xx, yy] && n < sp * 2) { yy += dir; n++ }
            n >= sp * 0.9f
        }
        val third = max(2, w / 3)
        if (stroke(x - 1, x + third, -1) || stroke(x + w - third, x + w + 1, 1)) return true
        // Between two beams: ink running on past both sides along its top or its bottom.
        val off = (sp * 0.35f).toInt()
        for (dy in listOf(-(sp * 0.42f).toInt(), (sp * 0.42f).toInt())) {
            val yy = y + dy
            val left = (-1..1).any { clean[x - off, yy + it] }
            val right = (-1..1).any { clean[x + w + off, yy + it] }
            if (left && right) return true
        }
        return false
    }

    /** The stem of [h], its length and direction, and the beams or flags at its end. */
    /** [stem], for looking at from tests. */
    fun debugStem(clean: Ink, s: Staff, h: Head, t: Int) = stem(clean, s, h, t)

    private fun stem(clean: Ink, s: Staff, h: Head, t: Int) {
        val sp = s.space
        val w = tpl(h.kind, sp).ink.width - 2
        // The stem's run from the head outwards: started from wherever in that half of the head
        // the column first has ink (a tie or a match a little off can leave the middle white).
        fun run(x: Int, y: Int, dir: Int): Int {
            var yy = y - dir * (sp * 0.3f).toInt()
            val limit = y + dir * (sp * 0.4f).toInt()
            while (!clean[x, yy] && (if (dir > 0) yy < limit else yy > limit)) yy += dir
            if (!clean[x, yy]) return 0
            // A thin stem can step a pixel sideways on its way (anti-aliased onto the next column):
            // followed, as long as it stays within a couple of pixels of where it started.
            // And it may stand a hair apart from its head, or have a speck missing: a gap of a few
            // pixels is crossed where the stem carries on straight beyond it.
            var cx = x
            val gap = max(2, (sp * 0.2f).toInt())
            while (true) {
                if (clean[cx, yy]) { yy += dir; continue }
                val side = listOf(cx - 1, cx + 1).firstOrNull { abs(it - x) <= 2 && clean[it, yy] }
                if (side != null) { cx = side; continue }
                val resumes = (1..gap).firstOrNull { k -> (cx - 1..cx + 1).any { abs(it - x) <= 2 && clean[it, yy + dir * k] && clean[it, yy + dir * (k + 1)] } }
                    ?: break
                yy += dir * resumes
            }
            // How far past [y] the stem reaches, so its end is y + dir * run.
            return ((yy - y) * dir).coerceAtLeast(0)
        }
        // Up: at the head's right side; down: at its left - allowing for another font's heads being
        // a little narrower or wider than these.
        // (A head's match can land a few pixels off its true place; the search allows a quarter of it.)
        val upX = (h.x + (w * 0.6f).toInt()..h.x + (w * 1.25f).toInt() + 1).maxByOrNull { run(it, h.y - (sp * 0.3f).toInt(), -1) }!!
        val downX = (h.x - (w * 0.25f).toInt() - 1..h.x + (w * 0.4f).toInt()).maxByOrNull { run(it, h.y + (sp * 0.3f).toInt(), 1) }!!
        val up = run(upX, h.y - (sp * 0.3f).toInt(), -1)
        val down = run(downX, h.y + (sp * 0.3f).toInt(), 1)
        val len = max(up, down)
        if (debug) println("    stem? head ${h.x},${h.y} w=$w up $up at $upX, down $down at $downX; " +
            "down runs ${(h.x - (w * 0.25f).toInt() - 1..h.x + (w * 0.4f).toInt()).map { it to run(it, h.y + (sp * 0.3f).toInt(), 1) }}")
        if (len < sp * 2.2f) return
        h.up = up >= down
        h.stemX = if (h.up) upX else downX
        h.stemEnd = if (h.up) h.y - (sp * 0.3f).toInt() - up else h.y + (sp * 0.3f).toInt() + down
        // Beams or flags: separate strokes crossing a column just beside the stem, near its end.
        var most = 0
        for (dx in listOf(-(sp * 0.55f).toInt(), (sp * 0.55f).toInt())) {
            val cx = h.stemX + dx
            var count = 0
            // Down the stem from its end, stopping short of the head - a short stem's head is not a beam.
            val span = min(sp * 2.6f, len - sp * 1.1f).toInt()
            var inRun = 0
            for (k in 0..span) {
                val y = if (h.up) h.stemEnd + k else h.stemEnd - k
                // A beam crosses in about half a space; a flag, steep by the stem, in up to two.
                if (clean[cx, y]) inRun++ else { if (inRun >= sp * 0.25f && inRun <= sp * 2.2f) count++; inRun = 0 }
            }
            if (inRun >= sp * 0.25f && inRun <= sp * 2.2f) count++
            most = max(most, count)
        }
        h.flags = most.coerceAtMost(3)
    }

    private fun dots(clean: Ink, s: Staff, h: Head) {
        val sp = s.space
        val w = tpl(h.kind, sp).ink.width
        // Close beside the head, in the space it is in or the one above - a staccato dot over the
        // next note is further off.
        val x0 = h.x + w - 1; val x1 = h.x + w + (sp * 0.85f).toInt()
        // In a space: the head's own, or the one above a head on a line.
        val spaceStep = if (h.step % 2 != 0) h.step else h.step - 1
        val yc = s.y(spaceStep, h.x + w).roundToInt()
        val y0 = yc - (sp * 0.35f).toInt(); val y1 = yc + (sp * 0.35f).toInt()
        if (dotIn(clean, sp, x0, y0, x1, y1, avoidX = h.stemX, centreY = yc)) h.dots = 1
    }

    /**
     * A dot in the box: a small round blob, apart from anything else - a quarter to three fifths of
     * a space across, well filled. [avoidX] is a stem not to count.
     */
    private fun dotIn(clean: Ink, sp: Float, x0: Int, y0: Int, x1: Int, y1: Int, avoidX: Int = -1, centreY: Int? = null): Boolean {
        val seen = HashSet<Int>()
        for (y in y0..y1) for (x in x0..x1) {
            if (!clean[x, y] || (avoidX >= 0 && abs(x - avoidX) <= 2)) continue
            val key = y * clean.width + x
            if (key in seen) continue
            // The blob this pixel is in, up to a little over a dot's size.
            val stack = ArrayDeque<Int>(); stack += key
            var n = 0; var l = x; var r = x; var t = y; var b = y
            val limit = (sp * sp).toInt()
            while (stack.isNotEmpty() && n <= limit) {
                val p = stack.removeLast()
                if (!seen.add(p)) continue
                val px = p % clean.width; val py = p / clean.width
                if (!clean[px, py]) continue
                n++; l = min(l, px); r = max(r, px); t = min(t, py); b = max(b, py)
                stack += p - 1; stack += p + 1; stack += p - clean.width; stack += p + clean.width
            }
            val bw = r - l + 1; val bh = b - t + 1
            if (n <= limit && bw in (sp * 0.25f).toInt()..(sp * 0.65f).toInt() + 1 && bh in (sp * 0.25f).toInt()..(sp * 0.65f).toInt() + 1 &&
                n >= bw * bh * 0.55f && abs(bw - bh) <= max(2, (sp * 0.2f).toInt()) &&
                (centreY == null || abs((t + b) / 2 - centreY) <= sp * 0.3f)) return true
        }
        return false
    }

    private fun accidental(clean: Ink, s: Staff, h: Head): Int? {
        val sp = s.space
        var best: Pair<Int, Float>? = null
        for ((name, alter) in listOf("accidentalSharp" to 1, "accidentalFlat" to -1, "accidentalNatural" to 0)) {
            for (x in h.x - (sp * 2.2f).toInt()..h.x - (sp * 0.5f).toInt()) for (dy in -1..1) {
                val sc = score(clean, name, sp, x, h.y + dy)
                if (sc > 0.62f && (best == null || sc > best.second)) best = alter to sc
            }
        }
        return best?.first
    }

    // ---- reading a page ----------------------------------------------------------------------

    /** Everything on a page, as measures numbered from [firstNumber]. */
    fun read(ink: Ink, page: Int = 0, firstNumber: Int = 1, carry: Carry = Carry()): PageReading {
        val (t, space) = metrics(ink) ?: return PageReading(emptyList(), emptyList(), emptyList(), 1, 0f)
        val staves = staves(ink, t, space)
        val clean = withoutLines(ink, staves, t)
        solid = if (adapt && staves.isNotEmpty()) clean.opened(max(1, (staves.first().space * 0.11f).roundToInt())) else null
        if (adapt) fitHeads(clean, staves, ink) else fitted = null
        val measures = ArrayList<Measure>()
        val bars = ArrayList<List<Int>>()
        var number = firstNumber
        // Every staff's heads first: a note high over one staff is also low under the one above,
        // and belongs to whichever it is nearer the middle of.
        val headsOf = staves.map { s -> heads(clean, s, s.left, s.right, ink).toMutableList() }
        // Stems now, and a hollow "head" at another note's stem end is its flag's curl.
        for ((si, hs) in headsOf.withIndex()) for (h in hs) if (h.kind != "noteheadWhole") stem(clean, staves[si], h, t)
        // A weak head needs a stem of its own: one it shares with a sure head is that head's flag or beam.
        val dropped = ArrayList<Pair<Head, String>>()
        for (hs in headsOf) hs.removeAll { h -> (h.weak && (h.stemX < 0 || hs.any { o -> !o.weak && o.stemX >= 0 && abs(o.stemX - h.stemX) <= t + 2 }))
            .also { if (it) dropped += h to (if (h.stemX < 0) "weak, no stem" else "weak, stem shared") } }
        val unflagged = headsOf.map { hs ->
            hs.filter { h ->
                (h.kind == "noteheadBlack" || hs.none { o -> o !== h && o.stemX >= 0 &&
                    abs(o.stemX - (h.x + space * 0.6f)) < space * 1.3f && abs(o.stemEnd - h.y) < space * 1.3f }).also { if (!it) dropped += h to "a flag's curl" }
            }
        }
        val kept = unflagged.mapIndexed { si, hs ->
            hs.filter { h ->
                val mine = abs(h.step - 4)
                listOf(si - 1, si + 1).none { oi ->
                    val other = unflagged.getOrNull(oi) ?: return@none false
                    other.any { o -> abs(o.x - h.x) <= 3 && abs(o.y - h.y) <= 3 && abs(o.step - 4) < mine }
                }.also { if (!it) dropped += h to "the other staff's" }
            }
        }
        for ((si, s) in staves.withIndex()) {
            // The start of the staff: clef, key, time.
            var x = s.left + (s.space * 0.3f).toInt()
            val clef = clefAt(clean, s, x, t)
            var showsClef = false
            if (clef != null) { carry.clef = clef.first; x = clef.second; showsClef = true }
            val key = keyAt(clean, s, x, carry.clef)
            var showsKey = false
            if (key != null) { carry.key = key.first; x = key.second; showsKey = true }
            val time = timeAt(clean, s, x)
            var showsTime = false
            if (time != null) { carry.time = time.first; x = time.second; showsTime = true }
            // A part's first staff has a time signature: one in a font not read here is stepped
            // over (taken as the time carried, and said so), not read as notes.
            var timeUnread = false
            if (time == null && page == 0 && si == 0) unreadTime(clean, s, x)?.let { x = it; timeUnread = true }
            // Heads and stems next, after the staff's start: a stem the height of the staff is not
            // a barline - and nor is a time signature's digits.
            val allHeads = kept[si].filter { it.x >= x }
            kept[si].filter { it.x < x }.forEach { dropped += it to "before the staff's start (clef, key, time end at $x)" }
            // Nothing fits between a staff's start and a line under three spaces on: that is a time
            // signature in another font, not a barline.
            val b = barlines(ink, clean, s, t, allHeads.filter { it.stemX >= 0 }.map { it.stemX }, allHeads).filter { it > x + s.space * 3f }
            bars += b
            val edges = (listOf(s.left) + b).distinct().sorted()
            val spans = edges.zipWithNext().filter { (a, c) -> c - a > s.space * 2 } +
                (if (b.isEmpty() || s.right - b.last() > s.space * 3) listOf((b.lastOrNull() ?: s.left) to s.right) else emptyList())
            val inSpan = HashSet<Head>()
            for ((i, span) in spans.withIndex()) {
                var from = if (i == 0) max(span.first, x) else span.first + (s.space * 0.3f).toInt()
                // A change of time at a bar's start: read, and in force from here.
                var timeHere = false
                if (i > 0) timeAt(clean, s, from)?.let { (sig, end) -> if (end < span.second - s.space) { carry.time = sig; from = end; timeHere = true } }
                inSpan += allHeads.filter { it.x >= from - 2 && it.x < span.second - (s.space * 0.2f).toInt() }
                val to = span.second - (s.space * 0.2f).toInt()
                val box = Box(span.first, s.top, span.second, s.bottom)
                // A multi-bar rest: its bar and number, and nothing else to read.
                // A bar with notes in it is no multi-bar rest, whatever runs along its middle line (a beam).
                val headsHere = allHeads.any { it.x >= from - 2 && it.x < to }
                val rest = if (headsHere) null else multiRest(clean, s, from, to)
                if (rest != null) {
                    val (restBars, x) = rest
                    measures += Measure(number, page, si, box, s.space, carry.clef, carry.key, carry.time,
                        listOf(Rest(Duration(1), x.toFloat())), showsClef = i == 0 && showsClef, showsKey = i == 0 && showsKey,
                        showsTime = i == 0 && showsTime, doubts = if (restBars == 0) listOf("rest of how many bars?") else emptyList(), bars = max(1, restBars))
                    number += max(1, restBars)
                } else {
                var events = eventsIn(clean, s, from, to, t, carry, allHeads.filter { it.x >= from - 2 && it.x < to })
                // Too long a bar: triplets read as plain notes, most often.
                var guessedTuplets = false
                if (events.sumOf { it.duration.quarters } > carry.time.quarters + 1e-6) tuplets(clean, s, events, carry.time.quarters)?.let { (e, printed) -> events = e; guessedTuplets = !printed }
                val m = Measure(
                    number++, page, si, box, s.space,
                    carry.clef, carry.key, carry.time, events,
                    showsClef = i == 0 && showsClef, showsKey = i == 0 && showsKey, showsTime = (i == 0 && showsTime) || timeHere
                )
                val doubts = ArrayList<String>()
                val q = m.quarters
                if (abs(q - carry.time.quarters) > 1e-6 && events.isNotEmpty()) doubts += "${fmt(q)} beats found, ${fmt(carry.time.quarters)} expected"
                if (events.isEmpty()) doubts += "nothing read"
                if (guessedTuplets) doubts += "triplets taken to make the bar add up, no 3 seen"
                if (i == 0 && timeUnread) doubts += "time signature not read - taken as ${carry.time.beats}/${carry.time.beatType}"
                // Another engraver's heads match these a little less well and are read right: only
                // a weak match is a doubt.
                events.filterIsInstance<Note>().filter { it.confidence < 0.75f }.takeIf { it.isNotEmpty() }?.let { doubts += "${it.size} unclear note${if (it.size > 1) "s" else ""}" }
                measures += m.copy(doubts = doubts)
                }
            }
            allHeads.filter { it !in inSpan }.forEach { dropped += it to "by a barline, outside every bar" }
        }
        return PageReading(staves, bars, measures, t, space, dropped)
    }

    /**
     * A multi-bar rest between [from] and [to]: a thick bar across the middle line, its number of
     * bars above the staff. (bars, x) - bars 0 when the number could not be read; null when none.
     */
    private fun multiRest(clean: Ink, s: Staff, from: Int, to: Int): Pair<Int, Int>? {
        val sp = s.space
        var best: Pair<Int, Int>? = null   // start, length
        var x = from
        while (x < to) {
            val y = s.y(4, x).roundToInt()
            fun thick(xx: Int): Boolean {
                var a = y; while (clean[xx, a - 1] && y - a < sp) a--
                var b = y; while (clean[xx, b + 1] && b - y < sp) b++
                // Engravers' bars run from a third of a space thick to a whole one.
                return clean[xx, y] && b - a + 1 >= sp * 0.3f && b - a + 1 <= sp * 1.2f
            }
            if (!thick(x)) { x++; continue }
            val start = x
            while (x < to && thick(x)) x++
            if (best == null || x - start > best.second) best = start to (x - start)
        }
        val (start, len) = best ?: return null
        if (len < sp * 2.5f) return null
        // Its ends are short upright strokes, a space or so each way from the middle line.
        fun serif(x0: Int): Boolean = (x0 - 3..x0 + 3).any { xx ->
            val up = s.y(2, xx).roundToInt(); val down = s.y(6, xx).roundToInt()
            (up..down).count { clean[xx, it] } >= (down - up) * 0.8f
        }
        val serifs = serif(start) && serif(start + len - 1)
        // The number above: engravers' digits, as in a time signature, a space and a half over the top line.
        val digits = ArrayList<Pair<Int, Int>>()
        var dx = start - (sp * 0.5f).toInt()
        while (dx < start + len) {
            val first = digit(clean, s, dx, dx + (sp * 0.8f).toInt(), listOf(-3, -4, -2, -5))
            if (first == null) { dx += (sp * 0.8f).toInt(); continue }
            // The first window with anything in it may hold only the edge of a digit - a "1" fits
            // the side of a bold "8": the best match around it is the digit.
            val hit = digit(clean, s, first.second - (sp * 0.6f).toInt(), first.second + (sp * 0.9f).toInt(), listOf(-3, -4, -2, -5)) ?: first
            digits += hit
            dx = hit.second + (sp * 1.4f).toInt()
        }
        // Read whole, in whatever typeface it is printed; the music font's digits if that finds none.
        val printed = Digits.number(clean, start - sp.toInt(), start + len + sp.toInt(), s.y(-9, start).roundToInt(), s.y(-1, start).roundToInt(), (sp * 0.8f).toInt(), (sp * 3.2f).toInt(), bottomFrom = s.y(-5, start).roundToInt())
        val bars = printed?.first ?: digits.sortedBy { it.second }.fold(0) { n, (d, _) -> n * 10 + d }
        // Some engravers end the bar in short strokes, or none: its number over it says what it is.
        if (!serifs && bars < 2) return null
        return bars to start
    }

    /**
     * A bar that comes to more than its time: groups of three equal notes (or rests) read as plain
     * are, most often, triplets - three in the time of two. The fewest such groups that make the
     * bar come out exactly, those with a "3" printed by them first: (events, every group had its
     * 3); null when no choice of groups makes it come out.
     */
    private fun tuplets(clean: Ink, s: Staff, events: List<Event>, expected: Double): Pair<List<Event>, Boolean>? {
        val sorted = events.sortedBy { it.x }
        val excess = sorted.sumOf { it.duration.quarters } - expected
        if (excess <= 1e-6) return null
        val windows = (0..sorted.size - 3).filter { i ->
            val d = sorted[i].duration
            d.base >= 4 && d.dots == 0 && !d.tuplet && (1..2).all { sorted[i + it].duration == d }
        }
        if (windows.isEmpty()) return null
        val sp = s.space
        val marks = HashMap<Int, Boolean>()
        fun marked(i: Int) = marks.getOrPut(i) {
            val x0 = sorted[i].x.toInt() - (sp * 0.5f).toInt(); val x1 = sorted[i + 2].x.toInt() + (sp * 1.5f).toInt()
            printedThree(clean, s, x0, x1)
        }
        var best: List<Int>? = null; var bestMarks = -1; var tie = false
        fun search(from: Int, chosen: List<Int>, saved: Double) {
            if (abs(saved - excess) < 1e-6) {
                val m = chosen.count { marked(it) }
                val b = best
                when {
                    b == null || chosen.size < b.size || (chosen.size == b.size && m > bestMarks) -> { best = chosen; bestMarks = m; tie = false }
                    chosen.size == b.size && m == bestMarks -> tie = true
                }
                return
            }
            if (saved > excess + 1e-6 || chosen.size >= 4) return
            for (i in windows) if (i >= from) search(i + 3, chosen + i, saved + sorted[i].duration.quarters)
        }
        search(0, emptyList(), 0.0)
        val chosen = best ?: return null
        // Two ways alike, neither printed: no telling which notes are the triplet.
        if (tie && bestMarks < chosen.size) return null
        val inTuplet = chosen.flatMap { listOf(it, it + 1, it + 2) }.toSet()
        val out = sorted.mapIndexed { k, e ->
            if (k !in inTuplet) e else when (e) {
                is Note -> e.copy(duration = e.duration.copy(actual = 3, normal = 2))
                is Rest -> e.copy(duration = e.duration.copy(actual = 3, normal = 2))
            }
        }
        return out to (bestMarks == chosen.size)
    }

    /** Whether a small "3" - a tuplet's number, italic or upright - is printed above or below [x0]..[x1] on [s]. */
    private fun printedThree(clean: Ink, s: Staff, x0: Int, x1: Int): Boolean {
        val sp = s.space
        val bands = listOf(s.y(-14, x0).roundToInt() to s.y(-2, x0).roundToInt(), s.y(10, x0).roundToInt() to s.y(22, x0).roundToInt())
        for (size in listOf(0.5f, 0.6f, 0.7f)) for ((a, b) in bands) {
            var y = a
            while (y <= b) {
                for (x in x0..x1 step 2) if (score(clean, "timeSig3", sp * size, x, y) > 0.55f) return true
                y += 2
            }
        }
        return false
    }

    private fun fmt(q: Double) = if (q == q.toLong().toDouble()) q.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, q).trimEnd('0')

    /** What carries from staff to staff and page to page: the clef, key and time in force. */
    class Carry(var clef: Clef = Clef.TREBLE, var key: Key = Key(0), var time: TimeSig = TimeSig(4, 4))

    private fun clefAt(clean: Ink, s: Staff, x0: Int, t: Int = 2): Pair<Clef, Int>? {
        val sp = s.space
        var best: Triple<Clef, Int, Float>? = null
        // The music font's clefs, and a little smaller and larger: engravers' clefs differ in size more than heads.
        for (size in listOf(1f, 0.9f, 1.1f)) for ((clef, glyph, step) in listOf(Triple(Clef.TREBLE, "gClef", 6), Triple(Clef.BASS, "fClef", 2), Triple(Clef.ALTO, "cClef", 4), Triple(Clef.TENOR, "cClef", 2))) {
            for (x in x0..x0 + (sp * 2.5f).toInt()) {
                val sc = score(clean, glyph, sp * size, x, s.y(step, x).roundToInt())
                if (sc > 0.6f && (best == null || sc > best.third)) best = Triple(clef, x + (MusicGlyphs[glyph].advance * sp * size).toInt(), sc)
            }
        }
        best?.let { return it.first to it.second + (sp * 0.4f).toInt() }
        return clefByShape(clean, s, x0, t)
    }

    /**
     * A clef in a shape no font here draws: the first big shape at the staff's start, read by its
     * outline - a treble clef stands out above and below the staff, a bass clef fills its upper
     * half (its dots beside it), a C clef its middle. So its loops are never read as notes.
     */
    private fun clefByShape(clean: Ink, s: Staff, x0: Int, t: Int): Pair<Clef, Int>? {
        val sp = s.space
        val yTop = s.y(-8, x0).roundToInt(); val yBottom = s.y(16, x0).roundToInt()
        val xMax = x0 + (sp * 6).toInt()
        val startX = (x0..x0 + (sp * 3).toInt()).firstOrNull { x -> (s.y(0, x).roundToInt()..s.y(8, x).roundToInt()).any { clean[x, it] } } ?: return null
        val startY = (s.y(0, startX).roundToInt()..s.y(8, startX).roundToInt()).first { clean[startX, it] }
        // Followed across the gaps the staff lines left in it.
        val gap = t + 1
        val seen = HashSet<Long>()
        val stack = ArrayDeque<Long>()
        fun key(x: Int, y: Int) = x.toLong() shl 32 or (y.toLong() and 0xffffffffL)
        stack += key(startX, startY)
        var l = startX; var r = startX; var top = startY; var bottom = startY
        while (stack.isNotEmpty() && seen.size < 40_000) {
            val k = stack.removeLast()
            if (!seen.add(k)) continue
            val x = (k shr 32).toInt(); val y = k.toInt()
            l = min(l, x); r = max(r, x); top = min(top, y); bottom = max(bottom, y)
            for (dx in -1..1) for (dy in -gap..gap) {
                val nx = x + dx; val ny = y + dy
                if (nx < x0 - sp || nx > xMax || ny < yTop || ny > yBottom) continue
                if (clean[nx, ny] && key(nx, ny) !in seen) stack += key(nx, ny)
            }
        }
        val w = (r - l) / sp; val h = (bottom - top) / sp
        val above = (s.y(0, l) - top) / sp; val below = (bottom - s.y(8, l)) / sp
        val clef = when {
            w in 1.2f..4.5f && h >= 5f && above > 0.5f && below > 0.5f -> Clef.TREBLE
            w in 1.5f..4.5f && h in 2.2f..4.8f && top <= s.y(1, l) && bottom <= s.y(7, l) -> Clef.BASS
            w in 1.5f..4.5f && h in 3.6f..5.2f && above < 0.8f && below < 0.8f -> Clef.ALTO
            else -> return null
        }
        if (debug) println("clef by shape: $clef, ${"%.1f".format(w)} x ${"%.1f".format(h)} spaces")
        // A bass clef's dots follow it.
        val end = if (clef != Clef.BASS) r else (r + 1..r + (sp * 1.2f).toInt()).lastOrNull { xx -> (s.y(1, xx).roundToInt()..s.y(3, xx).roundToInt()).any { clean[xx, it] } } ?: r
        return clef to end + (sp * 0.4f).toInt()
    }

    private fun keyAt(clean: Ink, s: Staff, x0: Int, clef: Clef): Pair<Key, Int>? {
        val sp = s.space
        for (sign in listOf(1, -1)) {
            val name = if (sign > 0) "accidentalSharp" else "accidentalFlat"
            val steps = Engraver.keySteps(Key(7 * sign), clef)
            var x = x0
            var n = 0
            while (n < 7) {
                val y = s.y(steps[n], x).roundToInt()
                val hit = (x..x + (sp * 1.6f).toInt()).firstOrNull { score(clean, name, sp, it, y) > 0.62f } ?: break
                x = hit + (sp * 0.8f).toInt()
                n++
            }
            if (n > 0) return Key(n * sign) to x + (sp * 0.4f).toInt()
        }
        return null
    }

    private fun timeAt(clean: Ink, s: Staff, x0: Int): Pair<TimeSig, Int>? {
        val sp = s.space
        fun digitAt(x: Int, step: Int): Pair<Int, Int>? = digit(clean, s, x, x + (sp * 2.2f).toInt(), listOf(step))
        // A digit's right edge, from where it was found.
        // Another engraver's digits may be a little wider than these.
        fun end(d: Int, x: Int) = x + ((MusicGlyphs["timeSig$d"].bounds[2]) * sp * 1.2f).toInt() + (sp * 0.3f).toInt()
        fun number(step: Int): Pair<Int, Int>? {
            val first = digitAt(x0, step) ?: return null
            val next = digitAt(first.second + (sp * 1.6f).toInt(), step)
            return if (next != null && next.second - first.second < sp * 2.4f) (first.first * 10 + next.first) to end(next.first, next.second)
            else first.first to end(first.first, first.second)
        }
        val top = number(2) ?: run {
            // Common and cut time, in whatever size the engraver drew them.
            var best: Triple<TimeSig, Int, Float>? = null
            for ((glyph, sig) in listOf("timeSigCommon" to TimeSig(4, 4), "timeSigCutCommon" to TimeSig(2, 2))) for (size in listOf(1f, 0.85f, 1.15f)) {
                for (x in x0..x0 + (sp * 2.2f).toInt()) {
                    val sc = score(clean, glyph, sp * size, x, s.y(4, x).roundToInt())
                    if (sc > 0.52f && (best == null || sc > best.third)) best = Triple(sig, x, sc)
                }
            }
            best?.let { return it.first to it.second + (sp * 2.4f).toInt() }
            return null
        }
        val bottom = number(6) ?: return null
        if (top.first !in 1..32 || bottom.first !in listOf(1, 2, 4, 8, 16, 32)) return null
        return TimeSig(top.first, bottom.first) to max(top.second, bottom.second) + (sp * 0.4f).toInt()
    }

    private fun eventsIn(clean: Ink, s: Staff, from: Int, to: Int, t: Int, carry: Carry, heads: List<Head>): List<Event> {
        val sp = s.space
        for (h in heads) dots(clean, s, h)
        // Heads sharing a stem are one chord.
        val chords = ArrayList<MutableList<Head>>()
        for (h in heads) {
            val with = chords.firstOrNull { c -> val o = c.first()
                (h.stemX >= 0 && o.stemX >= 0 && abs(h.stemX - o.stemX) <= max(2, t + 1)) ||
                    (h.kind == "noteheadWhole" && o.kind == "noteheadWhole" && abs(h.x - o.x) < sp * 0.5f) }
            if (with != null) with += h else chords += mutableListOf(h)
        }
        val events = ArrayList<Event>()
        val written = HashMap<Int, Int>()   // diatonic -> alter, for the rest of the measure
        val taken = chords.map { c -> c.minOf { it.x } to c.maxOf { it.x + (sp * 1.3f).toInt() } }
        for (c in chords) {
            val h = c.first()
            val base = when (h.kind) {
                "noteheadWhole" -> 1
                // A hollow head with no stem is a whole note, whatever its shape.
                "noteheadHalf" -> if (c.all { it.stemX < 0 }) 1 else 2
                else -> if (h.stemX < 0) 4 else when (c.maxOf { it.flags }) { 0 -> 4; 1 -> 8; 2 -> 16; else -> 32 }
            }
            val dur = Duration(base, c.maxOf { it.dots })
            val accs = HashMap<Int, Int>()
            val pitches = c.sortedBy { it.step }.map { head ->
                val d = carry.clef.at(head.step)
                val acc = accidental(clean, s, head)
                if (acc != null) { accs[head.step] = acc; written[d] = acc }
                val alter = written[d] ?: carry.key.alterOf(d.mod(7))
                Pitch.fromDiatonic(d, alter)
            }
            events += Note(c.map { it.step }.sorted(), pitches, dur, c.minOf { it.x }.toFloat(), accs, h.up.takeIf { h.stemX >= 0 }, c.minOf { it.score })
        }
        // Rests, where no note is.
        val rests = ArrayList<Pair<Rest, Float>>()
        // Strokes the staff's full height - a barline's thick stroke, a repeat's, a stem - where no
        // rest can be: none is that tall.
        val upright = (from until to).filter { x -> val a = s.lineY(0, x).roundToInt(); val b = s.lineY(4, x).roundToInt(); (a..b).count { clean[x, it] } >= (b - a + 1) * 0.92f }
        // Quarter, eighth and sixteenth rests are moved a space up or down to make room for
        // another voice's notes: looked for there too, held to a little more.
        val shifted = listOf(4 to 0f, 2 to 0.04f, 6 to 0.04f, 3 to 0.06f, 5 to 0.06f)
        for ((name, base, step0) in listOf(Triple("restQuarter", 4, 4), Triple("rest8th", 8, 4), Triple("rest16th", 16, 4), Triple("restHalf", 2, 4), Triple("restWhole", 1, 2))) {
            for (x in from until to) {
                if (taken.any { (a, b) -> x in a - (sp * 0.5f).toInt()..b }) continue
                if (upright.any { abs(it - x) < sp * 0.6f || (it > x && it < x + sp * 1.2f) }) continue
                var sc = -1f; var at = step0
                for ((step, extra) in if (base >= 4) shifted else listOf(step0 to 0f)) {
                    val v = score(clean, name, sp, x, s.y(step, x).roundToInt()) - extra
                    if (v > sc) { sc = v; at = step }
                }
                // Rests differ from font to font more than heads do; the small rectangles (whole,
                // half) are easily matched by other things, so they are held to more.
                // An eighth or sixteenth rest in another font's shape fits a little less: taken if it
                // has the round hook no flag has.
                val hooked = (base == 8 || base == 16) && sc in 0.48f..0.58f && hooks(s, x, (MusicGlyphs[name].advance * sp).toInt()) >= 1
                // Nor where a beam runs through: a long bar across the rest's place.
                val beam = base >= 4 && (s.y(0, x).roundToInt()..s.y(8, x).roundToInt()).any { yy -> (x..x + (sp * 1.6f).toInt()).all { (solid ?: clean)[it, yy] } }
                if ((sc > (if (base <= 2) 0.72f else 0.58f) || hooked) && !beam) {
                    val right = x + (MusicGlyphs[name].advance * sp).toInt()
                    val dots = if (dotIn(clean, sp, right, s.y(2, right).roundToInt(), right + (sp * 1.0f).toInt(), s.y(4, right).roundToInt())) 1 else 0
                    // An eighth or sixteenth rest is told by its hooks - one, two - counted, not by
                    // which font's outline fitted it best.
                    val kind = if (base == 8 || base == 16) when (hooks(s, x, (MusicGlyphs[name].advance * sp).toInt())) { 1 -> 8; 2 -> 16; 3 -> 32; else -> base } else base
                    rests += Rest(Duration(kind, dots), x.toFloat()) to sc
                }
            }
        }
        // Half and whole rests in any font: a solid block a space or so wide, sitting on the middle
        // line (half) or hanging from the line above it (whole).
        rests += blockRests(clean, s, from, to, taken)
        // A sixteenth rest is an eighth rest and a hook more: the eighth's shape fits its top as
        // well. Where a sixteenth fits nearly as well in the same place, it is one.
        rests.removeAll { (r, sc) -> r.duration.base == 8 && rests.any { (o, osc) -> o.duration.base == 16 && abs(o.x - r.x) < sp * 0.6f && osc >= sc - 0.08f } }
        rests.sortByDescending { it.second }
        val keptRests = ArrayList<Rest>()
        for ((r, _) in rests) if (keptRests.none { abs(it.x - r.x) < sp * 1.2f }) keptRests += r
        // A whole rest stands alone in its bar and fills it, whatever the time; among notes it is
        // something else.
        val whole = keptRests.filter { it.duration.base == 1 }
        if (events.isEmpty() && whole.isNotEmpty()) return listOf(whole.first())
        events += keptRests.filter { it.duration.base != 1 }
        return events.sortedBy { it.x }
    }
}
