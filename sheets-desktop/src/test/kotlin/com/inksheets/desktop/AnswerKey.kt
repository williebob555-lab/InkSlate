package com.inksheets.desktop

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.io.File

/**
 * The music on a page as its PDF says it is, where the notation program wrote the notes as
 * characters of a music font (Sibelius's Opus and Helsinki, MuseScore's Leland and Bravura):
 * every notehead, rest, clef, accidental and dot, what it is and exactly where - an answer key
 * for the music reader, from the library itself.
 */
object AnswerKey {
    enum class Kind { OTHER, HEAD_BLACK, HEAD_HALF, HEAD_WHOLE, REST_1, REST_2, REST_4, REST_8, REST_16, CLEF_G, CLEF_F, CLEF_C, FLAT, SHARP, NATURAL, DOT, FLAG_8, FLAG_16, FLAG_32 }

    /** One symbol: [x] its left edge, [y] its origin (a notehead's middle), in pixels at the page's drawing size. */
    data class Symbol(val kind: Kind, val x: Float, val y: Float, val width: Float, val text: String = "", val size: Float = 0f)

    /** The Sonata layout Opus and Helsinki keep, and SMuFL's code points (Leland, Bravura). */
    private val kinds: Map<Int, Kind> = mapOf(
        'œ'.code to Kind.HEAD_BLACK, '˙'.code to Kind.HEAD_HALF, 'w'.code to Kind.HEAD_WHOLE, '¿'.code to Kind.HEAD_BLACK, 0xE0A9 to Kind.HEAD_BLACK,
        '∑'.code to Kind.REST_1, 'Ó'.code to Kind.REST_2, 'Œ'.code to Kind.REST_4, '‰'.code to Kind.REST_8, '≈'.code to Kind.REST_16,
        '&'.code to Kind.CLEF_G, '?'.code to Kind.CLEF_F, 'B'.code to Kind.CLEF_C,
        'b'.code to Kind.FLAT, '#'.code to Kind.SHARP, 'n'.code to Kind.NATURAL, '.'.code to Kind.DOT,
        'j'.code to Kind.FLAG_8, 'J'.code to Kind.FLAG_8, 'k'.code to Kind.FLAG_16, 'K'.code to Kind.FLAG_16, 'r'.code to Kind.FLAG_16, 'R'.code to Kind.FLAG_16,
        0xE240 to Kind.FLAG_8, 0xE241 to Kind.FLAG_8, 0xE242 to Kind.FLAG_16, 0xE243 to Kind.FLAG_16, 0xE244 to Kind.FLAG_32, 0xE245 to Kind.FLAG_32,
        0xE0A4 to Kind.HEAD_BLACK, 0xE0A3 to Kind.HEAD_HALF, 0xE0A2 to Kind.HEAD_WHOLE,
        0xE4E3 to Kind.REST_1, 0xE4E4 to Kind.REST_2, 0xE4E5 to Kind.REST_4, 0xE4E6 to Kind.REST_8, 0xE4E7 to Kind.REST_16,
        0xE050 to Kind.CLEF_G, 0xE062 to Kind.CLEF_F, 0xE05C to Kind.CLEF_C,
        0xE260 to Kind.FLAT, 0xE262 to Kind.SHARP, 0xE261 to Kind.NATURAL, 0xE1E7 to Kind.DOT
    )

    /**
     * A music-font character its code does not name - a subset that renumbered its characters (a
     * second copy of Maestro whose "!" is a whole rest) - by its outline, as the app tells them
     * ([com.inksheets.core.omr.GlyphShapes]); null when it is none of the symbols the key names.
     */
    private fun byOutline(font: org.apache.pdfbox.pdmodel.font.PDFont, code: Int, name: String): Kind? {
        val contours = PdfPrinted.contours(font, code)?.map { c -> FloatArray(c.size) { c[it] * 4f } } ?: return null
        val family = com.inksheets.core.omr.Printed.familyOf(name)
        val m = family?.let { com.inksheets.core.omr.GlyphShapes.identify(it, contours) }?.let { (known, m) -> if (known) m ?: return null else null }
            ?: com.inksheets.core.omr.GlyphShapes.classify(contours) ?: return null
        return runCatching { Kind.valueOf(m.kind.name) }.getOrNull()
    }

    private fun special(name: String) = name.contains("special", true) && listOf("opus", "helsinki", "inkpen", "reprise").any { name.contains(it, true) }

    private fun musical(name: String) = listOf("opus", "helsinki", "leland", "bravura", "petrucci", "maestro", "sebastian", "gonville")
        .any { name.contains(it, true) } && listOf("text", "special", "metronome", "percussion", "chords", "ornaments", "figured", "function").none { name.contains(it, true) }

