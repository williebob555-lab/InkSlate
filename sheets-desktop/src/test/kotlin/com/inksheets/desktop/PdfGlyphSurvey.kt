package com.inksheets.desktop

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType1CFont
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Which fonts the library's parts write their text in - a music font (Opus, Helsinki, Leland...)
 * means the notes themselves are characters, whose kinds and places the PDF gives exactly: an
 * answer key for the music reader. Each music font's characters listed with what can be learned
 * of them: glyph name, unicode, size. -Dinksheets.omr=survey
 */
class PdfGlyphSurvey {
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")

    private fun musical(name: String) = listOf("opus", "helsinki", "leland", "bravura", "petrucci", "maestro", "jazz", "engraver", "sonata", "november", "inkpen", "reprise", "broadway", "finale")
        .any { name.contains(it, true) } && !name.contains("text", true)

    private fun nameOf(font: PDFont, code: Int): String? = runCatching {
        when (font) {
            is PDType1CFont -> font.codeToName(code)
            is PDType1Font -> font.codeToName(code)
            is PDTrueTypeFont -> font.encoding?.getName(code)
            is PDType0Font -> null
            else -> null
        }
    }.getOrNull()

    @Test
    fun `fonts in the parts`() {
        assumeTrue(System.getProperty("inksheets.omr") == "survey")
        val all = music.walkTopDown().filter { it.isFile && it.extension.equals("pdf", true) && !it.name.contains("score", true) }.toList()
        val picked = all.sortedBy { java.util.zip.CRC32().apply { update(it.relativeTo(music).path.lowercase().replace('\\', '/').toByteArray()) }.value }.take(6)
        for (f in picked) {
            println("== ${f.relativeTo(music).path}")
            Loader.loadPDF(f).use { doc ->
                val seen = HashMap<String, MutableMap<Int, Triple<Int, String, String>>>()
                object : PDFTextStripper() {
                    override fun writeString(text: String?, positions: MutableList<TextPosition>?) {
                        for (p in positions.orEmpty()) {
                            val font = p.font ?: continue
                            val fname = font.name?.substringAfter('+') ?: "?"
                            if (!musical(fname)) continue
                            val code = p.characterCodes?.firstOrNull() ?: continue
                            val m = seen.getOrPut("$fname (${font.javaClass.simpleName})") { HashMap() }
                            val old = m[code]
                            val size = "%.1fx%.1f".format(p.widthDirAdj, p.heightDir)
                            m[code] = Triple((old?.first ?: 0) + 1, nameOf(font, code) ?: "-", (p.unicode ?: "") + " w/h " + size)
                        }
                    }
                }.apply { startPage = 1; endPage = 1 }.getText(doc)
                for ((font, codes) in seen) {
                    println("  $font")
                    for ((code, v) in codes.entries.sortedBy { it.key }) println("    $code x${v.first}: ${v.second}  ${v.third}")
                }
            }
        }
    }
}
