package com.inksheets.desktop

import com.inksheets.core.omr.CleanPrint
import com.inksheets.core.omr.Note
import com.inksheets.core.omr.Recognizer
import com.inksheets.core.omr.Score
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * A part whose PDF states its notes, read with everything it marks besides them - articulations,
 * ties, slurs, dynamics, hairpins - and printed afresh from the reading: the original page and the
 * reprint side by side (to -Dinksheets.shots), and how many of each were read.
 */
class PrintedMarksShots {
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")

    @Test
    fun `the marks read with the notes, and printed again`() {
        val file = File(music, "Imported/PEP BAND/Music/Party Medley/Party Medley - Trumpet in Bb 1.pdf")
        assumeTrue(file.isFile)
        val (ink, _) = OmrRealPagesTest().renderAt(file, 0)!!
        val reading = Recognizer().read(ink, 0, printed = PdfPrinted.read(file, 0))
        val notes = reading.measures.flatMap { it.events.filterIsInstance<Note>() }
        val arts = notes.flatMap { it.articulations }.groupingBy { it }.eachCount()
        val dirs = reading.measures.flatMap { it.directions }.groupingBy { if (it.kind == "dynamic") "dynamic ${it.text}" else it.kind }.eachCount()
        println("MARKS: ${notes.size} notes; articulations $arts; ties ${notes.count { it.tie }}; directions $dirs")
        assertTrue("accents read", (arts["accent"] ?: 0) > 5)
        assertTrue("some ties read", notes.count { it.tie } > 0)
        val shots = System.getProperty("inksheets.shots") ?: return
        val pdf = CleanPrint.pdf(Score(reading.measures, 1), "Party Medley", "Trumpet in Bb 1 (reprinted)")
        val again = Loader.loadPDF(pdf).use { PDFRenderer(it).renderImageWithDPI(0, 110f, ImageType.RGB) }
        val orig = Loader.loadPDF(file).use { PDFRenderer(it).renderImageWithDPI(0, 110f, ImageType.RGB) }
        val both = BufferedImage(orig.width + again.width + 20, maxOf(orig.height, again.height), BufferedImage.TYPE_INT_RGB)
        val g = both.createGraphics()
        g.color = java.awt.Color.WHITE; g.fillRect(0, 0, both.width, both.height)
        g.drawImage(orig, 0, 0, null); g.drawImage(again, orig.width + 20, 0, null)
        g.dispose()
        File(shots).mkdirs()
        ImageIO.write(both, "png", File(shots, "marks-side-by-side.png"))
    }
}
