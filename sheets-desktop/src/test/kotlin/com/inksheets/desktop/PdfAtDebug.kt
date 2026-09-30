package com.inksheets.desktop

import org.apache.pdfbox.Loader
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.graphics.image.PDImage
import org.apache.pdfbox.util.Matrix
import org.apache.pdfbox.util.Vector
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.geom.Point2D
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Everything a PDF draws just right of the printed heads at x (page pixels at the reader's scale):
 * characters with their fonts, filled and stroked paths with their boxes - to find out how an
 * engraver drew something the answer key does not see. -Dinksheets.omr=at -Dinksheets.omr.file=...
 * -Dinksheets.omr.bar=x
 */
class PdfAtDebug {
    @Test
    fun `what is drawn beside a head`() {
        assumeTrue(System.getProperty("inksheets.omr") == "at")
        val f = File(System.getProperty("inksheets.omr.file")!!)
        val atX = System.getProperty("inksheets.omr.bar")!!.toFloat()
        val (_, dpi) = OmrRealPagesTest().renderAt(f, 0)!!
        val sp = 18f
        val heads = AnswerKey.read(f, 0, dpi)!!.filter { (it.kind == AnswerKey.Kind.HEAD_BLACK || it.kind == AnswerKey.Kind.HEAD_HALF || it.kind == AnswerKey.Kind.HEAD_WHOLE) && kotlin.math.abs(it.x - atX) < 4 }
        val k = dpi / 72f
        Loader.loadPDF(f).use { doc ->
            val page = doc.getPage(0)
            val h = page.mediaBox.height
            for (head in heads) {
                val wide = System.getProperty("inksheets.omr.why") != null
                val x0 = head.x + head.width * 0.5f; val x1 = head.x + head.width + sp * 2; val y0 = head.y - sp * (if (wide) 5 else 1); val y1 = head.y + sp * (if (wide) 5 else 1)
                println("HEAD at ${head.x.toInt()},${head.y.toInt()} w=${head.width}: looking in x $x0..$x1, y $y0..$y1")
                val engine = object : PDFGraphicsStreamEngine(page) {
                    val pts = ArrayList<Point2D.Float>(); var ops = 0; var curves = 0
                    var at = Point2D.Float()
                    fun pt(x: Float, y: Float) = Point2D.Float(x * k, (h - y) * k)
                    fun report(what: String) {
                        if (pts.isEmpty()) return
                        val bx0 = pts.minOf { it.x }; val bx1 = pts.maxOf { it.x }; val by0 = pts.minOf { it.y }; val by1 = pts.maxOf { it.y }
                        if (bx1 >= x0 && bx0 <= x1 && by1 >= y0 && by0 <= y1 && (wide || bx1 - bx0 < sp * 3))
                            println("  $what box ${bx0.toInt()}..${bx1.toInt()} x ${by0.toInt()}..${by1.toInt()} (${"%.1f".format(bx1 - bx0)}x${"%.1f".format(by1 - by0)}), $ops ops, $curves curves, line ${"%.2f".format(graphicsState.lineWidth * k)}, cap ${graphicsState.lineCap}")
                        pts.clear(); ops = 0; curves = 0
                    }
                    override fun appendRectangle(p0: Point2D, p1: Point2D, p2: Point2D, p3: Point2D) { listOf(p0, p1, p2, p3).forEach { pts += pt(it.x.toFloat(), it.y.toFloat()) }; ops++ }
                    override fun drawImage(pdImage: PDImage) {}
                    override fun clip(windingRule: Int) {}
                    override fun moveTo(x: Float, y: Float) { pts += pt(x, y); at = Point2D.Float(x, y); ops++ }
                    override fun lineTo(x: Float, y: Float) { pts += pt(x, y); at = Point2D.Float(x, y); ops++ }
                    override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) { pts += pt(x3, y3); pts += pt(x1, y1); pts += pt(x2, y2); at = Point2D.Float(x3, y3); ops++; curves++ }
                    override fun getCurrentPoint(): Point2D = at
                    override fun closePath() {}
                    override fun endPath() { pts.clear(); ops = 0; curves = 0 }
                    override fun strokePath() = report("STROKE")
                    override fun fillPath(windingRule: Int) = report("FILL")
                    override fun fillAndStrokePath(windingRule: Int) = report("FILL+STROKE")
                    override fun shadingFill(shadingName: COSName) {}
                    override fun showGlyph(textRenderingMatrix: Matrix, font: PDFont, code: Int, displacement: Vector) {
                        val x = textRenderingMatrix.translateX * k; val y = (h - textRenderingMatrix.translateY) * k
                        if (x in x0 - sp..x1 && y in y0 - sp..y1 + sp) println("  CHAR code $code '${runCatching { font.toUnicode(code) }.getOrNull()}' font ${font.name} name ${runCatching { (font as? org.apache.pdfbox.pdmodel.font.PDSimpleFont)?.encoding?.getName(code) ?: (font as? org.apache.pdfbox.pdmodel.font.PDType1CFont)?.codeToName(code) }.getOrNull()} at ${x.toInt()},${y.toInt()} size ${"%.1f".format(textRenderingMatrix.scalingFactorX * k)}")
                        super.showGlyph(textRenderingMatrix, font, code, displacement)
                    }
                }
                engine.processPage(page)
            }
        }
        if (false) println(max(0, min(0, 0)))
    }
}
