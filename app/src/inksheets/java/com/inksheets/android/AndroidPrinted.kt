package com.inksheets.android

import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import com.inksheets.core.omr.Printed
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDVectorFont
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File

/** A page's printed music as its PDF states it ([Printed]), read with PDFBox's Android port. */
object AndroidPrinted {
    /** What a character code of [font] stands for, by the font's own map (see the desktop's PdfPrinted). */
    private fun charOf(font: com.tom_roush.pdfbox.pdmodel.font.PDFont, code: Int): String? = runCatching { font.toUnicode(code) }.getOrNull()

    fun read(context: android.content.Context, file: File, index: Int): Printed? = runCatching {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(file).use { read(it, index) }
    }.getOrNull()

    fun read(doc: PDDocument, index: Int): Printed? {
        if (index >= doc.numberOfPages) return null
        val page = doc.getPage(index)
        if (page.rotation != 0) return null
        val box = page.cropBox
        val b = Printed.Builder(box.width, box.height)
        object : PDFTextStripper() {
            override fun writeString(text: String?, positions: MutableList<TextPosition>?) {
                for (p in positions.orEmpty()) {
                    val font = p.font ?: continue
                    val name = font.name ?: continue
                    val code = p.characterCodes?.firstOrNull() ?: continue
                    if (Printed.isSpecialFont(name)) {
                        val path = (font as? PDVectorFont)?.let { runCatching { it.getPath(code) }.getOrNull() } ?: continue
                        val r = RectF(); path.computeBounds(r, true)
                        // Glyph units to ems by the font's own matrix (a thousandth, most often).
                        val em = font.fontMatrix.scaleX
                        val size = p.textMatrix.scalingFactorX
                        b.specialChar(name, r.width() * em, r.height() * em,
                            p.xDirAdj + r.centerX() * em * size, p.yDirAdj - r.centerY() * em * size, size)
                    } else b.char(name, code, charOf(font, code) ?: p.unicode, p.xDirAdj, p.yDirAdj, p.widthDirAdj, p.textMatrix.scalingFactorX)
                }
            }
        }.apply { startPage = index + 1; endPage = index + 1; sortByPosition = false }.getText(doc)
        val left = box.lowerLeftX; val top = box.upperRightY
        object : PDFGraphicsStreamEngine(page) {
            val path = ArrayList<ArrayList<PointF>>()
            var at = PointF()
            fun pt(x: Float, y: Float) = PointF(x - left, top - y)
            override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) {
                path += arrayListOf(pt(p0.x, p0.y), pt(p1.x, p1.y), pt(p2.x, p2.y), pt(p3.x, p3.y))
            }
            override fun moveTo(x: Float, y: Float) { path += arrayListOf(pt(x, y)); at = PointF(x, y) }
            override fun lineTo(x: Float, y: Float) { (path.lastOrNull() ?: arrayListOf<PointF>().also { path += it }) += pt(x, y); at = PointF(x, y) }
            override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) { (path.lastOrNull() ?: arrayListOf<PointF>().also { path += it }) += pt(x3, y3); at = PointF(x3, y3) }
            override fun getCurrentPoint(): PointF = at
            override fun closePath() {}
            override fun endPath() { path.clear() }
            override fun clip(fillType: Path.FillType) {}
            override fun drawImage(pdImage: PDImage) {}
            override fun shadingFill(shadingName: COSName) {}
            override fun strokePath() {
                for (seg in path) for (i in 1 until seg.size) b.line(seg[i - 1].x, seg[i - 1].y, seg[i].x, seg[i].y)
                path.clear()
            }
            override fun fillPath(fillType: Path.FillType) {
                for (seg in path) b.fill(FloatArray(seg.size) { seg[it].x }, FloatArray(seg.size) { seg[it].y })
                path.clear()
            }
            override fun fillAndStrokePath(fillType: Path.FillType) = fillPath(fillType)
        }.processPage(page)
        return b.build()
    }
}
