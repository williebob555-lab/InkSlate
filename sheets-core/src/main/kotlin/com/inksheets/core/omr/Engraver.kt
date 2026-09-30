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
    /** A filled four-sided shape: a beam. */
    data class Slab(val points: FloatArray) : Mark()

    /** Measures laid out: the shapes, how wide, and where each measure starts and ends. */
    class Drawing(val marks: List<Mark>, val width: Float, val measures: List<Pair<Float, Float>>)

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

    /** A line of [measures] drawn in turn, from x 0. */
    fun line(measures: List<Measure>): Drawing {
        val marks = ArrayList<Mark>()
        val spans = ArrayList<Pair<Float, Float>>()
        var x = 0.3f
        for ((mi, m) in measures.withIndex()) {
            val start = x
            if (m.showsClef || mi == 0) {
                val (glyph, y) = when (m.clef) {
                    Clef.TREBLE -> "gClef" to 3f
                    Clef.BASS -> "fClef" to 1f
                    Clef.ALTO -> "cClef" to 2f
                    Clef.TENOR -> "cClef" to 1f
                }
                marks += Symbol(glyph, x + 0.4f, y)
                x += MusicGlyphs[glyph].advance + 0.8f
            }
            if (m.showsKey || (mi == 0 && m.key.fifths != 0)) {
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
            // A repeat's start: thick, thin, and its two dots.
            if (m.repeatStart) {
                marks += Stroke(x + 0.25f, 0f, x + 0.25f, 4f, 0.5f)
                marks += Stroke(x + 0.85f, 0f, x + 0.85f, 4f, 0.16f)
                marks += Symbol("augmentationDot", x + 1.2f, 1.5f)
                marks += Symbol("augmentationDot", x + 1.2f, 2.5f)
                x += 1.8f
            }
            x += 0.6f
            // Notes and rests, each its own room; beamed notes grouped a beat at a time.
            val placed = ArrayList<Pair<Event, Float>>()
            for (e in m.events) {
                val acc = (e as? Note)?.accidentals?.isNotEmpty() == true
                if (acc) x += 1.1f
                placed += e to x
                x += widthOf(e.duration)
            }
            drawEvents(marks, placed, m)
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
        return Drawing(marks, end, spans)
    }

    /**
     * [m] drawn where it is on its page: each note and rest at the place it was read (its x, in
     * staff spaces from the bar's left edge), no clef or key - to lay over the page, or replace a
     * patch of it, and line up with everything around it.
     */
    fun aligned(m: Measure): Drawing {
        val marks = ArrayList<Mark>()
        val width = m.box.width / m.space
        for (i in 0..4) marks += Stroke(0f, i.toFloat(), width, i.toFloat(), LINE)
        val placed = m.events.map { it to (it.x - m.box.left) / m.space }
        drawEvents(marks, placed, m)
        marks += Stroke(width, 0f, width, 4f, 0.16f)
        return Drawing(marks, width, listOf(0f to width))
    }

    private fun drawEvents(marks: MutableList<Mark>, placed: List<Pair<Event, Float>>, m: Measure) {
        // Beams: runs of eighths and shorter within one beat.
        val groups = ArrayList<List<Pair<Note, Float>>>()
        var run = ArrayList<Pair<Note, Float>>()
        var runBeat = -1.0
        var at = 0.0
        val beat = if (m.time.beatType == 8 && m.time.beats % 3 == 0) 1.5 else 1.0
        for ((e, x) in placed) {
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
                        val to = if (up) min(top * 0.5f - STEM_LENGTH, 2f) else max(bottom * 0.5f + STEM_LENGTH, 2f)
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
            val up = g.map { it.first.steps.average() }.average() >= 4
            val headW = MusicGlyphs["noteheadBlack"].advance
            val xs = g.map { (n, x) -> if (up) x + headW - STEM / 2 else x + STEM / 2 }
            val ends = g.map { (n, _) -> if (up) n.steps.min() * 0.5f - STEM_LENGTH else n.steps.max() * 0.5f + STEM_LENGTH }
            val y0 = if (up) min(ends.first(), 1f) else max(ends.first(), 3f)
            val y1 = if (up) min(ends.last(), 1f) else max(ends.last(), 3f)
            val slope = ((y1 - y0) / (xs.last() - xs.first())).coerceIn(-0.25f, 0.25f)
            // The beam clears every note: moved away from the heads as far as the lowest (highest) needs.
            var shift = 0f
            g.forEachIndexed { i, (n, _) ->
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
            is Slab -> Fill.polygons(ink, listOf(FloatArray(8) { i -> if (i % 2 == 0) x + mark.points[i] * space else y + mark.points[i] * space }))
        }
    }
}
