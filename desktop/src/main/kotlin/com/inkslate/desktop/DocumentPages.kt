package com.inkslate.desktop

import com.inkslate.core.InkDocument
import com.inkslate.core.PagePlan
import com.inkslate.core.PaperSpec
import com.inkslate.core.PlannedPage
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.util.Matrix
import java.awt.image.BufferedImage
import java.io.File
import java.io.FileOutputStream
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min

/**
 * Adding, removing, duplicating, turning and reordering the pages of a document.
 *
 * The plan and what it does to the handwriting are `core/PagePlan`, shared with the tablet -
 * rearranging rewrites where every mark lives, and that has to be one description rather than
 * two. This is the other half: rebuilding the PDF's page tree, which is each platform's own
 * PDFBox and cannot be shared.
 *
 * The two halves land together or not at all. A document whose pages moved and whose ink did not
 * is worse than either edit alone.
 */
object DocumentPages {

    /** Longest side of an imported picture, in pixels. Roughly 300dpi across a letter page. */
    private const val MAX_IMAGE_PX = 2600

    /**
     * Apply [plan] to [source], rewriting the pages and the handwriting in one operation.
     *
     * Returns the remapped handwriting, which the caller writes back into the document - so a
     * failure part-way leaves the file exactly as it was rather than half-rearranged.
     */
    fun rearrange(
        source: File,
        ink: InkDocument,
        plan: List<PlannedPage>,
        newId: () -> String
    ): Result<InkDocument> = runCatching {
        require(DesktopSources.isPdf(source)) { "Only PDFs can have their pages rearranged" }

        val sizes = HashMap<Int, Pair<Float, Float>>()
        val remapped = Loader.loadPDF(source).use { pdf ->
            for (i in 0 until pdf.numberOfPages) {
                val box = pdf.getPage(i).let { it.cropBox ?: it.mediaBox }
                sizes[i] = box.width to box.height
            }
            val ordered = PagePlan.remapInk(ink, plan, newId) { i ->
                sizes[i] ?: (612f to 792f)
            }

            applyToPdf(pdf, plan).use {
                val tmp = File(source.parentFile, "." + source.name + ".pages")
                FileOutputStream(tmp).use { out -> pdf.save(out) }
                if (!tmp.renameTo(source)) {
                    tmp.copyTo(source, overwrite = true)
                    tmp.delete()
                }
            }
            ordered
        }
        remapped
    }

    /**
     * Write a grown canvas's page out at the size it has grown to.
     *
     * Only for canvas documents, and only when they have actually outgrown their page - growth is
     * free while drawing precisely because it is deferred to here. Returns the canvas with its
     * paper recorded as caught up.
     */
    fun growCanvas(source: File, canvas: com.inkslate.core.InkCanvas): Result<
        com.inkslate.core.InkCanvas
        > = runCatching {
        require(DesktopSources.isPdf(source)) { "Only a PDF can be grown" }
        if (!canvas.paperIsBehind) return@runCatching canvas.withPaperMatched()

        var grown = canvas
        Loader.loadPDF(source).use { pdf ->
            grown = CanvasPaper.grow(pdf, canvas)
            val tmp = File(source.parentFile, "." + source.name + ".grow")
            FileOutputStream(tmp).use { out -> pdf.save(out) }
            if (!tmp.renameTo(source)) {
                tmp.copyTo(source, overwrite = true)
                tmp.delete()
            }
        }
        grown
    }

    /**
     * Rebuild [pdf]'s page tree to match [plan].
     *
     * Inherited attributes are written onto each page first. A PDF is free to put the page size,
     * the rotation and even the resources on a parent node and let the pages inherit them, and
     * moving a page to a different parent silently loses whatever it was inheriting - which shows
     * up as pages that come back the wrong size or blank. Resolving them beforehand costs a
     * dictionary entry per page and removes the whole class of failure.
     */
    fun applyToPdf(pdf: PDDocument, plan: List<PlannedPage>): AutoCloseable {
        val originals = (0 until pdf.numberOfPages).map { pdf.getPage(it) }
        originals.forEach { materialiseInherited(it) }

        // Documents being borrowed from stay open until the save has finished: an imported page
        // still reads its resources out of the file it came from.
        val borrowed = BorrowedDocuments()

        val used = HashSet<Int>()
        val rebuilt = plan.mapNotNull { p ->
            val page = when {
                p.import != null -> importedPage(pdf, p, borrowed)
                p.isNew -> blankPage(pdf, p)
                p.source !in originals.indices -> null      // a plan built against another document
                // The first appearance of a page is the page itself; a second appearance has to
                // be a copy, because one dictionary cannot sit in the page tree twice.
                used.add(p.source) -> originals[p.source]
                else -> duplicate(originals[p.source])
            }
            page?.also {
                if (p.quarterTurns != 0) {
                    it.rotation = normaliseDegrees(it.rotation + p.quarterTurns * 90)
                }
            }
        }

        // Clear the tree of everything currently in it - which includes pages the builders above
        // added themselves - and lay it out again in the order the plan asked for.
        val present = (0 until pdf.numberOfPages).map { pdf.getPage(it) }
        for (page in present) pdf.removePage(page)
        for (page in rebuilt) pdf.addPage(page)
        return borrowed
    }

