package com.inksheets.core.omr

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A music-font character told by its shape: its outline drawn at a fixed scale and laid over each
 * known symbol's (Bravura's), the best overlap taken. For a PDF whose font says nothing useful about
 * its characters - a subset renumbering them, a map scrambled, a companion "special" font - what is
 * printed is still known by what it looks like. A music font's em is four staff spaces, so a
 * symbol's size counts as well as its shape (a dot is not a whole note).
 */
object GlyphShapes {
    /** What a shape is taken for: its kind, and the articulation or dynamic letter or digit it is. */
    data class Match(val kind: Printed.Kind, val name: String, val score: Float, val digit: Int = -1)

    private data class Candidate(val glyph: String, val kind: Printed.Kind, val name: String = "", val digit: Int = -1)

    private val candidates: List<Candidate> by lazy {
        listOf(
            Candidate("noteheadBlack", Printed.Kind.HEAD_BLACK), Candidate("noteheadHalf", Printed.Kind.HEAD_HALF),
            Candidate("noteheadWhole", Printed.Kind.HEAD_WHOLE), Candidate("noteheadXBlack", Printed.Kind.HEAD_BLACK, "x"),
            Candidate("restWhole", Printed.Kind.REST_1), Candidate("restQuarter", Printed.Kind.REST_4),
            Candidate("rest8th", Printed.Kind.REST_8), Candidate("rest16th", Printed.Kind.REST_16), Candidate("rest32nd", Printed.Kind.REST_32),
            Candidate("flag8thUp", Printed.Kind.FLAG_8), Candidate("flag8thDown", Printed.Kind.FLAG_8),
            Candidate("flag16thUp", Printed.Kind.FLAG_16), Candidate("flag16thDown", Printed.Kind.FLAG_16),
            Candidate("flag32ndUp", Printed.Kind.FLAG_32), Candidate("flag32ndDown", Printed.Kind.FLAG_32),
            Candidate("accidentalFlat", Printed.Kind.FLAT), Candidate("accidentalSharp", Printed.Kind.SHARP), Candidate("accidentalNatural", Printed.Kind.NATURAL),
            Candidate("accidentalDoubleSharp", Printed.Kind.DOUBLE_SHARP), Candidate("accidentalDoubleFlat", Printed.Kind.DOUBLE_FLAT),
            Candidate("augmentationDot", Printed.Kind.DOT),
            Candidate("gClef", Printed.Kind.CLEF_G), Candidate("fClef", Printed.Kind.CLEF_F), Candidate("cClef", Printed.Kind.CLEF_C),
            Candidate("timeSigCommon", Printed.Kind.TIME_COMMON), Candidate("timeSigCutCommon", Printed.Kind.TIME_CUT),
            Candidate("articAccentAbove", Printed.Kind.ARTICULATION, "accent"), Candidate("articAccentBelow", Printed.Kind.ARTICULATION, "accent"),
            Candidate("articTenutoAbove", Printed.Kind.ARTICULATION, "tenuto"),
            Candidate("articStaccatissimoAbove", Printed.Kind.ARTICULATION, "staccatissimo"), Candidate("articStaccatissimoBelow", Printed.Kind.ARTICULATION, "staccatissimo"),
            Candidate("articMarcatoAbove", Printed.Kind.ARTICULATION, "marcato"), Candidate("articMarcatoBelow", Printed.Kind.ARTICULATION, "marcato"),
            Candidate("fermataAbove", Printed.Kind.ARTICULATION, "fermata"), Candidate("fermataBelow", Printed.Kind.ARTICULATION, "fermata"),
            Candidate("dynamicPiano", Printed.Kind.DYNAMIC, "p"), Candidate("dynamicMezzo", Printed.Kind.DYNAMIC, "m"),
            Candidate("dynamicForte", Printed.Kind.DYNAMIC, "f"), Candidate("dynamicRinforzando", Printed.Kind.DYNAMIC, "r"),
            Candidate("dynamicSforzando", Printed.Kind.DYNAMIC, "s"), Candidate("dynamicZ", Printed.Kind.DYNAMIC, "z"),
            Candidate("dynamicPP", Printed.Kind.DYNAMIC, "pp"), Candidate("dynamicPPP", Printed.Kind.DYNAMIC, "ppp"), Candidate("dynamicMP", Printed.Kind.DYNAMIC, "mp"),
            Candidate("dynamicMF", Printed.Kind.DYNAMIC, "mf"), Candidate("dynamicFF", Printed.Kind.DYNAMIC, "ff"), Candidate("dynamicFFF", Printed.Kind.DYNAMIC, "fff"),
            Candidate("dynamicFortePiano", Printed.Kind.DYNAMIC, "fp"), Candidate("dynamicSforzando1", Printed.Kind.DYNAMIC, "sf"),
            Candidate("dynamicSforzandoPiano", Printed.Kind.DYNAMIC, "sfp"), Candidate("dynamicSforzato", Printed.Kind.DYNAMIC, "sfz"), Candidate("dynamicForzando", Printed.Kind.DYNAMIC, "fz")
        ) + (0..9).map { Candidate("timeSig$it", Printed.Kind.TIME_DIGIT, digit = it) }
    }

