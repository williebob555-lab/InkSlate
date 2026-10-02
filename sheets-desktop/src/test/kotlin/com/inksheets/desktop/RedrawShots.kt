package com.inksheets.desktop

import com.inkslate.core.PageMark
import com.inksheets.core.omr.Ink
import com.inksheets.core.omr.Measure
import com.inksheets.core.omr.Net
import com.inksheets.core.omr.Recognizer
import com.inksheets.core.omr.Score
import com.inksheets.core.omr.Strips
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
import java.io.File
import javax.imageio.ImageIO

/**
 * A part read exactly as the app reads it (Transcriber: the page drawn 1600 wide to measure it,
 * then so a space is 18 pixels; the PDF's own symbols where it states them, else the trained
 * reader) and every bar redrawn over the print, as the clean view shows it - to
 * -Dinksheets.shots/redraw-<name>-p<N>.png. -Dinksheets.omr=redraw [-Dinksheets.omr.file=pdf]
 */
class RedrawShots {
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")

    @Test
    fun `the redraw over the print, as the app reads it`() {
        assumeTrue(System.getProperty("inksheets.omr") == "redraw")
        val file = File(System.getProperty("inksheets.omr.file") ?: File(music, "Imported/PEP BAND/Music/24K Magic/24K Magic - Electric Bass.pdf").path)
        val shots = File(System.getProperty("inksheets.shots") ?: "build/redraw").apply { mkdirs() }
        val net = Net.shipped
        val pages = Loader.loadPDF(file).use { it.numberOfPages }
        val source = com.inkslate.desktop.DesktopSources.open(file, detached = true)!!
        val measures = ArrayList<Measure>(); val widths = ArrayList<Int>()
        val carry = Recognizer.Carry(); var number = 1
        // -Dinksheets.omr.pages=3..5: only those pages (1-based), for a long book.
        val only = System.getProperty("inksheets.omr.pages")?.split("..")?.let { (a, b) -> (a.toInt() - 1) until b.toInt() }
        for (p in 0 until pages) {
            if (only != null && p !in only) continue
            // Drawn by the app's own renderer (as the Read button reads): its page size, its leaving out
            // of this app's own handwriting, and its limit on how far a small page is magnified.
            fun draw(width: Int): Pair<IntArray, Ink> {
                val img = source.render(p, width)!!
                val px = IntArray(img.width * img.height); img.readPixels(px)
                return Strips.grey(px) to Ink.fromArgb(img.width, img.height, px)
            }
            val space = Recognizer().metrics(draw(1600).second)?.second
            val width = if (space == null || space <= 0f) 1600 else (1600 * 18f / space).toInt().coerceIn(1000, 5000)
            val (grey, ink) = draw(width)
            val printed = runCatching { PdfPrinted.read(file, p) }.getOrNull()
            val rec = Recognizer(debug = System.getProperty("inksheets.omr.debug") != null).apply { traceRests = System.getProperty("inksheets.omr.rests") != null && p == 0 }
            val r = rec.read(ink, p, number, carry, printed, grey = grey, net = if (printed == null) net else null)
            println("REDRAW page ${p + 1}: drawn ${ink.width}x${ink.height}, ${if (printed != null) "PDF's symbols" else "trained reader"}, ${r.measures.size} bars, ${r.measures.count { it.sure }} sure, numbers ${r.measures.firstOrNull()?.number}..${r.measures.lastOrNull()?.number}")
            System.getProperty("inksheets.omr.bars")?.split(",")?.map { it.toInt() }?.let { want ->
                println("  BARLINES first staff: ${r.barlines.firstOrNull()} space ${r.space}")
                for (m in r.measures.filter { it.number in want }) println("  BAR ${m.number}: " + m.events.joinToString(" ") { e ->
                    when (e) { is com.inksheets.core.omr.Note -> "n${e.duration.base}${if (e.tie) "~" else ""}${if (e.articulations.isNotEmpty()) e.articulations.toString() else ""}"; is com.inksheets.core.omr.Rest -> "r${e.duration.base}" } } +
                    " | directions " + m.directions.joinToString { "${it.kind}${if (it.above) "^" else "v"} ${it.x.toInt()}..${it.x2.toInt()}" } + " | kept ${m.kept.size} | bars ${m.bars} doubts ${m.doubts}")
            }
            measures += r.measures; while (widths.size < p) widths += 0; widths += ink.width
            r.measures.lastOrNull()?.let { number = it.number + it.bars }
        }
        val score = Score(measures, pages, widths)
        val path = file.absolutePath
        ScoreTools.scoreSource = { if (it == path) score else null }
        try {
            // -Dinksheets.omr.clean=1: every bar cleaned up (the print hidden, the redraw and what it keeps in its place).
            val cleanAll = System.getProperty("inksheets.omr.clean") != null
            if (cleanAll) ScoreTools.cleanUp(path, measures.map { it.number }) else ScoreTools.showUnderlay(true)
            // -Dinksheets.omr.layers=1: what is kept as printed in red, the redraw in black.
            if (System.getProperty("inksheets.omr.layers") != null) ScoreTools.keptColor = 0xFFE02020.toInt()
            // -Dinksheets.omr.audit=1: each staff as the print over its redraw, bar numbers on, for checking bar by bar.
            val audit = System.getProperty("inksheets.omr.audit") != null
            for (p in (only?.toList() ?: (0 until minOf(pages, 2)).toList())) {
                val (wPts, hPts) = source.pageDim(p).let { it.width to it.height }
                val marks = ScoreTools.marks(path, p, wPts, hPts).orEmpty()
                val k = (System.getProperty("inksheets.omr.zoom") ?: "2").toFloat()
                val img = Loader.loadPDF(file).use { PDFRenderer(it).renderImageWithDPI(p, 72f * k, ImageType.RGB) }
                val print = if (audit) java.awt.image.BufferedImage(img.width, img.height, img.type).also { it.data = img.data } else null
                val g = img.createGraphics()
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.scale(k.toDouble(), k.toDouble())
                for (m in marks) {
                    g.color = Color(m.color, true)
                    when (m.kind) {
                        PageMark.Kind.FILL -> g.fill(Path2D.Float(Path2D.WIND_EVEN_ODD).apply {
                            for (c in m.contours) { if (c.size < 6) continue; moveTo(c[0], c[1]); var i = 2; while (i + 1 < c.size) { lineTo(c[i], c[i + 1]); i += 2 }; closePath() }
                        })
                        PageMark.Kind.LINE -> { g.stroke = BasicStroke(m.width); for (c in m.contours) { var i = 2; while (i + 1 < c.size) { g.draw(java.awt.geom.Line2D.Float(c[i - 2], c[i - 1], c[i], c[i + 1])); i += 2 } } }
                    }
                }
                g.dispose()
                if (print != null) auditStrips(print, img, measures.filter { it.page == p }, widths[p], shots, "${file.nameWithoutExtension.replace(Regex("[^A-Za-z0-9]+"), "-").take(24)}-p${p + 1}")
                else ImageIO.write(img, "png", File(shots, (if (cleanAll) "clean-" else "redraw-") + "${file.nameWithoutExtension.replace(Regex("[^A-Za-z0-9]+"), "-")}-p${p + 1}.png"))
            }
        } finally {
            ScoreTools.scoreSource = null
            ScoreTools.showUnderlay(false)
            ScoreTools.undoAllClean(path)
            ScoreTools.keptColor = null
        }
    }

    /**
     * Each staff of [measures] (read from a page drawn [inkWidth] wide) as the [print] over the
     * [clean] redraw, the same strip of page one above the other: bar numbers over the print, a bar
     * in doubt boxed in orange, every bar's left edge ruled through both.
     */
    private fun auditStrips(print: java.awt.image.BufferedImage, clean: java.awt.image.BufferedImage, measures: List<Measure>, inkWidth: Int, dir: File, name: String) {
        val f = print.width.toFloat() / inkWidth
        for ((staff, bars) in measures.groupBy { it.staff }.toSortedMap()) {
            val sp = (bars.first().box.bottom - bars.first().box.top) / 4f
            val top = ((bars.minOf { it.box.top } - sp * 4) * f).toInt().coerceAtLeast(0)
            val bottom = ((bars.maxOf { it.box.bottom } + sp * 4) * f).toInt().coerceAtMost(print.height)
            val left = ((bars.minOf { it.box.left } - sp * 4) * f).toInt().coerceAtLeast(0)
            val right = ((bars.maxOf { it.box.right } + sp) * f).toInt().coerceAtMost(print.width)
            val h = bottom - top; val w = right - left
            if (h <= 0 || w <= 0) continue
            val label = 22
            val out = java.awt.image.BufferedImage(w, label + h * 2 + 6, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g = out.createGraphics()
            g.color = Color.WHITE; g.fillRect(0, 0, out.width, out.height)
            g.drawImage(print.getSubimage(left, top, w, h), 0, label, null)
            g.color = Color(0x3060C0); g.fillRect(0, label + h, w, 6)
            g.drawImage(clean.getSubimage(left, top, w, h), 0, label + h + 6, null)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.font = java.awt.Font("SansSerif", java.awt.Font.BOLD, 16)
            for (m in bars) {
                val x0 = (m.box.left * f).toInt() - left; val x1 = (m.box.right * f).toInt() - left
                g.color = if (m.sure) Color(0x208020) else Color(0xE07000)
                g.drawString("${m.number}${if (m.bars > 1) "x${m.bars}" else ""}${if (m.sure) "" else "?"}", x0 + 3, 17)
                g.stroke = BasicStroke(if (m.sure) 1f else 3f)
                if (!m.sure) g.drawRect(x0, label, x1 - x0, out.height - label - 1)
                else { g.color = Color(0x80208020.toInt(), true); g.drawLine(x0, label, x0, out.height) }
            }
            g.dispose()
            ImageIO.write(out, "png", File(dir, "audit-$name-s${staff + 1}.png"))
        }
    }
}
