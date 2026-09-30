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
class Printed(val width: Float, val height: Float, val symbols: List<Symbol>, val stems: List<Stem>, val beams: List<Beam>) {
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
        /** A dynamic's letter ([Symbol.name]: p, m, f, s, z, r), set with its neighbours into "mf", "sfz". */
        DYNAMIC,
        /** A digit in a text font - a tuplet's number, among others (bar numbers, tempos). */
        TEXT_DIGIT,
        /** A music-font character not named here: an articulation, an ornament, a flag in another code. */
        OTHER
    }

    /**
     * One character: [x] its left edge, [y] its origin (a head's middle), [width] its advance, [size]
     * the size it is set at (a cue or grace note's is smaller), [digit] the number it is, if one.
     */
    data class Symbol(val kind: Kind, val x: Float, val y: Float, val width: Float, val size: Float = 0f, val digit: Int = -1, val name: String = "")
    /** A stem: upright at [x], from [y0] (top) to [y1]. */
    data class Stem(val x: Float, val y0: Float, val y1: Float)
    /** A beam: a filled slab across [x0]..[x1], its middle at [y0] and [y1] at each end, [thick] thick. */
    data class Beam(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val thick: Float) {
        fun yAt(x: Float) = if (x1 == x0) y0 else y0 + (y1 - y0) * (x - x0) / (x1 - x0)
    }

    /** The same in a picture of the page [pixelWidth] pixels wide. */
    fun scaled(pixelWidth: Int): Printed {
        val k = pixelWidth / width
        return Printed(width * k, height * k,
            symbols.map { it.copy(x = it.x * k, y = it.y * k, width = it.width * k, size = it.size * k) },
            stems.map { Stem(it.x * k, it.y0 * k, it.y1 * k) },
            beams.map { Beam(it.x0 * k, it.y0 * k, it.x1 * k, it.y1 * k, it.thick * k) })
    }

    val heads get() = symbols.filter { it.kind == Kind.HEAD_BLACK || it.kind == Kind.HEAD_HALF || it.kind == Kind.HEAD_WHOLE }

    companion object {
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

        /** The number a time-signature digit stands for. */
        fun digitOf(codePoint: Int): Int = when (codePoint) { in '0'.code..'9'.code -> codePoint - '0'.code; in 0xE080..0xE089 -> codePoint - 0xE080; else -> -1 }

        /** The cue and grace notes among [heads]: set clearly smaller than most. */
        fun small(heads: List<Symbol>): Set<Symbol> {
            if (heads.isEmpty()) return emptySet()
            val normal = heads.map { it.size }.sorted()[heads.size / 2]
            return heads.filter { it.size < normal * 0.85f }.toSet()
        }

        private val sonata: Map<Int, Kind> = mapOf(
            'œ'.code to Kind.HEAD_BLACK, '˙'.code to Kind.HEAD_HALF, 'w'.code to Kind.HEAD_WHOLE,
            // An X head (a spoken note, a hi-hat): a filled head's value.
            '¿'.code to Kind.HEAD_BLACK,
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
            0xE260 to Kind.FLAT, 0xE262 to Kind.SHARP, 0xE261 to Kind.NATURAL, 0xE264 to Kind.DOUBLE_FLAT, 0xE263 to Kind.DOUBLE_SHARP, 0xE1E7 to Kind.DOT
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
        private val codesToText = HashMap<String, HashMap<Int, String>>()

        /** A character of font [font], code [code], mapped to [text], at ([x], [y]) (its origin), [advance] wide, set at [size]. */
        fun char(font: String, code: Int, text: String?, x: Float, y: Float, advance: Float, size: Float) {
            if (text == null || text.codePointCount(0, text.length) != 1) return
            val cp = text.codePointAt(0)
            if (!isMusicFont(font)) {
                // Digits in words: a tuplet's number is one of them.
                if (cp in '0'.code..'9'.code) symbols += Symbol(Kind.TEXT_DIGIT, x, y, advance, size, cp - '0'.code)
                return
            }
            codesToText.getOrPut(font) { HashMap() }[code] = text
            symbols += Symbol(kindOf(cp), x, y, advance, size, digitOf(cp))
        }

        /** A special font's character whose outline measures [w] x [h] ems, centred at ([cx], [cy]). */
        fun specialChar(font: String, w: Float, h: Float, cx: Float, cy: Float, size: Float) {
            if (!isSpecialFont(font)) return
            specialKind(w, h)?.let { symbols += Symbol(it, cx, cy, w * size, size) }
        }

        /** A stroked straight line from ([x0], [y0]) to ([x1], [y1]): upright and long, a stem (or a barline). */
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) {
            if (abs(x0 - x1) < 0.4f && abs(y0 - y1) > 2f) stems += Stem((x0 + x1) / 2, min(y0, y1), max(y0, y1))
        }

        /** A filled outline through [xs], [ys] (its corners): a thin upright bar is a stem, a slab wider than tall a beam. */
        fun fill(xs: FloatArray, ys: FloatArray) {
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
            val p = Printed(width, height, symbols, stems, beams)
            return if (p.heads.size < ENOUGH_HEADS) null else p
        }
    }
}
