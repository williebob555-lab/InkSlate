package com.inksheets.core.omr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * What a PDF says is printed on a page, where the program that engraved it wrote its notes as
 * characters of a music font (Sibelius's Opus and Helsinki, MuseScore's Leland and Bravura, and
 * others laid out the same way): every head, rest, accidental, dot and flag - exactly what and
 * exactly where - with the stems and beams it drew as lines. Half the parts in a band library are
 * like this; for them the reader takes these instead of finding them in the picture, and reads
 * them as surely as they were printed.
 *
 * Coordinates are points from the page's top left ([width] x [height] points); [scaled] turns them
 * into a picture's pixels. Read from the PDF by the platform (PDFBox on the desktop, its port on
 * Android), which hands each character and path here to be told what it is.
 */
class Printed(val width: Float, val height: Float, val symbols: List<Symbol>, val stems: List<Stem>, val beams: List<Beam>,
              val arcs: List<Arc> = emptyList(), val lines: List<Line> = emptyList(),
              /**
               * Found by the trained reader on a picture, not stated by a PDF: heads, rests, dots and
               * accidentals only, each with how sure it is - the rest (clefs, keys, time, barlines,
               * marks) still read from the picture.
               */
              val learned: Boolean = false) {
    enum class Kind {
        HEAD_BLACK, HEAD_HALF, HEAD_WHOLE,
        REST_1, REST_2, REST_4, REST_8, REST_16, REST_32,
        FLAT, SHARP, NATURAL, DOUBLE_FLAT, DOUBLE_SHARP,
        DOT, FLAG_8, FLAG_16, FLAG_32,
        CLEF_G, CLEF_F, CLEF_C,
        /** A time signature's digit (in the music font); [Symbol.digit] says which. */
        TIME_DIGIT,
        /** Common time (4/4) and cut time (2/2) signs. */
        TIME_COMMON, TIME_CUT,
        /** A mark on a note ([Symbol.name]: "accent", "staccato", "tenuto", "marcato", "staccatissimo", "fermata"). */
        ARTICULATION,
        /** A dynamic ([Symbol.name]: "p", "mf", "sfz" - or a letter, set with its neighbours into one). */
        DYNAMIC,
        /** A breath mark (a comma or tick over the staff) or a caesura: a breath taken there - the note before ends early. */
        BREATH,
        /** A bar-repeat sign (%): the bar before, played again. */
        BAR_REPEAT,
        /** A digit in a text font - a tuplet's number, among others (bar numbers, tempos). */
        TEXT_DIGIT,
        /** An italic word not a dynamic ([Symbol.name], lower case): "rit.", "a tempo", "cresc.", "legato". */
        WORD,
        /** A music-font character not named here: an articulation, an ornament, a flag in another code. */
        OTHER
    }

    /**
     * One character: [x] its left edge, [y] its origin (a head's middle), [width] its advance, [size]
     * the size it is set at (a cue or grace note's is smaller), [digit] the number it is, if one.
     */
    data class Symbol(val kind: Kind, val x: Float, val y: Float, val width: Float, val size: Float = 0f, val digit: Int = -1, val name: String = "",
                      /** Learned: a head's beams or flags (0-3), its dots, and how likely it is there at all. */
                      val beams: Int = -1, val dots: Int = -1, val confidence: Float = 1f,
                      /** Learned: the odds behind those (see [Note.odds]). */
                      val odds: List<Float> = emptyList())
    /** A stem: upright at [x], from [y0] (top) to [y1]. */
    data class Stem(val x: Float, val y0: Float, val y1: Float)
    /** A beam: a filled slab across [x0]..[x1], its middle at [y0] and [y1] at each end, [thick] thick. */
    data class Beam(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val thick: Float) {
        fun yAt(x: Float) = if (x1 == x0) y0 else y0 + (y1 - y0) * (x - x0) / (x1 - x0)
    }

    /**
     * A tie's or slur's curve: from ([x0], [y0]) to ([x1], [y1]) (its two ends), bowing [bulge]
     * at its middle - negative bowing up, positive down.
     */
    data class Arc(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val bulge: Float)
    /** A straight stroke that is not upright: a hairpin's side, among others. */
    data class Line(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

    /** The same in a picture of the page [pixelWidth] pixels wide. */
    fun scaled(pixelWidth: Int): Printed {
        val k = pixelWidth / width
        return Printed(width * k, height * k,
            symbols.map { it.copy(x = it.x * k, y = it.y * k, width = it.width * k, size = it.size * k) },
            stems.map { Stem(it.x * k, it.y0 * k, it.y1 * k) },
            beams.map { Beam(it.x0 * k, it.y0 * k, it.x1 * k, it.y1 * k, it.thick * k) },
            arcs.map { Arc(it.x0 * k, it.y0 * k, it.x1 * k, it.y1 * k, it.bulge * k) },
            lines.map { Line(it.x0 * k, it.y0 * k, it.x1 * k, it.y1 * k) }, learned)
    }

    val heads get() = symbols.filter { it.kind == Kind.HEAD_BLACK || it.kind == Kind.HEAD_HALF || it.kind == Kind.HEAD_WHOLE }

    companion object {
        /** The markings a word of dynamic letters can be. */
        val DYNAMICS = setOf("ppp", "pp", "p", "mp", "mf", "f", "ff", "fff", "fp", "sf", "sfz", "sfp", "sffz", "sfpp", "fz", "rf", "rfz", "sp")

        /** Enough music on the page to go by: heads, of more than one kind of character. */
        const val ENOUGH_HEADS = 8

        /** A font whose characters are notes: named for a music font, and not its text or chord companion. */
        fun isMusicFont(name: String): Boolean {
            val n = name.substringAfter('+')
            return listOf("opus", "helsinki", "leland", "bravura", "petrucci", "maestro", "sebastian", "gonville", "inkpen", "reprise")
                .any { n.contains(it, true) } && listOf("text", "special", "metronome", "percussion", "chords", "ornaments", "figured", "function").none { n.contains(it, true) }
        }

        /**
         * The companion "special" font (Opus Special, Helsinki Special) that holds dots among other
         * marks, under codes a subset renumbers: its characters are told by their outline instead.
         */
        fun isSpecialFont(name: String): Boolean {
            val n = name.substringAfter('+')
            return n.contains("special", true) && listOf("opus", "helsinki", "inkpen", "reprise").any { n.contains(it, true) }
        }

        /** A special font's character by its outline's size in ems (width, height): small and round is a dot. */
        fun specialKind(w: Float, h: Float): Kind? = if (w in 0.05f..0.22f && h in 0.05f..0.22f && w / h in 0.75f..1.33f) Kind.DOT else null

        /** A music font's character by its unicode: the Sonata layout (Opus, Helsinki, Maestro...) and SMuFL (Leland, Bravura). */
        fun kindOf(codePoint: Int): Kind = sonata[codePoint] ?: smufl[codePoint] ?: if (codePoint in '0'.code..'9'.code || codePoint in 0xE080..0xE089) Kind.TIME_DIGIT else Kind.OTHER

        /** Which family a music font is, for what its characters mean (the same letter means different things in different ones). */
        fun familyOf(font: String): String? = font.substringAfter('+').lowercase().let { n ->
            listOf("opus", "helsinki", "finalemaestro", "maestro", "leland", "bravura", "petrucci").firstOrNull { n.contains(it) }
        }

        /**
         * What a music-font character marks besides notes, by its family and unicode - each one seen
         * in the library's own PDFs, not taken on trust from a font's supposed layout (Opus's "p", for
         * one, is a tuplet's 3): an articulation or a dynamic (kind and name), or null.
         */
        fun markOf(family: String?, codePoint: Int): Pair<Kind, String>? {
            val smuflFont = family == "leland" || family == "bravura" || family == "finalemaestro" || codePoint >= 0xE000
            if (smuflFont) return smuflMarks[codePoint]
            return when (codePoint) {
                '>'.code -> Kind.ARTICULATION to "accent"
                '^'.code -> Kind.ARTICULATION to "marcato"
                '-'.code -> Kind.ARTICULATION to "tenuto"
                'U'.code, 'u'.code -> Kind.ARTICULATION to "fermata"
                else -> if (family == "maestro") maestroDynamics[codePoint]?.let { Kind.DYNAMIC to it } else null
            }
        }

        /** Maestro's dynamics, as its glyphs show them. */
        private val maestroDynamics = mapOf('p'.code to "p", 'f'.code to "f", 'F'.code to "mf", 0x0192 to "ff", 0x00CD to "fp")

        /** SMuFL's articulations, fermatas and dynamics (Leland's checked against the standard). */
        private val smuflMarks: Map<Int, Pair<Kind, String>> = buildMap {
            for ((cp, name) in listOf(0xE4A0 to "accent", 0xE4A1 to "accent", 0xE4A2 to "staccato", 0xE4A3 to "staccato", 0xE4A4 to "tenuto", 0xE4A5 to "tenuto",
                0xE4A6 to "staccatissimo", 0xE4A7 to "staccatissimo", 0xE4AC to "marcato", 0xE4AD to "marcato", 0xE4C0 to "fermata", 0xE4C1 to "fermata")) put(cp, Kind.ARTICULATION to name)
            // An accent and a staccato in one character: both marks.
            put(0xE4B0, Kind.ARTICULATION to "accent+staccato"); put(0xE4B1, Kind.ARTICULATION to "accent+staccato")
            for ((cp, text) in listOf(0xE520 to "p", 0xE521 to "m", 0xE522 to "f", 0xE523 to "r", 0xE524 to "s", 0xE525 to "z",
                0xE52A to "ppp", 0xE52B to "pp", 0xE52C to "mp", 0xE52D to "mf", 0xE52E to "pf", 0xE52F to "ff", 0xE530 to "fff",
                0xE534 to "fp", 0xE535 to "fz", 0xE536 to "sf", 0xE537 to "sfp", 0xE539 to "sfz", 0xE53B to "sffz", 0xE53D to "rf")) put(cp, Kind.DYNAMIC to text)
        }

        /** The number a time-signature digit stands for. */
        fun digitOf(codePoint: Int): Int = when (codePoint) { in '0'.code..'9'.code -> codePoint - '0'.code; in 0xE080..0xE089 -> codePoint - 0xE080; else -> -1 }

        /**
         * The cue and grace notes among [heads]: set clearly smaller than most of those round them on
         * their own staff - not the page's: a PDF may state one line's notes at a smaller size and
         * scale them back up (an opening line set so), all of them the line's own notes.
         */
        fun small(heads: List<Symbol>): Set<Symbol> {
            if (heads.isEmpty()) return emptySet()
            val pageNormal = heads.map { it.size }.sorted()[heads.size / 2]
            val byY = heads.sortedBy { it.y }
            return heads.filter { h ->
                // Those within a staff's reach of it, up and down (its own staff, and no other).
                val near = byY.filter { abs(it.y - h.y) < pageNormal * 2.5f }.map { it.size }.sorted()
                val normal = if (near.size >= 4) near[near.size / 2] else pageNormal
                h.size < normal * 0.85f
            }.toSet()
        }

        private val sonata: Map<Int, Kind> = mapOf(
            'œ'.code to Kind.HEAD_BLACK, '˙'.code to Kind.HEAD_HALF, 'w'.code to Kind.HEAD_WHOLE,
            // An X head (a spoken note, a hi-hat): a filled head's value.
            '¿'.code to Kind.HEAD_BLACK,
            // A breath mark: the music font's comma (only ever read in the music font, never a word's).
            ','.code to Kind.BREATH,
            '∑'.code to Kind.REST_1, 'Ó'.code to Kind.REST_2, 'Œ'.code to Kind.REST_4, '‰'.code to Kind.REST_8, '≈'.code to Kind.REST_16,
            '&'.code to Kind.CLEF_G, '?'.code to Kind.CLEF_F, 'B'.code to Kind.CLEF_C,
            'c'.code to Kind.TIME_COMMON, 'C'.code to Kind.TIME_CUT,
            'b'.code to Kind.FLAT, '#'.code to Kind.SHARP, 'n'.code to Kind.NATURAL, '.'.code to Kind.DOT,
            'j'.code to Kind.FLAG_8, 'J'.code to Kind.FLAG_8, 'k'.code to Kind.FLAG_16, 'K'.code to Kind.FLAG_16,
            // The sixteenth's flag, up and down (Maestro, Opus).
            'r'.code to Kind.FLAG_16, 'R'.code to Kind.FLAG_16
        )
        private val smufl: Map<Int, Kind> = mapOf(
            0xE240 to Kind.FLAG_8, 0xE241 to Kind.FLAG_8, 0xE242 to Kind.FLAG_16, 0xE243 to Kind.FLAG_16, 0xE244 to Kind.FLAG_32, 0xE245 to Kind.FLAG_32,
            0xE0A4 to Kind.HEAD_BLACK, 0xE0A3 to Kind.HEAD_HALF, 0xE0A2 to Kind.HEAD_WHOLE, 0xE0A9 to Kind.HEAD_BLACK,
            0xE4E3 to Kind.REST_1, 0xE4E4 to Kind.REST_2, 0xE4E5 to Kind.REST_4, 0xE4E6 to Kind.REST_8, 0xE4E7 to Kind.REST_16, 0xE4E8 to Kind.REST_32,
            0xE050 to Kind.CLEF_G, 0xE062 to Kind.CLEF_F, 0xE05C to Kind.CLEF_C, 0xE08A to Kind.TIME_COMMON, 0xE08B to Kind.TIME_CUT,
            0xE260 to Kind.FLAT, 0xE262 to Kind.SHARP, 0xE261 to Kind.NATURAL, 0xE264 to Kind.DOUBLE_FLAT, 0xE263 to Kind.DOUBLE_SHARP, 0xE1E7 to Kind.DOT,
            // Breath marks (comma, tick, up-bow-like) and caesuras.
            0xE4CE to Kind.BREATH, 0xE4CF to Kind.BREATH, 0xE4D0 to Kind.BREATH, 0xE4D1 to Kind.BREATH, 0xE4D2 to Kind.BREATH, 0xE4D3 to Kind.BREATH
        )

        /**
         * Of a page's characters, whether the font's map can be trusted: one whose different codes
         * all read the same (or "?") says nothing. [codesToText] per font: code -> text it maps to.
         */
        fun mapsWell(codesToText: Map<String, Map<Int, String>>) = codesToText.values.none { m -> m.size >= 4 && m.values.toSet().size <= m.size / 2 }
    }

    /**
     * Builds a [Printed] from what a PDF engine hands over - characters with their fonts, filled and
     * stroked paths - in points from the page's top left.
     */
    class Builder(private val width: Float, private val height: Float) {
        private val symbols = ArrayList<Symbol>()
        private val stems = ArrayList<Stem>()
        private val beams = ArrayList<Beam>()
        private val arcs = ArrayList<Arc>()
        private val letters = ArrayList<Symbol>()
        private val lines = ArrayList<Line>()
        private val codesToText = HashMap<String, HashMap<Int, String>>()

        /** A character of font [font], code [code], mapped to [text], at ([x], [y]) (its origin), [advance] wide, set at [size]. */
        /**
         * True when it was named; false for a music-font character its code says nothing about - to
         * be told by its outline instead ([glyph]).
         */
        fun char(font: String, code: Int, text: String?, x: Float, y: Float, advance: Float, size: Float): Boolean {
            if (text == null || text.codePointCount(0, text.length) != 1) return !isMusicFont(font)
            val cp = text.codePointAt(0)
            if (!isMusicFont(font)) {
                // Digits in words: a tuplet's number is one of them.
                if (cp in '0'.code..'9'.code) symbols += Symbol(Kind.TEXT_DIGIT, x, y, advance, size, cp - '0'.code)
                // Italic letters: dynamics set as words (ff, mf, sfp), told from other words once all are in.
                else if (font.contains("italic", true) && Character.isLetter(cp)) letters += Symbol(Kind.OTHER, x, y, advance, size,
                    // A ligature ("ﬀ") as the letters it joins.
                    name = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC))
                return true
            }
            codesToText.getOrPut(font) { HashMap() }[code] = text
            val family = familyOf(font)
            val kind = kindOf(cp)
            if (kind != Kind.OTHER) { symbols += Symbol(kind, x, y, advance, size, digitOf(cp)); return true }
            // Opus's "p" is a tuplet's italic 3; its left quotation mark, the bar-repeat sign.
            if (family == "opus" || family == "helsinki") when (cp) {
                'p'.code -> { symbols += Symbol(Kind.TEXT_DIGIT, x, y, advance, size, 3); return true }
                0x2018 -> { symbols += Symbol(Kind.BAR_REPEAT, x, y, advance, size); return true }
            }
            val mark = markOf(family, cp) ?: return false
            symbols += Symbol(mark.first, x, y, advance, size, name = mark.second)
            return true
        }

        /**
         * A music-font character nothing could name by its code, by its outline: [contours] in ems (y
         * down, from the character's origin), set at [size], its origin at ([x], [y]). A wide, thin bow
         * is a tie or a slur (engravers set them as characters of their fonts); anything else is told
         * by its shape against the symbols known ([GlyphShapes]); nothing at all where it is like none.
         */
        fun glyph(font: String, contours: List<FloatArray>, x: Float, y: Float, size: Float) {
            if (contours.isEmpty() || size <= 0f) return
            // First, the shapes this font is known to draw (labelled from the library): exact.
            val family = familyOf(font)?.let { if (isSpecialFont(font)) "$it-special" else it }
            if (family != null) {
                val (known, m) = GlyphShapes.identify(family, contours.map { c -> FloatArray(c.size) { c[it] * 4f } })
                if (known) {
                    if (m != null) {
                        var mx0 = Float.MAX_VALUE; var mx1 = -Float.MAX_VALUE
                        for (c in contours) for (i in c.indices step 2) { mx0 = min(mx0, c[i]); mx1 = max(mx1, c[i]) }
                        symbols += Symbol(m.kind, x + mx0 * size, y, (mx1 - mx0) * size, size, m.digit, m.name)
                    }
                    return
                }
            }
            var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
            for (c in contours) for (i in c.indices step 2) { x0 = min(x0, c[i]); x1 = max(x1, c[i]); y0 = min(y0, c[i + 1]); y1 = max(y1, c[i + 1]) }
            val w = x1 - x0; val h = y1 - y0
            if (w <= 0f || h <= 0f) return
            // A bow: over a quarter of an em wide (a space), under half as tall as wide.
            if (w > 0.25f && h < w * 0.45f) {
                val pts = contours.flatMap { c -> (c.indices step 2).map { c[it] to c[it + 1] } }
                curve(FloatArray(pts.size) { x + pts[it].first * size }, FloatArray(pts.size) { y + pts[it].second * size })
                return
            }
            val m = GlyphShapes.classify(contours.map { c -> FloatArray(c.size) { c[it] * 4f } }) ?: return
            symbols += Symbol(m.kind, x + x0 * size, y, w * size, size, m.digit, m.name)
        }

        /** A special font's character whose outline measures [w] x [h] ems, centred at ([cx], [cy]). */
        fun specialChar(font: String, w: Float, h: Float, cx: Float, cy: Float, size: Float) {
            if (!isSpecialFont(font)) return
            specialKind(w, h)?.let { symbols += Symbol(it, cx, cy, w * size, size) }
        }

        /** A stroked straight line from ([x0], [y0]) to ([x1], [y1]): upright and long, a stem (or a barline). */
        /** A stroked line through several points: bowed, a tie or slur drawn as a line; otherwise its straight pieces. */
        fun polyline(xs: FloatArray, ys: FloatArray) {
            if (xs.size >= 5) {
                val a = 0; val b = xs.size - 1
                val dx = xs[b] - xs[a]; val dy = ys[b] - ys[a]; val len = kotlin.math.hypot(dx, dy)
                // How far the points stand off the straight line between its ends.
                val off = if (len < 1e-3f) 0f else xs.indices.maxOf { abs((xs[it] - xs[a]) * dy - (ys[it] - ys[a]) * dx) / len }
                if (abs(dx) > 3f && off > 0.3f) { curve(xs, ys); return }
            }
            for (i in 1 until xs.size) line(xs[i - 1], ys[i - 1], xs[i], ys[i])
        }

        fun line(x0: Float, y0: Float, x1: Float, y1: Float) {
            if (abs(x0 - x1) < 0.4f && abs(y0 - y1) > 2f) stems += Stem((x0 + x1) / 2, min(y0, y1), max(y0, y1))
            else if (abs(x1 - x0) > 3f && abs(y1 - y0) < abs(x1 - x0)) lines += if (x0 <= x1) Line(x0, y0, x1, y1) else Line(x1, y1, x0, y0)
        }

        /**
         * A filled outline with curves in it, through [xs], [ys] (its points and the curves' control
         * points): wide and shallow is a tie or a slur - its ends the outline's leftmost and
         * rightmost points, its bulge how far its middle stands off the line between them.
         */
        fun curve(xs: FloatArray, ys: FloatArray) {
            if (xs.size < 6) return
            val left = xs.indices.minBy { xs[it] }; val right = xs.indices.maxBy { xs[it] }
            val w = xs[right] - xs[left]; val h = ys.max() - ys.min()
            if (w < 3f || h > w * 0.8f || h < 0.3f) return
            val mid = (xs[left] + xs[right]) / 2; val base = (ys[left] + ys[right]) / 2
            // The points near the middle: which side of the ends' line they lie, and how far.
            val near = xs.indices.filter { abs(xs[it] - mid) < w * 0.2f }
            if (near.isEmpty()) return
            val up = near.minOf { ys[it] } - base; val down = near.maxOf { ys[it] } - base
            val bulge = if (-up > down) up else down
            arcs += Arc(xs[left], ys[left], xs[right], ys[right], bulge)
        }

        /** A filled outline through [xs], [ys] (its corners): a thin upright bar is a stem, a slab wider than tall a beam. */
        fun fill(xs: FloatArray, ys: FloatArray) {
            // Many corners: a curve drawn as a polygon (how some engravers write their ties and slurs).
            if (xs.size > 10) { curve(xs, ys); return }
            if (xs.size !in 4..10) return
            val wx = xs.max() - xs.min(); val hy = ys.max() - ys.min()
            when {
                wx < 1f && hy > 2f -> stems += Stem((xs.max() + xs.min()) / 2, ys.min(), ys.max())
                wx > 2f -> {
                    val left = xs.indices.filter { abs(xs[it] - xs.min()) < 0.4f }; val right = xs.indices.filter { abs(xs[it] - xs.max()) < 0.4f }
                    if (left.size >= 2 && right.size >= 2) {
                        val t = left.maxOf { ys[it] } - left.minOf { ys[it] }
                        if (t in 0.5f..8f) beams += Beam(xs.min(), left.map { ys[it] }.average().toFloat(), xs.max(), right.map { ys[it] }.average().toFloat(), t)
                    }
                }
            }
        }

        /** The page, or null when its music is not written as characters that can be told apart. */
        fun build(): Printed? {
            if (!mapsWell(codesToText)) return null
            // Italic words that are dynamics - every letter one of a dynamic's, the whole a dynamic.
            val byLine = letters.sortedWith(compareBy({ it.y }, { it.x }))
            var i = 0
            while (i < byLine.size) {
                var j = i
                while (j + 1 < byLine.size && abs(byLine[j + 1].y - byLine[i].y) < byLine[i].size * 0.3f &&
                    byLine[j + 1].x - (byLine[j].x + byLine[j].width) < byLine[i].size * 0.35f && byLine[j + 1].x >= byLine[j].x) j++
                val word = (i..j).joinToString("") { byLine[it].name }
                if (word in DYNAMICS) symbols += Symbol(Kind.DYNAMIC, byLine[i].x, byLine[i].y, byLine[j].x + byLine[j].width - byLine[i].x, byLine[i].size, name = word)
                else if (word.length >= 2) symbols += Symbol(Kind.WORD, byLine[i].x, byLine[i].y, byLine[j].x + byLine[j].width - byLine[i].x, byLine[i].size, name = word.lowercase())
                i = j + 1
            }
            // A curve drawn twice (filled, then its outline stroked) is one curve.
            val once = ArrayList<Arc>()
            for (a in arcs) if (once.none { o -> abs(o.x0 - a.x0) < 1f && abs(o.x1 - a.x1) < 1f && abs(o.y0 - a.y0) < 1f && abs(o.y1 - a.y1) < 1f }) once += a
            val p = Printed(width, height, symbols, stems, beams, once, lines)
            return if (p.heads.size < ENOUGH_HEADS) null else p
        }
    }
}
