package com.inkslate.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.inkslate.core.InkDocument
import com.inkslate.core.PageTurn
import com.inkslate.core.Stroke
import com.inkslate.core.turnedWithPage
import com.inkslate.ui.PaperStyle
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
data class PlannedPage(
    /** Index in the document as it stands, or -1 for a page that is not from it. */
    val source: Int,
    /** Stable while the plan is being edited, so a thumbnail follows its page as it is dragged. */
    val uid: Long,
    val blankWidth: Float = 612f,
    val blankHeight: Float = 792f,
    /**
     * What a new page is made of: pattern, paper colour, ruling colour and spacing.
     *
     * The same description the New-document screen builds, so a page inserted into a document has
     * every option a page created with one does.
     */
    val paper: PaperStyle = PaperStyle(),
    /** Clockwise quarter turns to apply to this page, 0 to 3. */
    val quarterTurns: Int = 0,
    /** Where this page came from, when it came from another file. */
    val import: ImportedPage? = null
) {
    /** A page this document does not have yet: blank paper, or brought in from elsewhere. */
    val isNew: Boolean get() = source < 0

    val isImported: Boolean get() = import != null

    /** Displayed size once the turn is taken into account. */
    fun displayWidth(fallback: Float): Float {
        val w = import?.width ?: (if (isNew) blankWidth else fallback)
        val h = import?.height ?: (if (isNew) blankHeight else fallback)
        return if (PageTurn.swapsDimensions(quarterTurns)) h else w
    }
}

/**
 * A page borrowed from another file.
 *
 * Both a page of another PDF and a plain image land here, because from the plan's point of view
 * they are the same thing: a page this document is about to gain, whose content is somewhere else
 * on disk until it is committed.
 */
data class ImportedPage(
    val path: String,
    /** Page within that PDF, or 0 for an image. */
    val pageIndex: Int,
    val isImage: Boolean,
    /** Size of the page this will become, in points. */
    val width: Float,
    val height: Float,
    /** Images only: fit the picture inside a page of the document's own size instead. */
    val fitToPage: Boolean = false
)

/**
 * Adding, removing, duplicating and reordering the pages of a PDF.
 *
 * ## The two halves have to move together
 *
 * Handwriting is stored per page index. Rearranging the pages of the document without rewriting
 * those indices would leave every mark on the wrong page - so the page tree and the ink are
 * rebuilt in one operation, from one plan, and either both land or neither does.
 *
 * ## Why every stroke gets a new id
 *
 * The sync model merges two copies of a document by page index, and settles ties per stroke id.
 * That is exactly the right model while pages stand still and exactly the wrong one the moment
 * they do not: a device that had not yet seen the rearrangement would merge its copy back in and
 * scatter old marks across the new order. Re-issuing every stroke under a fresh id and
 * tombstoning the old one makes the stale copy contribute nothing, because tombstones are unioned
 * from both sides of a merge. It costs a churn of ids once, and it is the difference between a
 * rearranged document syncing cleanly and syncing into a mess.
 */
object PageArrangement {

    /** The document exactly as it is: the starting point for any plan. */
    fun identity(pageCount: Int): List<PlannedPage> =
        (0 until pageCount).map { PlannedPage(source = it, uid = it.toLong()) }

    /** True when applying [plan] would change nothing. */
    fun isUnchanged(plan: List<PlannedPage>, pageCount: Int): Boolean =
        plan.size == pageCount &&
            plan.withIndex().all { (i, p) -> p.source == i && p.quarterTurns == 0 }

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
                    cs, p.paper.background, p.blankWidth, p.blankHeight,
                    p.paper.paperColor, p.paper.lineColor, p.paper.spacing
                )
            }
        }
        return page
    }

    // ---- the ink side --------------------------------------------------------

    /**
     * Move the handwriting to match [plan], re-issuing every stroke under a fresh id.
     *
     * [newId] supplies those ids; it must not repeat one this document has ever used, which is
     * what a device-tagged generator seeded from the existing ids guarantees.
     */
    fun remapInk(
        ink: InkDocument,
        plan: List<PlannedPage>,
        newId: () -> String,
        /** The document's real page sizes, which the recorded ones can lag behind. */
        pageSizeOf: (Int) -> Pair<Float, Float> = { i ->
            ink.pageSizes.getOrNull(i)?.let { it.w to it.h } ?: (612f to 792f)
        }
    ): InkDocument {
        val now = System.currentTimeMillis()
        val pages = LinkedHashMap<String, List<Stroke>>()
        for ((newIndex, p) in plan.withIndex()) {
            if (p.isNew) continue
            val existing = ink.strokesOn(p.source)
            if (existing.isEmpty()) continue
            val (w, h) = pageSizeOf(p.source)
            pages[newIndex.toString()] = existing.map {
                // Turning the page without turning what is written on it would leave the marks
                // beside the work rather than on it.
                it.turnedWithPage(p.quarterTurns, w, h)
                    .copy(id = newId(), pageIndex = newIndex, updatedUtc = now)
            }
        }

        // Everything that was here before is now retired, whether it moved, was copied or was
        // dropped with its page. See the note on this object about why.
        val retired = ink.pages.values.flatten().map { it.id }

        val sizes = plan.map { p ->
            val (w, h) = when {
                p.import != null -> p.import.width to p.import.height
                p.isNew -> p.blankWidth to p.blankHeight
                else -> pageSizeOf(p.source)
            }
            if (PageTurn.swapsDimensions(p.quarterTurns)) InkDocument.PageSize(h, w)
            else InkDocument.PageSize(w, h)
        }

        // A bookmark follows its page to wherever that page ended up; one whose page was removed
        // goes with it.
        val bookmarks = ink.bookmarks.mapNotNull { bm ->
            val at = plan.indexOfFirst { !it.isNew && it.source == bm.page }
            if (at < 0) null else bm.copy(page = at)
        }.distinctBy { it.page }.sortedBy { it.page }

        return ink.copy(
            pages = pages,
            pageSizes = sizes,
            bookmarks = bookmarks,
            deleted = ink.deleted + retired.associateWith { now },
            // The page layout has deliberately changed, so the recorded geometry is stale by
            // design. Blanking it lets the next open re-stamp it silently instead of announcing
            // a change the user just made on purpose.
            source = ink.source.copy(pageCount = plan.size, geometry = ""),
            modifiedUtc = now
        )
    }
}
