package com.inksheets.desktop

import org.apache.pdfbox.Loader
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.graphics.image.PDImage
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.geom.Point2D
import java.io.File

/**
 * Every wide, shallow shape a page draws - ties, slurs, hairpins are among them - with how it is
 * drawn (filled or stroked, its operations, curves): to learn how an engraver writes them.
 * -Dinksheets.omr=shapes-on -Dinksheets.omr.file=...
 */
class PdfShapesDebug {
    @Test
    fun `wide shallow shapes`() {
        assumeTrue(System.getProperty("inksheets.omr") == "shapes-on")
        val f = File(System.getProperty("inksheets.omr.file")!!)
        Loader.loadPDF(f).use { doc ->
            val page = doc.getPage(0)
            val counts = HashMap<String, Int>()
            object : PDFGraphicsStreamEngine(page) {
                val pts = ArrayList<Point2D.Float>(); var ops = ArrayList<String>(); var at = Point2D.Float()
                fun report(how: String) {
                    if (pts.size >= 2) {
                        val w = pts.maxOf { it.x } - pts.minOf { it.x }; val h = pts.maxOf { it.y } - pts.minOf { it.y }
                        if ("c" in ops || (w > 6f && h < w * 0.6f && h > 0.5f && w < 300f)) {
                            val sig = "$how ${ops.groupingBy { it }.eachCount()} lineWidth ${"%.2f".format(graphicsState.lineWidth)}"
                            counts.merge(sig, 1, Int::plus)
                            if (counts[sig] == 1) println("SHAPE $sig box ${"%.1f".format(w)}x${"%.1f".format(h)} at ${"%.0f".format(pts.minOf { it.x })},${"%.0f".format(pts.minOf { it.y })}")
                        }
                    }
                    pts.clear(); ops = ArrayList()
                }
                override fun appendRectangle(p0: Point2D, p1: Point2D, p2: Point2D, p3: Point2D) { listOf(p0, p1, p2, p3).forEach { pts += Point2D.Float(it.x.toFloat(), it.y.toFloat()) }; ops += "re" }
                override fun drawImage(pdImage: PDImage) {}
                override fun clip(windingRule: Int) {}
                override fun moveTo(x: Float, y: Float) { pts += Point2D.Float(x, y); at = Point2D.Float(x, y); ops += "m" }
                override fun lineTo(x: Float, y: Float) { pts += Point2D.Float(x, y); at = Point2D.Float(x, y); ops += "l" }
                override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) { pts += Point2D.Float(x3, y3); at = Point2D.Float(x3, y3); ops += "c" }
                override fun getCurrentPoint(): Point2D = at
                override fun closePath() { ops += "h" }
                override fun endPath() { pts.clear(); ops = ArrayList() }
                override fun strokePath() = report("STROKE")
                override fun fillPath(windingRule: Int) = report("FILL")
                override fun fillAndStrokePath(windingRule: Int) = report("FILL+STROKE")
                override fun shadingFill(shadingName: COSName) {}
            }.processPage(page)
            counts.entries.sortedByDescending { it.value }.take(20).forEach { println("COUNT ${it.value}x ${it.key}") }
        }
    }
}
