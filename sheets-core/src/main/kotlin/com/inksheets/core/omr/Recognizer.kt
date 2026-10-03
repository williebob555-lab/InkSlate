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

        /** This staff seen [scale] times as large about its middle line and [shift] spaces lower: a second look at the same print. */
        fun looked(scale: Float, shift: Float): Staff = Staff(left, right, Array(5) { l ->
            FloatArray(lines[l].size) { i -> val mid = lines[2][i]; mid + (lines[l][i] - mid) * scale + shift * space }
        }, space * scale)
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
        // Two staves never share their lines' height where both run. Of two that do: one lying
        // within the other's length is none (traced through a close-set page's beams and ledger
        // lines, lined up); two reaching past each other are one staff in two pieces (a scan's page
        // curling, the line followed at two heights) - joined, the one easing into the other.
        val kept = ArrayList<Staff>()
        for (st in staves.sortedByDescending { it.right - it.left }) {
            val i = kept.indexOfFirst { o ->
                val l = max(o.left, st.left); val r = min(o.right, st.right)
                if (r <= l) return@indexOfFirst false
                val x = (l + r) / 2
                o.lineY(0, x) < st.lineY(4, x) && st.lineY(0, x) < o.lineY(4, x)
            }
            if (i < 0) { kept += st; continue }
            val o = kept[i]
            val inside = st.left >= o.left - space * 2 && st.right <= o.right + space * 2
            // (Joined only where neither is a whole staff: a page's staves run its width; a piece of
            // one, short of it - beside a whole one, what lies across it is none.)
            val widest = staves.maxOf { it.right - it.left }
            if (inside || o.right - o.left >= widest * 0.9f) continue
            // Joined: each piece's lines where it alone runs, and from one to the other across where both do.
            val (a, b) = if (o.left <= st.left) o to st else st to o
            val left = a.left; val right = max(a.right, b.right)
            val l0 = b.left; val r0 = min(a.right, b.right)
            val lines = Array(5) { line -> FloatArray(right - left + 1) { k ->
                val x = left + k
                when {
                    x < l0 -> a.lineY(line, x)
                    x > r0 -> if (b.right >= a.right) b.lineY(line, x) else a.lineY(line, x)
                    else -> { val f = (x - l0).toFloat() / max(1, r0 - l0); a.lineY(line, x) * (1 - f) + b.lineY(line, x) * f }
                }
            } }
            kept[i] = Staff(left, right, lines, (a.space + b.space) / 2)
        }
        return kept.sortedBy { it.top }
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
        erased.clear()
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
        if (b - a + 1 <= t + 2) for (yy in a..b) { ink[x, yy] = false; erased.add(x, yy) }
    }

    /** Every pixel the last [withoutLines] took out (x, y pairs): where a stroke crossing a line may need putting back. */
    private val erased = IntPairs()

    /** A growing list of x, y pairs without a boxed number per pixel. */
    private class IntPairs {
        var data = IntArray(1024); var size = 0
        fun add(x: Int, y: Int) { if (size + 2 > data.size) data = data.copyOf(data.size * 2); data[size++] = x; data[size++] = y }
        fun clear() { size = 0 }
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
            // A head's stem runs from the head one way only: a line going on well past the head the
            // other way too is a barline the head stands against (a pickup's last note), not its stem.
            val isStem = if (heads.none { it.stemX >= 0 }) stems.any { abs(it - centre) <= t + 2 }
                else heads.any { h ->
                    h.stemX >= 0 && abs(h.stemX - centre) <= t + 2 &&
                        (if (h.stemEnd < h.y) runBottom - h.y else h.y - runTop) <= s.space * 1.2f
                }
            // Through a head's middle half: a head found a few pixels off, or a wide whole note, may reach a barline at its edge.
            val throughHead = heads.any { h -> val w = tpl(h.kind, s.space).ink.width; centre in h.x + w / 4..h.x + 3 * w / 4 }
            val headNear = isStem || throughHead || (stems.isEmpty() && listOf(runTop, runBottom).any { yEnd -> touching(-1, yEnd) || touching(1, yEnd) })
            val staysOnStaff = above < s.space * 0.6f && below < s.space * 0.6f
            val reachesAnotherStaff = above > s.space * 3 || below > s.space * 3
            if (debug) println("bar? x=$centre w=$width above=$above below=$below thin=$thin head=$headNear (stem $isStem, through ${heads.filter { h -> val w = tpl(h.kind, s.space).ink.width; centre in h.x + w / 4..h.x + 3 * w / 4 }.map { "${it.kind}@${it.x},${it.step}" }})")
            if (thin && (staysOnStaff || reachesAnotherStaff) && !headNear) {
                if (found.isEmpty() || centre - found.last() > s.space * 1.5f) found += centre
                else found[found.size - 1] = centre   // a double or final barline: its last stroke
            }
            x = x2 + 1
        }
        // A time signature changed at a bar's start: its digits' upright strokes, just after the
        // barline, are no barline of their own - two lines under three spaces apart with ink in both
        // halves of the staff between them (a signature's two figures) are one barline and a signature.
        return found.filterIndexed { i, b ->
            val a = found.getOrNull(i - 1) ?: return@filterIndexed true
            if (b - a >= s.space * 3.2f) return@filterIndexed true
            val x0 = a + (s.space * 0.3f).toInt(); val x1 = b - (s.space * 0.3f).toInt()
            if (x1 <= x0) return@filterIndexed true
            var upper = 0; var lower = 0; var n = 0
            for (xx in x0..x1) {
                val tp = s.lineY(0, xx).roundToInt(); val md = s.lineY(2, xx).roundToInt(); val bt = s.lineY(4, xx).roundToInt()
                for (y in tp until md) if (clean[xx, y]) upper++
                for (y in md until bt) if (clean[xx, y]) lower++
                n += md - tp
            }
            !(n > 0 && upper >= n * 0.12f && lower >= n * 0.12f)
        }
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
    private fun hollow(ink: Ink, space: Float, x: Int, y: Int, shape: String, font: Boolean = false, lines: Ink? = null): Float {
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
            if (m.ring[p]) { if (ink[x - tp.ox + i, y - tp.oy + j] || lines != null && onLine(lines, x - tp.ox + i, y - tp.oy + j, space)) ringInk++ }
            else if (m.core[p]) { if (!ink[x - tp.ox + i, y - tp.oy + j]) coreClear++ }
        }
        val r = ringInk.toFloat() / m.ringCount
        val c = coreClear.toFloat() / m.coreCount
        // The ring need not be whole (a thin hollow head's sides), the middle must be clear.
        return if (c < 0.55f) 0f else (r * 0.6f + c * 0.4f)
    }

    /** The page's staff line thickness, as [read] found it. */
    private var lineThickness = 0

    /**
     * Whether ([x], [y]) is ink [withoutLines] took for a staff or ledger line that something
     * thin touches: a head in a space has its thin top and bottom on the lines, and the line
     * taken away takes them too - but there the ink is thicker than the bare line either side
     * of it (a scan's lines are not the same thickness all along).
     */
    private fun onLine(lines: Ink, x: Int, y: Int, space: Float): Boolean {
        if (lineThickness <= 0 || !lines[x, y]) return false
        fun runAt(xx: Int, yy: Int): Pair<Int, Int> {
            var a = yy; while (lines[xx, a - 1] && yy - a < lineThickness * 3) a--
            var b = yy; while (lines[xx, b + 1] && b - yy < lineThickness * 3) b++
            return a to b
        }
        val (a, b) = runAt(x, y)
        // The bare line a head's width off each way: the thinnest run through where this one is.
        val off = (space * 1.3f).toInt()
        val bare = listOf(x - off, x + off).mapNotNull { xx -> (a..b).firstOrNull { lines[xx, it] }?.let { yy -> runAt(xx, yy).let { (p, q) -> q - p + 1 } } }
            .filter { it <= lineThickness + 1 }.minOrNull() ?: lineThickness
        return b - a + 1 in bare + 2..bare + 5
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

    /** How far ink runs left and right through ([x], [y]), up to [limit] each way; 0 on paper. */
    private fun run(ink: Ink, x: Int, y: Int, limit: Int): Int {
        if (!ink[x, y]) return 0
        var l = x; while (ink[l - 1, y] && x - l < limit) l--
        var r = x; while (ink[r + 1, y] && r - x < limit) r++
        return r - l + 1
    }

    /** Whether the middle of a filled head at ([x], [y]) - its shape worn in a fifth of a space all round - is all ink. */
    private fun solidCore(ink: Ink, sp: Float, x: Int, y: Int, share: Float = 0.97f): Boolean {
        val tp = tpl("noteheadBlack", sp)
        val w = tp.ink.width; val h = tp.ink.height
        val r = max(1, (sp * 0.2f).roundToInt())
        var n = 0; var got = 0
        for (j in 0 until h) for (i in 0 until w) {
            if ((-r..r).any { b -> (-r..r).any { a -> !tp.ink[i + a, j + b] } }) continue
            n++
            if (ink[x - tp.ox + i, y - tp.oy + j]) got++
        }
        return n > 0 && got >= n * share
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
    fun explainHollow(clean: Ink, s: Staff, x: Int, y: Int, lines: Ink? = null): String {
        val sp = s.space
        val headW = tpl("noteheadBlack", sp).ink.width - 2
        var best = ""; var bestRing = -1f
        for (dx in -4..4) for (dy in -2..2) {
            val xx = x + dx; val yy = y + dy
            val ring = hollow(clean, sp, xx, yy, "noteheadBlack", lines = lines)
            if (ring <= bestRing) continue
            bestRing = ring
            val half = score(clean, "noteheadHalf", sp, xx, yy)
            val whole = score(clean, "noteheadWhole", sp, xx, yy)
            best = "ring ${"%.2f".format(ring)} (font ${"%.2f".format(hollow(clean, sp, xx, yy, "noteheadBlack", font = true))}), " +
                "half ${"%.2f".format(half)} hole ${"%.2f".format(holeClear(clean, "noteheadHalf", sp, xx, yy))}, " +
                "whole ${"%.2f".format(whole)} hole ${"%.2f".format(holeClear(clean, "noteheadWhole", sp, xx, yy))}, " +
                "wide ${"%.2f".format(hollow(clean, sp, xx, yy, "noteheadWhole", lines = lines))}, solid ${solid?.let { solidCore(it, sp, xx, yy, 0.85f) }}, " +
                "accidental-like ${accidentalLike(clean, sp, xx, yy, headW)}, alone ${standsAlone(clean, sp, xx, yy, headW)}, " +
                "sides ${(xx..xx + max(2, headW / 4)).any { clean[it, yy] } && (xx + headW - max(2, headW / 4)..xx + headW).any { clean[it, yy] }} at ${dx},${dy}"
        }
        return best
    }

    /**
     * What the stem search makes of a black head with its left edge at [x] and middle at [y] on [s]:
     * the stem's runs up and down, its end, and the strokes met beside it - for finding out why a
     * note's value was read wrong.
     */
    private var explaining = false

    fun explainValue(clean: Ink, s: Staff, x: Int, y: Int): String {
        val sp = s.space
        val step = ((y - s.lineY(0, x)) / (sp / 2)).roundToInt()
        val h = Head(x, step, y, "noteheadBlack", 1f)
        explaining = true
        try { stem(clean, s, h, 0) } finally { explaining = false }
        if (h.stemX < 0) return "no stem"
        val len = abs(h.stemEnd - h.y)
        val cols = listOf(-(sp * 0.55f).toInt(), (sp * 0.55f).toInt()).joinToString(" | ") { dx ->
            val cx = h.stemX + dx
            val span = (sp * 3.5f).toInt()
            // The column beside the stem from past its end back toward the head: runs of ink (length@offset).
            val runs = ArrayList<String>(); var run = 0
            for (k in -(sp * 0.8f).toInt()..span) {
                val yy = if (h.up) h.stemEnd + k else h.stemEnd - k
                if (clean[cx, yy]) run++ else { if (run > 0) runs += "$run@${k - run}"; run = 0 }
            }
            if (run > 0) runs += "$run@${span - run}"
            "dx $dx: ${runs.joinToString(",")}"
        }
        return "stem ${if (h.up) "up" else "down"} at ${h.stemX}, ${"%.1f".format(len / sp)} sp, flags ${h.flags}; sp ${"%.1f".format(sp)}; $cols"
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
        /** A hollow head whole only with the staff lines' ink its top and bottom were on (see onLine). */
        var lined = false
    }

    /**
     * Whether head [h] could be a note where it stands: on or near the staff, or off it standing on
     * the ledger line at the staff's edge ([lines]: the page with its staff and ledger lines) -
     * without one it is a dynamic's loop, an accent, a word's letter, not a note.
     */
    /**
     * Whether head [h] has a stem: an upright stroke at its left or right side, from about its middle,
     * two and a half spaces long or more - a note's, where a letter of a word has none.
     */
    private fun hasStem(h: Head, s: Staff, lines: Ink): Boolean {
        val sp = s.space
        val w = tpl(h.kind, sp).ink.width
        val need = (sp * 2.5f).toInt()
        for (x in listOf(h.x - 2..h.x + 2, h.x + w - 3..h.x + w + 2).flatten()) for (dir in listOf(-1, 1)) {
            var y = h.y; var gap = 0; var len = 0
            while (len < need && gap <= 2) { y += dir; if (lines[x, y]) { len++; gap = 0 } else { gap++; len++ } }
            if (len >= need && gap <= 2) return true
        }
        return false
    }

    private fun onLedger(h: Head, s: Staff, lines: Ink): Boolean {
        if (h.step in -1..9) return true
        val w = tpl(h.kind, s.space).ink.width - 2
        val ledger = if (h.step < 0) -2 else 10
        val ly = s.y(ledger, h.x + w / 2).roundToInt()
        // A horizontal line under (or through) the head, at least as long as it is wide.
        return (-1..1).any { dy ->
            val y = ly + dy
            var a = h.x + w / 2; var b = a
            if (!lines[a, y]) return@any false
            while (lines[a - 1, y] && a > h.x - w) a--
            while (lines[b + 1, y] && b < h.x + 2 * w) b++
            b - a + 1 >= w * 0.9f
        }
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
                    // No head has solid ink running on past both its sides along its middle: that is a
                    // beam, or a multi-bar rest's bar.
                    val reach = (sp * 0.6f).toInt()
                    val barred = (-1..1).all { dy -> (1..reach).all { k -> body[x - k, y + dy] && body[x + headW + k, y + dy] } }
                    // A head is a head's width along its middle: a rest's round hook, however thick a scan makes it, is half that.
                    val across = run(body, x + headW / 2, y, (sp * 2).toInt())
                    val wide = across >= sp * 0.85f
                    if (sc > 0.68f && !barred && wide) found += Head(x, step, y, "noteheadBlack", sc)
                    else if (adapt && wide && sc > 0.5f && !filledOnly && solidCore(body, sp, x, y) && !accidentalLike(clean, sp, x, y, headW) &&
                        // Not a piece of a beam: ink that runs on past both sides of the head.
                        !(-1..1).all { dy -> clean[x - (sp * 0.35f).toInt(), y + dy] && clean[x + headW + (sp * 0.35f).toInt(), y + dy] })
                        found += Head(x, step, y, "noteheadBlack", sc).also { it.weak = true }
                    // Rhythm-only notes: an x (spoken, clapped, a percussion part) or a slash (play time here).
                    // Thin strokes, so matched on the page as drawn; kept only with a stem of their own, as a
                    // sharp's crossing strokes are no note.
                    if (!filledOnly && sc <= 0.68f) for (kind in listOf("noteheadXBlack", "noteheadSlashHorizontalEnds")) {
                        val xs = score(clean, kind, sp, x, y)
                        if (xs > 0.62f && !accidentalLike(clean, sp, x, y, headW)) found += Head(x, step, y, kind, xs).also { it.weak = true }
                    }
                }
                // A filled head a scan has flecked with white is not hollow: solid once the flecks are filled.
                val solidHere = solid?.let { solidCore(it, sp, x, y, 0.85f) } == true
                if (sides && !filledOnly && !solidHere) {
                    for (kind in listOf("noteheadHalf", "noteheadWhole")) {
                        val sc = score(clean, kind, sp, x, y)
                        if (sc > 0.66f && holeClear(clean, kind, sp, x, y) > 0.6f) found += Head(x, step, y, kind, sc).also { it.crowded = !standsAlone(clean, sp, x, y, tpl(kind, sp).ink.width - 2) }
                    }
                    // Any engraver's hollow head: a head-shaped ring of ink round a clear middle -
                    // and not a flat's or natural's bowl, whose strokes rise on the left or fall on
                    // the right, as no note's stem does.
                    if (!accidentalLike(clean, sp, x, y, headW)) {
                        // The page's own head's shape, or the font's - a whole note is rounder than either.
                        fun ringAt(l: Ink?) = max(hollow(clean, sp, x, y, "noteheadBlack", lines = l), if (fitted != null) hollow(clean, sp, x, y, "noteheadBlack", font = true, lines = l) else 0f)
                        val ring = ringAt(lines)
                        if (ring > 0.72f) found += Head(x, step, y, "noteheadHalf", ring).also { it.crowded = !standsAlone(clean, sp, x, y, headW); it.lined = ringAt(null) <= 0.72f }
                        val wide = hollow(clean, sp, x, y, "noteheadWhole", lines = lines)
                        if (wide > 0.66f) found += Head(x, step, y, "noteheadWhole", wide).also { it.crowded = !standsAlone(clean, sp, x, y, tpl("noteheadWhole", sp).ink.width - 2); it.lined = hollow(clean, sp, x, y, "noteheadWhole") <= 0.66f }
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
        if (lines != null) found.retainAll { h -> onLedger(h, s, lines) }
        // A hollow head with strokes running off it is a letter's, a digit's or a clef's loop - unless
        // another hollow head sits right above or below it: a chord's heads touch each other.
        found.removeAll { h -> h.crowded && found.none { o -> o !== h && o.kind != "noteheadBlack" && abs(o.x - h.x) <= headW / 3 && abs(o.step - h.step) in 2..3 &&
            standsAlone(clean, sp, h.x, h.y, tpl(h.kind, sp).ink.width - 2, besides = o) } }
        // A head made whole by the lines beside a head found without them is that head's ring and a
        // tie's curve touching it - a note's second head is found on its own. Off the staff, where
        // only one line runs by, a tie's curve over a note makes one too.
        found.removeAll { h -> h.lined && found.any { o -> o !== h && abs(o.x - h.x) < headW * 1.3f && (!o.lined && abs(o.step - h.step) in 1..2 || h.step !in 1..7 && o.step in 1..7) } }
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
    private fun digit(clean: Ink, s: Staff, x0: Int, x1: Int, steps: List<Int>, allowed: Set<Int> = (0..9).toSet(), least: Float = 0.5f): Pair<Int, Int>? {
        // Each digit's best fit, at whatever size and place.
        class Fit(val d: Int, val x: Int, val y: Int, val sp: Float, val sc: Float)
        val fits = HashMap<Int, Fit>()
        for (size in listOf(1f, 0.85f, 1.15f, 0.7f)) {
            val sp = s.space * size
            for (d in allowed) for (xx in x0..x1) for (step in steps) {
                val y = s.y(step, xx).roundToInt()
                val sc = score(clean, "timeSig$d", sp, xx, y)
                if (sc > least && sc > (fits[d]?.sc ?: 0f)) fits[d] = Fit(d, xx, y, sp, sc)
            }
        }
        val best = fits.values.maxByOrNull { it.sc } ?: return null
        // A "1" is a thin upright, and fits the upright of a printed 4 or 3 as well as a 1: where
        // another digit accounts for clearly more of the figure's ink, it is that digit.
        if (best.d == 1) {
            fun covers(f: Fit): Float {
                val tp = MusicGlyphs.template("timeSig${f.d}", f.sp)
                val left = f.x - tp.ox; val top = f.y - tp.oy
                val padX = tp.ink.width / 2; val padY = tp.ink.height / 8
                var ink = 0; var mine = 0
                for (yy in top - padY until top + tp.ink.height + padY) for (xx in left - padX until left + tp.ink.width + padX) {
                    if (!clean[xx, yy]) continue
                    ink++
                    if (tp.ink[xx - left, yy - top]) mine++
                }
                return if (ink == 0) 0f else mine.toFloat() / ink
            }
            val mine = covers(best)
            fits.values.filter { it.d != 1 }.maxByOrNull { covers(it) }?.let { alt -> if (covers(alt) > mine + 0.25f) return alt.d to alt.x }
        }
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
            // And clear on the line's other side: an eighth rest's solid head sits on the middle line
            // too, but its stroke runs on down; a whole rest hangs from its line with nothing over it.
            val across = if (kind == 2) mid + (sp * 0.35f).toInt() else second - (sp * 0.35f).toInt()
            val clearAcross = (x..x2).count { clean[it, across] } <= w / 5
            if (w >= sp * 0.8f && w <= sp * 1.7f && n > 0 && filled >= n * 0.8f && (if (kind == 2) clearAbove else clearBelow) && clearAcross) {
                out += Rest(Duration(kind), x.toFloat(), if (kind == 2) 4 else 2) to 0.8f
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
        // Only the rest's left part: its hooks hang there, off the slanting stem.
        val x0 = x - (sp * 0.2f).toInt(); val x1 = x + (w * 0.75f).toInt()
        val y0 = s.y(-1, x).roundToInt(); val y1 = s.y(9, x).roundToInt()
        val seen = HashSet<Int>()
        val blobs = ArrayList<Pair<Int, Int>>()   // middle x, middle y
        for (yy in y0..y1) for (xx in x0..x1) {
            if (!ink[xx, yy] || (yy * ink.width + xx) in seen) continue
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
            // Round and solid, a hook's size: not a speck, not the stem.
            if (bw in (sp * 0.28f).toInt()..(sp * 0.75f).toInt() && bh in (sp * 0.28f).toInt()..(sp * 0.75f).toInt() &&
                bw.toFloat() / bh in 0.6f..1.6f && size >= bw * bh * 0.6f) blobs += (l + r) / 2 to (t + b) / 2
        }
        if (blobs.isEmpty()) return 0
        // Hooks go down a space at a time, each a little left of the one above.
        blobs.sortBy { it.second }
        var n = 1
        var last = blobs[0]
        for (bl in blobs.drop(1)) {
            val dy = bl.second - last.second
            if (dy >= sp * 0.6f && dy <= sp * 1.4f && abs(bl.first - last.first) <= sp * 0.8f) { n++; last = bl }
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

    /**
     * The shortest run taken for a stem, in spaces: most engravers' stems are three and a half
     * spaces, some editions' barely two - so set from this page's own filled heads (see [read]).
     */
    var stemMin = 2.2f
        private set

    /**
     * Bar [m] read again on the page it is on ([ink], [grey]), its staff only, a few other ways - the
     * staff seen a little larger, smaller, higher, lower; [deeper]: further still (when the readings
     * offered first were all turned down). Each look's reading of the bar, where it found the bar.
     */
    /**
     * Bars [bars] - all on one staff, [first] that staff's first bar as read - each looked at again
     * as [lookAgain] looks at one, but the staff read once for each look rather than once a bar: what
     * each look saw of each, by its number (numbered as read).
     */
    fun lookAgainStaff(ink: Ink, grey: IntArray, net: Net, bars: List<Measure>, first: Measure, deeper: Boolean = false): Map<Int, List<Measure>> {
        val looks = if (deeper) listOf(1.15f to 0f, 0.87f to 0f, 1.08f to 0.25f, 0.93f to -0.25f) else listOf(1.08f to 0f, 0.93f to 0f, 1f to 0.15f, 1f to -0.15f)
        val out = HashMap<Int, MutableList<Measure>>()
        for (look in looks) {
            val r = Recognizer().read(ink, first.page, first.number, Carry(first.clef, first.key, first.time), grey = grey, net = net, look = look, onlyStaff = first.staff)
            for (m in bars) r.measures.firstOrNull { o -> o.staff == m.staff && o.bars == 1 && abs(o.box.left - m.box.left) <= m.space * 0.6f && abs(o.box.right - m.box.right) <= m.space * 0.6f }
                ?.let { out.getOrPut(m.number) { ArrayList() } += it.copy(number = m.number) }
        }
        return out
    }

    fun lookAgain(ink: Ink, grey: IntArray, net: Net, m: Measure, deeper: Boolean = false): List<Measure> {
        val looks = if (deeper) listOf(1.15f to 0f, 0.87f to 0f, 1.08f to 0.25f, 0.93f to -0.25f) else listOf(1.08f to 0f, 0.93f to 0f, 1f to 0.15f, 1f to -0.15f)
        return looks.mapNotNull { look ->
            val r = Recognizer().read(ink, m.page, m.number, Carry(m.clef, m.key, m.time), grey = grey, net = net, look = look, onlyStaff = m.staff)
            r.measures.firstOrNull { o -> o.staff == m.staff && o.bars == 1 && abs(o.box.left - m.box.left) <= m.space * 0.6f && abs(o.box.right - m.box.right) <= m.space * 0.6f }
        }
    }

    /** Bars read as repeat signs, by their index in the page's bars: where the sign stood (see barRepeat) - checked once the line is read. */
    private val repeatSide = HashMap<Int, Int>()
    private val repeatEvents = HashMap<Int, List<Event>>()

    /** The last look for a time signature found its ink, figures read or not (see timeAt). */
    private var timeInk = false

    /** The last multi-bar rest's count read two ways that differ (see multiRest): asked about. */
    private var restFigureDoubted = false

    /** Say why each bar was or was not taken for a multi-bar rest (for finding out). */
    var traceRests = System.getProperty("inksheets.omr.tracerests") != null

    /** How long the last [read]'s stages took, for finding what is slow: "staves 40 ms, ...". */
    var timings = ""
        private set

    private class Clock {
        private var last = System.nanoTime()
        private val parts = ArrayList<String>()
        /** Time spent in named parts done many times over (summed). */
        val sums = HashMap<String, Long>()
        inline fun <T> sum(name: String, f: () -> T): T { val a = System.nanoTime(); try { return f() } finally { sums[name] = (sums[name] ?: 0L) + System.nanoTime() - a } }
        fun mark(name: String) { val now = System.nanoTime(); parts += "$name ${(now - last) / 1_000_000}"; last = now }
        override fun toString() = parts.joinToString(", ") + " ms" + if (sums.isEmpty()) "" else " (of which " + sums.entries.joinToString { "${it.key} ${it.value / 1_000_000}" } + ")"
    }

    /** The page being read as printed (staff lines and all). */
    private var pageInk: Ink? = null

    /** The page in grey being read, where given: how dark what a cleaned bar keeps is printed. */
    private var pageGrey: IntArray? = null

    /** [stem]'s run from [h] (the longer of up and down), without taking it for a stem. */
    private fun stemLength(clean: Ink, s: Staff, h: Head, t: Int): Int {
        val keep = stemMin
        stemMin = Float.MAX_VALUE
        try { return stem(clean, s, h, t) } finally { stemMin = keep }
    }

    /** The stems of [heads]' filled heads measured: [stemMin] at seven tenths of their usual length, within 1.5 to 2.2 spaces. */
    private fun fitStemMin(clean: Ink, staves: List<Staff>, heads: List<List<Head>>, t: Int) {
        stemMin = 2.2f
        val lens = ArrayList<Float>()
        for ((si, hs) in heads.withIndex()) for (h in hs) if (h.kind == "noteheadBlack" && !h.weak) {
            val len = stemLength(clean, staves[si], h, t) / staves[si].space
            if (len >= 1.2f) lens += len
        }
        if (lens.size < 8) return
        lens.sort()
        stemMin = (lens[lens.size / 2] * 0.55f).coerceIn(1.5f, 2.2f)
    }

    private fun stem(clean: Ink, s: Staff, h: Head, t: Int): Int {
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
            // (At least a staff line's thickness and a little: where a line was taken out of a faint stem.)
            val wideGap = max((sp * 0.4f).toInt(), lineThickness + 3); val steady = (sp * 0.4f).toInt()
            while (true) {
                if (clean[cx, yy]) { yy += dir; continue }
                val side = listOf(cx - 1, cx + 1).firstOrNull { abs(it - x) <= 2 && clean[it, yy] }
                if (side != null) { cx = side; continue }
                val resumes = (1..gap).firstOrNull { k -> (cx - 1..cx + 1).any { abs(it - x) <= 2 && clean[it, yy + dir * k] && clean[it, yy + dir * (k + 1)] } }
                    // A wider gap - a scan's stem faded, or a staff line taken out of it - where the stem
                    // carries on straight a good way beyond it.
                    ?: (gap + 1..wideGap).firstOrNull { k -> (cx - 1..cx + 1).any { c -> abs(c - x) <= 2 && (0..steady).all { j -> clean[c, yy + dir * (k + j)] } } }
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
        if (debug || explaining) println("    stem? head ${h.x},${h.y} w=$w up $up at $upX, down $down at $downX; " +
            "down runs ${(h.x - (w * 0.25f).toInt() - 1..h.x + (w * 0.4f).toInt()).map { it to run(it, h.y + (sp * 0.3f).toInt(), 1) }}")
        if (len < sp * stemMin) return len
        h.up = up >= down
        h.stemX = if (h.up) upX else downX
        h.stemEnd = if (h.up) h.y - (sp * 0.3f).toInt() - up else h.y + (sp * 0.3f).toInt() + down
        // Beams or flags: separate strokes crossing a column just beside the stem, near its end.
        // A run of ink as tall as two beams and the gap between them, running on level (a beam, not a
        // flag's steep curve), is two beams a scan has run together.
        // A stroke that reaches this stem: followed from the column beside it back to the stem, a
        // pixel or two up or down at each step. A neighbour's short beam stops before it.
        fun reaches(cx: Int, y: Int): Boolean {
            var yy = y
            val step = if (h.stemX > cx) 1 else -1
            var x = cx
            var blank = 0
            while (abs(x - h.stemX) > 1) {
                x += step
                val next = listOf(0, -1, 1, -2, 2).map { yy + it }.firstOrNull { clean[x, it] }
                // A fleck of white in a scan's beam is stepped over; a real end is not.
                if (next == null) { if (++blank > 2) return false } else { yy = next; blank = 0 }
            }
            return true
        }
        fun strokes(cx: Int, after: Int, inRun: Int): Int {
            if (inRun < sp * 0.25f || inRun > sp * 2.2f) return 0
            val mid = if (h.up) after - inRun / 2 else after + inRun / 2
            if (!reaches(cx, mid)) return 0
            if (inRun < sp * 1.0f) return 1
            val level = (-(sp * 0.4f).toInt()..(sp * 0.4f).toInt()).all { d -> clean[cx + d, mid] }
            return if (level && inRun <= sp * 1.7f) 2 else 1
        }
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
                if (clean[cx, y]) inRun++ else { count += strokes(cx, y, inRun); inRun = 0 }
            }
            count += strokes(cx, if (h.up) h.stemEnd + span + 1 else h.stemEnd - span - 1, inRun)
            most = max(most, count)
        }
        h.flags = most.coerceAtMost(3)
        return len
    }

    private fun dots(clean: Ink, s: Staff, h: Head) {
        if (printed != null) { printedDots(s, h); return }
        val sp = s.space
        val w = tpl(h.kind, sp).ink.width
        // Beside the head, in the space it is in or the one above - some engravers leave most of a
        // space between; a staccato dot over the next note is further off.
        val x0 = h.x + w - 1; val x1 = h.x + w + (sp * 1.2f).toInt()
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
        if (printed != null) return printedAccidental[h]
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
    fun read(ink: Ink, page: Int = 0, firstNumber: Int = 1, carry: Carry = Carry(), printed: Printed? = null,
             /** The page in grey (0-255, as [ink]'s size) and the trained reader: its symbols read with it, where no PDF states them. */
             grey: IntArray? = null, net: Net? = null,
             /** A second look: each staff as the trained reader sees it, a little larger or smaller, higher or lower (see [Staff.looked]). */
             look: Pair<Float, Float>? = null,
             /** Only this staff read (a second look at one bar): the rest of the page left alone. */
             onlyStaff: Int? = null,
             /** A picture of a page turned on the glass read turned upright (see [Skew]); off once it is. */
             level: Boolean = true): PageReading {
        // A scan or photo turned more than a little: read upright, and what was read put back on the page.
        if (level && printed == null && look == null && onlyStaff == null) {
            val deg = Skew.of(ink)
            if (kotlin.math.abs(deg) >= Skew.LEAST) {
                val upright = read(Skew.turn(ink, deg), page, firstNumber, carry, null, grey?.let { Skew.turnGrey(it, ink.width, ink.height, deg) }, net, level = false)
                return Skew.back(upright, deg, ink.width, ink.height)
            }
        }
        val (t, space) = metrics(ink) ?: return PageReading(emptyList(), emptyList(), emptyList(), 1, 0f)
        val startTime = carry.time
        val clock = Clock()
        val staves = staves(ink, t, space)
        clock.mark("staves")
        // What the PDF says is printed, where it says it: heads, rests, accidentals, dots, flags,
        // stems and beams taken as stated rather than found. Staves, barlines, clefs and keys are
        // still read from the picture.
        pageGrey = grey?.takeIf { it.size == ink.width * ink.height }
        pageInk = ink
        this.printed = printed?.scaled(ink.width)?.let { aligned(it, staves) }
            ?: if (net != null && grey != null && staves.isNotEmpty()) Learned.symbols(grey, ink.width, ink.height, staves.filterIndexed { i, _ -> onlyStaff == null || i == onlyStaff }.map { if (look == null) it else it.looked(look.first, look.second) }, net) else null
        this.staves = staves
        symbolOf.clear(); fullSize.clear(); cueAt.clear(); printedAccidental.clear(); usedStems.clear(); claimedMarks.clear(); graceAt.clear(); normalHead.clear(); secondOut.clear(); repeatSide.clear(); repeatEvents.clear()
        val clean = withoutLines(ink, staves, t)
        clock.mark("lines out")
        lineThickness = t
        // Heads found by their shapes need these; symbols given (a PDF's, the trained reader's) do not.
        solid = if (adapt && staves.isNotEmpty() && this.printed == null) clean.opened(max(1, (staves.first().space * 0.11f).roundToInt())) else null
        if (adapt && this.printed == null) fitHeads(clean, staves, ink) else fitted = null
        val measures = ArrayList<Measure>()
        // Which of [measures] are multi-bar rests: their counts may be put right by the next line's number.
        val multiAt = ArrayList<Int>()
        val bars = ArrayList<List<Int>>()
        var number = firstNumber
        // Every staff's heads first: a note high over one staff is also low under the one above,
        // and belongs to whichever it is nearer the middle of.
        clock.mark("symbols")
        val headsOf = if (this.printed != null) printedHeads(staves, ink, clean) else staves.map { s -> heads(clean, s, s.left, s.right, ink).toMutableList() }
        clock.mark("heads")
        // Stems now, and a hollow "head" at another note's stem end is its flag's curl.
        if (this.printed == null) fitStemMin(clean, staves, headsOf, t)
        // A PDF that states its notes: key, time, barlines and marks from it too; one the trained reader read: from the picture.
        val stated = this.printed?.learned == false
        for ((si, hs) in headsOf.withIndex()) for (h in hs) if (h.kind != "noteheadWhole") {
            if (this.printed != null) printedStem(staves[si], h) else stem(clean, staves[si], h, t)
            // The trained reader says what the stem carries; where the stem itself stands is looked
            // for on the page (a barline is told from a stem by it, and a chord's heads share it).
            if (this.printed?.learned == true) {
                val g = Head(h.x, h.step, h.y, h.kind, h.score)
                stem(clean, staves[si], g, t)
                if (g.stemX >= 0) { h.stemX = g.stemX; h.up = g.up; h.stemEnd = g.stemEnd }
            }
        }
        // Which staff each head was found on: those let go are still the bar's maybes.
        val staffOf = HashMap<Head, Int>()
        headsOf.forEachIndexed { si, hs -> hs.forEach { staffOf[it] = si } }
        // A weak head needs a stem of its own: one it shares with a sure head is that head's flag or beam.
        val dropped = ArrayList<Pair<Head, String>>()
        // The trained reader's faint heads, each to the staff it is nearest: what a doubtful bar's other readings may put back.
        this.printed?.takeIf { it.learned }?.heads?.filter { Learned.faint(it) }?.forEach { sym ->
            val si = staves.indices.filter { sym.x >= staves[it].left - staves[it].space && sym.x <= staves[it].right }
                .minByOrNull { abs((staves[it].top + staves[it].bottom) / 2f - sym.y) } ?: return@forEach
            val s = staves[si]; val x = sym.x.roundToInt()
            // As a head read outright would be: near its staff, and not where one already was.
            if (abs((s.top + s.bottom) / 2f - sym.y) > s.space * 9) return@forEach
            val step = ((sym.y - s.lineY(0, x)) / (s.space / 2)).roundToInt()
            if (headsOf[si].any { o -> abs(o.x - x) < s.space * 0.6f && abs(o.step - step) <= 1 }) return@forEach
            val kind = when (sym.kind) { Printed.Kind.HEAD_HALF -> "noteheadHalf"; Printed.Kind.HEAD_WHOLE -> "noteheadWhole"; else -> "noteheadBlack" }
            val h = Head(x, step, s.y(step, x).roundToInt(), kind, sym.odds[7])
            dropped += h to "faint"
            staffOf[h] = si
        }
        // The second look's heads said to be none: let go the same way, marked so a bar short without them takes them back.
        for ((sym, si) in secondOut) {
            val s = staves[si]; val x = sym.x.roundToInt()
            val step = learnedStep(sym, s, ink)
            if (headsOf[si].any { o -> abs(o.x - x) < s.space * 0.6f && abs(o.step - step) <= 1 }) continue
            val kind = when (sym.kind) { Printed.Kind.HEAD_HALF -> "noteheadHalf"; Printed.Kind.HEAD_WHOLE -> "noteheadWhole"; else -> "noteheadBlack" }
            val h = Head(x, step, s.y(step, x).roundToInt(), kind, sym.confidence)
            // Its value as the trained reader saw it: a half head has its stem (a whole one is told by
            // its shape), a filled one its beams or flags.
            if (sym.kind != Printed.Kind.HEAD_WHOLE) { h.stemX = x; h.up = step >= 4 }
            if (sym.beams > 0) h.flags = sym.beams
            if (sym.dots > 0) h.dots = sym.dots
            symbolOf[h] = sym
            dropped += h to SECOND_OUT
            staffOf[h] = si
        }
        // An x or slash head with a stem of its own is a note, and a clear one: its thin strokes match less well than a head's fill.
        for (hs in headsOf) for (h in hs) if (h.kind != "noteheadBlack" && h.kind.startsWith("notehead") && h.weak && h.stemX >= 0 && (h.kind.contains("X") || h.kind.contains("Slash"))) h.score = max(h.score, 0.8f)
        for (hs in headsOf) hs.removeAll { h -> (h.weak && (h.stemX < 0 || hs.any { o -> !o.weak && o.stemX >= 0 && abs(o.stemX - h.stemX) <= t + 2 }))
            .also { if (it) dropped += h to (if (h.stemX < 0) "weak, no stem" else "weak, stem shared") } }
        val unflagged = headsOf.map { hs ->
            hs.filter { h ->
                (h.kind == "noteheadBlack" || hs.none { o -> o !== h && o.stemX >= 0 &&
                    abs(o.stemX - (h.x + space * 0.6f)) < space * 1.3f && abs(o.stemEnd - h.y) < space * 1.3f }).also { if (!it) dropped += h to "a flag's curl" }
            }
        }
        val kept = if (this.printed != null) headsOf else unflagged.mapIndexed { si, hs ->
            hs.filter { h ->
                val mine = abs(h.step - 4)
                listOf(si - 1, si + 1).none { oi ->
                    val other = unflagged.getOrNull(oi) ?: return@none false
                    other.any { o -> abs(o.x - h.x) <= 3 && abs(o.y - h.y) <= 3 && abs(o.step - 4) < mine }
                }.also { if (!it) dropped += h to "the other staff's" }
            }
        }
        // A page on its own: every line's printed number and how far it is from the count, taken
        // only where two lines agree (a single figure misread would number the page wrongly).
        val alone = carry.alone
        val offsets = ArrayList<Int>()
        // Each line's key, read first and then made to agree: a key read differently from the lines
        // either side of it, which agree with each other, is a misreading (a change of key comes
        // at a double bar within a line far more often than at a line's start) - theirs is taken.
        clock.mark("stems")
        val lineKeys: List<Pair<Key, Int>?> = if (stated) emptyList() else {
            var clefNow = carry.clef
            val read = staves.map { s ->
                var x = s.left + (s.space * 0.3f).toInt()
                clefAt(clean, s, x, t)?.let { clefNow = it.first; x = it.second }
                keyAt(clean, s, x, clefNow)
            }
            read.mapIndexed { i, k ->
                val before = read.getOrNull(i - 1)?.first; val after = read.getOrNull(i + 1)?.first
                val others = listOfNotNull(before, after)
                val agreed = if (others.size == 2 && before == after) before else if (i == 0 && read.size > 2 && read[1]?.first == read[2]?.first) read[1]?.first
                    else if (i == read.size - 1 && read.size > 2 && read[i - 1]?.first == read[i - 2]?.first) read[i - 1]?.first else null
                if (k != null && agreed != null && k.first != agreed) agreed to k.second else k
            }
        }
        clock.mark("keys")
        for ((si, s) in staves.withIndex()) {
            if (onlyStaff != null && si != onlyStaff) continue
            // The bar number printed over the line's start, where it reads clearly, is believed over
            // the count - a pickup bar numbered 0, a bar missed or found twice before - if it is later
            // than the last and near the count (a digit missed would put it far off).
            if (adapt) clock.sum("numbers") { Digits.number(ink, s.left - (s.space * 3).toInt(), s.left + (s.space * 4).toInt(), s.y(-8, s.left).roundToInt(), s.y(-1, s.left).roundToInt(),
                (s.space * 0.6f).toInt(), (s.space * 2.5f).toInt(), bottomFrom = s.y(-6, s.left).roundToInt(), space = s.space) }?.first?.let { p ->
                if (alone) offsets += p - number
                else {
                    // A multi-bar rest on the line before, of unknown length or misread (a scan's 14 read
                    // as 9): the bars the count is out by are its - where it is the line's only one.
                    val rests = multiAt.filter { measures[it].page == page && measures[it].staff == si - 1 }
                    val delta = p - number
                    // (Two or more: the longest, a count of two figures - read wrong far oftener than one.)
                    val k = rests.singleOrNull() ?: rests.filter { measures[it].bars >= 10 || "rest of how many bars?" in measures[it].doubts }.singleOrNull()
                    // (A long rest, but not a misread figure's worth: 12 read as 112 is no 100-bar rest.)
                    // (Only a figure not read, or one read short of a figure - "4" where "14" is printed: the
                    // figure over a rest, read, is believed over the count - the count is out by a bar
                    // misread somewhere else on the line far oftener.)
                    // (A figure read, but in doubt - its two readings differing - moved by less than itself
                    // only: the bar numbers jumping on - a medley's, a cut - are no rest of 54.)
                    val figureDoubted = k != null && ("rest of how many bars?" in measures[k].doubts && (measures[k].bars < 2 || abs(delta) < measures[k].bars) ||
                        // (A thin leading 1 lost - "4" where "14" is printed - and that only.)
                        (measures[k].bars < 10 && measures[k].bars + delta == measures[k].bars + 10))
                    if (p > carry.printed && delta != 0 && k != null && figureDoubted && measures[k].bars + delta in 2..64) {
                        val m = measures[k]
                        measures[k] = m.copy(bars = m.bars + delta, doubts = m.doubts - "rest of how many bars?")
                        for (j in k + 1 until measures.size) measures[j] = measures[j].copy(number = measures[j].number + delta)
                        number = p; carry.printed = p
                    } else if (p > carry.printed && p >= number && p - number <= 8) { number = p; carry.printed = p }
                    // (The count ahead of the print - a pickup counted, a bar taken for two: numbered on as
                    // counted, never back, so no number comes twice.)
                    else if (p > carry.printed && p < number && number - p <= 8) carry.printed = p
                }
            }
            // The start of the staff: clef, key, time.
            var x = s.left + (s.space * 0.3f).toInt()
            val clef = clock.sum("clef") { clefAt(clean, s, x, t) }
            var showsClef = false
            if (clef != null) { carry.clef = clef.first; x = clef.second; showsClef = true }
            val key = if (stated) printedKey(s, x) else lineKeys.getOrNull(si) ?: keyAt(clean, s, x, carry.clef)
            var showsKey = false
            if (key != null) { carry.key = key.first; x = key.second; showsKey = true }
            timeInk = false
            val time = clock.sum("time") { if (stated) printedTime(s, x) else timeAt(clean, s, x, opening = page == 0 && si == 0, lineStart = true) }
            var showsTime = false
            if (time != null) { carry.time = time.first; x = time.second; showsTime = true }
            // A part's first staff has a time signature: one in a font not read here is stepped
            // over (taken as the time carried, and said so), not read as notes.
            var timeUnread = false
            // (And on any line where what stands after the key is plainly a signature's ink: a change
            // of time there, its figures not read - not read as notes either.)
            if (time == null && !stated && (page == 0 && si == 0 || timeInk)) unreadTime(clean, s, x)?.let { x = it; timeUnread = true }
            // Heads and stems next, after the staff's start: a stem the height of the staff is not
            // a barline - and nor is a time signature's digits.
            val allHeads = kept[si].filter { it.x >= x }
            kept[si].filter { it.x < x }.forEach { dropped += it to "before the staff's start (clef, key, time end at $x)" }
            // Nothing fits between a staff's start and a line under three spaces on: that is a time
            // signature in another font, not a barline.
            val b = clock.sum("barlines") { (if (stated) printedBarlines(s) else null) ?: barlines(ink, clean, s, t, allHeads.filter { it.stemX >= 0 }.map { it.stemX }, allHeads) }.filter { it > x + s.space * 3f }
            bars += b
            val edges = (listOf(s.left) + b).distinct().sorted()
            val spans = edges.zipWithNext().filter { (a, c) -> c - a > s.space * 2 } +
                (if (b.isEmpty() || s.right - b.last() > s.space * 3) listOf((b.lastOrNull() ?: s.left) to s.right) else emptyList())
            // Repeats: a barline with a dot in each of the two middle spaces beside it.
            fun dotsAt(x0: Int, x1: Int) = listOf(3, 5).all { st -> val y = s.y(st, x0).roundToInt(); dotIn(clean, s.space, x0, y - (s.space * 0.4f).toInt(), x1, y + (s.space * 0.4f).toInt(), centreY = y) }
            fun dotsBefore(bx: Int) = dotsAt(bx - (s.space * 2.2f).toInt(), bx - (s.space * 0.25f).toInt())
            fun dotsAfter(bx: Int) = dotsAt(bx + (s.space * 0.25f).toInt(), bx + (s.space * 2.2f).toInt())
            val inSpan = HashSet<Head>()
            for ((i, span) in spans.withIndex()) {
                var from = if (i == 0) max(span.first, x) else span.first + (s.space * 0.3f).toInt()
                // A change of time at a bar's start: read, and in force from here.
                var timeHere = false
                timeInk = false
                if (i > 0) (if (stated) printedTime(s, from) else timeAt(clean, s, from))?.let { (sig, end) -> if (end < span.second - s.space) { carry.time = sig; from = end; timeHere = true } }
                // Something like a signature there - both halves of the staff inked, one over the other -
                // its figures not read: asked about (when nothing else stands there; see below).
                val sigUnreadAt = if (i > 0 && !stated && !timeHere && timeInk) from else -1
                inSpan += allHeads.filter { it.x >= from - 2 && it.x < span.second - (s.space * 0.2f).toInt() }
                val to = span.second - (s.space * 0.2f).toInt()
                val box = Box(span.first, s.top, span.second, s.bottom)
                // The staff's lines as printed just outside each end of the bar: a bar redrawn in place meets them.
                val lines = measuredLines(ink, s, span.first, -1) + measuredLines(ink, s, span.second, 1)
                // A multi-bar rest: its bar and number, and nothing else to read.
                // A bar with notes in it is no multi-bar rest, whatever runs along its middle line (a beam).
                // (A head far over the staff is a tempo marking's note, not one played here.)
                // (A PDF's own heads are its notes, however high - a piccolo's - its tempo marking's note
                // never stated as a head: any of them is a note here.)
                val headsHere = allHeads.any { it.x >= from - 2 && it.x < to && (stated || it.step in -6..14) }
                if (traceRests) println("  bar ${span.first}..${span.second} heads here: ${allHeads.filter { it.x >= from - 2 && it.x < to }.map { "${it.kind.removePrefix("notehead")} ${it.step}@${it.x}${if (it.stemX >= 0) (if (it.up) "^" else "v") else ""}" }}")
                // A time signature after a line's last barline, nothing else: the change ahead shown
                // at the line's end (the next line starts with it) - no bar of its own.
                if (i == spans.lastIndex && i > 0 && !headsHere && span.second - span.first < s.space * 5f &&
                    (if (stated) printedTime(s, span.first) else timeAt(clean, s, span.first + (s.space * 0.3f).toInt())) != null) continue
                val rest = if (headsHere) null else multiRest(clean, s, from, to, ink)
                if (rest != null) {
                    val (restBars, x) = rest
                    multiAt += measures.size
                    measures += Measure(number, page, si, box, s.space, carry.clef, carry.key, carry.time,
                        listOf(Rest(Duration(1), x.toFloat())), showsClef = i == 0 && showsClef, showsKey = i == 0 && showsKey,
                        showsTime = (i == 0 && showsTime) || timeHere, doubts = if (restBars == 0 || restFigureDoubted) listOf("rest of how many bars?") else emptyList(), bars = max(1, restBars), lines = lines, lineWidth = t.toFloat(), start = from)
                    number += max(1, restBars)
                } else {
                // A cue - another instrument's line, printed small to come in by - over the part's rest:
                // small heads found here and not one at the part's own size. Its notes are not the
                // part's: none of its heads are read (they are kept as printed), and the bar is the
                // part's rest, the whole of it. (A grace note before the part's own notes leaves those,
                // full size, and the bar as read.)
                // (Its small heads well outnumbering any of full size: one a little larger among a cue's
                // is the scan's blur - one small among the part's own is a grace note.)
                val smallHere = cueAt[si]?.count { it >= from - 2 && it < to } ?: 0
                val fullHere = allHeads.count { it.x >= from - 2 && it.x < to && it in fullSize }
                val maybeCue = this.printed?.learned == true && smallHere >= 1 && smallHere > fullHere * 2
                // And the part's own rest printed under it, as a cue always is (a drum part's small
                // heads have no rest beneath them): the bar read without its heads finds one.
                val restsOnly = if (maybeCue) clock.sum("events") { eventsIn(clean, s, from, to, t, carry, emptyList()) } else emptyList()
                // (Rests filling the bar on their own: a whole rest, or rests adding up to it - a drum
                // part's scattered rests between its notes do not.)
                val cueBar = maybeCue && restsOnly.isNotEmpty() && restsOnly.all { it is Rest } &&
                    (restsWholeBar(restsOnly) || abs(restsOnly.sumOf { it.duration.quarters } - carry.time.quarters) < 1e-6)
                var events = if (cueBar) restsOnly else clock.sum("events") { eventsIn(clean, s, from, to, t, carry, allHeads.filter { it.x >= from - 2 && it.x < to }) }
                // The part's own rest, where read, is the one lowest on the staff (a cue sits over it):
                // redrawn where it is printed, held for the whole bar.
                if (cueBar) events = listOf(events.filterIsInstance<Rest>().maxByOrNull { it.step ?: 4 }?.copy(duration = Duration(1)) ?: Rest(Duration(1), from + s.space * 0.5f))
                // Too long a bar: triplets read as plain notes, most often.
                var guessedTuplets = false
                // Two voices on the staff (stems up, stems down), each adding up: nothing to mend.
                val inVoices = voicesAddUp(events, carry.time.quarters)
                // Too long, and a small head just before one of the part's own size is what makes it so:
                // a grace note - it takes no time in the bar. Let go (kept among what was seen: another
                // reading may want it), where without the small ones the bar comes out exactly.
                var graces = emptyList<Event>()
                if (!inVoices && !cueBar && events.sumOf { it.duration.quarters } > carry.time.quarters + 1e-6) {
                    val small = cueAt[si].orEmpty()
                    fun isSmall(e: Event) = small.any { abs(it - e.x) <= 2f } || (e is Note && smallHead(clean, s, si, e, allHeads))
                    val grace = events.indices.filter { k ->
                        val e = events[k]
                        e is Note && isSmall(e) && (events.getOrNull(k + 1) as? Note)?.let { nx -> nx.x - e.x <= s.space * 3.5f && !isSmall(nx) } == true
                    }.toSet()
                    val without = events.filterIndexed { k, _ -> k !in grace }
                    if (grace.isNotEmpty() && abs(without.sumOf { it.duration.quarters } - carry.time.quarters) < 1e-6) {
                        graces = grace.map { events[it] }; events = without
                    }
                }
                if (!inVoices && events.sumOf { it.duration.quarters } > carry.time.quarters + 1e-6) clock.sum("tuplets") { tuplets(clean, s, events, carry.time.quarters) }?.let { (e, printed) -> events = e; guessedTuplets = !printed }
                // Still too long, and a stemless "whole note" (a loop - a flag's curl, a digit's, a letter's) or a
                // doubtful hollow head among other notes is what makes it so: without it the bar comes out exactly.
                if (!inVoices && events.sumOf { it.duration.quarters } > carry.time.quarters + 1e-6) events = withoutLoops(events, carry.time.quarters)
                // A piece may start with a short bar - a pickup - when what is in it is certain: the
                // file's first bar, or the first on a line that sets out its time (a book's next study).
                val pickup = (page == 0 && measures.isEmpty() || i == 0 && showsTime) && events.sumOf { it.duration.quarters } < carry.time.quarters - 1e-6 &&
                    (stated || events.filterIsInstance<Note>().let { n -> n.isNotEmpty() && n.all { it.confidence >= 0.8f } })
                // Short, and the second look's let-go heads here are what it lacks: taken back - the bar
                // adds up exactly with them, each where nothing else was read.
                if (!inVoices && !cueBar && events.isNotEmpty() || events.isEmpty() && !cueBar) {
                    val q = events.sumOf { it.duration.quarters }
                    if (q < carry.time.quarters - 1e-6) {
                        // (Off the staff, on ledger lines, only: where the second look is wrong - it is right far
                        // oftener than not about what sits on the staff.)
                        val back = dropped.filter { (h, why) -> why == SECOND_OUT && staffOf[h] == si && h.x >= from - 2 && h.x < to && (h.step <= -2 || h.step >= 10) }
                            .map { it.first }.distinctBy { it.x / 4 to it.step }
                            .filter { h -> events.none { e -> abs(e.x - h.x) < s.space * 0.6f } }
                            .map { h -> maybeNote(clean, s, h, carry) }
                        if (back.isNotEmpty() && abs(q + back.sumOf { it.duration.quarters } - carry.time.quarters) < 1e-6) {
                            events = (events + back).sortedBy { it.x }
                            dropped.removeAll { (h, why) -> why == SECOND_OUT && back.any { b -> abs(b.x - h.x) < 2f } }
                        }
                    }
                }
                // What was seen here and let go: another reading of the bar may want it back.
                val maybe = dropped.filter { (h, why) -> staffOf[h] == si && h.x >= from - 2 && h.x < to && !why.startsWith("the other staff") }
                    .map { it.first }.distinctBy { it.x / 4 to it.step }.map { h -> clock.sum("maybe") { maybeNote(clean, s, h, carry) } }
                // Its grace notes: a PDF's small heads just before its own notes; on a scan, the small
                // heads let go above for making the bar too long.
                val graceNotes = (graceNotes(si, from, to, events, carry) + graces.filterIsInstance<Note>().map { it.copy(duration = Duration(8)) }).let { found ->
                    // A scan's: the trained reader leaves small heads be - looked for on the page, just before each note.
                    if (this.printed?.learned == true && !cueBar && System.getProperty("inksheets.omr.nograces") == null)
                        found + scanGraces(clean, s, events, carry, from).filter { g -> found.none { abs(it.x - g.x) < s.space } }
                    else found
                }
                val m = Measure(
                    number++, page, si, box, s.space,
                    carry.clef, carry.key, carry.time, events,
                    maybe = maybe,
                    graces = graceNotes.sortedBy { it.x },
                    showsClef = i == 0 && showsClef, showsKey = i == 0 && showsKey, showsTime = (i == 0 && showsTime) || timeHere,
                    lines = lines, lineWidth = t.toFloat(), start = from
                )
                val doubts = ArrayList<String>()
                val q = m.quarters
                // A whole rest alone in a bar rests the whole bar, whatever the time (12/8's, 3/4's).
                val wholeBarRest = restsWholeBar(events)
                if (abs(q - carry.time.quarters) > 1e-6 && events.isNotEmpty() && !inVoices && !pickup && !wholeBarRest) doubts += "${fmt(q)} beats found, ${fmt(carry.time.quarters)} expected"
                // A bar with nothing in it but a bar-repeat sign is as sure as the bar it repeats.
                // A bar-repeat sign: where nothing was read - or only a note or two, unclear, that do not
                // add up (its slashes and dots taken for heads).
                val signAt = if (events.isEmpty() || events.size <= 2 && events.all { it is Note && it.confidence < 0.9f } &&
                    abs(events.sumOf { it.duration.quarters } - carry.time.quarters) > 1e-6) barRepeat(clean, s, from, to, i == 0) else 0
                if (signAt != 0) {
                    // (What was read in it kept, should the sign not stand - see below.)
                    repeatSide[measures.size] = signAt; repeatEvents[measures.size] = events
                    events = emptyList(); doubts.removeAll { it.contains("beats found") || it.startsWith("a time signature here not read") }
                }
                val repeatsBar = signAt != 0
                if (events.isEmpty() && !repeatsBar) doubts += "nothing read"
                if (guessedTuplets) doubts += "triplets taken to make the bar add up, no 3 seen"
                // (A part's first line: always said. Another's: where the bar does not come out in the time
                // carried - otherwise the ink taken for a signature may be a chord, and the time is right.)
                if (i == 0 && timeUnread && (page == 0 && si == 0 || doubts.any { it.contains("beats found") }))
                    doubts += "time signature not read - taken as ${carry.time.beats}/${carry.time.beatType}"
                // (Nothing read where it stood - no note, no rest: a signature's figures, not read.)
                if (sigUnreadAt >= 0 && events.none { it.x < sigUnreadAt + s.space * 1.6f } && doubts.any { it.contains("beats found") })
                    doubts += "a time signature here not read - say which with \"Clef, key or time wrong?\""
                // Another engraver's heads match these a little less well and are read right: only
                // a weak match is a doubt.
                events.filterIsInstance<Note>().filter { it.confidence < (if (this.printed?.learned == true) Learned.SURE else 0.75f) }.takeIf { it.isNotEmpty() }?.let { doubts += "${it.size} unclear note${if (it.size > 1) "s" else ""}" }
                // A repeat starting at a line's start sits just after the clef and key.
                val starts = (span.first in b && dotsAfter(span.first)) || (i == 0 && dotsAt(x - (s.space * 0.5f).toInt(), x + (s.space * 2.5f).toInt()))
                val ends = span.second in b && dotsBefore(span.second)
                // A curve read as a slur that is a tie (from a tied note, or short - a tie's end
                // arriving over the barline) is the tie's: drawn once, as a tie.
                val tiedAt = events.filterIsInstance<Note>().filter { it.tie }.map { it.x + s.space * 1.2f }
                val directions = if (!stated) emptyList() else printedDirections(s, span.first, span.second).filterNot { d ->
                    d.kind == "slur" && (d.x2 - d.x < s.space * 3f || tiedAt.any { abs(it - d.x) < s.space * 1.5f })
                }
                val done = m.copy(doubts = doubts, repeatStart = starts, repeatEnd = ends, directions = directions, repeatsBar = repeatsBar)
                measures += done
                }
            }
            allHeads.filter { it !in inSpan }.forEach { dropped += it to "by a barline, outside every bar" }
            // Segno and coda signs over the staff: to the bar they stand over (or the line's first).
            // On the page as printed: above the staff there are no lines to take out, and taking out
            // ledger-like strokes breaks a coda sign's circle into pieces.
            for ((sx, kind) in signs(ink, s)) {
                val here = measures.indices.filter { measures[it].staff == si && measures[it].page == page }
                val k = here.firstOrNull { sx >= measures[it].box.left - s.space && sx < measures[it].box.right } ?: here.firstOrNull() ?: continue
                measures[k] = if (kind == "segno") measures[k].copy(segno = true) else measures[k].copy(coda = true)
            }
            // First and second endings: a long thin bracket over the staff, hooked down at its
            // left end, its number beside the hook. The bars under it are that ending's.
            for ((x0, x1, n) in clock.sum("endings") { endings(clean, s, ink) }) {
                for (k in measures.indices) {
                    val m = measures[k]
                    if (m.staff == si && m.page == page && (m.box.left + m.box.right) / 2 in x0..x1) measures[k] = m.copy(ending = n)
                }
            }
        }
        // The bars tell their own metre: where a time signature was read here but most of this
        // page's bars agree on another length, the signature was misread (a 4 taken for a 1) and
        // the bars are right.
        // (A signature not read at all, taken as the time carried, is put to the same test.)
        val readTimes = measures.filter { it.showsTime || it.doubts.any { d -> d.startsWith("time signature not read") } }.map { it.time }.distinct()
        // The time this page began in, carried from the page before - a change at that page's end not
        // read (a scan's signature run into its lines) - put to the same test, the bars before this
        // page's first signature only, and more of them agreeing.
        val carriedIn = startTime.takeIf { page > 0 && it !in readTimes }
        for (signed in readTimes + listOfNotNull(carriedIn)) {
            val carried = signed == carriedIn
            val firstSigned = measures.indexOfFirst { it.showsTime }.let { if (it < 0) measures.size else it }
            val pool = if (carried) measures.subList(0, firstSigned) else measures
            val totals = pool.filter { it.time == signed && it.bars == 1 && it.events.any { e -> e is Note } }.map { it.quarters }
            val common = totals.groupingBy { Math.round(it * 4) / 4.0 }.eachCount().maxByOrNull { it.value }
            if (common != null && totals.size >= (if (carried) 8 else 6) && common.value >= totals.size * (if (carried) 0.75 else 0.6) && abs(common.key - signed.quarters) > 1e-6 && common.key in 1.0..12.0) {
                // A whole number of quarters in quarters (2/4, not 4/8), else in eighths.
                val beatType = if (carried) (if (abs(common.key - Math.round(common.key)) < 1e-6) 4 else 8) else signed.beatType
                val beats = Math.round(common.key * beatType / 4.0).toInt()
                val time = TimeSig(beats, beatType)
                if (abs(time.quarters - common.key) < 1e-6) {
                    if (!carried || firstSigned == measures.size) carry.time = time
                    for (k in measures.indices) {
                        val m = measures[k]
                        if (m.time != signed || (carried && k >= firstSigned)) continue
                        val doubts = m.doubts.filterNot { it.contains("beats found") || it.startsWith("a time signature here not read") }.toMutableList()
                        if (m.bars == 1 && m.events.isNotEmpty() && !restsWholeBar(m) && abs(m.quarters - time.quarters) > 1e-6) doubts.add(0, "${fmt(m.quarters)} beats found, ${fmt(time.quarters)} expected")
                        measures[k] = m.copy(time = time, doubts = doubts)
                    }
                }
            }
        }
        // A signature hardly ever written (one beat to the bar: a 3 or a 4 taken for a 1) is
        // overruled by its own bars - from it to the next signature - as soon as two of them agree.
        // Any other signature, a figure off by one (a 4 taken for a 3) - from it to the next signature,
        // four bars or more, three in four agreeing on the length a beat more or less.
        run {
            val starts = measures.indices.filter { measures[it].showsTime }
            for (k in starts) {
                val end = (k + 1 until measures.size).firstOrNull { measures[it].showsTime && measures[it].time != measures[k].time } ?: measures.size
                val signed = measures[k].time
                val one = signed.beats == 1
                val totals = (k until end).map { measures[it] }.filter { it.bars == 1 && it.events.any { e -> e is Note } }.map { Math.round(it.quarters * 4) / 4.0 }
                val common = totals.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: continue
                if (one && (totals.size < 2 || common.value < 2 || common.value < totals.size * 0.6)) continue
                if (!one && (common.value < 4 || common.value < totals.size * 0.75)) continue
                if (common.key !in 1.0..12.0 || abs(common.key - signed.quarters) < 1e-6) continue
                val beats = Math.round(common.key * signed.beatType / 4.0).toInt()
                val time = TimeSig(beats, signed.beatType)
                if (abs(time.quarters - common.key) > 1e-6 || (!one && abs(beats - signed.beats) != 1)) continue
                for (j in k until end) {
                    val m = measures[j]
                    val doubts = m.doubts.filterNot { it.contains("beats found") || it.startsWith("a time signature here not read") }.toMutableList()
                    if (m.bars == 1 && m.events.isNotEmpty() && !restsWholeBar(m) && abs(m.quarters - time.quarters) > 1e-6) doubts.add(0, "${fmt(m.quarters)} beats found, ${fmt(time.quarters)} expected")
                    measures[j] = m.copy(time = time, doubts = doubts)
                }
                if (end == measures.size) carry.time = time
            }
        }
        // A change of time not read: three bars or more in a row, on one line, coming to the same
        // length - not the time they were taken in - the first of them with a signature's ink at its
        // start (both halves of the staff filled before its first note): the time changed there.
        run {
            var k = 0
            while (k < measures.size) {
                val m = measures[k]
                val len = Math.round(m.quarters * 4) / 4.0
                // (Bars of notes only: a whole rest rests a whole bar, whatever its time.)
                if (m.bars != 1 || m.events.none { it is Note } || abs(len - m.time.quarters) < 1e-6 || len !in 1.0..12.0) { k++; continue }
                var end = k + 1
                while (end < measures.size && measures[end].staff == m.staff && measures[end].page == m.page && measures[end].bars == 1 && measures[end].events.any { it is Note } &&
                    measures[end].time == m.time && abs(Math.round(measures[end].quarters * 4) / 4.0 - len) < 1e-6) end++
                val s = staves[m.staff]
                val firstX = m.events.minOf { it.x }.roundToInt()
                val signed = firstX - m.box.left > s.space * 1.5f && run {
                    val x0 = m.box.left + (s.space * 0.3f).toInt(); val x1 = firstX - (s.space * 0.3f).toInt()
                    var upper = 0; var lower = 0; var n = 0
                    for (x in x0..x1) {
                        val top = s.lineY(0, x).roundToInt(); val mid = s.lineY(2, x).roundToInt(); val bottom = s.lineY(4, x).roundToInt()
                        for (y in top until mid) if (clean[x, y]) upper++
                        for (y in mid until bottom) if (clean[x, y]) lower++
                        n += mid - top
                    }
                    n > 0 && upper >= n * 0.08f && lower >= n * 0.08f
                }
                if (end - k >= 3 && signed) {
                    // Counted in quarters where it comes to whole ones (3/4), else in eighths (7/8).
                    val beatType = if ((len * 2).roundToInt() % 2 != 0) 8 else 4
                    val time = TimeSig(Math.round(len * beatType / 4.0).toInt(), beatType)
                    if (abs(time.quarters - len) < 1e-6) {
                        for (j in k until end) measures[j] = measures[j].copy(time = time, showsTime = j == k || measures[j].showsTime,
                            doubts = measures[j].doubts.filterNot { it.contains("beats found") || it.startsWith("a time signature here not read") })
                        k = end; continue
                    }
                }
                k++
            }
        }
        if (alone) {
            carry.alone = false
            // Agreeing within a couple of bars: the count across a scanned page can be a bar or two out.
            offsets.map { o -> o to offsets.count { abs(it - o) <= 2 } }.filter { it.second >= 2 }.maxByOrNull { it.second }?.first
                ?.takeIf { measures.isNotEmpty() && measures.first().number + it >= 1 }?.let { o ->
                measures.replaceAll { it.copy(number = it.number + o) }
                carry.printed = measures.last().number
            }
        }
        // The page without its staff lines, but with every stroke that crossed a line whole again
        // (a tie, a slur, a hairpin, a word): what was taken out with the line is put back where
        // there is ink just above and just below it - one shape, not pieces either side.
        // A two-bar repeat sign stands across the barline between its two bars: the bar the other side
        // of that barline is one with it (a sign, or nothing read). Where it is not, what was taken
        // for the sign was something else's end (a tie's, a beam's): the bar as it was read.
        for ((idx, side) in repeatSide) {
            if (side == 1) continue
            val m = measures.getOrNull(idx) ?: continue
            val other = measures.getOrNull(if (side == 2) idx - 1 else idx + 1)?.takeIf { it.page == m.page && it.staff == m.staff }
            if (other != null && (other.repeatsBar || other.events.isEmpty() && other.bars == 1)) continue
            val back = repeatEvents[idx].orEmpty()
            if (traceRests) println("    repeat let go: bar ${m.number} staff ${m.staff} side $side, the other ${other?.number} events ${other?.events?.size} repeats ${other?.repeatsBar}")
            measures[idx] = m.copy(repeatsBar = false, events = back, doubts = if (back.isEmpty()) m.doubts + "nothing read" else m.doubts + "a bar-repeat sign or these?")
        }
        clock.mark("bars")
        // A second look at one bar wants its notes only: what a cleaned bar keeps is not worked out.
        if (onlyStaff != null) { timings = clock.toString(); return PageReading(staves, bars, measures, t, space, dropped) }
        val healed = clean.copy()
        // Only what was taken out with the lines can need putting back: each erased run once, from its top.
        val w = ink.width
        for (e in 0 until erased.size step 2) {
            val x = erased.data[e]; val y = erased.data[e + 1]
            if (y > 0 && !clean[x, y - 1] && ink[x, y - 1]) continue   // not a run's top: done with its top
            var bot = y; while (bot + 1 < ink.height && ink[x, bot + 1] && !clean[x, bot + 1] && bot - y < t + 3) bot++
            var over = false; var under = false
            for (dx in -1..1) { if (clean[x + dx, y - 1]) over = true; if (clean[x + dx, bot + 1]) under = true }
            if (over && under) for (yy in y..bot) healed.bits[yy * w + x] = true
        }
        // A slur or tie running level where a ledger line would be was taken out as one: put back
        // where the stroke taken out runs on into ink at both its ends (a ledger line stops short,
        // free at both ends; a curve carries on beyond them).
        val wasErased = BooleanArray(w * ink.height)
        for (e in 0 until erased.size step 2) wasErased[erased.data[e + 1] * w + erased.data[e]] = true
        fun erasedAt(x: Int, y: Int) = x in 0 until w && y in 0 until ink.height && wasErased[y * w + x]
        fun onStaffLine(x: Int, y: Int) = staves.any { st -> x >= st.left - 2 && x <= st.right + 2 && (0..4).any { l -> abs(st.lineY(l, x) - y) <= t + 1 } }
        for (e in 0 until erased.size step 2) {
            val x = erased.data[e]; val y = erased.data[e + 1]
            if (healed[x, y] || erasedAt(x - 1, y) || onStaffLine(x, y)) continue   // each level run once, from its left end
            var xb = x; while (erasedAt(xb + 1, y)) xb++
            fun inkAt(cx: Int) = (-2..2).any { dy -> clean[cx, y + dy] }
            if (xb - x >= 2 && (inkAt(x - 1) || inkAt(x - 2)) && (inkAt(xb + 1) || inkAt(xb + 2)))
                for (xx in x..xb) for (dy in -t..t) if (erasedAt(xx, y + dy)) healed.bits[(y + dy) * w + xx] = true
        }
        clock.mark("healed")
        // Ties, where nothing states them (a scan): seen in the ink between notes at one pitch.
        if (this.printed?.learned != false) findTies(healed, staves, measures)
        // Each bar's on its own (several at once where there are the cores): nothing one finds changes another's.
        val keptNow = Workers.map(measures.toList()) { m ->
            if (m.bars > 1) m else {
                val staffBars = measures.filter { it.staff == m.staff && it.bars == 1 }
                val (kept, shades) = keptIn(healed, staves[m.staff], m, staffBars)
                m.copy(kept = kept, keptShade = shades)
            }
        }
        for (i in measures.indices) measures[i] = keptNow[i]
        clock.mark("kept")
        // What the kept marks are, for playing the music as marked (a picture states none of them).
        if (this.printed?.learned == true && MarkReader.available && System.getProperty("inksheets.omr.nomarks") == null) { readMarks(measures); clock.mark("marks") }
        timings = clock.toString()
        return PageReading(staves, bars, measures, t, space, dropped)
    }

    /**
     * A multi-bar rest between [from] and [to]: a thick bar across the middle line, its number of
     * bars above the staff. (bars, x) - bars 0 when the number could not be read; null when none.
     */
    private fun multiRest(clean: Ink, s: Staff, from: Int, to: Int, page: Ink = clean): Pair<Int, Int>? {
        val sp = s.space
        var best: Pair<Int, Int>? = null   // start, length
        var x = from
        // Its middle on the middle line, or (some engravers', a scan's) half a space either side of it.
        val step = listOf(4, 3, 5).maxByOrNull { st ->
            var run = 0; var longest = 0
            for (xx in from until to) {
                val yy = s.y(st, xx).roundToInt()
                if (clean[xx, yy] || (page[xx, yy] && page[xx, yy - 2] && page[xx, yy + 2])) { run++; longest = max(longest, run) } else run = 0
            }
            longest
        } ?: 4
        while (x < to) {
            val y = s.y(step, x).roundToInt()
            fun thick(xx: Int): Boolean {
                var a = y; while (clean[xx, a - 1] && y - a < sp) a--
                var b = y; while (clean[xx, b + 1] && b - y < sp) b++
                // Engravers' bars run from a third of a space thick to a whole one.
                if (clean[xx, y] && b - a + 1 >= sp * 0.3f && b - a + 1 <= sp * 1.2f) return true
                // Some draw it thin, lying on the middle line - taken out with the line: on the page
                // as printed it is the line made plainly thicker.
                var pa = y; while (page[xx, pa - 1] && y - pa < sp) pa--
                var pb = y; while (page[xx, pb + 1] && pb - y < sp) pb++
                return page[xx, y] && pb - pa + 1 >= max(sp * 0.3f, lineThickness + 3f) && pb - pa + 1 <= sp * 1.2f
            }
            if (!thick(x)) { x++; continue }
            val start = x
            while (x < to && thick(x)) x++
            if (best == null || x - start > best.second) best = start to (x - start)
        }
        if (traceRests) println("    multiRest $from..$to step $step best $best (space $sp)")
        val (start, len) = best ?: return null
        if (len < sp * 2.5f) return null
        // Its ends are short upright strokes, a space or so each way from the middle line.
        // (Short ones too: some engravers' run only a little way past the bar - on the page as printed,
        // as the middle line may have been taken out across them.)
        fun serif(x0: Int): Boolean = (x0 - 3..x0 + 3).any { xx ->
            val up = s.y(2, xx).roundToInt(); val down = s.y(6, xx).roundToInt()
            val mid = s.y(step, xx).roundToInt(); val a = mid - (sp * 0.8f).toInt(); val b = mid + (sp * 0.8f).toInt()
            (up..down).count { clean[xx, it] } >= (down - up) * 0.8f || (a..b).count { page[xx, it] } >= (b - a) * 0.85f
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
        // Read on the page as printed: taking out ledger-like strokes breaks a 7's top off.
        val printed = Digits.number(page, start - sp.toInt(), start + len + sp.toInt(), s.y(-9, start).roundToInt(), s.y(-1, start).roundToInt(), (sp * 0.8f).toInt(), (sp * 3.2f).toInt(), bottomFrom = s.y(-6, start).roundToInt(), space = sp, musicFont = true)
        // (Over 64 bars is a figure misread - pencilled words over it, a tempo's equation - not a
        // part's rest: how many, unknown, and the next bar number printed tells.)
        // The two readings of it: whole (any typeface - the surer of the two), and figure by figure
        // in the music font's own shapes. Whole where it reads one; the figures where it does not;
        // the two differing, the count in doubt - asked about rather than played wrong.
        val whole = printed?.first?.takeIf { it in 2..64 }
        val font = digits.takeIf { it.isNotEmpty() }?.sortedBy { it.second }?.fold(0) { n, (d, _) -> n * 10 + d }?.takeIf { it in 2..64 }
        // (Two figures read in the music font alone - nothing whole - are as often one figure and a
        // smudge: asked about too.)
        // (Figure by figure, a stray mark off to the side is taken for a second figure often - "41"
        // for a 4: only one figure each, differing, is a doubt.)
        restFigureDoubted = whole != null && font != null && whole != font && whole < 10 && font < 10 || whole == null && font != null && font >= 10
        val bars = whole ?: font ?: 0
        if (traceRests) println("    multiRest serifs $serifs bars $bars digits $digits whole $whole font $font at $start")
        // Some engravers end the bar in short strokes, or none: its number over it says what it is.
        if (!serifs && bars < 2) return null
        // A "1" over a short thick bar: a rest of the one bar, as some engravers print it - the
        // bar's rest, sure. Over a long one, a figure misread (a multi-bar rest is never of one
        // bar): how many, unknown - the next bar number printed may tell (see read).
        val one = printed?.first == 1 || digits.size == 1 && digits[0].first == 1
        if (one && len < sp * 4f) { restFigureDoubted = false; return 1 to start }
        return (if (bars == 1) 0 else bars) to start
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

    /**
     * Whether a bar that does not add up as one line of notes does as two voices - stems up and
     * stems down (a drum part, two players on one staff) - each coming to [expected]. A rest goes
     * with the voice on its side of the middle line; one on it, with whichever it completes.
     */
    private fun voicesAddUp(events: List<Event>, expected: Double): Boolean {
        val notes = events.filterIsInstance<Note>()
        if (abs(events.sumOf { it.duration.quarters } - expected) < 1e-6) return false
        if (notes.any { it.stemUp == null } || notes.none { it.stemUp == true } || notes.none { it.stemUp == false }) return false
        var up = notes.filter { it.stemUp == true }.sumOf { it.duration.quarters }
        var down = notes.filter { it.stemUp == false }.sumOf { it.duration.quarters }
        val middle = ArrayList<Rest>()
        for (r in events.filterIsInstance<Rest>()) {
            val st = r.step
            when {
                st == null || st == 4 -> middle += r
                st < 4 -> up += r.duration.quarters
                else -> down += r.duration.quarters
            }
        }
        if (middle.size > 8) return false
        for (mask in 0 until (1 shl middle.size)) {
            var u = up; var d = down
            for (i in middle.indices) if (mask and (1 shl i) != 0) u += middle[i].duration.quarters else d += middle[i].duration.quarters
            if (abs(u - expected) < 1e-6 && abs(d - expected) < 1e-6) return true
        }
        return false
    }

    /**
     * [events] without the fewest stemless whole notes and doubtful hollow heads - up to two -
     * whose going makes the bar come to [expected] exactly; as it was when none do. A whole note
     * shares its bar with nothing but its own chord, so one among other notes is suspect.
     */
    private fun withoutLoops(events: List<Event>, expected: Double): List<Event> {
        val suspects = events.indices.filter { i ->
            val e = events[i]
            e is Note && (e.duration.base == 1 || (e.duration.base == 2 && e.confidence < 0.8f)) && events.size > 1
        }
        val total = events.sumOf { it.duration.quarters }
        for (i in suspects) if (abs(total - events[i].duration.quarters - expected) < 1e-6) return events.filterIndexed { k, _ -> k != i }
        for (a in suspects) for (b in suspects) if (a < b && abs(total - events[a].duration.quarters - events[b].duration.quarters - expected) < 1e-6)
            return events.filterIndexed { k, _ -> k != a && k != b }
        return events
    }

    /** Whether a small "3" - a tuplet's number, italic or upright - is printed above or below [x0]..[x1] on [s]. */
    private fun printedThree(clean: Ink, s: Staff, x0: Int, x1: Int): Boolean {
        val sp = s.space
        val bands = listOf(s.y(-14, x0).roundToInt() to s.y(-2, x0).roundToInt(), s.y(10, x0).roundToInt() to s.y(22, x0).roundToInt())
        // Where the PDF states its text, the 3 is one of its characters. (The trained reader's symbols
        // are no PDF's: they hold no digits, and the 3 is looked for on the page.)
        printed?.takeIf { !it.learned }?.let { p -> return p.symbols.any { d -> d.digit == 3 && (d.kind == Printed.Kind.TEXT_DIGIT || d.kind == Printed.Kind.TIME_DIGIT) &&
            d.x >= x0 - sp && d.x <= x1 + sp && bands.any { (a, b) -> d.y >= a && d.y <= b } } }
        // Each figure-sized shape over or under the group, read by the trained digit reader: a 3 in
        // any typeface (an old engraving's bold italic one is nothing like the music font's).
        if (Digits.hasTrained) for ((a, b) in bands) {
            val rx0 = max(0, x0 - (sp * 2).toInt()); val ry0 = max(0, a - sp.toInt())
            val region = Outline.Region(rx0, ry0, min(clean.width, x1 + (sp * 2).toInt()) - rx0, min(clean.height, b + sp.toInt()) - ry0)
            for (y in max(0, a)..min(clean.height - 1, b)) for (x in max(0, x0 - (sp * 0.5f).toInt())..min(clean.width - 1, x1 + (sp * 0.5f).toInt())) {
                if (!clean[x, y] || !region.inside(x, y) || region.seen[region.index(x, y)]) continue
                val px = Outline.component(clean, x, y, region, 4_000)
                if (px.isEmpty()) continue
                var l = Int.MAX_VALUE; var r = Int.MIN_VALUE; var t = Int.MAX_VALUE; var bt = Int.MIN_VALUE
                for (i in px.indices step 2) { l = min(l, px[i]); r = max(r, px[i]); t = min(t, px[i + 1]); bt = max(bt, px[i + 1]) }
                val h = bt - t + 1; val w = r - l + 1
                if (h < sp * 0.5f || h > sp * 2.2f || w > h * 1.3f || (l + r) / 2 !in x0 - sp.toInt()..x1 + sp.toInt()) continue
                val m = Digits.mask(clean, l, t, r, bt) ?: continue
                if (Digits.readTrained(m, h / sp, w.toFloat() / h)?.first == 3) return true
            }
        }
        for (size in listOf(0.5f, 0.6f, 0.7f)) for ((a, b) in bands) {
            var y = a
            while (y <= b) {
                for (x in x0..x1 step 2) if (score(clean, "timeSig3", sp * size, x, y) > 0.55f) return true
                y += 2
            }
        }
        return false
    }

    /**
     * Segno and coda signs over [s]: each shape a sign's size in the band above the staff, the
     * sign's outline fitted to it and compared - (x of its middle, "segno" or "coda").
     */
    private fun signs(clean: Ink, s: Staff): List<Pair<Int, String>> {
        val sp = s.space
        val out = ArrayList<Pair<Int, String>>()
        val y0 = s.y(-14, s.left).roundToInt(); val y1 = s.y(-1, s.left).roundToInt()
        // Each shape over the staff, followed within the band (and a little past it).
        val rx0 = max(0, s.left - (sp * 3).toInt()); val ry0 = max(0, y0 - (sp * 2).toInt())
        val region = Outline.Region(rx0, ry0, min(clean.width, s.right + (sp * 4).toInt()) - rx0, min(clean.height, y1 + (sp * 6).toInt()) - ry0)
        for (y in y0..y1) for (x in s.left - (sp * 3).toInt()..s.right) {
            if (!clean[x, y] || !region.inside(x, y) || region.seen[region.index(x, y)]) continue
            val px = Outline.component(clean, x, y, region, 30_000)
            if (px.isEmpty()) continue
            var l = Int.MAX_VALUE; var r = Int.MIN_VALUE; var t = Int.MAX_VALUE; var b = Int.MIN_VALUE
            for (i in px.indices step 2) { l = min(l, px[i]); r = max(r, px[i]); t = min(t, px[i + 1]); b = max(b, px[i + 1]) }
            val w = r - l + 1; val h = b - t + 1
            if (h < sp * 1.5f || h > sp * 6f || w < sp * 1.2f || w > sp * 6f || b > y1 + sp) continue
            // Engravers draw both signs their own way: a fair likeness, and clearly more the one than the other.
            val seg = overlap(clean, l, t, w, h, "segno"); val cod = overlap(clean, l, t, w, h, "coda")
            when {
                seg > 0.5f && seg > cod + 0.12f -> out += (l + r) / 2 to "segno"
                cod > 0.5f && cod > seg + 0.12f -> out += (l + r) / 2 to "coda"
            }
        }
        return out
    }

    /** How much ink in the box ([l], [t]) [w] x [h] and glyph [name] drawn to fill it share: 1 the same. */
    private fun overlap(clean: Ink, l: Int, t: Int, w: Int, h: Int, name: String): Float {
        val g = MusicGlyphs[name]
        val bb = g.bounds
        val gw = bb[2] - bb[0]; val gh = bb[3] - bb[1]
        if (gw <= 0f || gh <= 0f) return 0f
        // The glyph's shape, not stretched: fitted by its height, then its width checked.
        val scale = h / gh
        if (gw * scale > w * 1.35f || gw * scale < w * 0.65f) return 0f
        val drawn = Ink(w, h)
        Fill.polygons(drawn, g.polygons(scale, -bb[0] * scale + (w - gw * scale) / 2, -bb[1] * scale))
        var both = 0; var either = 0
        for (yy in 0 until h) for (xx in 0 until w) {
            val a = clean[l + xx, t + yy]; val c = drawn[xx, yy]
            if (a && c) both++
            if (a || c) either++
        }
        return if (either == 0) 0f else both.toFloat() / either
    }

    /** The ending brackets over [s]: (from x, to x, which ending). */
    private fun endings(clean: Ink, s: Staff, page: Ink = clean): List<Triple<Int, Int, Int>> {
        val sp = s.space
        val out = ArrayList<Triple<Int, Int, Int>>()
        val top = s.y(-12, s.left).roundToInt(); val bottom = s.y(-2, s.left).roundToInt()
        var y = top
        while (y <= bottom) {
            var x = s.left
            while (x < s.right) {
                if (!clean[x, y]) { x++; continue }
                var e = x
                while (e < s.right && (clean[e + 1, y] || clean[e + 2, y])) e++
                val len = e - x
                // Thin: nothing just above or below it along most of its length.
                val thin = len >= sp * 4 && (x..e step 3).count { xx -> !clean[xx, y - (sp * 0.35f).toInt()] && !clean[xx, y + (sp * 0.35f).toInt()] } >= len / 3 * 0.8f
                // Hooked down at its left end, a space or more.
                val hook = thin && (x - 2..x + 2).any { xx -> (y..y + (sp * 0.9f).toInt()).all { yy -> clean[xx, yy] || clean[xx, yy - 1] } }
                if (hook) {
                    val n = Digits.number(page, x, x + (sp * 3).toInt(), y - (sp * 0.5f).toInt(), y + (sp * 2.5f).toInt(), (sp * 0.5f).toInt(), (sp * 2.2f).toInt(), space = sp)?.first
                    if (n != null && n in 1..3 && out.none { abs(it.first - x) < sp }) out += Triple(x, e, n)
                }
                x = e + 1
            }
            y++
        }
        return out
    }

    private fun fmt(q: Double) = if (q == q.toLong().toDouble()) q.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, q).trimEnd('0')

    /** What carries from staff to staff and page to page: the clef, key and time in force. */
    class Carry(var clef: Clef = Clef.TREBLE, var key: Key = Key(0), var time: TimeSig = TimeSig(4, 4)) {
        /** The last bar number printed at a line's start and taken: numbers only go up. */
        var printed = 0
        /**
         * A page read on its own, out of the middle of a part: the bar numbers before it are not
         * known, so the first one printed at a line's start is taken, whatever it is.
         */
        var alone = false
    }

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

    /** A whole rest alone: the whole bar rested, whatever its time. */
    private fun restsWholeBar(events: List<Event>) = events.size == 1 && events[0].let { it is Rest && it.duration.base == 1 && it.duration.dots == 0 }
    private fun restsWholeBar(m: Measure) = restsWholeBar(m.events)

    private fun timeAt(clean: Ink, s: Staff, x0: Int, opening: Boolean = false, lineStart: Boolean = false): Pair<TimeSig, Int>? {
        val sp = s.space
        // A quick look first: a time signature fills both halves of the staff there, one figure over
        // another (or a C across the middle) - most bars' starts have a note or nothing, and are passed by.
        run {
            val x1 = x0 + (sp * 2.6f).toInt()
            var upper = 0; var lower = 0; var n = 0
            for (x in x0..x1) {
                val top = s.lineY(0, x).roundToInt(); val mid = s.lineY(2, x).roundToInt(); val bottom = s.lineY(4, x).roundToInt()
                for (y in top until mid) if (clean[x, y]) upper++
                for (y in mid until bottom) if (clean[x, y]) lower++
                n += mid - top
            }
            if (traceRests) println("    timeAt $x0: upper ${"%.2f".format(upper.toFloat() / max(1, n))} lower ${"%.2f".format(lower.toFloat() / max(1, n))}")
            if (n == 0 || upper < n * 0.08f || lower < n * 0.08f) return null
            // (Plainly inked both halves: what stands here is like a signature, read or not.)
            if (upper >= n * 0.15f && lower >= n * 0.15f) timeInk = true
        }
        fun digitAt(x: Int, step: Int, allowed: Set<Int> = (0..9).toSet()): Pair<Int, Int>? = digit(clean, s, x, x + (sp * 2.2f).toInt(), listOf(step), allowed)
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
        // The bottom number is a note's value - 2, 4, 8, 16 - never 1, and may sit centred under a
        // top of two figures: read as one of those, from the top's left to its end.
        val bottom = number(6)?.takeIf { it.first in listOf(2, 4, 8, 16) }
            // (Only at the piece's start, where a signature surely is: elsewhere that finds one in anything.)
            ?: (if (opening) digit(clean, s, x0, top.second, listOf(6), setOf(2, 4, 8))?.let { (d, x) -> d to end(d, x) } else null)
            ?: return null
        if (traceRests) println("    timeAt $x0: top $top bottom $bottom")
        if (top.first !in 1..16) return null
        return TimeSig(top.first, bottom.first) to max(top.second, bottom.second) + (sp * 0.4f).toInt()
    }

    // ---- a page whose PDF says what is printed ------------------------------------------------

    /** This page's printed symbols in its pixels, when its PDF states them; null when they are found in the picture. */
    private var printed: Printed? = null
    private var staves: List<Staff> = emptyList()
    /** Each head taken from [printed], and the character it is. */
    private val symbolOf = HashMap<Head, Printed.Symbol>()
    /** The trained reader's filled heads measured at the part's own size: not a cue's. */
    private val fullSize = HashSet<Head>()
    /** Where small heads (a cue's, a grace note's) were found and left out, by staff. */
    private val cueAt = HashMap<Int, MutableList<Int>>()

    /** A PDF's small heads, by staff: (x, step) - a grace note's where one of the part's own follows it closely. */
    private val graceAt = HashMap<Int, MutableList<Pair<Int, Int>>>()

    /** The trained reader's heads its second look said were none, with their staves: put back where a bar wants them. */
    private val secondOut = ArrayList<Pair<Printed.Symbol, Int>>()

    /** How wide the part's own filled heads print on each staff, in spaces (measured once a page). */
    private val normalHead = HashMap<Int, Float>()
    private val printedAccidental = HashMap<Head, Int>()
    /** The printed lines some head took as its stem: the rest that cross a staff are barlines. */
    private val usedStems = HashSet<Printed.Stem>()
    private val usedAsBarline = HashSet<Printed.Stem>()

    private val headKinds = setOf(Printed.Kind.HEAD_BLACK, Printed.Kind.HEAD_HALF, Printed.Kind.HEAD_WHOLE)

    /**
     * Some fonts' heads sit a little off their origin: the heads (and dots) moved by however far
     * most of them on the staves sit off a line or space.
     */
    private fun aligned(p: Printed, staves: List<Staff>): Printed {
        val sp = staves.firstOrNull()?.space ?: return p
        val fracs = p.heads.mapNotNull { h ->
            val s = staves.firstOrNull { h.y > it.top - it.space && h.y < it.bottom + it.space && h.x >= it.left && h.x <= it.right } ?: return@mapNotNull null
            val x = h.x.roundToInt()
            val exact = (h.y - s.lineY(0, x)) / ((s.lineY(4, x) - s.lineY(0, x)) / 8f)
            exact - Math.round(exact)
        }.sorted()
        if (p.learned) return p
        val shift = fracs.getOrNull(fracs.size / 2)?.takeIf { abs(it) > 0.1f }?.let { it * sp / 2 } ?: return p
        return Printed(p.width, p.height, p.symbols.map { if (it.kind in headKinds || it.kind == Printed.Kind.DOT) it.copy(y = it.y - shift) else it }, p.stems, p.beams, p.arcs, p.lines)
    }

    /**
     * The line or space a trained reader's head [sym] sits on: its height measured against the
     * staff's own lines where it stands (a scan's staff is not evenly spaced from end to end, and a
     * head on the bottom line is eight half-spaces down, where a little off adds up), then settled
     * by the ink round the line nearest it - the reader's guess at the height can be a fraction
     * off, and a fraction is a line or a space.
     */
    private fun learnedStep(sym: Printed.Symbol, s: Staff, ink: Ink?): Int {
        val cx = (sym.x + sym.width / 2).roundToInt()
        val top = s.lineY(0, cx); val half = (s.lineY(4, cx) - top) / 8f
        val e = (sym.y - top) / half
        // Two looks at the ink, each fooled now and then by what touches a head (a tie's end, a
        // stem, a dot): taken only where they agree.
        val middle = if (ink != null && sym.kind == Printed.Kind.HEAD_BLACK) inkMiddle(ink, cx, sym.y, s.space)?.let { ((it - top) / half).roundToInt() } else null
        val balance = if (ink != null) onLineOrBeside(ink, s, cx, e) else null
        val step = if (middle != null && middle == balance && abs(middle - e) <= 1.2f) middle else e.roundToInt()
        if (System.getProperty("inksheets.omr.steps") != null) println("  STEP x=$cx y=${sym.y.toInt()} net=${"%.2f".format(e)} middle=$middle balance=$balance -> $step")
        return step
    }

    /**
     * The middle of a filled head's ink about ([cx], [y]): down each column across the head's middle
     * (clear of its stem), the ink's run through the head - which takes in a staff line the head
     * sits on, or the two it sits between, either way evenly - and the median of their middles.
     * Null where the runs are not a head's: a stem, a beam, a slur through it, nothing.
     */
    private fun inkMiddle(ink: Ink, cx: Int, y: Float, sp: Float): Float? {
        val across = (sp * 0.28f).toInt().coerceAtLeast(1)
        val reach = (sp * 0.4f).toInt().coerceAtLeast(1)
        val mids = ArrayList<Float>()
        for (x in cx - across..cx + across) {
            val y0 = y.roundToInt()
            val start = (0..reach).asSequence().flatMap { d -> sequenceOf(y0 + d, y0 - d) }.firstOrNull { ink[x, it] } ?: continue
            var a = start; while (ink[x, a - 1] && start - a < sp * 1.5f) a--
            var b = start; while (ink[x, b + 1] && b - start < sp * 1.5f) b++
            val len = b - a + 1
            if (len < sp * 0.6f || len > sp * 1.45f) continue
            mids += (a + b) / 2f
        }
        if (mids.size < 3) return null
        mids.sort()
        return mids[mids.size / 2]
    }

    /**
     * Whether a head at about [e] half-spaces down sits on the line nearest it or in the space
     * beside: a head on a line has as much of its ink above the line as below, a head in a space
     * all of it to one side. (Its middle measured outright moves with whatever touches it - a
     * slur's end, a tie - where the balance across the line hardly does.) Off the staff the line is
     * the ledger line found there, not the staff's spacing carried on. Null where the ink does not say.
     */
    private fun onLineOrBeside(ink: Ink, s: Staff, cx: Int, e: Float): Int? {
        val sp = s.space
        val line = Math.round(e / 2f) * 2
        var ly = s.y(line, cx)
        val t = max(1, lineThickness)
        if (line < 0 || line > 8) {
            // The ledger line itself: the row near where it should be whose ink runs on furthest past the head both ways.
            val reach = (sp * 0.85f).toInt()
            fun runAt(y: Int): Int {
                if (!ink[cx - reach, y] || !ink[cx + reach, y]) return 0
                var n = 0; for (x in cx - reach..cx + reach) if (ink[x, y]) n++; return n
            }
            val y0 = (ly - sp * 0.3f).roundToInt(); val y1 = (ly + sp * 0.3f).roundToInt()
            val best = (y0..y1).maxByOrNull { y -> runAt(y) * 1000 - abs(y - ly).roundToInt() } ?: return null
            if (runAt(best) < reach * 2 * 0.85f) return null
            ly = best.toFloat()
        }
        val across = (sp * 0.3f).toInt().coerceAtLeast(1)
        val near = (sp * 0.45f).roundToInt()
        var above = 0; var below = 0
        for (x in cx - across..cx + across) for (dy in t..near) {
            if (ink[x, (ly - dy).roundToInt()]) above++
            if (ink[x, (ly + dy).roundToInt()]) below++
        }
        val most = max(above, below)
        if (most < (2 * across + 1) * (near - t + 1) * 0.25f) return null
        val step = when {
            // (A slur's end or a dot touching one side tips it a little, never to nothing on the other.)
            min(above, below) >= most * 0.25f -> line
            above > below -> line - 1
            else -> line + 1
        }
        return step.takeIf { abs(it - e) <= 1.2f }
    }

    /**
     * Whether accidental [a], taken for a flat or a natural, is a natural: followed as one stroke
     * of ink (the staff's lines out), a natural's right-hand upright runs on well below where its
     * left one stops, while a flat's bowl closes at its stem's foot. Null when the ink cannot say
     * (nothing there, or more than an accidental's worth joined to it).
     */
    private fun looksNatural(clean: Ink, a: Printed.Symbol, sp: Float): Boolean? {
        val cx = (a.x + a.width / 2).roundToInt(); val cy = a.y.roundToInt()
        val x0 = (cx - sp * 0.8f).toInt(); val x1 = (cx + sp * 0.5f).toInt()
        val y0 = (cy - sp * 2.6f).toInt(); val y1 = (cy + sp * 2.2f).toInt()
        // The ink nearest the middle, and all joined to it inside the box.
        val r = (sp * 0.5f).toInt()
        var seed: Pair<Int, Int>? = null
        loop@ for (d in 0..r) for (dy in -d..d) for (dx in -d..d) { if (clean[cx + dx, cy + dy]) { seed = cx + dx to cy + dy; break@loop } }
        val start = seed ?: return null
        val w = x1 - x0 + 1; val h = y1 - y0 + 1
        val seen = BooleanArray(w * h)
        val stack = ArrayDeque<Int>()
        fun push(x: Int, y: Int) { if (x in x0..x1 && y in y0..y1 && clean[x, y]) { val k = (y - y0) * w + (x - x0); if (!seen[k]) { seen[k] = true; stack.addLast(k) } } }
        push(start.first, start.second)
        var count = 0
        val low = IntArray(w) { Int.MIN_VALUE }
        while (stack.isNotEmpty()) {
            val k = stack.removeLast(); val x = k % w + x0; val y = k / w + y0
            count++
            if (y > low[x - x0]) low[x - x0] = y
            push(x + 1, y); push(x - 1, y); push(x, y + 1); push(x, y - 1)
        }
        // More ink than an accidental's: run into a head, a beam, a slur - not to be judged.
        if (count > sp * sp * 2.2f || count < sp * sp * 0.15f) return null
        val cols = (0 until w).filter { low[it] != Int.MIN_VALUE }
        if (cols.size < 3) return null
        val a0 = cols.first(); val a1 = cols.last()
        val third = max(1, (a1 - a0 + 1) / 3)
        val leftFoot = (a0 until a0 + third).maxOf { low[it] }
        val rightFoot = (a1 - third + 1..a1).maxOf { low[it] }
        if (a1 - a0 < sp * 0.25f) return null
        return rightFoot - leftFoot > sp * 0.45f
    }

    /**
     * How big a filled head found by the trained reader is printed, in staff spaces: its ink's
     * widest level run near its middle (the staff's lines out; a stem adds a line's width at
     * most). A cue's or grace note's heads are set clearly smaller than the part's own. Null where
     * the run is not a head's (joined to a beam, a slur, another head).
     */
    internal fun headSize(clean: Ink, sym: Printed.Symbol, sp: Float): Float? {
        val cx = (sym.x + sym.width / 2).roundToInt(); val cy = sym.y.roundToInt()
        var best = 0
        for (dy in -(sp * 0.2f).toInt()..(sp * 0.2f).toInt()) {
            val y = cy + dy
            var x0 = cx; var x1 = cx
            if (!clean[cx, y]) continue
            while (clean[x0 - 1, y] && cx - x0 < sp * 1.2f) x0--
            while (clean[x1 + 1, y] && x1 - cx < sp * 1.2f) x1++
            // (A row a ledger line runs along, or a beam, is wider than any head: not the head's.)
            if (x1 - x0 + 1 <= sp * 1.6f) best = max(best, x1 - x0 + 1)
        }
        if (best == 0) return null
        // Solid, as a filled head is: an x or a slash (a drum part's) is mostly paper inside.
        val hw = (best / 2f * 0.7f).toInt().coerceAtLeast(1); val hh = (sp * 0.3f).toInt().coerceAtLeast(1)
        var inked = 0; var all = 0
        for (y in cy - hh..cy + hh) for (x in cx - hw..cx + hw) { all++; if (clean[x, y]) inked++ }
        if (inked < all * 0.6f) return null
        return best / sp
    }

    /**
     * What [SymbolReader] makes of the trained reader's symbol [sym] on staff [s], where it is sure
     * (null where it is not, or cannot look: no page in grey, no network).
     */
    private fun secondLook(sym: Printed.Symbol, s: Staff, sure: Float = SECOND_SURE): String? {
        val g = pageGrey ?: return null
        val ink = pageInk ?: return null
        if (!SymbolReader.available || System.getProperty("inksheets.omr.nosecond") != null) return null
        val cx = sym.x + sym.width / 2; val cy = sym.y
        val top = s.lineY(0, cx.roundToInt()); val half = (s.lineY(4, cx.roundToInt()) - top) / 8f
        val (label, p) = SymbolReader.read(g, ink.width, ink.height, cx, cy, s.space, (cy - top) / half, SymbolReader.labelOf(sym.kind.name)) ?: return null
        return label.takeIf { p >= sure }
    }

    /**
     * A PDF's grace notes in the bar from [from] to [to] on staff [si]: its small heads with one of
     * [events]' notes just after (no other small head between) - a cue's run of small heads is none.
     */
    private fun graceNotes(si: Int, from: Int, to: Int, events: List<Event>, carry: Carry): List<Note> {
        val small = graceAt[si]?.filter { it.first >= from - 2 && it.first < to } ?: return emptyList()
        val sp = staves.getOrNull(si)?.space ?: return emptyList()
        val notes = events.filterIsInstance<Note>()
        return small.filter { (x, _) ->
            val next = notes.filter { it.x > x }.minByOrNull { it.x } ?: return@filter false
            next.x - x <= sp * 3.5f && small.none { (ox, _) -> ox > x + 2 && ox < next.x }
        }.distinctBy { it.first / 3 to it.second }.groupBy { it.first / 3 }.map { (_, chord) ->
            val steps = chord.map { it.second }.sorted()
            Note(steps, steps.map { st -> val d = carry.clef.at(st); Pitch.fromDiatonic(d, carry.key.alterOf(d.mod(7))) }, Duration(8), chord.minOf { it.first }.toFloat())
        }
    }

    /**
     * A scan's grace notes in a bar of [events] on staff [s]: before each note (and after the one
     * before it), a small solid head - narrower than a space, not as tall as one - with a stem
     * rising from its right side, struck through or not. A dot is too small, a flat or a natural
     * hollow, a sharp a lattice: none is a filled head with its stem up.
     */
    private fun scanGraces(clean: Ink, s: Staff, events: List<Event>, carry: Carry, musicStart: Int): List<Note> {
        val sp = s.space
        val out = ArrayList<Note>()
        val notes = events.filterIsInstance<Note>()
        for (n in notes) {
            val before = events.filter { it.x < n.x - 1 }.maxOfOrNull { it.x }
            // (Not before the bar's music starts: a clef, key or time printed there is none.)
            val x0 = (if (before != null) before + sp * 1.6f else n.x - sp * 3.2f).coerceAtLeast(n.x - sp * 3.2f).coerceAtLeast(musicStart + sp * 0.3f).roundToInt()
            // Clear of the note's own accidentals.
            val x1 = (n.x - sp * (if (n.accidentals.isNotEmpty()) 1.6f else 0.45f)).roundToInt()
            if (x1 - x0 < sp * 0.5f) continue
            var best: Triple<Int, Int, Float>? = null
            for (step in -6..14) {
                val y = s.y(step, (x0 + x1) / 2).roundToInt()
                var cx = x0
                while (cx <= x1) {
                    if (!clean[cx, y]) { cx++; continue }
                    // Across it at its middle, and up and down through its middle.
                    var a = cx; var b = cx
                    while (clean[a - 1, y] && cx - a < sp) a--
                    while (clean[b + 1, y] && b - cx < sp * 1.2f) b++
                    val w = b - a + 1
                    val mx = (a + b) / 2
                    var top = y; var bot = y
                    while (clean[mx, top - 1] && y - top < sp) top--
                    while (clean[mx, bot + 1] && bot - y < sp) bot++
                    val h = bot - top + 1
                    cx = b + 1
                    if (w < sp * 0.5f || w > sp * 0.95f || h < sp * 0.38f || h > sp * 0.8f) continue
                    // Its middle row where the head's middle would be (a step's height out at most).
                    if (abs((top + bot) / 2f - y) > sp * 0.2f) continue
                    // Solid: an ellipse of ink, not a ring.
                    var inked = 0; var all = 0
                    for (yy in top..bot) for (xx in a..b) {
                        val dx = (xx - mx) / (w / 2f); val dy = (yy - (top + bot) / 2f) / (h / 2f)
                        if (dx * dx + dy * dy <= 0.7f) { all++; if (clean[xx, yy]) inked++ }
                    }
                    if (all == 0 || inked < all * 0.85f) continue
                    // Rounded: its box's corners paper, as a head's are - a beam's short piece, a sharp's
                    // lattice, a digit's stroke fill them.
                    fun corner(cx: Int, cy: Int): Boolean {
                        var k = 0; var c = 0
                        for (yy in cy - 1..cy + 1) for (xx in cx - 1..cx + 1) { c++; if (clean[xx, yy]) k++ }
                        return k * 2 > c
                    }
                    val cw = maxOf(1, (w * 0.12f).toInt()); val ch = maxOf(1, (h * 0.12f).toInt())
                    val inkedCorners = listOf(corner(a + cw, top + ch), corner(b - cw, top + ch), corner(a + cw, bot - ch), corner(b - cw, bot - ch)).count { it }
                    if (inkedCorners >= 3) continue
                    // Its stem rises only: ink running on down from its right side is a beam's stem, or a note's.
                    val down = (b - 2..b + 2).maxOf { sx -> var yy = bot; while (clean[sx, yy + 1] && yy - bot < sp) yy++; yy - bot }
                    if (down > sp * 0.5f) continue
                    // A stem up from its right side: ink in a column at its right edge, well up.
                    val stem = (b - 2..b + 2).maxOf { sx ->
                        var yy = y; var gap = 0
                        while (y - yy < sp * 3.5f && gap <= 2) { yy--; if (clean[sx, yy]) gap = 0 else gap++ }
                        y - yy - gap
                    }
                    if (stem < sp * 1.5f) continue
                    val fill = inked.toFloat() / all
                    if (best == null || fill > best.third) best = Triple(mx - w / 2, step, fill)
                }
            }
            best?.let { (gx, step, _) ->
                val d = carry.clef.at(step)
                out += Note(listOf(step), listOf(Pitch.fromDiatonic(d, carry.key.alterOf(d.mod(7)))), Duration(8), gx.toFloat())
            }
        }
        return out
    }

    /** -Dinksheets.omr.headwhy=1: say why a head the trained reader found was let go (for finding out). */
    private fun headWhy(sym: Printed.Symbol, why: String) {
        if (System.getProperty("inksheets.omr.headwhy") != null) println("HEADWHY ${sym.kind} at ${sym.x.toInt()},${sym.y.toInt()} conf ${"%.2f".format(sym.confidence)}: $why")
    }

    /**
     * The staff a head between two staves belongs to - [near] the one whose middle it is nearer -
     * as the page shows it: the ledger lines climb from its own staff (the first of them, next to
     * that staff's line, printed; next to the other's, not), and its stem points into its own
     * staff (a high note's down, a low note's up). Where neither says, [near].
     */
    private fun staffBetween(sym: Printed.Symbol, near: Int, staves: List<Staff>, ink: Ink): Int {
        val s = staves[near]; val sp = s.space
        val cx = (sym.x + sym.width / 2).roundToInt()
        val below = sym.y > s.lineY(4, cx) + sp * 1.5f
        val above = sym.y < s.lineY(0, cx) - sp * 1.5f
        val oi = if (below) near + 1 else if (above) near - 1 else return near
        val o = staves.getOrNull(oi)?.takeIf { cx >= it.left - sp && cx <= it.right } ?: return near
        // A ledger line one space out from a staff, across the head (and not much further: a ledger is short).
        fun rung(st: Staff, side: Int): Boolean {
            val y = (if (side > 0) st.lineY(4, cx) + st.space else st.lineY(0, cx) - st.space).roundToInt()
            val l = (sym.x - sp * 0.2f).roundToInt(); val r = (sym.x + sym.width + sp * 0.2f).roundToInt()
            return (-1..1).any { dy -> (l..r).count { ink[it, y + dy] } >= (r - l + 1) * 0.75f }
        }
        // Each staff's first ledger towards the head - where the head is far enough out to want one.
        val wantsNear = if (below) sym.y > s.lineY(4, cx) + sp * 0.9f else sym.y < s.lineY(0, cx) - sp * 0.9f
        val wantsOther = if (below) sym.y < o.lineY(0, cx) - sp * 0.9f else sym.y > o.lineY(4, cx) + sp * 0.9f
        val nearRung = wantsNear && rung(s, if (below) 1 else -1)
        val otherRung = wantsOther && rung(o, if (below) -1 else 1)
        if (otherRung && !nearRung) return oi
        if (nearRung && !otherRung) return near
        // Its stem: up from its right side, or down from its left - into its own staff.
        val top = (sym.y - sp * 0.45f).roundToInt(); val bot = (sym.y + sp * 0.45f).roundToInt()
        fun run(x: Int, from: Int, step: Int): Int { var y = from; var gap = 0; while (abs(y - from) < sp * 4 && gap <= 2) { y += step; if (ink[x, y]) gap = 0 else gap++ }; return abs(y - from) - gap }
        val right = (sym.x + sym.width).roundToInt()
        val up = (right - 3..right + 1).maxOf { run(it, top, -1) }
        val down = (sym.x.roundToInt() - 1..sym.x.roundToInt() + 3).maxOf { run(it, bot, 1) }
        val stemDown = down >= sp * 2.2f && up < sp * 1.2f
        val stemUp = up >= sp * 2.2f && down < sp * 1.2f
        // (Only well out from the nearer staff: a low note just under it is its own, whichever way its stem.)
        val far = if (below) sym.y > s.lineY(4, cx) + sp * 2.4f else sym.y < s.lineY(0, cx) - sp * 2.4f
        if (far && below && stemDown) return oi
        if (far && above && stemUp) return oi
        return near
    }

    /**
     * Whether note [n] on staff [s] is printed small - a grace note's head, read by the trained
     * reader as one of the part's own: its filled head plainly narrower than the staff's others.
     */
    private fun smallHead(clean: Ink, s: Staff, si: Int, n: Note, heads: List<Head>): Boolean {
        if (n.duration.base < 4) return false
        val sp = s.space
        fun width(h: Head) = symbolOf[h]?.let { headSize(clean, it, sp) }
        val normal = normalHead.getOrPut(si) {
            heads.filter { it.kind == "noteheadBlack" }.mapNotNull { width(it) }.sorted().let { w -> if (w.size < 4) 0f else w[w.size / 2] }
        }
        if (normal <= 0f) return false
        val h = heads.firstOrNull { it.x == n.x.roundToInt() && it.step in n.steps } ?: return false
        val w = width(h) ?: return false
        return w < normal * 0.8f
    }

    private val SECOND_OUT = "second look: not a head"

    /** How sure the second look must be to overrule the trained reader. */
    var SECOND_SURE = 0.9f
    var HEAD_OUT_SURE = System.getProperty("inksheets.omr.headout")?.toFloatOrNull() ?: 0.98f

    /** The printed heads, each on the staff it is nearest the middle of; cue and grace notes (small) left out, as they take no time in the bar. */
    private fun printedHeads(staves: List<Staff>, lines: Ink? = null, clean: Ink? = null): List<MutableList<Head>> {
        val p = printed!!
        val all = p.heads
        val out = staves.map { ArrayList<Head>() }
        if (all.isEmpty() || staves.isEmpty()) return out
        val small = Printed.small(all)
        for (sym in all) {
            val near = staves.indices.filter { val s = staves[it]; sym.x >= s.left - s.space && sym.x <= s.right }
                .minByOrNull { abs((staves[it].top + staves[it].bottom) / 2f - sym.y) } ?: continue
            // Between two staves: whichever its ledger lines and stem say (see staffBetween), not just
            // whichever middle is nearer - a high note over one staff is that staff's.
            val si = if (lines != null) staffBetween(sym, near, staves, lines) else near
            val s = staves[si]
            if (abs((s.top + s.bottom) / 2f - sym.y) > s.space * 9) continue
            // Small: a cue's or a grace note's - no time in the bar. Kept aside: a grace note is
            // told by one of the part's own just after it (see graceNotes).
            if (sym in small) {
                val x = sym.x.roundToInt()
                if (!p.learned) graceAt.getOrPut(si) { ArrayList() } += x to ((sym.y - s.lineY(0, x)) / (s.space / 2)).roundToInt()
                continue
            }
            // The second look at a head the trained reader found: a word's letter, a dynamic, a mark
            // it is sure is none is left out; a faint one it is sure is a head is taken after all.
            // (Faint ones not taken are not notes, but put back if a bar wants them - see read.)
            val second = if (p.learned && System.getProperty("inksheets.omr.noheadlook") == null) secondLook(sym, s) else null
            // (A head left out on a sterner word than anything else: a note lost is a bar wrong.)
            if (second == "other" && secondLook(sym, s, HEAD_OUT_SURE) == "other") {
                // Let go, not lost: a bar that adds up only with it takes it back (see read) - the second
                // look, sure on the scans it learned from, is wrong now and then on a real one.
                headWhy(sym, "second look: not a head")
                if (p.learned) secondOut += sym to si
                continue
            }
            if (p.learned && Learned.faint(sym) && second != "black" && second != "half" && second != "whole") { headWhy(sym, "faint"); continue }
            val x = sym.x.roundToInt()
            val step = if (p.learned) learnedStep(sym, s, lines) else ((sym.y - s.lineY(0, x)) / (s.space / 2)).roundToInt()
            val kind = when (sym.kind) { Printed.Kind.HEAD_HALF -> "noteheadHalf"; Printed.Kind.HEAD_WHOLE -> "noteheadWhole"; else -> "noteheadBlack" }
            // (A faint one taken after all is as sure as the reader's floor: no surer.)
            val h = Head(x, step, s.y(step, x).roundToInt(), kind, if (Learned.faint(sym)) Learned.floor else sym.confidence)
            // The trained reader takes a word's letter off the staff for a head (legato's "o"): off
            // the staff, a head needs its ledger line - or, where a worn scan's ledger line is too
            // faint to see, its stem. (A PDF's own heads are its notes.)
            if (p.learned && lines != null && !onLedger(h, s, lines) && !hasStem(h, s, lines)) { headWhy(sym, "off the staff, no ledger line or stem (step ${h.step})"); continue }
            symbolOf[h] = sym
            out[si] += h
        }
        // Cue notes in a scan: the trained reader finds them as heads like any other, but they are
        // printed smaller - a cue's heads about five sixths of the part's own. Measured on the page.
        // (-Dinksheets.omr.nocues=1: not looked for - for measuring what it changes.)
        if (p.learned && clean != null && System.getProperty("inksheets.omr.nocues") == null) {
            val sp = staves.first().space
            val size = HashMap<Head, Float>()
            for (hs in out) for (h in hs) if (h.kind == "noteheadBlack") symbolOf[h]?.let { sym -> headSize(clean, sym, sp)?.let { size[h] = it } }
            val sizes = size.values.sorted()
            val normal = sizes.getOrNull(sizes.size * 3 / 4) ?: 0f
            // Only where the sizes fall plainly in two (a cue's and the part's, with a gap between):
            // heads of every size in between (a drum part's x's and slashes, a blurred scan) say nothing.
            val between = sizes.count { it > normal * 0.84f && it < normal * 0.95f }
            // (Nor on a page whose filled heads are often not solid - a drum part's x's and slashes.)
            val filled = out.sumOf { hs -> hs.count { it.kind == "noteheadBlack" } }
            if (sizes.size >= 12 && between <= sizes.size / 10 && sizes.size >= filled * 0.6f) {
                val cut = normal * 0.9f
                fun smallHere(h: Head, hs: List<Head>): Boolean {
                    size[h]?.let { return it < cut }
                    // A hollow head (not measured): as the filled heads either side of it on its staff are -
                    // a cue's whole note between a cue's crotchets is the cue's.
                    val left = hs.filter { o -> size[o] != null && o.x < h.x }.maxByOrNull { it.x }
                    val right = hs.filter { o -> size[o] != null && o.x > h.x }.minByOrNull { it.x }
                    return when {
                        left != null && right != null -> size[left]!! < cut && size[right]!! < cut
                        else -> (left ?: right)?.let { o -> size[o]!! < cut && abs(o.x - h.x) < sp * 12 } ?: false
                    }
                }
                for (hs in out) for (h in hs) if (size[h]?.let { it >= cut } == true) fullSize += h
                if (System.getProperty("inksheets.omr.headsize") != null) for ((si, hs) in out.withIndex()) println("  SIZES staff $si cut ${"%.2f".format(cut)}: " +
                    hs.joinToString(" ") { h -> "${h.x}${h.kind.removePrefix("notehead").take(1)}=${size[h]?.let { "%.2f".format(it) } ?: "-"}${if (smallHere(h, hs)) "s" else ""}" })
                // Noted, not left out: a bar of them, with none of the part's own size, is a cue (see read).
                for ((si, hs) in out.withIndex()) {
                    val small = hs.filter { smallHere(it, hs) }
                    if (small.isNotEmpty()) cueAt.getOrPut(si) { ArrayList() } += small.map { it.x }
                }
            }
        }
        // Each accidental to the head just right of it at its height (a chord's are staggered further left).
        val heads = out.flatten()
        // Two flats side by side at one height, close: a double flat (the trained reader knows a flat,
        // not a double one) - the one nearer the note takes it, the other is part of it.
        val doubleFlat = HashSet<Printed.Symbol>(); val partOfDouble = HashSet<Printed.Symbol>()
        if (p.learned) {
            val sp0 = staves.firstOrNull()?.space ?: 0f
            val flats = p.symbols.filter { it.kind == Printed.Kind.FLAT }.sortedBy { it.x }
            for (a in flats) if (a !in partOfDouble && a !in doubleFlat)
                flats.firstOrNull { b -> b !== a && b.x > a.x && b.x - a.x < sp0 * 1.0f && abs(b.y - a.y) < sp0 * 0.3f && b !in doubleFlat && b !in partOfDouble }
                    ?.let { b -> partOfDouble += a; doubleFlat += b }
        }
        for (a in p.symbols) {
            if (a in partOfDouble) continue
            var alter = when (a.kind) { Printed.Kind.FLAT -> -1; Printed.Kind.SHARP -> 1; Printed.Kind.NATURAL -> 0; Printed.Kind.DOUBLE_FLAT -> -2; Printed.Kind.DOUBLE_SHARP -> 2; else -> continue }
            if (a in doubleFlat) alter = -2
            val sp = staves.first().space
            // The trained reader's accidental looked at again on its own; else (no second look, or
            // not sure) a natural told from a flat by its strokes on the page.
            val second = if (p.learned && (alter in -1..1)) staves.minByOrNull { st -> abs((st.top + st.bottom) / 2f - a.y) }?.let { st -> secondLook(a, st) } else null
            when (second) {
                "other" -> continue
                "sharp" -> alter = 1
                "flat" -> alter = -1
                "natural" -> alter = 0
                else -> if (p.learned && clean != null && (alter == -1 || alter == 0)) looksNatural(clean, a, sp)?.let { alter = if (it) 0 else -1 }
            }
            val h = heads.filter { h -> val sym = symbolOf[h]!!; sym.x - a.x in sp * 0.3f..sp * 3f && abs(sym.y - a.y) <= sp * 0.3f && h !in printedAccidental }
                .minByOrNull { symbolOf[it]!!.x - a.x } ?: continue
            printedAccidental[h] = alter
        }
        return out.map { it.sortedBy { h -> h.x }.toMutableList() }
    }

    /**
     * A printed head's stem - an upright line beside it, running from it or through it (a chord's
     * inner heads) - and the beams across its far end, or its flag.
     */
    private fun printedStem(s: Staff, h: Head) {
        val p = printed ?: return
        val sym = symbolOf[h] ?: return
        if (p.learned) {
            // The trained reader says how many beams or flags; a stem is taken as there (a chord's
            // heads share it, at their middle) for every filled or half head.
            h.stemX = (sym.x + sym.width / 2).roundToInt()
            // Where its stem ends is not known until it is seen on the page (see read): none yet.
            h.stemEnd = 0
            h.flags = sym.beams.coerceIn(0, 3)
            return
        }
        val sp = s.space
        val x = sym.x; val y = sym.y; val w = sym.width
        // A character's advance runs a little past its head's right edge: the stem may stand inside it.
        // A stem on the right rises from the head (the head at its bottom end), one on the left falls
        // from it (at its top end); a chord's inner heads sit along it, between its ends. A stem just
        // left of a head with its head at the bottom is the note before's.
        val stem = p.stems.filter { st ->
            if (st.y1 - st.y0 !in sp * 2f..sp * 12f || st in usedAsBarline) return@filter false
            val along = y > st.y0 + sp * 0.8f && y < st.y1 - sp * 0.8f
            val right = st.x in x + w * 0.5f..x + w + sp * 0.4f && (abs(y - st.y1) < sp * 0.8f || along)
            val left = st.x in x - sp * 0.4f..x + w * 0.4f && (abs(y - st.y0) < sp * 0.8f || along)
            right || left
        }.minByOrNull { st -> min(abs(st.x - x), abs(st.x - (x + w))) + min(abs(st.y1 - y), abs(st.y0 - y)) * 0.1f } ?: return
        usedStems += stem
        val up = abs(stem.y1 - y) < abs(stem.y0 - y)
        val end = if (up) stem.y0 else stem.y1
        h.up = up
        h.stemX = stem.x.roundToInt()
        h.stemEnd = end.roundToInt()
        val beams = p.beams.count { b -> b.thick >= sp * 0.3f && stem.x in b.x0 - sp * 0.15f..b.x1 + sp * 0.15f && abs(b.yAt(stem.x) - end) < sp * 2.2f }
        h.flags = if (beams > 0) min(beams, 3) else p.symbols.firstOrNull { f -> (f.kind == Printed.Kind.FLAG_8 || f.kind == Printed.Kind.FLAG_16 || f.kind == Printed.Kind.FLAG_32) &&
            abs(f.x - stem.x) < sp * 0.8f && abs(f.y - end) < sp * 3.5f }?.let { when (it.kind) { Printed.Kind.FLAG_8 -> 1; Printed.Kind.FLAG_16 -> 2; else -> 3 } } ?: 0
    }

    /** A printed head's dots (one or two): right of it, in its space or the one above, and not over another head (a staccato). */
    private fun printedDots(s: Staff, h: Head) {
        val p = printed ?: return
        val sym = symbolOf[h] ?: return
        if (p.learned) { h.dots = sym.dots.coerceIn(0, 2); return }
        val sp = s.space
        val heads = p.heads
        h.dots = p.symbols.filter { d -> d.kind == Printed.Kind.DOT && d.x > sym.x + sym.width * 0.8f && d.x < sym.x + sym.width + sp * 2.2f &&
            d.y > sym.y - sp * 0.9f && d.y < sym.y + sp * 0.4f && heads.none { o -> d.x in o.x..o.x + o.width && abs(d.y - o.y) > sp * 0.3f && abs(d.y - o.y) < sp * 3f } }
            .map { it.x }.sorted().fold(ArrayList<Float>()) { acc, x -> if (acc.isEmpty() || x - acc.last() > sp * 0.25f) acc += x; acc }.size.coerceAtMost(2)
    }

    /**
     * The five lines of [s] as printed by column [x] (see [measuredLine]), kept in step: each is
     * off the trace by about as much as the others are there - a line that seems far from where
     * the rest put it caught something else (a hairpin, a slur, a note against it) and is set by
     * the trace and the others' shift instead.
     */
    private fun measuredLines(ink: Ink, s: Staff, x: Int, dir: Int): List<Float> {
        val xc = x.coerceIn(s.left, s.right)
        val measured = (0..4).map { measuredLine(ink, s, it, x, dir) }
        val offs = (0..4).map { measured[it] - s.lineY(it, xc) }
        val shift = offs.sorted()[2]
        return (0..4).map { if (abs(offs[it] - shift) > 1.5f) s.lineY(it, xc) + shift else measured[it] }
    }

    /**
     * Where printed line [i] of [s] really is by column [x], looking [dir]ward (-1 left, +1 right)
     * a few columns past it: in each, the middle of the line-thin dark run nearest the traced line;
     * the median of those - to a fraction of a pixel. Looked for the other way where the staff
     * starts or ends there; the trace where nothing clean is found (notes on the line all along).
     */
    private fun measuredLine(ink: Ink, s: Staff, i: Int, x: Int, dir: Int): Float {
        val sp = s.space
        fun look(d: Int): List<Float> {
            val found = ArrayList<Float>()
            for (k in 2..9) {
                val c = x + d * k
                if (c < s.left + 1 || c > s.right - 1) continue
                val expected = s.lineY(i, c)
                val r = (sp * 0.3f).toInt()
                var best: Float? = null
                var y = (expected - r).toInt()
                while (y <= expected + r) {
                    if (!ink[c, y]) { y++; continue }
                    var e = y
                    while (e < expected + r + 3 && ink[c, e + 1]) e++
                    // As thin as a staff line: not a head on it, nor a beam.
                    if (e - y + 1 <= max(3, (sp * 0.28f).roundToInt())) {
                        val mid = (y + e) / 2f
                        if (best == null || abs(mid - expected) < abs(best - expected)) best = mid
                    }
                    y = e + 1
                }
                best?.let { found += it }
            }
            return found
        }
        val found = look(dir).takeIf { it.size >= 3 } ?: look(-dir).takeIf { it.size >= 3 } ?: return s.lineY(i, x.coerceIn(s.left, s.right))
        return found.sorted()[found.size / 2]
    }

    /** A head let go, as the note it would be: its value by its kind, stem and flags, its dot, its pitch in the key. */
    private fun maybeNote(clean: Ink, s: Staff, h: Head, carry: Carry): Note {
        dots(clean, s, h)
        val base = when (h.kind) {
            "noteheadWhole" -> 1
            "noteheadHalf" -> if (h.stemX < 0) 1 else 2
            else -> if (h.stemX < 0) 4 else when (h.flags) { 0 -> 4; 1 -> 8; 2 -> 16; else -> 32 }
        }
        val d = carry.clef.at(h.step)
        return Note(listOf(h.step), listOf(Pitch.fromDiatonic(d, carry.key.alterOf(d.mod(7)))), Duration(base, h.dots), h.x.toFloat(), stemUp = h.up.takeIf { h.stemX >= 0 }, confidence = h.score)
    }

    /**
     * The barlines the PDF draws across [s]: upright lines from its top line to its bottom one (or
     * on past both, joining staves), that no head took as its stem. A double
     * or final barline's strokes as one, at its last. Null when it draws none (they are found in
     * the picture then).
     */
    private fun printedBarlines(s: Staff): List<Int>? {
        val p = printed ?: return null
        val sp = s.space
        val found = ArrayList<Int>()
        for (st in p.stems.sortedBy { it.x }) {
            val x = st.x.roundToInt()
            if (x < s.left || x > s.right + sp) continue
            val top = s.lineY(0, x); val bottom = s.lineY(4, x)
            if (st.y0 > top + sp * 0.3f || st.y1 < bottom - sp * 0.3f) continue
            // Joining this staff to another is a barline; a stem reaching past a line only a little is a note's.
            val past = max(top - st.y0, st.y1 - bottom)
            if (past > sp * 0.4f && past < sp * 3f) continue
            if (st in usedStems) continue
            if (found.isEmpty() || x - found.last() > sp * 1.5f) found += x else found[found.size - 1] = x
        }
        return found.takeIf { it.isNotEmpty() }
    }

    /**
     * The key signature printed at [x0] on [s]: a run of sharps or flats close together (naturals
     * cancelling the key before are stepped over) - the key, and where it ends; null when none.
     */
    private fun printedKey(s: Staff, x0: Int): Pair<Key, Int>? {
        val p = printed ?: return null
        val sp = s.space
        val accs = p.symbols.filter { it.kind == Printed.Kind.FLAT || it.kind == Printed.Kind.SHARP || it.kind == Printed.Kind.NATURAL }
            .filter { a -> a.x >= x0 - sp * 0.3f && a.y > s.y(-3, a.x.roundToInt()) && a.y < s.y(11, a.x.roundToInt()) }.sortedBy { it.x }
        var at = x0.toFloat()
        val run = ArrayList<Printed.Symbol>()
        for (a in accs) {
            if (a.x - at > sp * 1.8f) break
            if (a.kind == Printed.Kind.NATURAL) { if (run.isEmpty()) { at = a.x + a.width; continue } else break }
            if (run.isNotEmpty() && a.kind != run.first().kind) break
            run += a; at = a.x + a.width
        }
        // A line of engraved music restates its key at its start: none printed there is C (a change
        // to it, cancelled by naturals, or none all along).
        if (run.isEmpty()) return Key(0) to x0
        // Not a key if the run is a note's own accidentals: a head just after the first one, at its height.
        val first = run.first()
        if (p.heads.any { h -> h.x - first.x in sp * 0.3f..sp * 2.5f && abs(h.y - first.y) < sp * 0.3f && run.size == 1 }) return null
        val sign = if (run.first().kind == Printed.Kind.SHARP) 1 else -1
        return Key(run.size.coerceAtMost(7) * sign) to (at + sp * 0.4f).roundToInt()
    }

    /**
     * The time signature printed at [x0] on [s]: its digits (the top number above the middle line,
     * the bottom below), or common or cut time - the signature, and where it ends; null when none.
     */
    private fun printedTime(s: Staff, x0: Int): Pair<TimeSig, Int>? {
        val p = printed ?: return null
        val sp = s.space
        val near = p.symbols.filter { d -> d.x >= x0 - sp * 0.3f && d.x <= x0 + sp * 3.5f && d.y > s.y(-1, d.x.roundToInt()) && d.y < s.y(9, d.x.roundToInt()) }
        near.firstOrNull { it.kind == Printed.Kind.TIME_COMMON }?.let { return TimeSig(4, 4) to (it.x + it.width + sp * 0.4f).roundToInt() }
        near.firstOrNull { it.kind == Printed.Kind.TIME_CUT }?.let { return TimeSig(2, 2) to (it.x + it.width + sp * 0.4f).roundToInt() }
        val digits = near.filter { it.kind == Printed.Kind.TIME_DIGIT && it.digit >= 0 }
        if (digits.isEmpty()) return null
        val mid = s.y(4, x0)
        fun number(ds: List<Printed.Symbol>) = ds.sortedBy { it.x }.fold(0) { n, d -> n * 10 + d.digit }
        val top = digits.filter { it.y < mid }; val bottom = digits.filter { it.y >= mid }
        if (top.isEmpty() || bottom.isEmpty()) return null
        val beats = number(top); val type = number(bottom)
        if (beats !in 1..32 || type !in listOf(1, 2, 4, 8, 16, 32)) return null
        return TimeSig(beats, type) to (digits.maxOf { it.x + it.width } + sp * 0.4f).roundToInt()
    }

    /**
     * The marks the PDF prints on chord [c] of [s]: its articulations (a staccato is a dot over or
     * under a head, not beside it), a fermata over the staff - and whether it is tied on (a curve
     * from one of its heads to the next note at that pitch, or off the end of the line).
     */
    private fun printedMarks(s: Staff, c: List<Head>): Pair<List<String>, Boolean> {
        val p = printed ?: return emptyList<String>() to false
        val sp = s.space
        val syms = c.mapNotNull { symbolOf[it] }
        if (syms.isEmpty()) return emptyList<String>() to false
        val left = syms.minOf { it.x }; val right = syms.maxOf { it.x + it.width * 0.8f }
        val top = syms.minOf { it.y }; val bottom = syms.maxOf { it.y }
        val out = ArrayList<String>()
        for (m in p.symbols) {
            val cx = m.x + m.width / 2
            if (cx < left - sp * 0.3f || cx > right + sp * 0.3f) continue
            when {
                m.kind == Printed.Kind.ARTICULATION && m.name == "fermata" -> if (m.y > top - sp * 8 && m.y < bottom + sp * 8) out += "fermata"
                m.kind == Printed.Kind.ARTICULATION -> if (m.y > top - sp * 4.5f && m.y < bottom + sp * 4.5f && m in unclaimed(m)) out += m.name.split('+')
                // A dot over or under a head (not beside it, where it lengthens the note) is a staccato.
                m.kind == Printed.Kind.DOT -> if ((m.y < top - sp * 0.6f && m.y > top - sp * 3f) || (m.y > bottom + sp * 0.6f && m.y < bottom + sp * 3f)) out += "staccato"
            }
        }
        // A tie: a curve from beside one of its heads, level, to the next head at that height (or the line's end).
        val tied = p.arcs.any { a ->
            abs(a.y1 - a.y0) < sp * 0.8f && a.x1 - a.x0 > sp * 0.8f && syms.any { h -> a.x0 > h.x + h.width * 0.3f && a.x0 < h.x + h.width + sp * 1.2f && abs(a.y0 - h.y) < sp * 1.2f } &&
                (a.x1 > s.right - sp * 1.5f || p.heads.any { o -> o.x > right && o.x - a.x1 < sp * 1.2f && a.x1 - o.x < o.width + sp * 0.4f && syms.any { h -> abs(o.y - h.y) < sp * 0.3f } })
        }
        return out.distinct() to tied
    }

    /**
     * Ties on a picture: between two notes one after the other on a staff (in a bar, or over its
     * barline) sharing a pitch, a thin curve hugging their heads - from just past the first head to
     * just before the second, under them or over, its ends nearer the heads than its middle (it bows
     * away), and not running on past either (a slur does). Each first note found so is marked tied.
     */
    private fun findTies(ink: Ink, staves: List<Staff>, measures: MutableList<Measure>) {
        for ((si, s) in staves.withIndex()) {
            val sp = s.space
            val headW = sp * 1.18f
            // The staff's notes and rests in order, with where each is (measure, event).
            val seq = ArrayList<Triple<Int, Int, Event>>()
            for ((mi, m) in measures.withIndex()) if (m.staff == si && m.bars == 1) m.events.forEachIndexed { ei, e -> seq += Triple(mi, ei, e) }
            for (k in 0 until seq.size - 1) {
                val a = seq[k].third as? Note ?: continue
                val b = seq[k + 1].third as? Note ?: continue
                val shared = a.steps.filter { it in b.steps }
                if (shared.isEmpty()) continue
                if (shared.any { st -> tieBetween(ink, s, a.x + headW, b.x, st, sp) }) {
                    val (mi, ei, _) = seq[k]
                    val m = measures[mi]
                    measures[mi] = m.copy(events = m.events.mapIndexed { i, e -> if (i == ei && e is Note) e.copy(tie = true) else e })
                }
            }
        }
    }

    /**
     * Whether a tie runs from [x1] (the first head's right edge) to [x2] (the second's left) at
     * [step] on [s]: a thin curve over the heads or under them, followed from its middle out to
     * both ends - a long one arching higher, past an accent or a dot by its ends - that comes back
     * towards the heads at both ends (it bows away from them) and does not run on past the first
     * (a slur over more notes does).
     */
    private fun tieBetween(ink: Ink, s: Staff, x1: Float, x2: Float, step: Int, sp: Float): Boolean {
        val from = (x1 + sp * 0.15f).roundToInt(); val to = (x2 - sp * 0.15f).roundToInt()
        val span = to - from
        if (span < sp * 0.5f || span > sp * 16) return false
        // How far from the heads the curve may arch: higher the longer it is.
        val reach = min(sp * 3.4f, sp * 1.1f + span * 0.14f)
        val thin = max(2f, sp * 0.45f)
        for (side in listOf(1, -1)) {
            // Every thin stroke across each column in the band beside the heads' line: its middle, as a distance from the line.
            fun runs(x: Int): List<Float> {
                val y = s.y(step, x)
                val out = ArrayList<Float>()
                var yy = (y + side * sp * 0.25f).roundToInt()
                val end = (y + side * reach).roundToInt()
                while (if (side > 0) yy <= end else yy >= end) {
                    if (ink[x, yy]) {
                        var a = yy; var b = yy
                        while (ink[x, a - 1] && b - a < sp) a--
                        while (ink[x, b + 1] && b - a < sp) b++
                        if (b - a + 1 <= thin) out += abs((a + b) / 2f - y)
                        yy = if (side > 0) b + 1 else a - 1
                        continue
                    }
                    yy += side
                }
                return out
            }
            val mid = (from + to) / 2
            val starts = (-2..2).flatMap { d -> runs(mid + d).map { it to mid + d } }
            for ((d0, x0) in starts) {
                // Followed out both ways, a column at a time, never leaping.
                val path = HashMap<Int, Float>()
                path[x0] = d0
                var miss = 0
                for (dir in listOf(-1, 1)) {
                    var prev = d0; var gap = 0
                    var x = x0 + dir
                    while (x in from..to) {
                        val next = runs(x).minByOrNull { abs(it - prev) }?.takeIf { abs(it - prev) <= sp * 0.4f + gap * sp * 0.08f }
                        if (next != null) { path[x] = next; prev = next; gap = 0 } else { gap++; miss++; if (gap > max(4, (sp * 0.6f).toInt())) break }
                        x += dir
                    }
                }
                // Most of its middle followed, and it reaches well towards both heads.
                val inner = (from + span * 0.15f).toInt()..(to - span * 0.15f).toInt()
                if (inner.count { it in path } < (inner.last - inner.first + 1) * 0.8f) continue
                val left = path.keys.min(); val right = path.keys.max()
                if (left > from + span * 0.25f || right < to - span * 0.25f) continue
                // Bowing away: its ends nearer the heads than its middle.
                val ends = (path[left]!! + path[right]!!) / 2
                if (path.values.max() - ends < sp * 0.12f) continue
                // Not running on before the first head (a slur over more notes does).
                val y0 = s.y(step, from) + side * path[left]!!
                val before = (x1 - sp * 1.18f - sp * 0.4f).roundToInt()
                if ((-1..1).any { d -> ink[before, (y0 + d).roundToInt()] } && (-1..1).any { d -> ink[before - 2, (y0 + d).roundToInt()] }) continue
                return true
            }
        }
        return false
    }

    /**
     * The marks each bar keeps as printed, read ([MarkReader]): dynamics (their letters set together
     * into one - "m" "f" is mf), accents, staccatos, tenutos, marcatos and fermatas (to the note they
     * stand by), slurs (curves over more than one note, not ties) and hairpins (louder towards the
     * open end) - each marked seen, so the clean view leaves the print's own in place.
     */
    private fun readMarks(measures: MutableList<Measure>) {
        val g = pageGrey ?: return
        val ink = pageInk ?: return
        class Found(val mi: Int, val label: String, val p: Float, val box: IntArray) {
            val sure get() = p >= (MARK_SURE[label] ?: 2f)
        }
        // Every shape kept, with what it is taken for (however unsure), in the bar its middle is in.
        val all = ArrayList<Found>()
        val done = HashSet<String>()
        val shapes = ArrayList<Triple<Int, Staff, IntArray>>()
        for ((mi, m) in measures.withIndex()) {
            if (m.bars > 1) continue
            val s = staves.getOrNull(m.staff) ?: continue
            for (o in m.kept) {
                val bx = MarkReader.box(o)
                if (!done.add(bx.joinToString(","))) continue
                if (bx[2] - bx[0] > s.space * 20 || bx[3] - bx[1] > s.space * 6) continue
                if (bx[2] - bx[0] + 1 < s.space * 0.22f && bx[3] - bx[1] + 1 < s.space * 0.22f) continue
                shapes += Triple(mi, s, bx)
            }
        }
        // Each shape looked at on its own (several at once where there are the cores).
        val seen = Workers.map(shapes) { (_, s, bx) -> MarkReader.read(g, ink.width, ink.height, bx, s.space, s.lineY(0, ((bx[0] + bx[2]) / 2).coerceIn(s.left, s.right))) }
        for ((k, shape) in shapes.withIndex()) {
            val (mi, _, bx) = shape
            val (label, p) = seen[k] ?: continue
            val m = measures[mi]
            val cx = (bx[0] + bx[2]) / 2
            val home = measures.indices.firstOrNull { k2 -> measures[k2].staff == m.staff && measures[k2].page == m.page && cx >= measures[k2].box.left && cx < measures[k2].box.right } ?: mi
            all += Found(home, label, p, bx)
        }
        if (System.getProperty("inksheets.omr.shapes") != null) for (f in all) println("  SHAPE staff ${measures[f.mi].staff} bar ${measures[f.mi].number} ${f.box.toList()} ${f.label} %.2f".format(f.p))
        // Words first, by how they lie: small shapes off the staff, side by side along one line,
        // three or more - whatever each letter alone was taken for (poco's "p" is no piano).
        fun small(f: Found): Boolean {
            val s = staves[measures[f.mi].staff]; val sp = s.space
            val x = ((f.box[0] + f.box[2]) / 2).coerceIn(s.left, s.right)
            val off = f.box[3] < s.lineY(0, x) - sp * 0.3f || f.box[1] > s.lineY(4, x) + sp * 0.3f
            // (A scan's italic runs its letters together: a whole word one shape, a few spaces long.)
            // (A tall letter - an l, a t - is two spaces high.)
            return off && f.box[3] - f.box[1] <= sp * 2.4f && f.box[2] - f.box[0] <= sp * 9f && f.label != "curve"
        }
        // How much of a box is ink: a word's letters fill a good part of theirs, a hairpin's two thin strokes little of its.
        fun density(b: IntArray): Float {
            var n = 0; var all = 0
            for (y in b[1]..b[3]) for (x in b[0]..b[2]) { all++; if (ink[x, y]) n++ }
            return if (all == 0) 0f else n.toFloat() / all
        }
        val inWord = HashSet<Found>()
        val wordBoxes = ArrayList<Pair<Int, IntArray>>()   // staff, box
        val add = HashMap<Int, MutableList<Direction>>()
        val cands = all.filter { small(it) }.sortedWith(compareBy({ measures[it.mi].page }, { measures[it.mi].staff }, { it.box[0] }))
        var w = 0
        while (w < cands.size) {
            val first = cands[w]
            val sp = measures[first.mi].space
            val box = first.box.copyOf()
            val group = arrayListOf(first)
            var v = w + 1
            while (v < cands.size) {
                val c = cands[v]
                if (measures[c.mi].staff != measures[first.mi].staff || measures[c.mi].page != measures[first.mi].page) break
                if (c.box[0] - box[2] > sp * 1.6f) break
                // On one line: their bottoms near (a letter's descender aside) or their middles.
                if (abs(c.box[3] - box[3]) < sp * 0.9f || abs((c.box[1] + c.box[3]) - (box[1] + box[3])) < sp * 1.2f) {
                    group += c
                    box[0] = min(box[0], c.box[0]); box[1] = min(box[1], c.box[1]); box[2] = max(box[2], c.box[2]); box[3] = max(box[3], c.box[3])
                }
                v++
            }
            val dynamicOnly = group.all { it.label.startsWith("dyn_") }
            // Three letters or more, or letters run together as one wide shape (a scan's italic) - and inked as letters are.
            val isWord = !dynamicOnly && box[2] - box[0] >= sp * 2f && box[3] - box[1] <= sp * 2.6f && density(box) >= 0.12f
            if (isWord) {
                WordReader.candidates.get()?.invoke(box)
                inWord += group
                wordBoxes += measures[first.mi].staff to box
                // A word that changes the tempo: played by.
                if (WordReader.available) WordReader.read(g, ink.width, ink.height, box)?.let { (label, p) ->
                    if (System.getProperty("inksheets.omr.words") != null) println("  WORD staff ${measures[first.mi].staff} bar ${measures[first.mi].number} box ${box.toList()} parts ${group.size} ${group.map { it.label }}: $label %.3f".format(p))
                    val text = WordReader.TEXT[label]
                    if (text != null && p >= WORD_SURE) {
                        val m = measures[first.mi]
                        val k = measures.indices.firstOrNull { measures[it].staff == m.staff && measures[it].page == m.page && box[0] + sp >= measures[it].box.left && box[0] + sp < measures[it].box.right } ?: first.mi
                        add.getOrPut(k) { ArrayList() } += Direction("text", box[0].toFloat(), box[2].toFloat(), text, above = box[3] < m.box.top, seen = true)
                    }
                }
            }
            w = if (isWord) v else w + 1
        }
        val found = all.filter { it !in inWord && it.sure && it.label != "other" && it.label != "word" && it.label != "digit" }
        // Dynamics: letters side by side, one word; letters run on into other letters are a word, not a dynamic.
        val letters = found.filter { it.label.startsWith("dyn_") }.sortedWith(compareBy({ measures[it.mi].staff }, { it.box[0] }))
        var i = 0
        while (i < letters.size) {
            var j = i
            val sp = measures[letters[i].mi].space
            while (j + 1 < letters.size && measures[letters[j + 1].mi].staff == measures[letters[i].mi].staff &&
                letters[j + 1].box[0] - letters[j].box[2] < sp * 0.7f && abs((letters[j + 1].box[1] + letters[j + 1].box[3]) - (letters[i].box[1] + letters[i].box[3])) < sp * 1.6f) j++
            val word = (i..j).joinToString("") { letters[it].label.removePrefix("dyn_") }
            val x0 = letters[i].box[0]; val x1 = letters[j].box[2]
            val touchesOther = all.any { o -> o !in letters.subList(i, j + 1) && small(o) && measures[o.mi].staff == measures[letters[i].mi].staff &&
                o.box[2] >= x0 - sp * 0.6f && o.box[0] <= x1 + sp * 0.6f && abs((o.box[1] + o.box[3]) - (letters[i].box[1] + letters[i].box[3])) < sp * 1.4f }
            if (!touchesOther && (word in Performance.LEVELS || word in setOf("sf", "sfz", "sffz", "fz", "rfz", "rf", "sfp", "fp"))) {
                val m = measures[letters[i].mi]
                add.getOrPut(letters[i].mi) { ArrayList() } += Direction("dynamic", x0.toFloat(), x1.toFloat(), word, above = letters[i].box[3] < m.box.top, seen = true)
            }
            i = j + 1
        }
        // Slurs and hairpins: in each bar of the staff they run across (a hairpin over a word is the word's letters).
        for (f in found) {
            if (f.label != "curve" && f.label != "hairpin") continue
            val m0 = measures[f.mi]
            if (f.label == "hairpin" && (density(f.box) > 0.14f || wordBoxes.any { (st, b) -> st == m0.staff && b[0] <= f.box[2] && b[2] >= f.box[0] && b[1] <= f.box[3] + m0.space && b[3] >= f.box[1] - m0.space })) continue
            val kind = if (f.label == "curve") "slur" else {
                // Which end is open: the side where its two strokes are further apart.
                val o = m0.kept.firstOrNull { MarkReader.box(it).contentEquals(f.box) } ?: continue
                val wd = f.box[2] - f.box[0]
                fun spread(lo: Int, hi: Int): Int { var a = Int.MAX_VALUE; var b = Int.MIN_VALUE
                    for (k in 0 until o.size - 1 step 2) if (o[k] in lo..hi) { a = min(a, o[k + 1]); b = max(b, o[k + 1]) }; return if (b >= a) b - a else 0 }
                if (spread(f.box[2] - wd / 5, f.box[2]) > spread(f.box[0], f.box[0] + wd / 5)) "cresc" else "dim"
            }
            val d = Direction(kind, f.box[0].toFloat(), f.box[2].toFloat(), above = f.box[3] < m0.box.top, seen = true)
            for (k in measures.indices) {
                val m = measures[k]
                if (m.staff == m0.staff && m.page == m0.page && m.box.right > f.box[0] && m.box.left < f.box[2]) add.getOrPut(k) { ArrayList() } += d
            }
        }
        // Breath marks: the mark reader knows none - a comma over the staff, between notes, taken for
        // something else. Told by its shape: taller than wide, its round head on top and a thin tail
        // under it, standing clear over the staff and not over a note (an accent or a dot is over one).
        val breathsSeen = ArrayList<Found>()
        for (f in all) {
            if (f in inWord || f.label.startsWith("dyn_") && f.sure || f.label == "digit" || f.label == "word") continue
            val m = measures[f.mi]
            val sp = m.space
            val s = staves.getOrNull(m.staff) ?: continue
            val w = f.box[2] - f.box[0] + 1; val h = f.box[3] - f.box[1] + 1
            if (w < sp * 0.2f || w > sp * 0.9f || h < sp * 0.5f || h > sp * 1.6f || h < w * 1.2f) continue
            val cx = (f.box[0] + f.box[2]) / 2
            if (System.getProperty("inksheets.omr.breathwhy") != null) println("BREATHWHY bar ${m.number} ${f.label} box ${f.box.toList()} w ${"%.2f".format(w / sp)} h ${"%.2f".format(h / sp)} top line ${s.lineY(0, cx).toInt()}")
            // Over the staff: its head well over the top line, its tail down to it at most.
            if (f.box[1] > s.lineY(0, cx) - sp * 0.5f || f.box[3] > s.lineY(0, cx) + sp * 0.2f) continue
            if (m.events.any { e -> e is Note && abs(e.x + sp * 0.6f - cx) < sp * 0.9f }) continue
            // Alone: a shape beside another at its height is a number's figure or a word's letter.
            if (all.any { o -> o !== f && measures[o.mi].staff == m.staff && o.box[0] - f.box[2] < sp * 0.7f && f.box[0] - o.box[2] < sp * 0.7f &&
                    o.box[1] < f.box[3] && o.box[3] > f.box[1] }) continue
            // Not where a bar's number stands: over its first beat, just after the barline.
            if (cx - m.box.left < sp * 1.5f) continue
            // Its ink: the upper half much heavier than the lower (the comma's head over its tail).
            val midY = (f.box[1] + f.box[3]) / 2
            var top = 0; var bottom = 0
            for (y in f.box[1]..f.box[3]) for (x in f.box[0]..f.box[2]) if (ink[x, y]) { if (y <= midY) top++ else bottom++ }
            if (top < bottom * 1.6f || bottom == 0) continue
            // A solid round head over a thin tail: the upper half mostly ink, the lower half's ink in a
            // narrow column (a note's fragment, a dot beside a note, a slur's end are none of this).
            val upperArea = (midY - f.box[1] + 1) * w
            if (top < upperArea * 0.5f) continue
            val tailWidth = (midY + 1..f.box[3]).maxOf { y -> (f.box[0]..f.box[2]).count { x -> ink[x, y] } }
            if (tailWidth > w * 0.6f) continue
            // Not beside a note high over the staff (its head's side, its ledger's end).
            if (m.events.any { e -> e is Note && e.steps.min() < 0 && abs(e.x + sp * 0.6f - cx) < sp * 1.8f }) continue
            breathsSeen += f
            add.getOrPut(f.mi) { ArrayList() } += Direction("breath", cx.toFloat(), above = true, seen = true)
        }
        for ((k, ds) in add) measures[k] = measures[k].copy(directions = measures[k].directions + ds)
        // Articulations: to the note standing over or under them (not a breath mark's comma).
        for (f in found) {
            if (f in breathsSeen) continue
            val name = when (f.label) { "accent", "staccato", "tenuto", "marcato", "fermata" -> f.label; else -> continue }
            val m = measures[f.mi]
            val sp = m.space
            val cx = (f.box[0] + f.box[2]) / 2f
            val reach = if (name == "fermata") sp * 1.6f else sp * 1.0f
            val target = m.events.withIndex().filter { (_, e) -> e is Note && abs(e.x + sp * 0.6f - cx) < reach }.minByOrNull { (_, e) -> abs(e.x + sp * 0.6f - cx) } ?: continue
            val n = target.value as Note
            if (name in n.articulations) continue
            measures[f.mi] = m.copy(events = m.events.mapIndexed { idx, e -> if (idx == target.index) n.copy(articulations = n.articulations + name, marksSeen = true) else e })
        }
    }

    /**
     * How sure the word reader must be that a word changes the tempo (a wrong one would slow the
     * music). Off for now (over 1): on real scans with pencil and handwriting it takes scribbles
     * and hairpins for tempo words, sure of them - not yet to be played by. Words are still found,
     * so their letters are not taken for dynamics or hairpins. -Dinksheets.omr.wordsure=0.95 to try.
     */
    var WORD_SURE = System.getProperty("inksheets.omr.wordsure")?.toFloatOrNull() ?: 1.01f

    val MARK_SURE = mapOf("curve" to 0.97f, "tenuto" to 0.97f, "accent" to 0.97f, "marcato" to 0.97f, "staccato" to 0.99f,
        "dyn_p" to 0.97f, "dyn_f" to 0.99f, "dyn_m" to 0.99f, "dyn_s" to 0.99f, "dyn_z" to 0.99f, "dyn_r" to 0.99f, "hairpin" to 0.99f)

    /** An articulation claimed by one chord only (the nearest). */
    private val claimedMarks = HashSet<Printed.Symbol>()
    private fun unclaimed(m: Printed.Symbol): Set<Printed.Symbol> = if (claimedMarks.add(m)) setOf(m) else emptySet()

    /**
     * What the PDF prints over or under [s] between [from] and [to] besides notes: dynamics (letters
     * set together read as one - "m" "f" is "mf"), hairpins (two strokes meeting at a point: louder
     * towards the open end), slurs (curves that are not ties; one running on past the bar is in
     * each bar it crosses).
     */
    private fun printedDirections(s: Staff, from: Int, to: Int): List<Direction> {
        val p = printed ?: return emptyList()
        val sp = s.space
        val mid = (s.top + s.bottom) / 2f
        fun near(y: Float) = abs(y - mid) < sp * 9 && staves.none { o -> o !== s && abs((o.top + o.bottom) / 2f - y) < abs(mid - y) }
        val out = ArrayList<Direction>()
        // Dynamics: a run of letters close together is one marking.
        val dyn = p.symbols.filter { it.kind == Printed.Kind.DYNAMIC && it.x >= from && it.x < to && near(it.y) }.sortedBy { it.x }
        var i = 0
        while (i < dyn.size) {
            var text = dyn[i].name; var j = i
            while (j + 1 < dyn.size && dyn[j + 1].x - (dyn[j].x + dyn[j].width) < sp * 0.4f && abs(dyn[j + 1].y - dyn[i].y) < sp * 0.5f) { j++; text += dyn[j].name }
            out += Direction("dynamic", dyn[i].x, dyn[j].x + dyn[j].width, text, above = dyn[i].y < s.top)
            i = j + 1
        }
        // Hairpins: two strokes sharing an end (the point), their other ends apart.
        val strokes = p.lines.filter { it.x1 - it.x0 > sp * 1.5f && near((it.y0 + it.y1) / 2) && it.x1 > from && it.x0 < to && abs(it.y1 - it.y0) < (it.x1 - it.x0) * 0.4f }
        val used = HashSet<Printed.Line>()
        for (a in strokes) for (b in strokes) {
            if (a === b || a in used || b in used || a.y0 + a.y1 > b.y0 + b.y1) continue
            val cresc = abs(a.x0 - b.x0) < sp * 0.4f && abs(a.y0 - b.y0) < sp * 0.4f && abs(a.y1 - b.y1) in sp * 0.4f..sp * 3f
            val dim = abs(a.x1 - b.x1) < sp * 0.4f && abs(a.y1 - b.y1) < sp * 0.4f && abs(a.y0 - b.y0) in sp * 0.4f..sp * 3f
            if (!cresc && !dim) continue
            used += a; used += b
            // Its whole length, not just this bar's piece: each bar it crosses draws its slice of the one wedge.
            out += Direction(if (cresc) "cresc" else "dim", a.x0, a.x1, above = (a.y0 + a.y1) / 2 < s.top)
        }
        // Breath marks: a breath taken there (the note before ends early).
        for (b in p.symbols) if (b.kind == Printed.Kind.BREATH && b.x >= from && b.x < to && near(b.y)) out += Direction("breath", b.x, above = true)
        // Words: how to play it ("rit.", "a tempo", "cresc.", "legato"), at the word's place - a
        // word over the bar's start may stand just before it.
        for (w in p.symbols) if (w.kind == Printed.Kind.WORD && w.x >= from - sp && w.x < to - sp && near(w.y)) out += Direction("text", w.x, w.x + w.width, w.name, above = w.y < s.top)
        // Slurs: every curve that is not a note's tie, in each bar it crosses.
        for (a in p.arcs) {
            if (a.x1 < from || a.x0 >= to || !near((a.y0 + a.y1) / 2)) continue
            if (abs(a.y1 - a.y0) < sp * 0.8f && a.x1 - a.x0 < sp * 6 && tieLike(a)) continue
            fun step(y: Float, x: Float) = ((y - s.lineY(0, x.toInt().coerceIn(s.left, s.right))) / (sp / 2)).roundToInt()
            out += Direction("slur", a.x0, a.x1, above = a.bulge < 0, step = step(a.y0, a.x0), step2 = step(a.y1, a.x1))
        }
        return out
    }

    /** A curve from beside a head to the next head at its height: a tie, not a slur. */
    private fun tieLike(a: Printed.Arc): Boolean {
        val p = printed ?: return false
        return p.heads.any { h -> a.x0 > h.x + h.width * 0.3f && a.x0 - (h.x + h.width) < p.heads.first().width * 1.5f && abs(a.y0 - h.y) < h.width } &&
            p.heads.any { o -> o.x >= a.x1 - o.width * 1.5f && o.x - a.x1 < o.width * 1.5f && abs(a.y1 - o.y) < o.width }
    }

    /** The printed rests in [from]..[to] on [s] (the staff they are nearest), with their dots. */
    private fun printedRests(s: Staff, from: Int, to: Int): List<Pair<Rest, Float>> {
        val p = printed ?: return emptyList()
        val sp = s.space
        val mid = (s.top + s.bottom) / 2f
        return p.symbols.mapNotNull { r ->
            var base = when (r.kind) { Printed.Kind.REST_1 -> 1; Printed.Kind.REST_2 -> 2; Printed.Kind.REST_4 -> 4; Printed.Kind.REST_8 -> 8; Printed.Kind.REST_16 -> 16; Printed.Kind.REST_32 -> 32; else -> return@mapNotNull null }
            if (r.x < from - 2 || r.x >= to || abs(r.y - mid) > sp * 5) return@mapNotNull null
            // The trained reader's rest looked at again on its own: a letter, a dynamic, a breath mark
            // taken for one is turned down; one taken for another rest put right.
            if (p.learned) secondLook(r, s)?.let { label ->
                base = when (label) { "other" -> return@mapNotNull null; "block" -> if (base <= 2) base else return@mapNotNull null
                    "rest4" -> 4; "rest8" -> 8; "rest16" -> if (base >= 16) base else 16; else -> base }
            }
            if (staves.any { o -> o !== s && abs((o.top + o.bottom) / 2f - r.y) < abs(mid - r.y) && r.x >= o.left && r.x <= o.right }) return@mapNotNull null
            val dotted = p.symbols.any { d -> d.kind == Printed.Kind.DOT && d.x > r.x + r.width * 0.8f && d.x < r.x + r.width + sp * 1.2f && abs(d.y - r.y) < sp * 1.5f }
            Rest(Duration(base, if (dotted) 1 else 0), r.x, ((r.y - s.lineY(0, r.x.roundToInt())) / (sp / 2)).roundToInt()) to 1f
        }
    }

    /**
     * What is printed in and round bar [m] (on [clean], staff [s]) that its redrawing will not
     * draw again, each shape whole as its outline (see [Measure.kept]). A shape is redrawn - and so
     * not kept - where it holds a note's head or a rest the bar was read with, is a note's
     * accidental or dot, or is the clef, key or time at the bar's start where those are redrawn;
     * and, from a PDF that states them, a note's articulations and ties and the bar's dynamics,
     * hairpins and slurs. Everything else - numbers, words, boxes, marks not read - is kept.
     */
    /**
     * The parts of bar [m]'s print (on staff [s]) its redraw draws again, worked out once: boxes for
     * its heads, stems (where a stem's way is not seen, both ways) and flags, and bands along its
     * beams - a page point is covered if it is in any of them (see [Coverage.covers]).
     */
    private class Coverage(val boxes: List<FloatArray>, val bands: List<FloatArray>, val stems: List<FloatArray> = emptyList()) {
        /**
         * A box is l, t, r, b; a band runs from x0 to x1 between y0a..y0b at x0 and y1a..y1b at x1;
         * a stem column (l, t, r, b) covers only what stands tall in it ([upright]: a stem, either way
         * from its head - not an accent or a dot over or under it).
         */
        fun covers(x: Float, y: Float, upright: Boolean = false,
                   /** A slur's or tie's stroke: only a beam's band takes it (a head's or stem's box does not). */
                   stroke: Boolean = false): Boolean {
            if (!stroke) for (bx in boxes) if (x >= bx[0] && x <= bx[2] && y >= bx[1] && y <= bx[3]) return true
            if (upright && !stroke) for (bx in stems) if (x >= bx[0] && x <= bx[2] && y >= bx[1] && y <= bx[3]) return true
            for (b in bands) if (x >= b[0] && x <= b[1]) {
                val f = if (b[1] - b[0] > 1f) (x - b[0]) / (b[1] - b[0]) else 0f
                if (y >= b[2] + (b[4] - b[2]) * f && y <= b[3] + (b[5] - b[3]) * f) return true
            }
            return false
        }
    }

    private fun coverage(m: Measure, s: Staff): Coverage {
        val sp = s.space
        val boxes = ArrayList<FloatArray>(); val bands = ArrayList<FloatArray>(); val stems = ArrayList<FloatArray>()
        val notes = m.events.filterIsInstance<Note>()
        for ((i, n) in notes.withIndex()) {
            val headW = sp * (if (n.duration.base == 1) 1.6f else 1.18f)
            val ys = n.steps.map { s.y(it, n.x.roundToInt()) }
            // The heads.
            for (y in ys) boxes += floatArrayOf(n.x - sp * 0.2f, y - sp * 0.65f, n.x + headW + sp * 0.2f, y + sp * 0.65f)
            if (n.duration.base < 2) continue
            val left = n.x - sp * 0.3f; val right = n.x + headW + sp * 0.3f
            // Whichever way the print's stem went (it may not be the way redrawn): what stands tall
            // in the head's column, up or down a stem's length.
            stems += floatArrayOf(n.x - sp * 0.55f, ys.min() - sp * 4.1f, n.x + headW + sp * 0.55f, ys.max() + sp * 4.1f)
            val known = n.stemUp
            if (known == null) {
                // The stem's way not seen: anywhere a stem of it could run, up or down.
                boxes += floatArrayOf(left, ys.min() - sp * 4.1f, right, ys.max() + sp * 4.1f)
                continue
            }
            val up = known
            val top = s.lineY(0, n.x.roundToInt()); val space = (s.lineY(4, n.x.roundToInt()) - top) / 4f
            val tip = n.stemTip?.let { top + it * space } ?: (if (up) ys.min() - sp * 3.5f else ys.max() + sp * 3.5f)
            // As far as a stem usually reaches, where the one seen stopped short (a scan's faint stem
            // broken where it crosses a line): what is printed past the break is stem too.
            val reach = if (up) min(tip, ys.min() - sp * 3.5f) else max(tip, ys.max() + sp * 3.5f)
            val y0 = min(reach, if (up) ys.max() else ys.min()); val y1 = max(reach, if (up) ys.max() else ys.min())
            // The stem: across the head's column (engravers' heads differ in width), head to past its end.
            boxes += floatArrayOf(left, y0 - sp * 0.6f, right, y1 + sp * 0.6f)
            val sx = if (up) n.x + headW - sp * 0.1f else n.x + sp * 0.1f
            // A flag, from the stem's end.
            if (n.duration.base >= 8 && n.beam == 0)
                boxes += if (up) floatArrayOf(sx - sp * 0.2f, tip - sp * 0.2f, sx + sp * 1.4f, tip + sp * 3.2f) else floatArrayOf(sx - sp * 0.2f, tip - sp * 3.2f, sx + sp * 1.4f, tip + sp * 0.2f)
            // The beam to the next note of its group: between the stems, along the line of their ends,
            // up to three beams stacked back towards the heads.
            val next = notes.getOrNull(i + 1)
            if (n.beam > 0 && next != null && next.beam == n.beam && next.stemTip != null && n.stemTip != null) {
                val nUp = next.stemUp ?: up
                val nx = if (nUp) next.x + sp * 1.18f - sp * 0.1f else next.x + sp * 0.1f
                val nTop = s.lineY(0, next.x.roundToInt()); val nSpace = (s.lineY(4, next.x.roundToInt()) - nTop) / 4f
                val nTip = nTop + next.stemTip * nSpace
                val depth = sp * 1.9f
                bands += if (up) floatArrayOf(sx - sp * 0.3f, nx + sp * 0.3f, tip - sp * 0.3f, tip + depth, nTip - sp * 0.3f, nTip + depth)
                    else floatArrayOf(sx - sp * 0.3f, nx + sp * 0.3f, tip - depth, tip + sp * 0.3f, nTip - depth, nTip + sp * 0.3f)
            }
        }
        // Rests, accidentals and dots are shapes of their own (see keptIn): nothing to cut from another.
        return Coverage(boxes, bands, stems)
    }

    /**
     * Whether staff [s] between [from] and [to] holds a bar-repeat sign: one slash (or two side by
     * side, for two bars - across the barline between them) a couple of spaces tall, rising to the
     * right across the staff's middle, its dots beside it.
     */
    private fun barRepeat(clean: Ink, s: Staff, from: Int, to: Int, lineStart: Boolean = false): Int {
        val sp = s.space
        val mid = (from + to) / 2
        val top = s.lineY(0, mid).roundToInt(); val bottom = s.lineY(4, mid).roundToInt()
        // In the bar's middle (one bar's sign), or across either barline (a two-bar sign, half in each)
        // - not back past a line's start into its clef, key and time (a repeat's dots there are none).
        val x0 = if (lineStart) from else max(0, from - (sp * 3f).toInt()); val x1 = to + (sp * 3f).toInt()
        val region = Outline.Region(x0, max(0, top - sp.toInt()), max(1, x1 - x0), bottom - top + 2 * sp.toInt())
        for (y in top..bottom) for (x in x0 until x1) {
            if (!clean[x, y] || !region.inside(x, y) || region.seen[region.index(x, y)]) continue
            val all = Outline.component(clean, x, y, region, 20_000)
            // A barline it straddles set aside: columns of it standing the staff's height.
            val tallCols = HashSet<Int>()
            run {
                val byCol = HashMap<Int, Int>()
                for (i in all.indices step 2) byCol.merge(all[i], 1, Int::plus)
                for ((c, n) in byCol) if (n >= sp * 3.2f) tallCols += c
            }
            val px = ArrayList<Int>()
            for (i in all.indices step 2) if (all[i] !in tallCols && all[i] - 1 !in tallCols && all[i] + 1 !in tallCols) { px += all[i]; px += all[i + 1] }
            if (px.size < 2 * (sp * sp * 0.3f).toInt()) continue
            var l = Int.MAX_VALUE; var r = Int.MIN_VALUE; var t = Int.MAX_VALUE; var b = Int.MIN_VALUE
            for (i in px.indices step 2) { l = min(l, px[i]); r = max(r, px[i]); t = min(t, px[i + 1]); b = max(b, px[i + 1]) }
            val w = r - l + 1; val h = b - t + 1
            val cx = (l + r) / 2
            // Its middle in this bar's middle, or near one of its barlines.
            val placed = cx in (mid - sp * 2.5f).toInt()..(mid + sp * 2.5f).toInt() || abs(cx - from) <= sp * 2f || abs(cx - to) <= sp * 2f
            if (traceRests) println("    barRepeat $from..$to shape $l..$r x $t..$b (w ${"%.1f".format(w / sp)} h ${"%.1f".format(h / sp)} sp) placed $placed")
            if (!placed || w < sp * 0.9f || w > sp * 5f || h < sp * 1.4f || h > sp * 4.2f) continue
            // Slashes rising to the right: its ink across the slant falls in one band or two (one
            // slash, or two side by side), a fifth of a space each, with its dots outside them.
            val len = kotlin.math.sqrt((w * w + h * h).toFloat())
            val bins = IntArray(64)
            for (i in px.indices step 2) {
                val d = ((px[i] - l) * h.toFloat() + (px[i + 1] - b) * w.toFloat()) / len   // across the slant, from the corner's line
                val k = ((d / (sp * 0.2f)) + 32).toInt().coerceIn(0, 63)
                bins[k]++
            }
            val n = px.size / 2
            val peaks = bins.indices.sortedByDescending { bins[it] }.take(2)
            fun around(k: Int) = (k - 1..k + 1).sumOf { j -> bins.getOrElse(j) { 0 } }
            val inBands = if (abs(peaks[0] - peaks[1]) <= 2) around(peaks[0]) else around(peaks[0]) + around(peaks[1])
            if (traceRests) println("    barRepeat bands ${"%.2f".format(inBands.toFloat() / n)}")
            // (A two-bar sign - two slashes, across a barline, the line's own ink and its dots in among
            // them - held to a little less: the bar the other side is asked to be one too; see read.)
            // (By whichever it is nearest: the bar's middle, or one of its barlines - a narrow bar's own
            // sign is near both its barlines too.)
            val across = when {
                abs(cx - mid) <= abs(cx - from) && abs(cx - mid) <= abs(cx - to) -> 1
                abs(cx - from) <= abs(cx - to) -> 2
                else -> 3
            }
            if (inBands >= n * (if (across != 1 && w >= sp * 2.2f) 0.5f else 0.6f)) return across
        }
        return 0
    }

    /**
     * How far a thin stroke through ([x], [y]) runs left and right, as far as [cap]: following its ink
     * a pixel up or down at each step, as a curve rises and falls.
     */
    private fun run(ink: Ink, x: Int, y: Int, cap: Float): Int {
        var n = 0
        for (dir in listOf(-1, 1)) {
            var cx = x; var cy = y
            while (n < cap * 2) {
                val nx = cx + dir
                cy = listOf(cy, cy - 1, cy + 1).firstOrNull { ink[nx, it] } ?: break
                cx = nx; n++
            }
        }
        return n
    }

    private fun keptIn(clean: Ink, s: Staff, m: Measure, staffBars: List<Measure>): Pair<List<IntArray>, List<Int>> {
        val sp = s.space
        val mid = (m.box.left + m.box.right) / 2
        val x0 = m.box.left; val x1 = m.box.right
        val y0 = (s.lineY(0, mid) - sp * 4.5f).roundToInt(); val y1 = (s.lineY(4, mid) + sp * 4.5f).roundToInt()
        val headW = sp * 1.18f
        val stated = this.printed?.learned == false
        // Shapes followed within the bar and a good way either side (a word, a hairpin running on).
        val rx0 = max(0, (x0 - sp * 8f).toInt()); val rx1 = min(clean.width - 1, (x1 + sp * 8f).toInt())
        val ry0 = max(0, (y0 - sp * 2f).toInt()); val ry1 = min(clean.height - 1, (y1 + sp * 2f).toInt())
        val region = Outline.Region(rx0, ry0, rx1 - rx0 + 1, ry1 - ry0 + 1)
        val coverages = HashMap<Measure, Coverage>()
        val out = ArrayList<IntArray>()
        val shades = ArrayList<Int>()
        val g = pageGrey
        // Each shape kept once, by the bar its middle is in (a bar cleaned draws its neighbours' that
        // reach into it too - see ScoreTools); one no single bar holds (over a multi-bar rest) by this one.
        fun owns(shape: IntArray): Boolean {
            var lo = Int.MAX_VALUE; var hi = Int.MIN_VALUE
            for (i in shape.indices step 2) { lo = min(lo, shape[i]); hi = max(hi, shape[i]) }
            val cx = (lo + hi) / 2
            if (cx >= m.box.left && cx < m.box.right) return true
            return staffBars.none { o -> o !== m && cx >= o.box.left && cx < o.box.right }
        }
        // How dark a shape is printed: the darkest tenth of its pixels (an edge's softness aside).
        fun shadeOf(px: IntArray): Int = if (g == null) 0 else {
            val levels = (px.indices step 2).map { i -> g.getOrElse(px[i + 1] * clean.width + px[i]) { 0 } }.sorted()
            levels[levels.size / 10]
        }
        for (y in y0..y1) for (x in x0..x1) {
            if (!clean[x, y] || !region.inside(x, y) || region.seen[region.index(x, y)]) continue
            val px = Outline.component(clean, x, y, region, 60_000)
            if (px.isEmpty()) continue
            var l = Int.MAX_VALUE; var r = Int.MIN_VALUE; var t = Int.MAX_VALUE; var b = Int.MIN_VALUE
            for (i in px.indices step 2) { l = min(l, px[i]); r = max(r, px[i]); t = min(t, px[i + 1]); b = max(b, px[i + 1]) }
            val w = r - l + 1; val h = b - t + 1
            // Holds a head's middle: inside the shape's bounds and ink near it (a hollow head's middle is paper, its ring a little way out).
            fun holds(cx: Float, cy: Float) = cx >= l - 1 && cx <= r + 1 && cy >= t - 1 && cy <= b + 1 &&
                (0 until px.size step 2).any { i -> abs(px[i] - cx) <= sp * 0.75f && abs(px[i + 1] - cy) <= sp * 0.5f }
            val top = s.lineY(0, (l + r) / 2); val bottom = s.lineY(4, (l + r) / 2)
            // [heads]: whether it holds a head of [m]'s notes (then only what the redraw covers goes);
            // else whether [m]'s redraw draws the whole of it again (a rest, accidental, dot, signature, marking).
            fun redrawnBy(m: Measure, heads: Boolean): Boolean {
            val notes = m.events.filterIsInstance<Note>()
            val rests = m.events.filterIsInstance<Rest>()
            // Where the clef, key and time end: where the bar's music was found to start, else its first symbol.
            val firstX = if (m.start > 0) m.start.toFloat() + sp * 0.2f else m.events.minOfOrNull { it.x } ?: m.box.right.toFloat()
            // A head of a note read here (its stem and beams with it, where they touch).
            if (heads) return notes.any { n -> n.steps.any { st -> holds(n.x + headW / 2, s.y(st, n.x.roundToInt())) } }
            return (
                // A rest read here.
                rests.any { rr -> val ry = rr.step?.let { s.y(it, rr.x.roundToInt()) } ?: ((top + bottom) / 2)
                    l <= rr.x + sp * 1.2f && r >= rr.x - sp * 0.2f && t <= ry + sp * 1.6f && b >= ry - sp * 1.6f && w <= sp * 2.2f && h <= sp * 4f } ||
                // A bar's one whole rest, wherever on the staff it is printed (a cue over it pushes it down).
                (rests.size == 1 && notes.isEmpty() && rests[0].duration.base == 1 && rests[0].let { rr ->
                    l <= rr.x + sp * 1.2f && r >= rr.x - sp * 0.6f && w <= sp * 2.2f && h <= sp && t >= top - sp * 2.5f && b <= bottom + sp * 2.5f }) ||
                // A note's accidental, just left of its head.
                notes.any { n -> n.accidentals.isNotEmpty() && r <= n.x + sp * 0.2f && l >= n.x - sp * 2.4f && w <= sp * 1.6f &&
                    n.steps.any { st -> val hy = s.y(st, n.x.roundToInt()); t <= hy + sp * 1.5f && b >= hy - sp * 1.5f } } ||
                // A note's dot, just right of its head.
                notes.any { n -> n.duration.dots > 0 && w <= sp * 0.7f && h <= sp * 0.7f && l >= n.x + headW - sp * 0.2f && r <= n.x + headW + sp * 2.2f &&
                    n.steps.any { st -> abs((t + b) / 2f - s.y(st, n.x.roundToInt())) <= sp * 0.9f } } ||
                // The clef, key and time at the bar's start, where redrawn: on the staff, before the first note.
                ((m.showsClef || m.showsKey || m.showsTime) && r < firstX - sp * 0.2f && b >= top - sp * 0.5f && t <= bottom + sp * 0.5f && t >= top - sp * 3f && b <= bottom + sp * 3f) ||
                // From a PDF that states them: a note's articulations (small, over or under it), its ties, and the bar's dynamics, hairpins and slurs.
                (stated && (
                    notes.any { n -> n.articulations.isNotEmpty() && w <= sp * 1.6f && h <= sp * 1.6f && l <= n.x + headW && r >= n.x &&
                        n.steps.any { st -> val hy = s.y(st, n.x.roundToInt()); abs((t + b) / 2f - hy) in sp * 0.6f..sp * 4f } } ||
                    m.directions.any { d -> d.kind != "slur" && d.kind != "text" && l <= d.x2 + sp && r >= d.x - sp && (if (d.above) b < top else t > bottom) }
                )))
            }
            // Redrawn by any bar it reaches into, this one or a neighbour: then only the parts the
            // redraw covers go - heads, stems, flags and beams where printed - and what is joined to
            // them (a tie leaving a head, an accent touching a stem) is kept as printed.
            val near = staffBars.filter { o -> o.box.right >= l && o.box.left <= r }
            if (near.any { o -> redrawnBy(o, heads = false) }) continue
            // Any part of it the redraw covers goes (a head, a stem - whole or a piece a scan broke off -
            // a flag, a beam); what is left of it is kept as printed.
            val rest = ArrayList<Int>()
            val covering = near.map { o -> coverages.getOrPut(o) { coverage(o, s) } }
            // Which of its pixels stand in a tall upright run on the page as printed (a stem, unbroken
            // by the lines it crosses - not an accent or a dot).
            val page = pageInk ?: clean
            for (i in px.indices step 2) {
                val qx = px[i]; val qy = px[i + 1]
                var a = qy; while (page[qx, a - 1] && qy - a < sp * 2) a--
                var e = qy; while (page[qx, e + 1] && e - qy < sp * 2) e++
                val upright = e - a + 1 >= sp * 1.4f
                // A slur or tie running past a head or across a stem's way: a thin stroke (on the page
                // without its lines, under half a space deep) running on longer than a head is wide - not
                // the head, stem or flag the redraw draws again. Kept whole where it passes them (a beam's
                // band, as deep, still takes what is in it).
                var ta = qy; while (clean[qx, ta - 1] && qy - ta < sp) ta--
                var tb = qy; while (clean[qx, tb + 1] && tb - qy < sp) tb++
                val thin = tb - ta + 1 < sp * 0.45f && run(clean, qx, qy, sp * 1.6f) > sp * 1.6f
                if (covering.none { c -> c.covers(px[i].toFloat(), px[i + 1].toFloat(), upright, stroke = thin) }) { rest += px[i]; rest += px[i + 1] }
            }
            if (rest.size < px.size) {
                if (rest.size < 8) continue
                // What is left, piece by piece: each kept if it is more than a sliver.
                for (piece in Outline.pieces(rest.toIntArray())) {
                    var pl = Int.MAX_VALUE; var pr = Int.MIN_VALUE; var pt = Int.MAX_VALUE; var pb = Int.MIN_VALUE
                    for (j in piece.indices step 2) { pl = min(pl, piece[j]); pr = max(pr, piece[j]); pt = min(pt, piece[j + 1]); pb = max(pb, piece[j + 1]) }
                    if (max(pr - pl, pb - pt) < sp * 0.6f || piece.size / 2 < sp * sp * 0.08f) continue
                    if (!owns(piece)) continue
                    val shade = shadeOf(piece)
                    Outline.loops(piece).forEach { loop -> out += Outline.simplified(loop).let { sl -> IntArray(sl.size) { sl[it].toInt() } }; shades += shade }
                }
                continue
            }
            if (!owns(px)) continue
            val shade = shadeOf(px)
            Outline.loops(px).forEach { loop -> out += Outline.simplified(loop).let { sl -> IntArray(sl.size) { sl[it].toInt() } }; shades += shade }
        }
        return out to shades
    }

    /**
     * Whether the page shows one stem, not two, by heads [a] and [b] side by side: upright strokes a
     * couple of spaces long across the span of the two, counted - one a chord's, two two notes'.
     * (Whole notes have none: two side by side, a step apart, are a chord.)
     */
    private fun oneStemBetween(clean: Ink, s: Staff, a: Head, b: Head): Boolean {
        val sp = s.space
        if (a.kind == "noteheadWhole" && b.kind == "noteheadWhole") return true
        val x0 = min(a.x, b.x) - (sp * 0.3f).toInt(); val x1 = max(a.x, b.x) + (sp * 1.5f).toInt()
        val yMid = (a.y + b.y) / 2
        var stems = 0; var inRun = false
        for (x in x0..x1) {
            // Upright ink through the heads' height, running on a couple of spaces either way.
            var up = 0; var y = yMid; while (clean[x, y - 1] && yMid - y < sp * 4) { y--; up++ }
            var down = 0; y = yMid; while (clean[x, y + 1] && y - yMid < sp * 4) { y++; down++ }
            val stem = up >= sp * 2.2f || down >= sp * 2.2f
            if (stem && !inRun) stems++
            inRun = stem
        }
        return stems == 1
    }

    private fun eventsIn(clean: Ink, s: Staff, from: Int, to: Int, t: Int, carry: Carry, heads: List<Head>): List<Event> {
        val sp = s.space
        for (h in heads) dots(clean, s, h)
        // Heads sharing a stem are one chord.
        val chords = ArrayList<MutableList<Head>>()
        for (h in heads) {
            val with = chords.firstOrNull { c -> val o = c.first()
                (h.stemX >= 0 && o.stemX >= 0 && abs(h.stemX - o.stemX) <= max(2, t + 1)) ||
                    (h.kind == "noteheadWhole" && o.kind == "noteheadWhole" && abs(h.x - o.x) < sp * 0.5f) ||
                    // One over the other at one place, alike - both filled or both hollow, as many flags
                    // or beams: one chord, its stems' places guessed a little apart (two voices with
                    // rhythms of their own differ in one or the other).
                    (abs(h.x - o.x) <= sp * 0.35f && h.kind == o.kind && h.flags == o.flags && h.step != o.step) }
            if (with != null) with += h else chords += mutableListOf(h)
        }
        // Two heads a second apart are printed side by side, one each side of the stem they share:
        // one chord, not two notes - told from two notes in a row by the one stem between them
        // (two notes have one each).
        if (System.getProperty("inksheets.omr.noseconds") == null) {
            chords.sortBy { c -> c.minOf { it.x } }
            var k = 0
            while (k + 1 < chords.size) {
                val a = chords[k]; val b = chords[k + 1]
                val pair = a.firstNotNullOfOrNull { ha -> b.firstOrNull { hb -> abs(ha.step - hb.step) == 1 && (hb.x - ha.x) in (sp * 0.6f).toInt()..(sp * 1.5f).toInt() &&
                    (ha.kind == "noteheadBlack") == (hb.kind == "noteheadBlack") && ha.flags == hb.flags }?.let { ha to it } }
                if (pair != null && oneStemBetween(clean, s, pair.first, pair.second)) { a += b; chords.removeAt(k + 1) } else k++
            }
        }
        val events = ArrayList<Event>()
        val written = HashMap<Int, Int>()   // diatonic -> alter, for the rest of the measure
        val taken = chords.map { c -> c.minOf { it.x } to c.maxOf { it.x + (sp * 1.3f).toInt() } }
        // A chord is filled heads or hollow, never both: a hollow one among filled ones is a stray loop.
        for (c in chords) if (c.any { it.kind == "noteheadBlack" } && c.any { it.kind == "noteheadHalf" || it.kind == "noteheadWhole" }) c.removeAll { it.kind == "noteheadHalf" || it.kind == "noteheadWhole" }
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
            val (marks, tied) = if (printed?.learned == false) printedMarks(s, c) else emptyList<String>() to false
            // Its stem's direction where its stem was seen (a trained reader's head whose stem was not found on the page: unknown).
            val seen = h.stemX >= 0 && (this.printed?.learned != true || h.stemEnd != 0)
            events += Note(c.map { it.step }.sorted(), pitches, dur, c.minOf { it.x }.toFloat(), accs, h.up.takeIf { seen }, c.minOf { it.score }, marks, tied,
                odds = symbolOf[h]?.odds.orEmpty())
        }
        // As printed: where each stem ends, and which notes one beam joins - a stroke along from one
        // stem's end to the next's - so a redrawn bar keeps the print's groups and their slant.
        if (chords.size == events.size) {
            fun tipOf(c: List<Head>): Pair<Int, Int>? {
                // A stem seen on the page, and long enough to be one (not a head's own edge).
                val h = c.firstOrNull { it.stemX >= 0 && it.stemEnd != 0 && abs(it.stemEnd - it.y) >= sp * 1.5f } ?: return null
                return h.stemX to h.stemEnd
            }
            val tips = chords.map { tipOf(it) }
            fun joined(a: Pair<Int, Int>, b: Pair<Int, Int>): Boolean {
                if (b.first - a.first < sp * 0.8f || b.first - a.first > sp * 12f) return false
                var hit = 0; var n = 0
                for (x in a.first + 1 until b.first) {
                    val y = a.second + (b.second - a.second) * (x - a.first).toFloat() / (b.first - a.first)
                    n++
                    if ((-(sp * 0.35f).toInt()..(sp * 0.35f).toInt()).any { clean[x, (y + it).roundToInt()] }) hit++
                }
                return n > 0 && hit >= n * 0.9f
            }
            var group = 0
            for (i in events.indices) {
                val n = events[i] as? Note ?: continue
                val tip = tips[i]
                val spaceAt = tip?.let { (s.lineY(4, it.first) - s.lineY(0, it.first)) / 4f }
                var beam = 0
                if (tip != null && n.duration.base >= 8) {
                    val prev = events.getOrNull(i - 1) as? Note
                    val prevTip = tips.getOrNull(i - 1)
                    beam = if (prev != null && prev.beam > 0 && prevTip != null && prev.duration.base >= 8 && joined(prevTip, tip)) prev.beam
                        else if (i + 1 < events.size && (events[i + 1] as? Note)?.duration?.base?.let { it >= 8 } == true && tips[i + 1]?.let { joined(tip, it) } == true) ++group
                        else 0
                }
                events[i] = n.copy(stemTip = if (tip != null && spaceAt != null && spaceAt > 0f) (tip.second - s.lineY(0, tip.first)) / spaceAt else null, beam = beam)
            }
        }
        // Rests, where no note is.
        val rests = ArrayList<Pair<Rest, Float>>()
        // Strokes the staff's full height - a barline's thick stroke, a repeat's, a stem - where no
        // rest can be: none is that tall.
        if (printed != null) rests += printedRests(s, from, to) else {
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
                    rests += Rest(Duration(kind, dots), x.toFloat(), at) to sc
                }
            }
        }
        // Half and whole rests in any font: a solid block a space or so wide, sitting on the middle
        // line (half) or hanging from the line above it (whole).
        rests += blockRests(clean, s, from, to, taken)
        }
        // A sixteenth rest is an eighth rest and a hook more: the eighth's shape fits its top as
        // well. Where a sixteenth fits nearly as well in the same place, it is one.
        if (printed == null) rests.removeAll { (r, sc) -> r.duration.base == 8 && rests.any { (o, osc) -> o.duration.base == 16 && abs(o.x - r.x) < sp * 0.6f && osc >= sc - 0.08f } }
        rests.sortByDescending { it.second }
        val keptRests = ArrayList<Rest>()
        // Printed rests are each one printed: two at one place are two voices' (a drum part's).
        if (printed != null) keptRests += rests.map { it.first }
        else for ((r, _) in rests) if (keptRests.none { abs(it.x - r.x) < sp * 1.2f }) keptRests += r
        // A whole rest stands alone in its bar and fills it, whatever the time; among notes it is
        // something else.
        val whole = keptRests.filter { it.duration.base == 1 }
        if (events.isEmpty() && whole.isNotEmpty()) return listOf(whole.first())
        events += keptRests.filter { it.duration.base != 1 }
        return events.sortedBy { it.x }
    }
}