    /** Pixels to a staff space the shapes are compared at. */
    private const val SCALE = 12f

    /** A shape drawn from its outlines (staff spaces, y down), cropped to its ink. */
    private class Raster(val w: Int, val h: Int, val bits: BooleanArray, val widthSp: Float, val heightSp: Float)

    private fun raster(contours: List<FloatArray>): Raster? {
        if (contours.isEmpty()) return null
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (c in contours) for (i in c.indices step 2) { x0 = min(x0, c[i]); x1 = max(x1, c[i]); y0 = min(y0, c[i + 1]); y1 = max(y1, c[i + 1]) }
        if (x1 <= x0 || y1 <= y0) return null
        val w = ((x1 - x0) * SCALE).roundToInt() + 2; val h = ((y1 - y0) * SCALE).roundToInt() + 2
        if (w > 200 || h > 200) return null
        val ink = Ink(w, h)
        Fill.polygons(ink, contours.map { c -> FloatArray(c.size) { i -> if (i % 2 == 0) (c[i] - x0) * SCALE + 1 else (c[i] - y0) * SCALE + 1 } })
        return Raster(w, h, BooleanArray(w * h) { ink[it % w, it / w] }, x1 - x0, y1 - y0)
    }

    private val known: List<Pair<Candidate, Raster>> by lazy {
        candidates.mapNotNull { c -> raster(MusicGlyphs[c.glyph].polygons(1f, 0f, 0f))?.let { c to it } }
    }

    /** Overlap of two shapes laid top-left to top-left (a pixel's shift either way allowed): shared ink over all ink. */
    private fun overlap(a: Raster, b: Raster): Float {
        var best = 0f
        for (dy in -1..1) for (dx in -1..1) {
            var both = 0; var either = 0
            val w = max(a.w, b.w + dx); val h = max(a.h, b.h + dy)
            for (y in 0 until h) for (x in 0 until w) {
                val ia = x < a.w && y < a.h && a.bits[y * a.w + x]
                val bx = x - dx; val by = y - dy
                val ib = bx in 0 until b.w && by in 0 until b.h && b.bits[by * b.w + bx]
                if (ia && ib) both++
                if (ia || ib) either++
            }
            if (either > 0) best = max(best, both.toFloat() / either)
        }
        return best
    }

    /** A labelled shape a font draws that no PDF names (see omr/fontglyphs-unnamed.txt): its family, what it is, its raster. */
    private class Labelled(val family: String, val label: String, val raster: Raster)

    private val labelled: List<Labelled> by lazy {
        val text = GlyphShapes::class.java.getResourceAsStream("/omr/fontglyphs-unnamed.txt")?.bufferedReader()?.readText() ?: return@lazy emptyList()
        text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { l ->
            val p = l.split(' ', limit = 4)
            if (p.size < 4 || p[1] == "?") return@mapNotNull null
            val contours = p[3].split('|').map { c -> c.split(',').map { it.toFloat() }.toFloatArray() }
            raster(contours)?.let { Labelled(p[0], p[1], it) }
        }
    }

    /** What a label says a shape is; null for one to leave be (a parenthesis, a line's piece). */
    private fun meaning(label: String): Match? {
        if (label == "IGNORE") return null
        val (kind, name) = label.split(':').let { it[0] to it.getOrElse(1) { "" } }
        return when (kind) {
            "HEAD_X" -> Match(Printed.Kind.HEAD_BLACK, "x", 1f)
            "TIME_DIGIT" -> Match(Printed.Kind.TIME_DIGIT, "", 1f, name.toIntOrNull() ?: -1)
            else -> runCatching { Match(Printed.Kind.valueOf(kind), name, 1f) }.getOrNull()
        }
    }

    /**
     * A character of font [family] (see [Printed.familyOf]; "-special" for its special font) that
     * its PDF does not name, by its outline ([contours], staff spaces, y down): the shape the font
     * draws it with, as labelled from the library. Returns (known, meaning): known false when this
     * font draws nothing like it (to be told another way), meaning null for a shape to leave be.
     */
    fun identify(family: String, contours: List<FloatArray>): Pair<Boolean, Match?> {
        val r = raster(contours) ?: return false to null
        val best = labelled.filter { it.family == family }.maxByOrNull { overlap(r, it.raster) } ?: return false to null
        return if (overlap(r, best.raster) >= 0.85f) true to meaning(best.label) else false to null
    }

    /**
     * What a character whose outline is [contours] (staff spaces, y down: an em is four) is
     * taken for, or null when it is like none of them well enough ([least] overlap).
     */
    fun classify(contours: List<FloatArray>, least: Float = 0.62f): Match? {
        val r = raster(contours) ?: return null
        var best: Match? = null
        for ((c, k) in known) {
            // Sizes far apart are not the same symbol.
            if (r.widthSp > k.widthSp * 1.5f || r.widthSp < k.widthSp / 1.5f || r.heightSp > k.heightSp * 1.5f || r.heightSp < k.heightSp / 1.5f) continue
            val s = overlap(r, k)
            if (s >= least && (best == null || s > best.score)) best = Match(c.kind, c.name, s, c.digit)
        }
        return best
    }
}
