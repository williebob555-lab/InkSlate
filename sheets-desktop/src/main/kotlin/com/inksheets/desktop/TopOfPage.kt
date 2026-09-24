package com.inksheets.desktop

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The top third of a page as a picture, for text recognition: where a part's title and
 * instrument are printed. Wide enough (1600 px) for small print; the staves below left out.
 */
internal object TopOfPage {

    private const val WIDTH = 1600
    private const val SHARE = 0.34

    fun render(file: File, page: Int): BufferedImage? = runCatching {
        when (file.extension.lowercase()) {
            "pdf" -> Loader.loadPDF(file).use { pdf ->
                if (page < 1 || page > pdf.numberOfPages) return null
                val box = pdf.getPage(page - 1).cropBox
                val scale = WIDTH / box.width
                val full = PDFRenderer(pdf).renderImage(page - 1, scale, ImageType.GRAY)
                full.getSubimage(0, 0, full.width, (full.height * SHARE).toInt().coerceAtLeast(1))
            }
            "png", "jpg", "jpeg", "webp" -> ImageIO.read(file)?.let { full ->
                full.getSubimage(0, 0, full.width, (full.height * SHARE).toInt().coerceAtLeast(1))
            }
            else -> null
        }
    }.getOrNull()
}
