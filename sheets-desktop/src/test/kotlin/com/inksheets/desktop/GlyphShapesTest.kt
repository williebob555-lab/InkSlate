package com.inksheets.desktop

import com.inksheets.core.omr.GlyphShapes
import com.inksheets.core.omr.Printed
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDVectorFont
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.geom.AffineTransform
import java.awt.geom.GeneralPath
import java.awt.geom.PathIterator
import java.io.File

/**
 * Telling a music-font character by its shape ([GlyphShapes]) - for fonts whose codes say nothing -
 * checked where the codes do say: every distinct character of the library's readable music fonts,
 * drawn from its outline and classified, against what its code says it is. How often it is right,
 * per kind, and what it takes things for when wrong. -Dinksheets.omr=shapes
 */
class GlyphShapesTest {
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")

    /** A glyph's outline in staff spaces, y down (a music font's em is four spaces). */
    fun outline(font: PDFont, code: Int): List<FloatArray>? = runCatching {
        val path: GeneralPath = (font as? PDVectorFont)?.getNormalizedPath(code) ?: return null
        val it = path.getPathIterator(AffineTransform(0.004, 0.0, 0.0, -0.004, 0.0, 0.0), 0.002)
        val out = ArrayList<FloatArray>(); var cur = ArrayList<Float>()
        val c = DoubleArray(6)
        while (!it.isDone) {
            when (it.currentSegment(c)) {
                PathIterator.SEG_MOVETO -> { if (cur.size >= 6) out += cur.toFloatArray(); cur = arrayListOf(c[0].toFloat(), c[1].toFloat()) }
                PathIterator.SEG_LINETO -> { cur += c[0].toFloat(); cur += c[1].toFloat() }
                PathIterator.SEG_CLOSE -> { if (cur.size >= 6) out += cur.toFloatArray(); cur = ArrayList() }
            }
            it.next()
        }
        if (cur.size >= 6) out += cur.toFloatArray()
        out.takeIf { it.isNotEmpty() }
    }.getOrNull()

    @Test
    fun `shapes against codes`() {
        assumeTrue(System.getProperty("inksheets.omr") == "shapes")
        val parts = music.walkTopDown().filter { it.isFile && it.extension.equals("pdf", true) && !it.path.contains(".inksheets") && !it.name.contains("score", true) }
            .toList().sortedBy { java.util.zip.CRC32().apply { update(it.path.toByteArray()) }.value }.take(150)
        val seen = HashSet<String>()
        val right = HashMap<Printed.Kind, Int>(); val total = HashMap<Printed.Kind, Int>(); val wrong = HashMap<String, Int>()
        for (f in parts) runCatching {
            Loader.loadPDF(f).use { doc ->
                object : PDFTextStripper() {
                    override fun writeString(text: String?, positions: MutableList<TextPosition>?) {
                        for (p in positions.orEmpty()) {
                            val font = p.font ?: continue
                            val name = font.name?.substringAfter('+') ?: continue
                            if (!Printed.isMusicFont(name)) continue
                            val code = p.characterCodes?.firstOrNull() ?: continue
                            val uni = runCatching { font.toUnicode(code) }.getOrNull() ?: continue
                            if (uni.codePointCount(0, uni.length) != 1) continue
                            val kind = Printed.kindOf(uni.codePointAt(0))
                            if (kind == Printed.Kind.OTHER || kind == Printed.Kind.TEXT_DIGIT) continue
                            // One try per font and code: a character is the same wherever it is printed.
                            if (!seen.add("$name/$code")) continue
                            val shape = outline(font, code) ?: continue
                            val got = GlyphShapes.classify(shape)
                            total.merge(kind, 1, Int::plus)
                            // Dots, whether augmentation or staccato, are one shape; so are the flags up and down.
                            if (got?.kind == kind) right.merge(kind, 1, Int::plus)
                            else wrong.merge("$kind as ${got?.kind ?: "nothing"}${got?.let { " %.2f".format(it.score) } ?: ""} ($name '${uni}')", 1, Int::plus)
                        }
                    }
                }.apply { startPage = 1; endPage = 1 }.getText(doc)
            }
        }
        val all = total.values.sum(); val ok = right.values.sum()
        println("SHAPES: $ok of $all distinct characters told right (${if (all == 0) "-" else "%.1f%%".format(100.0 * ok / all)})")
        total.entries.sortedByDescending { it.value }.forEach { (k, n) -> println("  $k: ${right[k] ?: 0}/$n") }
        wrong.entries.sortedByDescending { it.value }.take(40).forEach { println("  WRONG ${it.key} x${it.value}") }
        assertTrue(all > 20)
    }
}