    /**
     * [file]'s page [index] (0-based) at [dpi]; null when it has no music font to go by, or its
     * characters cannot be told apart (a broken map gives every one the same, or "?").
     */
    fun read(file: File, index: Int, dpi: Float): List<Symbol>? = runCatching {
        Loader.loadPDF(file).use { doc ->
            // A page turned to be shown (rotation set) is read as shown: the stripper's places are.
            if (index >= doc.numberOfPages) return null
            val out = ArrayList<Symbol>()
            val codesToText = HashMap<String, HashMap<Int, String>>()
            // Each symbol's font and code, by its place in [out]: to name it by its outline if the font's map proves broken.
            val glyphOf = HashMap<Int, Triple<String, org.apache.pdfbox.pdmodel.font.PDFont, Int>>()
            val k = dpi / 72f
            object : PDFTextStripper() {
                // Every glyph as drawn, each once: the stripper's own grouping takes a glyph that looks like an
                // accent (a half head, "˙") for one and merges it into the character before it - an accent
                // mark over a half note lost the note.
                private val glyphs = ArrayList<TextPosition>()
                private val seen = HashSet<String>()
                override fun processTextPosition(text: TextPosition) {
                    if (seen.add("${text.font?.name}|${text.characterCodes?.firstOrNull()}|${Math.round(text.xDirAdj * 2)}|${Math.round(text.yDirAdj * 2)}")) glyphs += text
                }
                override fun writePage() = writeString("", glyphs)
                override fun writeString(text: String?, positions: MutableList<TextPosition>?) {
                    for (p in positions.orEmpty()) {
                        val fname = p.font?.name?.substringAfter('+') ?: continue
                        // The engraver's "special" font (Opus Special, Helsinki Special) holds dots
                        // among other marks, under codes a subset renumbers and names it scrambles:
                        // told by the glyph's own outline - small and round is a dot.
                        if (special(fname)) {
                            val code = p.characterCodes?.firstOrNull() ?: continue
                            val b = (p.font as? org.apache.pdfbox.pdmodel.font.PDVectorFont)?.let { vf -> runCatching { vf.getNormalizedPath(code).bounds2D }.getOrNull() } ?: continue
                            val size = p.textMatrix.scalingFactorX
                            val w = b.width / 1000f; val h = b.height / 1000f
                            if (w in 0.05..0.22 && h in 0.05..0.22 && w / h in 0.75..1.33) {
                                out += Symbol(Kind.DOT, (p.xDirAdj + (b.centerX / 1000f * size).toFloat()) * k, (p.yDirAdj - (b.centerY / 1000f * size).toFloat()) * k, (w * size).toFloat() * k, "special")
                            }
                            continue
                        }
                        if (!musical(fname)) continue
                        // A font with no map at all (a Mac's print to PDF sets each character by its
                        // glyph's number): every character told by its outline, as the app tells them.
                        val code0 = p.characterCodes?.firstOrNull()
                        if (code0 != null && runCatching { p.font.toUnicode(code0) }.getOrNull() == null) {
                            val kind = byOutline(p.font, code0, fname) ?: Kind.OTHER
                            out += Symbol(kind, p.xDirAdj * k, p.yDirAdj * k, p.widthDirAdj * k, "#$code0", p.textMatrix.scalingFactorX * k)
                            continue
                        }
                        // The font's own map: the stripper merges a dot over a note into its text.
                        val uni = p.characterCodes?.firstOrNull()?.let { c -> runCatching { p.font.toUnicode(c) }.getOrNull() } ?: p.unicode ?: continue
                        p.characterCodes?.firstOrNull()?.let { codesToText.getOrPut(fname) { HashMap() }[it] = uni }
                        if (uni.codePointCount(0, uni.length) != 1) continue
                        // A music-font character not known here is kept as OTHER: where something
                        // the key cannot name is printed (a flag in another code, an ornament).
                        val kind = kinds[uni.codePointAt(0)] ?: p.characterCodes?.firstOrNull()?.let { byOutline(p.font, it, fname) } ?: Kind.OTHER
                        p.characterCodes?.firstOrNull()?.let { glyphOf[out.size] = Triple(fname, p.font, it) }
                        out += Symbol(kind, p.xDirAdj * k, p.yDirAdj * k, p.widthDirAdj * k, uni, p.textMatrix.scalingFactorX * k)
                    }
                }
            }.apply { startPage = index + 1; endPage = index + 1; sortByPosition = false }.getText(doc)
            // A font whose different characters all read the same has no usable map: its characters
            // named by their outlines instead.
            val broken = codesToText.filterValues { m -> m.size >= 4 && m.values.toSet().size <= m.size / 2 }.keys
            if (broken.isNotEmpty()) for ((i, g) in glyphOf) if (g.first in broken) out[i] = out[i].copy(kind = byOutline(g.second, g.third, g.first) ?: Kind.OTHER, text = "#${g.third}")
            if (out.count { it.kind == Kind.HEAD_BLACK || it.kind == Kind.HEAD_HALF || it.kind == Kind.HEAD_WHOLE } < 8) null else out
        }
    }.getOrNull()
}
