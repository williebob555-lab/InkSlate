package com.inksheets.core.omr

import kotlin.math.max
import kotlin.math.min

/**
 * Music drawn cleanly: a line of measures laid out as shapes in staff spaces (the top staff line
 * at y 0, the bottom at 4) - drawn on screen for a measure that scanned badly, and into a picture
 * for testing the reader against music whose every note is known.
 */
object Engraver {
    sealed class Mark
    /** A music symbol, its origin at ([x], [y]). */
    data class Symbol(val name: String, val x: Float, val y: Float) : Mark()
    /** A straight stroke [w] thick. */
    data class Stroke(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val w: Float) : Mark()
    /** A filled shape through [points] (x, y in turn): a beam's four corners, a tie's or slur's curve. */
    data class Slab(val points: FloatArray) : Mark()

    /**
     * A tie's or slur's curve from ([x1], [y1]) to ([x2], [y2]), bowing [h] up (or down, [up]
     * false) at its middle, [t] thick there and coming to a point at each end.
     */
    fun curve(x1: Float, y1: Float, x2: Float, y2: Float, h: Float, up: Boolean, t: Float = 0.16f): Slab {
        val n = 14
        val sign = if (up) -1f else 1f
        val pts = ArrayList<Float>()
        for (i in 0..n) { val u = i.toFloat() / n; val b = 4 * u * (1 - u); pts += x1 + (x2 - x1) * u; pts += y1 + (y2 - y1) * u + sign * h * b }
        for (i in n downTo 0) { val u = i.toFloat() / n; val b = 4 * u * (1 - u); pts += x1 + (x2 - x1) * u; pts += y1 + (y2 - y1) * u + sign * (h - t) * b }
        return Slab(pts.toFloatArray())
    }

    /**
     * Measures laid out: the shapes, how wide, where each measure starts and ends, and where each of
     * its events stands (x in spaces, a measure's in its order) - what a tap on the drawing is on.
     */
    class Drawing(val marks: List<Mark>, val width: Float, val measures: List<Pair<Float, Float>>, val events: List<List<Float>> = emptyList())

    const val LINE = 0.13f
    const val STEM = 0.12f
    const val STEM_LENGTH = 3.5f
    const val BEAM = 0.5f
    const val BEAM_GAP = 0.25f

    /** Where a key's sharps or flats sit, in steps below the top line, for [clef]. */
    fun keySteps(key: Key, clef: Clef): List<Int> {
        val treble = if (key.fifths >= 0) intArrayOf(0, 3, -1, 2, 5, 1, 4) else intArrayOf(4, 1, 5, 2, 6, 3, 7)
        val diff = clef.topLine - Clef.TREBLE.topLine
        val shift = diff + 7 * Math.round(-diff / 7.0).toInt()
        return treble.take(kotlin.math.abs(key.fifths)).map { it + shift }
    }

    private fun widthOf(d: Duration) = when (d.base) { 1 -> 4f; 2 -> 3f; 4 -> 2.4f; 8 -> 1.9f; else -> 1.6f } + d.dots * 0.4f

