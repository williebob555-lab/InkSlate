package com.inksheets.desktop.review

import com.inkslate.core.PageMark
import com.inksheets.core.omr.Score
import com.inksheets.ui.ScoreTools
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * T5 round 1: the clean view (every bar redrawn over the page) next to the print, at 4x, as
 * stacked crops of a few systems: print on top, clean below. build/t5/clean/.
 */
class T5CleanTest {
    private val out = File("build/t5/clean").apply { mkdirs() }

    private fun render(name: String, pdfName: String, page: Int) {
        val pdf = T5Data.pdfs().first { it.name == pdfName }
        val score = T5Data.load(T5Data.readingOf(pdf)!!)!!
        val path = pdf.absolutePath
        ScoreTools.scoreSource = { if (it == path) score else null }
        try {
            val (w, h) = Loader.loadPDF(pdf).use { d -> d.getPage(page).mediaBox.let { it.width to it.height } }
            val numbers = score.measures.flatMap { m -> m.number until m.number + m.bars }
            ScoreTools.cleanUp(path, numbers)
            val marks = ScoreTools.marks(path, page, w, h).orEmpty()
            val k = 4f
            val print = Loader.loadPDF(pdf).use { PDFRenderer(it).renderImageWithDPI(page, 72f * k, ImageType.RGB) }
            val clean = BufferedImage(print.width, print.height, BufferedImage.TYPE_INT_RGB)
            val g = clean.createGraphics()
            g.drawImage(print, 0, 0, null)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.scale(k.toDouble(), k.toDouble())
            for (m in marks) {
                g.color = Color(m.color, true)
                when (m.kind) {
                    PageMark.Kind.FILL -> g.fill(Path2D.Float(Path2D.WIND_EVEN_ODD).apply { for (c in m.contours) { if (c.size < 6) continue; moveTo(c[0], c[1]); var i = 2; while (i + 1 < c.size) { lineTo(c[i], c[i + 1]); i += 2 }; closePath() } })
                    PageMark.Kind.LINE -> { g.stroke = BasicStroke(m.width); for (c in m.contours) { var i = 2; while (i + 1 < c.size) { g.draw(java.awt.geom.Line2D.Float(c[i - 2], c[i - 1], c[i], c[i + 1])); i += 2 } } }
                }
            }
            g.dispose()
            // Systems on this page: the staves, top to bottom; crop three of them across the page.
            val sys = score.measures.filter { it.page == page }.groupBy { it.staff }.toSortedMap()
            val toPx = w * k / score.pageWidths[page]
            var n = 0
            for ((staff, bars) in sys) {
                if (n >= 4) break
                if (staff % 2 != 0 && sys.size > 5) continue
                val top = (bars.minOf { it.box.top } - bars.first().space * 7) * toPx
                val bottom = (bars.maxOf { it.box.bottom } + bars.first().space * 7) * toPx
                val y0 = top.toInt().coerceIn(0, print.height - 2); val y1 = bottom.toInt().coerceIn(y0 + 1, print.height)
                val hh = y1 - y0
                val ww = minOf(print.width, 2400)
                val x0 = ((bars.minOf { it.box.left } - 20) * toPx).toInt().coerceIn(0, print.width - ww)
                val sheet = BufferedImage(ww, hh * 2 + 10, BufferedImage.TYPE_INT_RGB)
                val sg = sheet.createGraphics(); sg.color = Color.RED; sg.fillRect(0, 0, ww, hh * 2 + 10)
                sg.drawImage(print.getSubimage(x0, y0, ww, hh), 0, 0, null)
                sg.drawImage(clean.getSubimage(x0, y0, ww, hh), 0, hh + 10, null)
                sg.dispose()
                ImageIO.write(sheet, "png", File(out, "$name-system$staff.png"))
                n++
            }
            ImageIO.write(clean, "png", File(out, "$name-page${page + 1}-clean.png"))
            println("T5 CLEAN $name: ${marks.size} marks, ${score.measures.count { it.page == page }} bars on page, not-sure ${score.measures.count { it.page == page && !it.sure }}")
        } finally {
            ScoreTools.undoAllClean(path)
            ScoreTools.scoreSource = null
        }
    }

    @Test fun `scan`() { assumeTrue(T5Data.readings.isDirectory); render("scan-timewarp", "Time Warp - Trombone 1.pdf", 0) }
    @Test fun `digital`() { assumeTrue(T5Data.readings.isDirectory); render("digital-diva", "Diva- Trumpet 1.pdf", 0) }
    @Test fun `digital2`() { assumeTrue(T5Data.readings.isDirectory); render("digital-maryland", "Maryland School Songs - Trombone 1.pdf", 0) }
}
