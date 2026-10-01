package com.inksheets.desktop

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/** A page of a PDF as printed, at -Dinksheets.omr.zoom, to -Dinksheets.shots/print-<name>-p<N>.png: to set beside a redraw. -Dinksheets.omr=print */
class PageCrop {
    @Test
    fun `the page as printed`() {
        assumeTrue(System.getProperty("inksheets.omr") == "print")
        val file = File(System.getProperty("inksheets.omr.file")!!)
        val k = (System.getProperty("inksheets.omr.zoom") ?: "3").toFloat()
        val p = (System.getProperty("inksheets.omr.page") ?: "1").toInt() - 1
        val img = Loader.loadPDF(file).use { PDFRenderer(it).renderImageWithDPI(p, 72f * k, ImageType.RGB) }
        ImageIO.write(img, "png", File(System.getProperty("inksheets.shots"), "print-${file.nameWithoutExtension.replace(Regex("[^A-Za-z0-9]+"), "-")}-p${p + 1}.png"))
    }
}
