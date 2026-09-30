package com.inksheets.desktop

import com.inksheets.core.omr.CleanPrint
import com.inksheets.core.omr.Event
import com.inksheets.core.omr.Ink
import com.inksheets.core.omr.Measure
import com.inksheets.core.omr.Note
import com.inksheets.core.omr.Recognizer
import com.inksheets.core.omr.Rest
import com.inksheets.core.omr.Score
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * A part printed afresh opens as a PDF, and reads back as what it was printed from: every bar,
 * almost every note. Pictures of the first page to -Dinksheets.shots.
 */
class CleanPrintTest {
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")

    private fun describe(e: Event) = when (e) {
        is Note -> e.pitches.joinToString("+") + "/" + e.duration.base + ".".repeat(e.duration.dots)
        is Rest -> "r/" + e.duration.base
    }

    @Test
    fun `a clean printout reads back as it was read`() {
        val file = File(music, "Imported/PEP BAND/Music/Sweet Caroline/SweetC - Trumpet 1.pdf")
        assumeTrue(file.isFile)
        val carry = Recognizer.Carry()
        val read = ArrayList<Measure>()
        var number = 1
        for (p in 0 until 2) {
            val (ink, _) = OmrRealPagesTest().renderAt(file, p) ?: break
            val r = Recognizer().read(ink, p, number, carry)
            read += r.measures
            r.measures.lastOrNull()?.let { number = it.number + it.bars }
        }
        val bytes = CleanPrint.pdf(Score(read, 2), "Sweet Caroline", "Trumpet 1")
        // The same, re-written for an alto sax (trumpet 2 above sounding, alto 9).
        System.getProperty("inksheets.shots")?.let { dir ->
            val alto = CleanPrint.pdf(com.inksheets.core.omr.Transpose.forInstrument(Score(read, 2), 2, 9), "Sweet Caroline", "Alto Sax (from Trumpet 1)")
            Loader.loadPDF(alto).use { ImageIO.write(PDFRenderer(it).renderImageWithDPI(0, 110f, ImageType.RGB), "png", File(dir, "clean-print-alto.png")) }
        }
        val out = File(System.getProperty("inksheets.shots") ?: System.getProperty("java.io.tmpdir"), "clean-sweet-caroline.pdf")
        out.parentFile.mkdirs(); out.writeBytes(bytes)
        Loader.loadPDF(bytes).use { doc ->
            println("${doc.numberOfPages} pages, ${bytes.size / 1024} KB, from ${read.size} bars")
            assertTrue(doc.numberOfPages >= 1)
            val renderer = PDFRenderer(doc)
            System.getProperty("inksheets.shots")?.let { ImageIO.write(renderer.renderImageWithDPI(0, 110f, ImageType.RGB), "png", File(it, "clean-print-p1.png")) }
            // Read back at the reader's own scale: a staff space 18 pixels.
            val back = ArrayList<Measure>()
            val c2 = Recognizer.Carry()
            var n2 = 1
            for (p in 0 until doc.numberOfPages) {
                val img = renderer.renderImageWithDPI(p, 72f * 18f / CleanPrint.SPACE, ImageType.RGB)
                val px = IntArray(img.width * img.height); img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
                val r = Recognizer().read(Ink.fromArgb(img.width, img.height, px), p, n2, c2)
                back += r.measures
                r.measures.lastOrNull()?.let { n2 = it.number + it.bars }
            }
            // Bar for bar in order when as many came back: where the source was read with a gap (its
            // printed numbers said a bar was missed), the reprint's bars run on without one, and are
            // counted so until its next line's number puts them right.
            val before = read.associateBy { it.number }
            var same = 0; var compared = 0
            for ((i, m) in back.withIndex()) {
                val o = (if (back.size == read.size) read[i] else before[m.number]) ?: continue
                compared++
                if (o.events.map(::describe) == m.events.map(::describe) && o.bars == m.bars) same++
                else if (compared - same <= 8) println("  m${m.number}: printed ${o.events.map(::describe)} (${o.bars}), read back ${m.events.map(::describe)} (${m.bars})")
            }
            println("read back: ${back.size} bars (numbered to ${back.lastOrNull()?.let { it.number + it.bars - 1 }}) against ${read.size} (to ${read.last().number + read.last().bars - 1}); $same of $compared the same")
            assertEquals(read.last().number + read.last().bars, back.last().number + back.last().bars)
            assertTrue("$same of $compared", same >= compared * 0.9)
        }
    }
}