    private fun normaliseDegrees(deg: Int) = ((deg % 360) + 360) % 360

    /**
     * A page taken from another file.
     *
     * A PDF page is imported rather than redrawn, so its text stays text and its fonts stay
     * embedded. An image becomes a page with the picture drawn onto it - scaled to fit and
     * centred, so a photograph of a worksheet lands the right way up and the right size instead
     * of being stretched to whatever shape the page happens to be.
     */
    private fun importedPage(
        pdf: PDDocument,
        p: PlannedPage,
        borrowed: BorrowedDocuments
    ): PDPage? {
        val spec = p.import ?: return null
        return if (spec.isImage) imagePage(pdf, spec) else {
            val other = borrowed.get(spec.path) ?: return null
            if (spec.pageIndex !in 0 until other.numberOfPages) return null
            runCatching { pdf.importPage(other.getPage(spec.pageIndex)) }.getOrNull()
        }
    }

    private fun imagePage(pdf: PDDocument, spec: com.inkslate.core.ImportedPage): PDPage? {
        val picture = decodeForPage(File(spec.path)) ?: return null
        val page = PDPage(PDRectangle(spec.width, spec.height))
        runCatching {
            val image = LosslessFactory.createFromImage(pdf, picture)
            // Fit inside the page, preserving the picture's own shape, and centre what is left.
            val scale = min(spec.width / picture.width, spec.height / picture.height)
            val w = picture.width * scale
            val h = picture.height * scale
            PDPageContentStream(pdf, page).use { cs ->
                cs.drawImage(image, (spec.width - w) / 2f, (spec.height - h) / 2f, w, h)
            }
        }
        return page
    }

    /**
     * Decode an image at a sane size for a printed page.
     *
     * A 50-megapixel phone photo carries no more detail at page size than a fraction of it does,
     * and embedding the original would make a page weigh more than the textbook it was added to.
     */
    private fun decodeForPage(file: File): BufferedImage? = runCatching {
        val full = ImageIO.read(file) ?: return null
        val longest = max(full.width, full.height)
        if (longest <= MAX_IMAGE_PX) return full
        val scale = MAX_IMAGE_PX.toFloat() / longest
        val w = (full.width * scale).toInt().coerceAtLeast(1)
        val h = (full.height * scale).toInt().coerceAtLeast(1)
        val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        scaled.createGraphics().apply {
            setRenderingHint(
                java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR
            )
            drawImage(full, 0, 0, w, h, null)
            dispose()
        }
        scaled
    }.getOrNull()

    private fun materialiseInherited(page: PDPage) {
        val dict = page.cosObject
        if (dict.getDictionaryObject(COSName.MEDIA_BOX) == null) page.mediaBox = page.mediaBox
        if (dict.getDictionaryObject(COSName.CROP_BOX) == null) page.cropBox = page.cropBox
        if (dict.getDictionaryObject(COSName.ROTATE) == null) page.rotation = page.rotation
        if (dict.getDictionaryObject(COSName.RESOURCES) == null) page.resources = page.resources
    }

    /**
     * A shallow copy of a page.
     *
     * Content streams and resources are shared, which is the point: duplicating a page of a
     * scanned textbook must not duplicate its images. The annotation array is copied, though -
     * shared, an annotation added to one copy would appear on the other, and this app writes
     * annotations into pages when it exports.
     */
    private fun duplicate(page: PDPage): PDPage {
        val copy = COSDictionary(page.cosObject)
        (page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray)?.let { annots ->
            val cloned = COSArray()
            for (i in 0 until annots.size()) cloned.add(annots.getObject(i))
            copy.setItem(COSName.ANNOTS, cloned)
        }
        // /Parent is set by the page tree when this is added; leaving the old one behind makes
        // some readers walk back up into the document it came from.
        copy.removeItem(COSName.PARENT)
        return PDPage(copy)
    }

    private fun blankPage(pdf: PDDocument, p: PlannedPage): PDPage {
        val page = PDPage(PDRectangle(p.blankWidth, p.blankHeight))
        runCatching {
            PDPageContentStream(pdf, page).use { cs ->
                // flip into PDF user space: display y runs down, user space runs up
                cs.transform(Matrix(1f, 0f, 0f, -1f, 0f, p.blankHeight))
                BlankDocumentFactory.paintBackground(
                    cs,
                    backgroundOf(p.paper),
                    p.blankWidth,
                    p.blankHeight,
                    p.paper.paperColor,
                    p.paper.lineColor,
                    p.paper.spacing
                )
            }
        }
        return page
    }

    /** The plan names its pattern; a name this build has never heard of is plain paper. */
    private fun backgroundOf(spec: PaperSpec): BlankDocumentFactory.Background =
        runCatching { BlankDocumentFactory.Background.valueOf(spec.background) }
            .getOrDefault(BlankDocumentFactory.Background.PLAIN)

    /** Documents opened only to lift pages out of, closed together once the save is done. */
    class BorrowedDocuments : AutoCloseable {
        private val open = LinkedHashMap<String, PDDocument>()

        fun get(path: String): PDDocument? = open.getOrPut(path) {
            runCatching { Loader.loadPDF(File(path)) }.getOrNull() ?: return null
        }

        override fun close() {
            open.values.forEach { runCatching { it.close() } }
            open.clear()
        }
    }
}
