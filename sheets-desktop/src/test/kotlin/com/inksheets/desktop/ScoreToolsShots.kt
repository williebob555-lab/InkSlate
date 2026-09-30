package com.inksheets.desktop

import com.inkslate.core.PageMark
import com.inksheets.core.omr.Ink
import com.inksheets.core.omr.Recognizer
import com.inksheets.core.omr.Score
import com.inksheets.ui.ScoreTools
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.io.File
import javax.imageio.ImageIO

/**
 * The music tools' marks on a real part, drawn as the editors draw them - the clean reading over
 * the print, a selection, bars cleaned up - to see that they sit where the music is.
 * Pictures to -Dinksheets.shots.
 */
class ScoreToolsShots {
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")

    @Test
    fun `the reading sits on the print`() {
        val file = File(music, "Imported/PEP BAND/Music/Sweet Caroline/SweetC - Trumpet 1.pdf")
        assumeTrue(file.isFile)
        // Read as the app reads: the page drawn so a staff space is about 18 pixels.
        val (ink, _) = OmrRealPagesTest().renderAt(file, 0)!!
        val reading = Recognizer().read(ink, 0)
        val score = Score(reading.measures, 1, listOf(ink.width))
        val path = file.absolutePath
        ScoreTools.scoreSource = { if (it == path) score else null }
        try {
            val (wPts, hPts) = Loader.loadPDF(file).use { d -> d.getPage(0).mediaBox.let { it.width to it.height } }
            ScoreTools.showUnderlay(true)
            ScoreTools.select(5..7)
            ScoreTools.cleanUp(path, listOf(10, 11))
            val marks = ScoreTools.marks(path, 0, wPts, hPts).orEmpty()
            println("${marks.size} marks for ${reading.measures.size} bars")
            assertTrue(marks.size > reading.measures.size * 5)
            // Every mark on the page.
            assertTrue(marks.all { m -> m.contours.all { c -> c.indices.all { i -> if (i % 2 == 0) c[i] in -5f..wPts + 5 else c[i] in -5f..hPts + 5 } } })
            val shots = System.getProperty("inksheets.shots") ?: return
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
            ImageIO.write(img, "png", File(shots, "tools-underlay.png"))
        } finally {
            ScoreTools.scoreSource = null
            ScoreTools.showUnderlay(false)
            ScoreTools.select(null)
        }
    }
}
