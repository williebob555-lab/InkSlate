package com.inksheets.desktop

import com.inksheets.core.omr.Printed
import org.apache.pdfbox.Loader
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.font.PDVectorFont
import org.apache.pdfbox.pdmodel.graphics.image.PDImage
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.awt.geom.Point2D
import java.io.File

/** A page's printed music as its PDF states it ([Printed]), read with PDFBox. */
object PdfPrinted {
    /**
     * What a character code of [font] stands for, by the font's own map: the text stripper's
     * merges a dot printed over a note into the note's text as an accent, which would hide it.
     */
    private fun charOf(font: org.apache.pdfbox.pdmodel.font.PDFont, code: Int): String? = runCatching { font.toUnicode(code) }.getOrNull()

    fun read(file: File, index: Int): Printed? = runCatching { Loader.loadPDF(file).use { read(it, index) } }.getOrNull()

    fun read(doc: PDDocument, index: Int): Printed? {
        if (index >= doc.numberOfPages) return null
        val page = doc.getPage(index)
        if (page.rotation != 0) return null
        val box = page.cropBox
        val b = Printed.Builder(box.width, box.height)
        // Characters: where the text stripper puts them (from the page's top left, in points).
        object : PDFTextStripper() {
            override fun writeString(text: String?, positions: MutableList<TextPosition>?) {
                for (p in positions.orEmpty()) {
                    val font = p.font ?: continue
                    val name = font.name ?: continue
                    val code = p.characterCodes?.firstOrNull() ?: continue
                    if (Printed.isSpecialFont(name)) {
                        val outline = (font as? PDVectorFont)?.let { runCatching { it.getNormalizedPath(code).bounds2D }.getOrNull() } ?: continue
                        val size = p.textMatrix.scalingFactorX
                        b.specialChar(name, (outline.width / 1000).toFloat(), (outline.height / 1000).toFloat(),
                            p.xDirAdj + (outline.centerX / 1000 * size).toFloat(), p.yDirAdj - (outline.centerY / 1000 * size).toFloat(), size)
                    } else b.char(name, code, charOf(font, code) ?: p.unicode, p.xDirAdj, p.yDirAdj, p.widthDirAdj, p.textMatrix.scalingFactorX)
                }
            }
        }.apply { startPage = index + 1; endPage = index + 1; sortByPosition = false }.getText(doc)
        // Lines and shapes: stems and beams.
        val left = box.lowerLeftX; val top = box.upperRightY
        object : PDFGraphicsStreamEngine(page) {
            val path = ArrayList<ArrayList<Point2D.Float>>()
            var at = Point2D.Float()
            fun pt(x: Float, y: Float) = Point2D.Float(x - left, top - y)
            override fun appendRectangle(p0: Point2D, p1: Point2D, p2: Point2D, p3: Point2D) {
                path += arrayListOf(pt(p0.x.toFloat(), p0.y.toFloat()), pt(p1.x.toFloat(), p1.y.toFloat()), pt(p2.x.toFloat(), p2.y.toFloat()), pt(p3.x.toFloat(), p3.y.toFloat()))
            }
            override fun moveTo(x: Float, y: Float) { path += arrayListOf(pt(x, y)); at = Point2D.Float(x, y) }
            override fun lineTo(x: Float, y: Float) { (path.lastOrNull() ?: arrayListOf<Point2D.Float>().also { path += it }) += pt(x, y); at = Point2D.Float(x, y) }
            override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) { (path.lastOrNull() ?: arrayListOf<Point2D.Float>().also { path += it }) += pt(x3, y3); at = Point2D.Float(x3, y3) }
            override fun getCurrentPoint(): Point2D = at
            override fun closePath() {}
            override fun endPath() { path.clear() }
            override fun clip(windingRule: Int) {}
            override fun drawImage(pdImage: PDImage) {}
            override fun shadingFill(shadingName: COSName) {}
            override fun strokePath() {
                for (seg in path) for (i in 1 until seg.size) b.line(seg[i - 1].x, seg[i - 1].y, seg[i].x, seg[i].y)
                path.clear()
            }
            override fun fillPath(windingRule: Int) {
                for (seg in path) b.fill(FloatArray(seg.size) { seg[it].x }, FloatArray(seg.size) { seg[it].y })
                path.clear()
            }
            override fun fillAndStrokePath(windingRule: Int) = fillPath(windingRule)
        }.processPage(page)
        return b.build()
    }
}