    /**
     * A line of [measures] drawn in turn, from x 0. [lineStart]: the first bar shows its clef and key
     * as a line's first bar does; otherwise only what each bar prints itself (one bar shown alone,
     * beside the print of it).
     */
    fun line(measures: List<Measure>, lineStart: Boolean = true): Drawing {
        val marks = ArrayList<Mark>()
        val spans = ArrayList<Pair<Float, Float>>()
        val eventXs = ArrayList<List<Float>>()
        var x = 0.3f
        for ((mi, m) in measures.withIndex()) {
            val start = x
            x = drawStart(marks, m, mi == 0 && lineStart, x)
            x += 0.6f
            // Notes and rests, each its own room; beamed notes grouped a beat at a time.
            val placed = ArrayList<Pair<Event, Float>>()
            for (e in m.events) {
                val acc = (e as? Note)?.accidentals?.isNotEmpty() == true
                if (acc) x += 1.1f
                placed += e to x
                x += widthOf(e.duration)
            }
            drawEvents(marks, placed, m, x + 0.4f)
            eventXs += placed.map { it.second }
            // Dynamics, hairpins and slurs where they were printed among the notes: a page x mapped
            // across by the notes either side of it.
            val anchors = placed.map { (e, lx) -> e.x to lx }.sortedBy { it.first }
            val end = x + 0.4f
            drawDirections(marks, m, end, from = start) { px ->
                val after = anchors.indexOfFirst { it.first >= px }
                when {
                    // No notes to go by (a bar of rests): across the bar as it was printed.
                    anchors.isEmpty() -> start + (end - start) * ((px - m.box.left) / m.box.width.coerceAtLeast(1))
                    after < 0 -> anchors.last().second + ((px - anchors.last().first) / m.space).coerceAtMost(2f)
                    after == 0 -> anchors.first().second - ((anchors.first().first - px) / m.space).coerceAtMost(1.5f)
                    else -> {
                        val (a, la) = anchors[after - 1]; val (b, lb) = anchors[after]
                        la + (lb - la) * ((px - a) / (b - a).coerceAtLeast(1e-3f))
                    }
                }
            }
            x += 0.4f
            if (m.ending > 0) {
                // An ending's bracket over the bar, its number by the hook where it begins.
                val begins = mi == 0 || measures[mi - 1].ending != m.ending
                marks += Stroke(start + 0.2f, -3f, x, -3f, 0.12f)
                if (begins) {
                    marks += Stroke(start + 0.2f, -3f, start + 0.2f, -1.8f, 0.12f)
                    marks += Symbol("timeSig${m.ending}", start + 0.6f, -1.9f)
                }
            }
            if (m.repeatEnd) {
                // A repeat's end: its two dots, thin, thick.
                marks += Symbol("augmentationDot", x, 1.5f)
                marks += Symbol("augmentationDot", x, 2.5f)
                marks += Stroke(x + 0.7f, 0f, x + 0.7f, 4f, 0.16f)
                marks += Stroke(x + 1.3f, 0f, x + 1.3f, 4f, 0.5f)
                x += 1.55f
            } else marks += Stroke(x, 0f, x, 4f, 0.16f)
            spans += start to x
            x += 0.3f
        }
        val end = x
        for (i in 0..4) marks.add(0, Stroke(0f, i.toFloat(), end, i.toFloat(), LINE))
        return Drawing(marks, end, spans, eventXs)
    }

    /**
     * [m] drawn where it is on its page: each note and rest at the place it was read (its x, in
     * staff spaces from the bar's left edge), no clef or key - to lay over the page, or replace a
     * patch of it, and line up with everything around it.
     */
    /**
     * What stands at a bar's start, from [x] (spaces): its clef, key and time where it shows them
     * ([first]: a line's first bar shows clef and key), a segno or coda, a repeat's start. Where
     * the bar's notes may begin.
     */
    private fun drawStart(marks: MutableList<Mark>, m: Measure, first: Boolean, x0: Float): Float {
        var x = x0
        val start = x
        if (m.showsClef || first) {
            val (glyph, y) = when (m.clef) {
                Clef.TREBLE -> "gClef" to 3f
                Clef.BASS -> "fClef" to 1f
                Clef.ALTO -> "cClef" to 2f
                Clef.TENOR -> "cClef" to 1f
            }
            marks += Symbol(glyph, x + 0.4f, y)
            // Clear of the clef's dots (an F clef's reach past its advance) before a key or time.
            x += MusicGlyphs[glyph].advance + 1.2f
        }
        if (m.showsKey || (first && m.key.fifths != 0)) {
            val name = if (m.key.fifths > 0) "accidentalSharp" else "accidentalFlat"
            for (s in keySteps(m.key, m.clef)) { marks += Symbol(name, x, s * 0.5f); x += 1.0f }
            x += 0.4f
        }
        if (m.showsTime) {
            val top = m.time.beats.toString(); val bottom = m.time.beatType.toString()
            val w = max(top.length, bottom.length) * 1.8f
            top.forEachIndexed { i, c -> marks += Symbol("timeSig$c", x + i * 1.8f + (w - top.length * 1.8f) / 2, 1f) }
            bottom.forEachIndexed { i, c -> marks += Symbol("timeSig$c", x + i * 1.8f + (w - bottom.length * 1.8f) / 2, 3f) }
            x += w + 0.8f
        }
        // D.S. and coda signs over the bar's start.
        if (m.segno) marks += Symbol("segno", start + 0.3f, -2f)
        if (m.coda) marks += Symbol("coda", start + 0.3f, -2f)
        // A repeat's start: thick, thin, and its two dots.
        if (m.repeatStart) {
            marks += Stroke(x + 0.25f, 0f, x + 0.25f, 4f, 0.5f)
            marks += Stroke(x + 0.85f, 0f, x + 0.85f, 4f, 0.16f)
            marks += Symbol("augmentationDot", x + 1.2f, 1.5f)
            marks += Symbol("augmentationDot", x + 1.2f, 2.5f)
            x += 1.8f
        }
        return x
    }

