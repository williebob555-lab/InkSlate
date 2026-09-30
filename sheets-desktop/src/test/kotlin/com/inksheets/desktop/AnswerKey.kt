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
    enum class Kind { HEAD_BLACK, HEAD_HALF, HEAD_WHOLE, REST_1, REST_2, REST_4, REST_8, REST_16, CLEF_G, CLEF_F, CLEF_C, FLAT, SHARP, NATURAL, DOT }

    /** One symbol: [x] its left edge, [y] its origin (a notehead's middle), in pixels at the page's drawing size. */
    data class Symbol(val kind: Kind, val x: Float, val y: Float, val width: Float)

    /** The Sonata layout Opus and Helsinki keep, and SMuFL's code points (Leland, Bravura). */
    private val kinds: Map<Int, Kind> = mapOf(
        'œ'.code to Kind.HEAD_BLACK, '˙'.code to Kind.HEAD_HALF, 'w'.code to Kind.HEAD_WHOLE,
        '∑'.code to Kind.REST_1, 'Ó'.code to Kind.REST_2, 'Œ'.code to Kind.REST_4, '‰'.code to Kind.REST_8, '≈'.code to Kind.REST_16,
        '&'.code to Kind.CLEF_G, '?'.code to Kind.CLEF_F, 'B'.code to Kind.CLEF_C,
        'b'.code to Kind.FLAT, '#'.code to Kind.SHARP, 'n'.code to Kind.NATURAL, '.'.code to Kind.DOT,
        0xE0A4 to Kind.HEAD_BLACK, 0xE0A3 to Kind.HEAD_HALF, 0xE0A2 to Kind.HEAD_WHOLE,
        0xE4E3 to Kind.REST_1, 0xE4E4 to Kind.REST_2, 0xE4E5 to Kind.REST_4, 0xE4E6 to Kind.REST_8, 0xE4E7 to Kind.REST_16,
        0xE050 to Kind.CLEF_G, 0xE062 to Kind.CLEF_F, 0xE05C to Kind.CLEF_C,
        0xE260 to Kind.FLAT, 0xE262 to Kind.SHARP, 0xE261 to Kind.NATURAL, 0xE1E7 to Kind.DOT
    )

    private fun musical(name: String) = listOf("opus", "helsinki", "leland", "bravura", "petrucci", "maestro", "sebastian", "gonville")
        .any { name.contains(it, true) } && listOf("text", "special", "metronome", "percussion", "chords", "ornaments", "figured", "function").none { name.contains(it, true) }

    /**
     * [file]'s page [index] (0-based) at [dpi]; null when it has no music font to go by, or its
     * characters cannot be told apart (a broken map gives every one the same, or "?").
     */
    fun read(file: File, index: Int, dpi: Float): List<Symbol>? = runCatching {
        Loader.loadPDF(file).use { doc ->
            if (index >= doc.numberOfPages || doc.getPage(index).rotation != 0) return null
            val out = ArrayList<Symbol>()
            val codesToText = HashMap<String, HashMap<Int, String>>()
            val k = dpi / 72f
            object : PDFTextStripper() {
                override fun writeString(text: String?, positions: MutableList<TextPosition>?) {
                    for (p in positions.orEmpty()) {
                        val fname = p.font?.name?.substringAfter('+') ?: continue
                        if (!musical(fname)) continue
                        val uni = p.unicode ?: continue
                        p.characterCodes?.firstOrNull()?.let { codesToText.getOrPut(fname) { HashMap() }[it] = uni }
                        val kind = kinds[uni.codePointAt(0)] ?: continue
                        if (uni.codePointCount(0, uni.length) != 1) continue
                        out += Symbol(kind, p.xDirAdj * k, p.yDirAdj * k, p.widthDirAdj * k)
                    }
                }
            }.apply { startPage = index + 1; endPage = index + 1; sortByPosition = false }.getText(doc)
            // A font whose different characters all read the same has no usable map.
            val broken = codesToText.values.any { m -> m.size >= 4 && m.values.toSet().size <= m.size / 2 }
            if (broken || out.count { it.kind == Kind.HEAD_BLACK || it.kind == Kind.HEAD_HALF || it.kind == Kind.HEAD_WHOLE } < 8) null else out
        }
    }.getOrNull()
}
