package com.inkslate.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.image.BufferedImage
import java.io.Closeable
import com.inkslate.core.Box
import java.io.File
import kotlin.math.roundToInt
import javax.imageio.ImageIO

data class PageDim(val width: Float, val height: Float)

/**
 * A paginated document the desktop app can draw on.
 *
 * Unlike the Android port, full Apache PDFBox renders pages itself, so there is no separate
 * rendering engine here - the same library reads the file, rasterises it, and writes the export.
 */
interface DesktopSource : Closeable {
    val pageCount: Int
    val kind: String
    fun pageDim(index: Int): PageDim
    fun render(index: Int, targetWidthPx: Int): ImageBitmap?

    /**
     * Render only [region] of a page, in page points, [targetWidthPx] pixels across.
     *
     * The whole page at the size it is being shown at is a bitmap of some seventy megabytes once
     * the view is magnified, which is far too big for a graphics card to keep hold of - so it was
     * being sent across again for every frame, at around forty milliseconds a page. What is on
     * screen is never bigger than the window, however far in the view goes.
     *
     * Null from a source that cannot do it, and the caller falls back to the whole page.
     */
    fun renderRegion(index: Int, region: Box, targetWidthPx: Int): ImageBitmap? = null
}

class PdfSource(private val file: File) : DesktopSource {

    private val doc: PDDocument = Loader.loadPDF(file)
    private val renderer = PDFRenderer(doc)
    private val lock = Any()

    override val kind = "pdf"
    override val pageCount: Int get() = doc.numberOfPages

    override fun pageDim(index: Int): PageDim = synchronized(lock) {
        if (index !in 0 until doc.numberOfPages) return PageDim(612f, 792f)
        val page = doc.getPage(index)
        val box = page.cropBox ?: page.mediaBox
        val rotated = ((page.rotation % 360) + 360) % 360 == 90 ||
            ((page.rotation % 360) + 360) % 360 == 270
        // report the page the way it is displayed, matching how ink coordinates were captured
        return if (rotated) PageDim(box.height, box.width) else PageDim(box.width, box.height)
    }

    override fun renderRegion(index: Int, region: Box, targetWidthPx: Int): ImageBitmap? =
        synchronized(lock) {
            if (index !in 0 until doc.numberOfPages) return null
            if (region.width <= 0f || region.height <= 0f) return null
            return runCatching {
                val scale = (targetWidthPx / region.width).coerceIn(0.05f, 12f)
                val w = (region.width * scale).roundToInt().coerceIn(1, 8_000)
                val h = (region.height * scale).roundToInt().coerceIn(1, 8_000)
                val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                val g = image.createGraphics()
                g.color = java.awt.Color.WHITE
                g.fillRect(0, 0, w, h)
                // Slide the page under the window this bitmap is, then let the renderer draw it
                // at the size it would have been. Everything outside falls off the edges.
                g.translate(
                    (-region.left * scale).toDouble(),
                    (-region.top * scale).toDouble()
                )
                renderer.renderPageToGraphics(index, g, scale)
                g.dispose()
                image.toComposeImageBitmap()
            }.getOrNull()
        }

    override fun render(index: Int, targetWidthPx: Int): ImageBitmap? = synchronized(lock) {
        if (index !in 0 until doc.numberOfPages) return null
        return runCatching {
            val dim = pageDim(index)
            val scale = targetWidthPx / dim.width
            // renderImage takes a scale factor relative to 72dpi, which is exactly page points
            val image: BufferedImage = renderer.renderImage(index, scale.coerceIn(0.2f, 6f))
            image.toComposeImageBitmap()
        }.getOrNull()
    }

    /** The underlying document, for export. */
    fun document(): PDDocument = doc

    override fun close() {
        runCatching { doc.close() }
    }
}

class ImageSource(private val file: File) : DesktopSource {

    private val image: BufferedImage? = runCatching { ImageIO.read(file) }.getOrNull()

    override val kind = "image"
    override val pageCount = 1

    override fun pageDim(index: Int) =
        PageDim((image?.width ?: 1000).toFloat(), (image?.height ?: 1000).toFloat())

    override fun render(index: Int, targetWidthPx: Int): ImageBitmap? =
        image?.toComposeImageBitmap()

    override fun close() = Unit
}

object DesktopSources {

    private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")

    fun isPdf(f: File) = f.extension.equals("pdf", ignoreCase = true)
    fun isImage(f: File) = f.extension.lowercase() in IMAGE_EXT
    fun isSupported(f: File) = isPdf(f) || isImage(f)

    fun open(file: File): DesktopSource? = runCatching {
        when {
            isPdf(file) -> PdfSource(file)
            isImage(file) -> ImageSource(file)
            else -> null
        }
    }.getOrNull()

    fun fingerprint(file: File) = "${file.length()}-${file.lastModified()}"
}
