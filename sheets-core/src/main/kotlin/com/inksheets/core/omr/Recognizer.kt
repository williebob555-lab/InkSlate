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
class Recognizer(private val debug: Boolean = false) {

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
    class PageReading(val staves: List<Staff>, val barlines: List<List<Int>>, val measures: List<Measure>, val thickness: Int, val space: Float)

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
            // Line candidates: runs of rows mostly ink, no thicker than a line and a bit.
            val lines = ArrayList<Float>()
            var y = 0
            while (y < ink.height) {
                if (rows[y] > 0.55f) {
                    val y0 = y
                    while (y < ink.height && rows[y] > 0.55f) y++
                    if (y - y0 <= t * 2 + 2) lines += (y0 + y - 1) / 2f
                } else y++
            }
            // Fives, evenly spaced.
            var i = 0
            while (i + 4 < lines.size) {
                val five = lines.subList(i, i + 5)
                val gaps = (1..4).map { five[it] - five[it - 1] }
                if (gaps.all { abs(it - space) <= space * 0.3f }) {
                    groups += Group((sx + x1) / 2, five.toFloatArray())
                    i += 5
                } else i++
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
            staves += Staff(left, right, lines, space)
        }
        return staves.sortedBy { it.top }
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
            val throughHead = heads.any { h -> centre in h.x + 2..h.x + MusicGlyphs.template(h.kind, s.space).ink.width - 4 }
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
    fun score(ink: Ink, name: String, space: Float, x: Int, y: Int): Float {
        val tp = MusicGlyphs.template(name, space)
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
    private fun hollow(ink: Ink, space: Float, x: Int, y: Int, shape: String): Float {
        val tp = MusicGlyphs.template(shape, space)
        val m = rings.getOrPut(shape to (space * 10).toInt()) {
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
            val core = BooleanArray(w * h) { depth[it] > coreDepth && depth[it] != Int.MAX_VALUE }
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

    /** A notehead found: where, which kind, how sure. */
    class Head(val x: Int, val step: Int, val y: Int, val kind: String, val score: Float) {
        var stemX = -1
        var stemEnd = 0
        var up = false
        var flags = 0
        var dots = 0
    }

    /** Noteheads on [s] between [from] and [to]; [lines] is the page with its staff and ledger lines, to check notes off the staff by. */
    fun heads(clean: Ink, s: Staff, from: Int, to: Int, lines: Ink? = null): List<Head> {
        val found = ArrayList<Head>()
        val sp = s.space
        val black = MusicGlyphs.template("noteheadBlack", sp)
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
                    val sc = score(clean, "noteheadBlack", sp, x, y)
                    if (sc > 0.72f) found += Head(x, step, y, "noteheadBlack", sc)
                }
                if (sides) {
                    for (kind in listOf("noteheadHalf", "noteheadWhole")) {
                        val sc = score(clean, kind, sp, x, y)
                        if (sc > 0.66f && holeClear(clean, kind, sp, x, y) > 0.6f) found += Head(x, step, y, kind, sc)
                    }
                    // Any engraver's hollow head: a head-shaped ring of ink round a clear middle -
                    // and not a flat's or natural's bowl, whose strokes rise on the left or fall on
                    // the right, as no note's stem does.
                    if (!accidentalLike(clean, sp, x, y, headW)) {
                        val ring = hollow(clean, sp, x, y, "noteheadBlack")
                        if (ring > 0.72f) found += Head(x, step, y, "noteheadHalf", ring)
                        val wide = hollow(clean, sp, x, y, "noteheadWhole")
                        if (wide > 0.74f) found += Head(x, step, y, "noteheadWhole", wide)
                    }
                }
            }
        }
        // A note off the staff stands on ledger lines: without one at the staff's edge it is a
        // dynamic's loop, an accent, a word - not a note.
        if (lines != null) found.retainAll { h ->
            if (h.step in -1..9) return@retainAll true
            val w = MusicGlyphs.template(h.kind, sp).ink.width - 2
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
        // The best of each cluster: heads cannot overlap, except a second's two in a chord.
        found.sortByDescending { it.score }
        val kept = ArrayList<Head>()
        for (h in found) {
            val w = MusicGlyphs.template(h.kind, sp).ink.width
            if (kept.none { k -> abs(k.x - h.x) < w * 0.7f && abs(k.step - h.step) < 2 }) kept += h
        }
        return kept.sortedBy { it.x }
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
    private fun stem(clean: Ink, s: Staff, h: Head, t: Int) {
        val sp = s.space
        val w = MusicGlyphs.template(h.kind, sp).ink.width - 2
        fun run(x: Int, y: Int, dir: Int): Int { var n = 0; var yy = y; while (clean[x, yy] || clean[x - 1, yy] && clean[x + 1, yy]) { yy += dir; n++ }; return n }
        // Up: at the head's right side; down: at its left - allowing for another font's heads being
        // a little narrower or wider than these.
        val upX = (h.x + (w * 0.6f).toInt()..h.x + w + 3).maxByOrNull { run(it, h.y - (sp * 0.3f).toInt(), -1) }!!
        val downX = (h.x - 3..h.x + (w * 0.4f).toInt()).maxByOrNull { run(it, h.y + (sp * 0.3f).toInt(), 1) }!!
        val up = run(upX, h.y - (sp * 0.3f).toInt(), -1)
        val down = run(downX, h.y + (sp * 0.3f).toInt(), 1)
        val len = max(up, down)
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
        val w = MusicGlyphs.template(h.kind, sp).ink.width
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
        val measures = ArrayList<Measure>()
        val bars = ArrayList<List<Int>>()
        var number = firstNumber
        for ((si, s) in staves.withIndex()) {
            // The start of the staff: clef, key, time.
            var x = s.left + (s.space * 0.3f).toInt()
            val clef = clefAt(clean, s, x)
            var showsClef = false
            if (clef != null) { carry.clef = clef.first; x = clef.second; showsClef = true }
            val key = keyAt(clean, s, x, carry.clef)
            var showsKey = false
            if (key != null) { carry.key = key.first; x = key.second; showsKey = true }
            val time = timeAt(clean, s, x)
            var showsTime = false
            if (time != null) { carry.time = time.first; x = time.second; showsTime = true }
            // Heads and stems next, after the staff's start: a stem the height of the staff is not
            // a barline - and nor is a time signature's digits.
            val allHeads = heads(clean, s, x, s.right, ink)
            for (h in allHeads) if (h.kind != "noteheadWhole") stem(clean, s, h, t)
            // Nothing fits between a staff's start and a line under three spaces on: that is a time
            // signature in another font, not a barline.
            val b = barlines(ink, clean, s, t, allHeads.filter { it.stemX >= 0 }.map { it.stemX }, allHeads).filter { it > x + s.space * 3f }
            bars += b
            val edges = (listOf(s.left) + b).distinct().sorted()
            val spans = edges.zipWithNext().filter { (a, c) -> c - a > s.space * 2 } +
                (if (b.isEmpty() || s.right - b.last() > s.space * 3) listOf((b.lastOrNull() ?: s.left) to s.right) else emptyList())
            for ((i, span) in spans.withIndex()) {
                val from = if (i == 0) max(span.first, x) else span.first + (s.space * 0.3f).toInt()
                val to = span.second - (s.space * 0.2f).toInt()
                val box = Box(span.first, s.top, span.second, s.bottom)
                // A multi-bar rest: its bar and number, and nothing else to read.
                val rest = multiRest(clean, s, from, to)
                if (rest != null) {
                    val (restBars, x) = rest
                    measures += Measure(number, page, si, box, s.space, carry.clef, carry.key, carry.time,
                        listOf(Rest(Duration(1), x.toFloat())), showsClef = i == 0 && showsClef, showsKey = i == 0 && showsKey,
                        showsTime = i == 0 && showsTime, doubts = if (restBars == 0) listOf("rest of how many bars?") else emptyList(), bars = max(1, restBars))
                    number += max(1, restBars)
                } else {
                val events = eventsIn(clean, s, from, to, t, carry, allHeads.filter { it.x >= from - 2 && it.x < to })
                val m = Measure(
                    number++, page, si, box, s.space,
                    carry.clef, carry.key, carry.time, events,
                    showsClef = i == 0 && showsClef, showsKey = i == 0 && showsKey, showsTime = i == 0 && showsTime
                )
                val doubts = ArrayList<String>()
                val q = m.quarters
                if (abs(q - carry.time.quarters) > 1e-6 && events.isNotEmpty()) doubts += "${fmt(q)} beats found, ${fmt(carry.time.quarters)} expected"
                if (events.isEmpty()) doubts += "nothing read"
                // Another engraver's heads match these a little less well and are read right: only
                // a weak match is a doubt.
                events.filterIsInstance<Note>().filter { it.confidence < 0.75f }.takeIf { it.isNotEmpty() }?.let { doubts += "${it.size} unclear note${if (it.size > 1) "s" else ""}" }
                measures += m.copy(doubts = doubts)
                }
            }
        }
        return PageReading(staves, bars, measures, t, space)
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
                return clean[xx, y] && b - a + 1 >= sp * 0.4f && b - a + 1 <= sp * 1.2f
            }
            if (!thick(x)) { x++; continue }
            val start = x
            while (x < to && thick(x)) x++
            if (best == null || x - start > best.second) best = start to (x - start)
        }
        val (start, len) = best ?: return null
        if (len < sp * 2.5f) return null
        // The number above: engravers' digits, as in a time signature, a space and a half over the top line.
        val digits = ArrayList<Pair<Int, Int>>()
        var dx = start - (sp * 0.5f).toInt()
        while (dx < start + len) {
            var hit: Triple<Int, Int, Float>? = null
            for (d in 0..9) for (xx in dx..dx + (sp * 0.8f).toInt()) for (step in listOf(-3, -4, -2)) {
                val sc = score(clean, "timeSig$d", sp, xx, s.y(step, xx).roundToInt())
                if (sc > 0.5f && (hit == null || sc > hit.third)) hit = Triple(d, xx, sc)
            }
            if (hit == null) { dx += (sp * 0.8f).toInt(); continue }
            digits += hit.first to hit.second
            dx = hit.second + (sp * 1.4f).toInt()
        }
        val bars = digits.sortedBy { it.second }.fold(0) { n, (d, _) -> n * 10 + d }
        return bars to start
    }