    /**
     * [m] drawn where it is printed, for showing in place of its print. Ties and slurs are left to
     * the print itself (a cleaned bar keeps them exactly as printed - see [Measure.kept]): free
     * curves, which the print's own shape follows truly and a drawn arc only roughly.
     */
    fun aligned(m0: Measure): Drawing {
        val m = m0.copy(events = m0.events.map { if (it is Note && it.tie) it.copy(tie = false) else it }, directions = m0.directions.filter { it.kind != "slur" })
        val marks = ArrayList<Mark>()
        val width = m.box.width / m.space
        for (i in 0..4) marks += Stroke(0f, i.toFloat(), width, i.toFloat(), LINE)
        // A line's first bar, or one changing clef, key or time: those redrawn where printed, in
        // the room before its first note.
        // (A repeat's start, a segno or a coda stay as printed: kept, not drawn again.)
        if (m.showsClef || m.showsKey || m.showsTime) drawStart(marks, m.copy(repeatStart = false, segno = false, coda = false), false, 0.3f)
        val placed = m.events.map { it to (it.x - m.box.left) / m.space }
        drawEvents(marks, placed, m, width)
        drawDirections(marks, m, width) { x -> (x - m.box.left) / m.space }
        marks += Stroke(width, 0f, width, 4f, 0.16f)
        return Drawing(marks, width, listOf(0f to width))
    }

    /**
     * A multi-bar rest [m] drawn where it is: its staff, the clef, key and time where the bar shows
     * them (a line beginning with a long rest), then the thick bar across the middle with its end
     * strokes, and how many bars over it.
     */
    fun multiRest(m: Measure): Drawing {
        val marks = ArrayList<Mark>()
        val width = m.box.width / m.space
        for (i in 0..4) marks += Stroke(0f, i.toFloat(), width, i.toFloat(), LINE)
        val from = if (m.showsClef || m.showsKey || m.showsTime) drawStart(marks, m.copy(repeatStart = false, segno = false, coda = false), false, 0.3f) + 0.6f else 0f
        val room = (width - from).coerceAtLeast(1f)
        val a = from + room * 0.18f; val b = from + room * 0.82f
        marks += Slab(floatArrayOf(a, 1.6f, b, 1.6f, b, 2.4f, a, 2.4f))
        marks += Stroke(a, 1f, a, 3f, 0.16f); marks += Stroke(b, 1f, b, 3f, 0.16f)
        val n = m.bars.toString()
        val digitW = 1.8f
        n.forEachIndexed { i, c -> marks += Symbol("timeSig$c", (a + b) / 2 - n.length * digitW / 2 + i * digitW, -1.2f) }
        marks += Stroke(width, 0f, width, 4f, 0.16f)
        return Drawing(marks, width, listOf(0f to width))
    }

    /** The dynamic letters a marking is set in ("mf": m, f), by glyph. */
    private fun dynamicGlyphs(text: String): List<String>? = text.map { c ->
        when (c) { 'p' -> "dynamicPiano"; 'm' -> "dynamicMezzo"; 'f' -> "dynamicForte"; 'r' -> "dynamicRinforzando"; 's' -> "dynamicSforzando"; 'z' -> "dynamicZ"; else -> return null }
    }

