package com.inkslate.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.common.PDRectangle
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

class PdfSource(
    private val file: File,
    /**
     * Read the document without keeping its file open.
     *
     * For the editor, which holds a document for as long as it is on screen. Windows refuses to let
     * anything replace a file this program holds open - measured, not assumed: see
     * OpenDocumentLetsGoTest - and replacing it is exactly how file sync delivers the other
     * device's copy. With a document open here, the tablet's writes were refused over and over and
     * arrived only once it was closed. Short-lived readers, such as a thumbnail, leave this off and
     * close the file within the call.
     */
    detached: Boolean = false
) : DesktopSource {

    /** The copy the document was read from when it was too large to hold in memory, if any. */
    private var detachedCopy: File? = null

    private val doc: PDDocument = when {
        !detached -> Loader.loadPDF(file)
        file.length() <= IN_MEMORY_BYTES -> Loader.loadPDF(file.readBytes())
        else -> {
            // A large book read into memory whole is a large book's worth of heap for the whole
            // session. A private copy costs a moment of disk instead, and nothing syncs it.
            val dir = File(
                System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "InkSlate/open"
            ).apply { mkdirs() }
            val copy = File(dir, "${java.util.UUID.randomUUID()}.pdf")
            file.copyTo(copy, overwrite = true)
            copy.deleteOnExit()
            detachedCopy = copy
            Loader.loadPDF(copy)
        }
    }
    private val renderer = pictureOf(doc)
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

    /**
     * Render part of a page by asking for a smaller page, rather than by moving the paper.
     *
     * The obvious way - draw the page into a bitmap the size of the part, shifted so the rest
     * falls off the edges - produces a black rectangle for some documents. Measured, not guessed:
     * with no shift at all, no clip, and either kind of bitmap, the graphics-surface renderer
     * returns black for a page whose content it renders perfectly well the ordinary way. Whatever
     * it does with that page's transparency does not survive being drawn anywhere but its own
     * image.
     *
     * So the page is narrowed instead. A PDF page already carries the rectangle of itself that is
     * meant to be shown; setting that to the part wanted and rendering normally uses the path that
     * works, and returns exactly the piece asked for. The rectangle is put back afterwards -
     * nothing is written, but this document is open for the life of the editor and every other
     * reader of it expects the page it opened with.
     *
     * A rotated page is refused rather than guessed at: the caller renders the whole page, which
     * is what it did before, and the only cost is a picture bigger than it needs to be.
     */
    override fun renderRegion(index: Int, region: Box, targetWidthPx: Int): ImageBitmap? =
        synchronized(lock) {
            if (index !in 0 until doc.numberOfPages) return null
            if (region.width <= 0f || region.height <= 0f) return null
            val page = doc.getPage(index)
            if (((page.rotation % 360) + 360) % 360 != 0) return null
            val whole = page.cropBox ?: page.mediaBox ?: return null
            val kept = PDRectangle(
                whole.lowerLeftX, whole.lowerLeftY, whole.width, whole.height
            )
            return runCatching {
                // No upper limit on how magnified the piece may be, only on how many pixels
                // it comes to - which the caller has already decided by asking for a width. The
                // limit here is left over from rendering whole pages, where the magnification
                // was what decided the size of the bitmap; a piece of a page has a size of its
                // own, and capping the magnification instead just renders it too small and then
                // stretches it. That is a picture of the page going soft as the view comes in -
                // and on a whiteboard, where the picture covers ruling that is drawn underneath
                // it perfectly sharply, it reads as the background fading away entirely.
                val scale = (targetWidthPx / region.width).coerceAtLeast(0.05f)
                // The page's own coordinates count upwards from the bottom; ink and the screen
                // count downwards from the top, which is the whole of the conversion.
                page.setCropBox(
                    PDRectangle(
                        kept.lowerLeftX + region.left,
                        kept.upperRightY - region.bottom,
                        region.width,
                        region.height
                    )
                )
                pictureOf(doc).renderImage(index, scale).toComposeImageBitmap()
            }.also {
                page.setCropBox(kept)
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

    private companion object {
        /** Documents up to this size are read into memory when opened detached. */
        const val IN_MEMORY_BYTES = 48L * 1024 * 1024

        /**
         * A renderer that leaves out the handwriting this app wrote into the file.
         *
         * A saved document carries its marks twice: as PDF annotations, so they show in any other
         * reader, and as the editable copy that is drawn on top here. The tablet has always taken
         * its page pictures from a copy with those annotations removed. This build drew them, so
         * every mark had a second, flattened twin under it - invisible only while the two lined
         * up exactly, and plainly doubled the moment they did not, which a whiteboard that had
         * grown since the page was read was enough to cause.
         *
         * Filtered at render time rather than stripped from the document, which is open for the
         * life of the editor and must stay what is on disk. Anyone else's annotations still show.
         */
        fun pictureOf(doc: PDDocument): PDFRenderer = PDFRenderer(doc).apply {
            setAnnotationsFilter { annotation -> !isOurs(annotation.cosObject) }
        }

        // Both builds' keys: the laptop and the tablet each wrote a slightly different one, and
        // either can be the one that last saved the file.
        private val KEYS = listOf("InkSlateObj", "InkSlateObject")

        fun isOurs(dict: COSDictionary): Boolean =
            dict.getString(COSName.T) == "InkSlate" ||
                KEYS.any { dict.getString(COSName.getPDFName(it)) == "InkSlate" }
    }

    override fun close() {
        runCatching { doc.close() }
        detachedCopy?.let { runCatching { it.delete() } }
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

    /** Open a document to draw from. [detached] for anything that keeps it open - see [PdfSource]. */
    fun open(file: File, detached: Boolean = false): DesktopSource? = runCatching {
        when {
            isPdf(file) -> PdfSource(file, detached)
            isImage(file) -> ImageSource(file)
            else -> null
        }
    }.getOrNull()

    fun fingerprint(file: File) = "${file.length()}-${file.lastModified()}"
}
