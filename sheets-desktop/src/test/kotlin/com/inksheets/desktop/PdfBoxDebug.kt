package com.inksheets.desktop

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Every character a PDF prints in a box of a page (reader pixels, at the scale the reader reads it):
 * font, code, unicode, size - to learn what a symbol nothing reads is.
 * -Dinksheets.omr=box -Dinksheets.omr.file=... -Dinksheets.omr.bar=page,x0,x1,y0,y1
 */
class PdfBoxDebug {
    @Test
    fun `characters in a box`() {
        assumeTrue(System.getProperty("inksheets.omr") == "box")
        val f = File(System.getProperty("inksheets.omr.file")!!)
        val (page, x0, x1, y0, y1) = System.getProperty("inksheets.omr.bar")!!.split(',').map { it.trim().toFloat() }
        val (_, dpi) = OmrRealPagesTest().renderAt(f, page.toInt())!!
        val k = dpi / 72f
        Loader.loadPDF(f).use { doc ->
            object : PDFTextStripper() {
                override fun writeString(text: String?, positions: MutableList<TextPosition>?) {
                    for (p in positions.orEmpty()) {
                        val x = p.xDirAdj * k; val y = p.yDirAdj * k
                        if (x in x0..x1 && y in y0..y1) println("CHAR ${x.toInt()},${y.toInt()} font ${p.font?.name} code ${p.characterCodes?.toList()} '${p.unicode}' U+%04X size %.1f".format(p.unicode?.codePointAt(0) ?: 0, p.textMatrix.scalingFactorX))
                    }
                }
            }.apply { startPage = page.toInt() + 1; endPage = page.toInt() + 1; sortByPosition = true }.getText(doc)
        }
    }
}