    private fun fmt(q: Double) = if (q == q.toLong().toDouble()) q.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, q).trimEnd('0')

    /** What carries from staff to staff and page to page: the clef, key and time in force. */
    class Carry(var clef: Clef = Clef.TREBLE, var key: Key = Key(0), var time: TimeSig = TimeSig(4, 4))

    private fun clefAt(clean: Ink, s: Staff, x0: Int): Pair<Clef, Int>? {
        val sp = s.space
        var best: Triple<Clef, Int, Float>? = null
        for ((clef, glyph, step) in listOf(Triple(Clef.TREBLE, "gClef", 6), Triple(Clef.BASS, "fClef", 2), Triple(Clef.ALTO, "cClef", 4), Triple(Clef.TENOR, "cClef", 2))) {
            for (x in x0..x0 + (sp * 2.5f).toInt()) {
                val sc = score(clean, glyph, sp, x, s.y(step, x).roundToInt())
                if (sc > 0.6f && (best == null || sc > best.third)) best = Triple(clef, x + (MusicGlyphs[glyph].advance * sp).toInt(), sc)
            }
        }
        return best?.let { it.first to it.second + (sp * 0.4f).toInt() }
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
        fun digitAt(x: Int, step: Int): Pair<Int, Int>? {
            var best: Triple<Int, Int, Float>? = null
            for (d in 0..9) for (xx in x..x + (sp * 1.2f).toInt()) {
                val sc = score(clean, "timeSig$d", sp, xx, s.y(step, xx).roundToInt())
                // Engravers' digits differ more than their noteheads: taken more loosely.
                if (sc > 0.5f && (best == null || sc > best.third)) best = Triple(d, xx, sc)
            }
            return best?.let { it.first to it.second }
        }
        // A digit's right edge, from where it was found.
        fun end(d: Int, x: Int) = x + ((MusicGlyphs["timeSig$d"].bounds[2]) * sp).toInt() + 2
        fun number(step: Int): Pair<Int, Int>? {
            val first = digitAt(x0, step) ?: return null
            val next = digitAt(first.second + (sp * 1.6f).toInt(), step)
            return if (next != null && next.second - first.second < sp * 2.4f) (first.first * 10 + next.first) to end(next.first, next.second)
            else first.first to end(first.first, first.second)
        }
        val top = number(2) ?: run {
            for ((glyph, sig) in listOf("timeSigCommon" to TimeSig(4, 4), "timeSigCutCommon" to TimeSig(2, 2))) {
                for (x in x0..x0 + (sp * 1.2f).toInt()) if (score(clean, glyph, sp, x, s.y(4, x).roundToInt()) > 0.62f) return sig to x + (sp * 2.2f).toInt()
            }
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
                "noteheadHalf" -> 2
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
        for ((name, base, step) in listOf(Triple("restQuarter", 4, 4), Triple("rest8th", 8, 4), Triple("rest16th", 16, 4), Triple("restHalf", 2, 4), Triple("restWhole", 1, 2))) {
            for (x in from until to) {
                if (taken.any { (a, b) -> x in a - (sp * 0.5f).toInt()..b }) continue
                val y = s.y(step, x).roundToInt()
                val sc = score(clean, name, sp, x, y)
                // Rests differ from font to font more than heads do; the small rectangles (whole,
                // half) are easily matched by other things, so they are held to more.
                if (sc > (if (base <= 2) 0.72f else 0.58f)) {
                    val right = x + (MusicGlyphs[name].advance * sp).toInt()
                    val dots = if (dotIn(clean, sp, right, s.y(2, right).roundToInt(), right + (sp * 1.0f).toInt(), s.y(4, right).roundToInt())) 1 else 0
                    rests += Rest(Duration(base, dots), x.toFloat()) to sc
                }
            }
        }
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
