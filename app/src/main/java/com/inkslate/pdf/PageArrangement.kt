package com.inkslate.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.inkslate.core.InkDocument
import com.inkslate.core.PageTurn
import com.inkslate.core.Stroke
import com.inkslate.core.turnedWithPage
import com.inkslate.ui.toStyle
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * One page in a planned arrangement.
 *
 * A plan is a list of these: the document the user is asking for, described entirely in terms of
 * the document they have. Nothing is applied until they commit, so removing thirty pages and
 * changing their mind costs nothing.
 */
/**
 * The plan itself lives in `:core`, shared with the Windows build.
 *
 * Rearranging pages rewrites where every mark lives, so what a plan *means* - which source page
 * becomes which, how the ink is turned with it, which bookmarks follow, and why every stroke is
 * re-issued under a fresh id - has to be one description rather than two. What stays here is the
 * half that cannot be shared: rebuilding a PDF's page tree with this platform's PDFBox.
 */
typealias PlannedPage = com.inkslate.core.PlannedPage
typealias ImportedPage = com.inkslate.core.ImportedPage

object PageArrangement {

    /** The document exactly as it is: the starting point for any plan. */
    fun identity(pageCount: Int): List<PlannedPage> =
        com.inkslate.core.PagePlan.identity(pageCount)

    /** True when applying [plan] would change nothing. */
    fun isUnchanged(plan: List<PlannedPage>, pageCount: Int): Boolean =
        com.inkslate.core.PagePlan.isUnchanged(plan, pageCount)

    // ---- the PDF side --------------------------------------------------------

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

    /** Documents opened only to lift pages out of, closed together once the save is done. */
    class BorrowedDocuments : AutoCloseable {
        private val open = LinkedHashMap<String, PDDocument>()

        fun get(path: String): PDDocument? = open.getOrPut(path) {
            runCatching { PDDocument.load(File(path)) }.getOrNull() ?: return null
        }

        override fun close() {
            open.values.forEach { runCatching { it.close() } }
            open.clear()
        }
    }

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

    private fun imagePage(pdf: PDDocument, spec: ImportedPage): PDPage? {
        val bitmap = decodeForPage(File(spec.path)) ?: return null
        val page = PDPage(PDRectangle(spec.width, spec.height))
        runCatching {
            val image = LosslessFactory.createFromImage(pdf, bitmap)
            // Fit inside the page, preserving the picture's own shape, and centre what is left.
            val scale = min(spec.width / bitmap.width, spec.height / bitmap.height)
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            PDPageContentStream(pdf, page).use { cs ->
                cs.drawImage(image, (spec.width - w) / 2f, (spec.height - h) / 2f, w, h)
            }
        }
        bitmap.recycle()
        return page
    }

    /**
     * Decode an image at a sane size for a printed page.
     *
     * A 50-megapixel phone photo carries no more detail at page size than a fraction of it does,
     * and embedding the original would make a page weigh more than the textbook it was added to.
     */
    private fun decodeForPage(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val longest = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / sample > MAX_IMAGE_PX) sample *= 2
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }.getOrNull()

    /** Longest side of an imported picture, in pixels. Roughly 300dpi across a letter page. */
    private const val MAX_IMAGE_PX = 2600

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
        // The page does not need to be in the tree yet: the content stream only needs the
        // document to allocate its object in. It is added in order with the rest.
        runCatching {
            PDPageContentStream(pdf, page).use { cs ->
                BlankDocumentFactory.paintBackground(
                    cs, p.paper.toStyle().background, p.blankWidth, p.blankHeight,
                    p.paper.paperColor, p.paper.lineColor, p.paper.spacing
                )
            }
        }
        return page
    }

    // ---- the ink side --------------------------------------------------------

    /**
     * Rewrite the handwriting to match [plan]. Shared with the Windows build - see the note on
     * `core/PagePlan` about why every stroke is re-issued under a new id.
     */
    fun remapInk(
        ink: InkDocument,
        plan: List<PlannedPage>,
        pageSizeOf: (Int) -> Pair<Float, Float> = { i ->
            ink.pageSizes.getOrNull(i)?.let { it.w to it.h } ?: (612f to 792f)
        }
    ): InkDocument = com.inkslate.core.PagePlan.remapInk(ink, plan, pageSizeOf)
}