    /**
     * [m]'s dynamics, hairpins and slurs, at the places [at] maps its page x to, across a bar
     * [width] spaces wide. Dynamics and hairpins go under the staff (or over it, where printed so);
     * a slur arcs over its notes or under them, and runs to the bar's edge where it goes on.
     */
    private fun drawDirections(marks: MutableList<Mark>, m: Measure, width: Float, from: Float = 0f, at: (Float) -> Float) {
        // Under the staff, clear of the lowest note; over it, clear of the highest.
        val steps = m.events.filterIsInstance<Note>().flatMap { it.steps }
        val below = max(6.2f, (steps.maxOrNull() ?: 8) * 0.5f + 1.8f)
        val over = min(-2.2f, (steps.minOrNull() ?: 0) * 0.5f - 1.8f)
        for (d in m.directions) {
            val x = at(d.x).coerceIn(from, width); val x2 = at(d.x2).coerceIn(from, width)
            val y = if (d.above) over else below
            when (d.kind) {
                "dynamic" -> {
                    var cx = x
                    for (g in dynamicGlyphs(d.text) ?: continue) { marks += Symbol(g, cx, y); cx += MusicGlyphs[g].advance - 0.1f }
                }
                "cresc", "dim" -> {
                    // One wedge however many bars it crosses: closed end a point, open end a space
                    // apart, and this bar drawing its slice of it - as open at each edge as the
                    // whole wedge is there.
                    val xa = at(d.x); val xb = at(d.x2)
                    val c0 = max(xa, from); val c1 = min(xb, width)
                    if (xb - xa > 0.5f && c1 - c0 > 0.05f) {
                        fun open(xx: Float) = (if (d.kind == "cresc") (xx - xa) else (xb - xx)) / (xb - xa) * 0.5f
                        val yc = y - 0.2f
                        marks += Stroke(c0, yc - open(c0), c1, yc - open(c1), 0.1f)
                        marks += Stroke(c0, yc + open(c0), c1, yc + open(c1), 0.1f)
                    }
                }
                "slur" -> if (x2 - x > 0.5f) {
                    val y1 = (d.step ?: 0) * 0.5f + (if (d.above) -0.8f else 0.8f)
                    val y2 = (d.step2 ?: d.step ?: 0) * 0.5f + (if (d.above) -0.8f else 0.8f)
                    marks += curve(x, y1, x2, y2, min(1.2f, 0.25f + (x2 - x) * 0.06f), d.above)
                }
            }
        }
    }

    /** The glyph for articulation [name] on the side [above] or below. */
    private fun articulation(name: String, above: Boolean): String? = when (name) {
        "accent" -> "articAccent"; "staccato" -> "articStaccato"; "tenuto" -> "articTenuto"
        "staccatissimo" -> "articStaccatissimo"; "marcato" -> "articMarcato"; else -> null
    }?.let { it + if (above) "Above" else "Below" }

    /**
     * Each note's marks - articulations on its head's side, away from the stem, stacked outwards;
     * a fermata over the staff - and its ties to the next note at a pitch it shares (to the bar's
     * end, [width], where the note tied to is in the next bar).
     */
    private fun drawNoteMarks(marks: MutableList<Mark>, placed: List<Pair<Event, Float>>, width: Float?) {
        val notes = placed.filter { it.first is Note }.map { it.first as Note to it.second }
        for ((i, pair) in notes.withIndex()) {
            val (n, x) = pair
            val head = when (n.duration.base) { 1 -> "noteheadWhole"; 2 -> "noteheadHalf"; else -> "noteheadBlack" }
            val headW = MusicGlyphs[head].advance
            val up = stemUp(n)
            val top = n.steps.min() * 0.5f; val bottom = n.steps.max() * 0.5f
            var y = if (up) bottom + 1.0f else top - 1.0f
            for (a in n.articulations) {
                if (a == "fermata") {
                    val g = "fermataAbove"
                    marks += Symbol(g, x + headW / 2 - MusicGlyphs[g].advance / 2, min(top - 1.6f, -1.2f))
                    continue
                }
                val g = articulation(a, !up) ?: continue
                marks += Symbol(g, x + headW / 2 - MusicGlyphs[g].advance / 2, y)
                y += if (up) 0.9f else -0.9f
            }
            if (n.tie) {
                val next = notes.getOrNull(i + 1)
                for (s in n.steps) {
                    if (next != null && s !in next.first.steps) continue
                    val x1 = x + headW + 0.1f
                    val x2 = next?.second?.minus(0.1f) ?: (width ?: (x + headW + 2.5f))
                    if (x2 - x1 < 0.3f) continue
                    // Away from the stem: under the heads when it goes up, over them when down.
                    val ty = s * 0.5f + if (up) 0.55f else -0.55f
                    marks += curve(x1, ty, x2, ty, min(0.6f, 0.2f + (x2 - x1) * 0.08f), !up, 0.13f)
                }
            }
        }
    }

