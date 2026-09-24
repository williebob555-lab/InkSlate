package com.inksheets.desktop

import com.inksheets.core.InstrumentReader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/**
 * A scanned part - a PDF holding nothing but a picture of the page - read by Tesseract, the way
 * the desktop reads scans. Skipped where Tesseract is not installed; the Linux build installs it.
 */
class RecogniseTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `the instrument is read off a scan`() {
        val platform = DesktopSheetsPlatform {}
        assumeTrue("Tesseract is not installed here", platform.canRecognise)

        // A letter-size page at 150 dpi: title in the middle, the part name top left, staves below.
        val page = BufferedImage(1275, 1650, BufferedImage.TYPE_INT_RGB)
        with(page.createGraphics()) {
            setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            color = Color.WHITE
            fillRect(0, 0, page.width, page.height)
            color = Color.BLACK
            font = Font(Font.SERIF, Font.BOLD, 56)
            drawString("The Liberty Bell", 380, 140)
            font = Font(Font.SERIF, Font.PLAIN, 34)
            drawString("Baritone T.C.", 90, 230)
            drawString("J. P. Sousa", 1000, 230)
            for (staff in 0 until 8) for (line in 0 until 5) {
                val y = 320 + staff * 160 + line * 14
                drawLine(90, y, 1185, y)
            }
            dispose()
        }
        val scan = tmp.newFile("scan.pdf")
        PDDocument().use { doc ->
            val p = PDPage(PDRectangle.LETTER)
            doc.addPage(p)
            val image = LosslessFactory.createFromImage(doc, page)
            PDPageContentStream(doc, p).use { it.drawImage(image, 0f, 0f, p.mediaBox.width, p.mediaBox.height) }
            doc.save(scan)
        }

        // It has no text of its own - that is what makes it a scan.
        assertEquals(null, platform.pageText(scan))
        val text = platform.recognise(scan)
        assertEquals("read: $text", "baritone-tc", text?.let { InstrumentReader.read(it) }?.instrument?.id)
    }
}
