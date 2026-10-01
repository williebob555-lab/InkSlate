package com.inksheets.desktop

import com.inkslate.core.PageMark
import com.inksheets.core.omr.Recognizer
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
import java.io.File
import javax.imageio.ImageIO

/**
 * The wind ensemble's set (all scans) read as the app reads them: per piece, its bars, how many
 * read sure, how many left to ask about - and each first page with the reading laid faintly over
 * it (a mark over each bar in doubt), to -Dinksheets.shots. -Dinksheets.omr=wind
 */
class WindEnsembleShots {
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets/MobileSheets")

    private val net by lazy { System.getProperty("inksheets.bench.net")?.let { File(it).inputStream().use { s -> com.inksheets.core.omr.Net.load(s) } } }

    @Test
    fun `the wind ensemble's set as read`() {
        assumeTrue(System.getProperty("inksheets.omr") == "wind")
        val pieces = listOf("Chester.pdf", "Be Glad Then, America.pdf", "Fanfare and Allegro.pdf", "Highwater Rising.pdf", "Untitled (p2-3).pdf", "Untitled (p8-9).pdf")
        val shots = System.getProperty("inksheets.shots")
        // -Dinksheets.omr.only=Glad: just the pieces whose names have it.
        val only = System.getProperty("inksheets.omr.only")
        for (name in pieces) {
            if (only != null && !name.contains(only, ignoreCase = true)) continue
            val file = File(music, name)
            if (!file.isFile) continue
            val pages = Loader.loadPDF(file).use { it.numberOfPages }
            var bars = 0; var sure = 0
            val carry = Recognizer.Carry(); var number = 1
            val measures = ArrayList<com.inksheets.core.omr.Measure>(); val widths = ArrayList<Int>()
            for (p in 0 until pages) {
                val (ink, dpi) = OmrRealPagesTest().renderAt(file, p) ?: continue
                // With the trained reader (-Dinksheets.bench.net=weights): given the page in grey.
                val grey = if (net == null) null else Loader.loadPDF(file).use { d ->
                    val img = PDFRenderer(d).renderImageWithDPI(p, dpi, ImageType.RGB)
                    val px = IntArray(img.width * img.height); img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
                    com.inksheets.core.omr.Strips.grey(px)
                }
                val r = Recognizer().read(ink, p, number, carry, grey = grey, net = net)
                measures += r.measures; widths += ink.width
                r.measures.lastOrNull()?.let { number = it.number + it.bars }
                bars += r.measures.size; sure += r.measures.count { it.sure }
            }
            if (System.getProperty("inksheets.omr.why") != null) measures.take(24).forEach { m ->
                println("  BAR $name m${m.number} p${m.page} st${m.staff} ${m.time.beats}/${m.time.beatType}${if (m.showsTime) "*" else ""} q=${"%.2f".format(m.quarters)} ${m.doubts} | " +
                    m.events.joinToString(" ") { e -> when (e) { is com.inksheets.core.omr.Note -> "n${e.duration.base}${".".repeat(e.duration.dots)}${if (e.duration.tuplet) "t" else ""}"; is com.inksheets.core.omr.Rest -> "r${e.duration.base}${".".repeat(e.duration.dots)}" } })
            }
            val doubts = measures.filter { !it.sure }.flatMap { it.doubts }.map { it.replace(Regex("[0-9.]+"), "#") }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(4)
            println("WIND $name: $pages pages, $bars bars, ${sure} sure (${if (bars == 0) "-" else "${sure * 100 / bars}%"}), ${bars - sure} to ask about; doubts: ${doubts.joinToString { "${it.key} x${it.value}" }}")
            if (shots == null || measures.isEmpty()) continue
            val score = Score(measures, pages, widths)
            val path = file.absolutePath
            ScoreTools.scoreSource = { if (it == path) score else null }
            try {
                ScoreTools.showUnderlay(true)
                val (wPts, hPts) = Loader.loadPDF(file).use { d -> d.getPage(0).mediaBox.let { it.width to it.height } }
                val marks = ScoreTools.marks(path, 0, wPts, hPts).orEmpty()
                val k = 2f
                val img = Loader.loadPDF(file).use { PDFRenderer(it).renderImageWithDPI(0, 72f * k, ImageType.RGB) }
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
                File(shots).mkdirs()
                ImageIO.write(img, "png", File(shots, "wind-" + name.substringBefore(".pdf").replace(Regex("[^A-Za-z0-9]+"), "-") + ".png"))
            } finally {
                ScoreTools.scoreSource = null
                ScoreTools.showUnderlay(false)
            }
        }
    }
}