    private fun drawEvents(marks: MutableList<Mark>, placed: List<Pair<Event, Float>>, m: Measure, width: Float? = null) {
        drawNoteMarks(marks, placed, width)
        // Beams: the print's own groups where it was read (notes sharing a beam number), else runs
        // of eighths and shorter within one beat.
        val groups = ArrayList<List<Pair<Note, Float>>>()
        val printedGroups = placed.any { (e, _) -> e is Note && e.beam > 0 }
        var run = ArrayList<Pair<Note, Float>>()
        var runBeat = -1.0
        var at = 0.0
        val beat = if (m.time.beatType == 8 && m.time.beats % 3 == 0) 1.5 else 1.0
        if (printedGroups) {
            for ((e, x) in placed) {
                if (e is Note && e.beam > 0 && run.isNotEmpty() && run.last().first.beam == e.beam) run += e to x
                else { if (run.isNotEmpty()) groups += run; run = if (e is Note && e.beam > 0) arrayListOf(e to x) else ArrayList() }
            }
        } else for ((e, x) in placed) {
            val b = Math.floor(at / beat + 1e-9)
            if (e is Note && e.duration.beams > 0) {
                if (run.isNotEmpty() && b != runBeat) { groups += run; run = ArrayList() }
                if (run.isEmpty()) runBeat = b
                run += e to x
            } else if (run.isNotEmpty()) { groups += run; run = ArrayList() }
            at += e.duration.quarters
        }
        if (run.isNotEmpty()) groups += run
        val beamed = groups.filter { it.size > 1 }.flatMap { g -> g.map { it.first } }.toSet()

        for ((e, x) in placed) {
            when (e) {
                is Rest -> {
                    val (name, y) = when (e.duration.base) {
                        1 -> "restWhole" to 1f; 2 -> "restHalf" to 2f; 4 -> "restQuarter" to 2f
                        8 -> "rest8th" to 2f; else -> "rest16th" to 2f
                    }
                    marks += Symbol(name, x, y)
                    if (e.duration.dots > 0) marks += Symbol("augmentationDot", x + MusicGlyphs[name].advance + 0.3f, 1.5f)
                }
                is Note -> {
                    val head = when (e.duration.base) { 1 -> "noteheadWhole"; 2 -> "noteheadHalf"; else -> "noteheadBlack" }
                    val headW = MusicGlyphs[head].advance
                    for (s in e.steps) {
                        marks += Symbol(head, x, s * 0.5f)
                        e.accidentals[s]?.let { a ->
                            val name = when (a) { 1 -> "accidentalSharp"; -1 -> "accidentalFlat"; else -> "accidentalNatural" }
                            marks += Symbol(name, x - 1.1f, s * 0.5f)
                        }
                        if (e.duration.dots > 0) {
                            val dy = if (s % 2 == 0) s * 0.5f - 0.5f else s * 0.5f
                            marks += Symbol("augmentationDot", x + headW + 0.3f, dy)
                        }
                    }
                    // Ledger lines above and below the staff.
                    val top = e.steps.min(); val bottom = e.steps.max()
                    var l = -2
                    while (l >= top) { marks += Stroke(x - 0.35f, l * 0.5f, x + headW + 0.35f, l * 0.5f, LINE * 1.3f); l -= 2 }
                    l = 10
                    while (l <= bottom) { marks += Stroke(x - 0.35f, l * 0.5f, x + headW + 0.35f, l * 0.5f, LINE * 1.3f); l += 2 }
                    if (e.duration.base >= 2) {
                        val up = stemUp(e)
                        val sx = if (up) x + headW - STEM / 2 else x + STEM / 2
                        val from = if (up) bottom * 0.5f else top * 0.5f
                        // As long as printed, where it was seen; else the usual length.
                        val to = e.stemTip ?: if (up) min(top * 0.5f - STEM_LENGTH, 2f) else max(bottom * 0.5f + STEM_LENGTH, 2f)
                        if (e !in beamed) {
                            marks += Stroke(sx, from, sx, to, STEM)
                            if (e.duration.beams > 0) {
                                val flag = when (e.duration.beams) { 1 -> if (up) "flag8thUp" else "flag8thDown"; else -> if (up) "flag16thUp" else "flag16thDown" }
                                marks += Symbol(flag, sx - STEM / 2, to)
                            }
                        }
                    }
                }
            }
        }
        // Beamed groups: stems to one straight beam, a second beam for sixteenths.
        for (g in groups.filter { it.size > 1 }) {
            // The print's stems' side and ends, where they were seen: its beam, slant and all.
            val tips = g.map { it.first.stemTip }
            val asPrinted = tips.all { it != null }
            val up = if (asPrinted) g.first().first.stemUp ?: (tips.first()!! < g.first().first.steps.min() * 0.5f) else g.map { it.first.steps.average() }.average() >= 4
            val headW = MusicGlyphs["noteheadBlack"].advance
            val xs = g.map { (n, x) -> if (up) x + headW - STEM / 2 else x + STEM / 2 }
            val ends = g.map { (n, _) -> if (up) n.steps.min() * 0.5f - STEM_LENGTH else n.steps.max() * 0.5f + STEM_LENGTH }
            val y0 = if (asPrinted) tips.first()!! else if (up) min(ends.first(), 1f) else max(ends.first(), 3f)
            val y1 = if (asPrinted) tips.last()!! else if (up) min(ends.last(), 1f) else max(ends.last(), 3f)
            val slope = ((y1 - y0) / (xs.last() - xs.first())).let { if (asPrinted) it else it.coerceIn(-0.25f, 0.25f) }
            // The beam clears every note: moved away from the heads as far as the lowest (highest) needs.
            var shift = 0f
            if (!asPrinted) g.forEachIndexed { i, (n, _) ->
                val y = y0 + slope * (xs[i] - xs.first())
                val need = if (up) n.steps.min() * 0.5f - 2.5f - y else y - (n.steps.max() * 0.5f + 2.5f)
                if (need < 0) shift = if (up) min(shift, need) else max(shift, -need)
            }
            fun beamY(x: Float) = y0 + shift + slope * (x - xs.first())
            g.forEachIndexed { i, (n, _) ->
                val from = if (up) n.steps.max() * 0.5f else n.steps.min() * 0.5f
                marks += Stroke(xs[i], from, xs[i], beamY(xs[i]), STEM)
            }
            val levels = g.maxOf { it.first.duration.beams }
            for (level in 0 until levels) {
                val off = level * (BEAM + BEAM_GAP) * (if (up) 1 else -1)
                // A secondary beam across the notes short enough for it.
                var i = 0
                while (i < g.size) {
                    if (g[i].first.duration.beams <= level) { i++; continue }
                    var j = i
                    while (j + 1 < g.size && g[j + 1].first.duration.beams > level) j++
                    val xa = xs[i] - STEM / 2; val xb = if (j > i) xs[j] + STEM / 2 else xs[i] + 1.2f * (if (i == g.size - 1) -1 else 1)
                    val ya = beamY(xa) + off; val yb = beamY(xb) + off
                    val t = BEAM * (if (up) 1 else -1)
                    marks += Slab(floatArrayOf(xa, ya, xb, yb, xb, yb + t, xa, ya + t))
                    i = j + 1
                }
            }
        }
    }

    /** Stems go up from notes low on the staff, down from high ones. */
    fun stemUp(n: Note): Boolean = n.stemUp ?: (n.steps.average() >= 4)

    /** [drawing] rasterized [space] pixels to the staff space, the top line at ([x], [y]). */
    fun paint(ink: Ink, drawing: Drawing, space: Float, x: Float, y: Float) {
        for (mark in drawing.marks) when (mark) {
            is Stroke -> Fill.line(ink, x + mark.x1 * space, y + mark.y1 * space, x + mark.x2 * space, y + mark.y2 * space, (mark.w * space).coerceAtLeast(1f))
            is Symbol -> Fill.polygons(ink, MusicGlyphs[mark.name].polygons(space, x + mark.x * space, y + mark.y * space))
            is Slab -> Fill.polygons(ink, listOf(FloatArray(mark.points.size) { i -> if (i % 2 == 0) x + mark.points[i] * space else y + mark.points[i] * space }))
        }
    }
}
