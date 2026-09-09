package com.inkslate.core

/**
 * What a piece of paper is made of: pattern, paper colour, ruling colour and spacing.
 *
 * The pattern is stored by name rather than as an enum, the same way [InkCanvas.background] does,
 * so a build that has never heard of a later pattern still carries it through a rearrangement
 * instead of silently flattening it to plain.
 */
data class PaperSpec(
    val background: String = "PLAIN",
    val paperColor: Int = InkCanvas.WHITE,
    val lineColor: Int = InkCanvas.DEFAULT_RULING,
    val spacing: Float = 24f
)

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
    val paper: PaperSpec = PaperSpec(),
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
 * Adding, removing, duplicating, turning and reordering the pages of a document.
 *
 * ## The two halves have to move together
 *
 * Handwriting is stored per page index. Rearranging the pages without rewriting those indices
 * would leave every mark on the wrong page - so the page tree and the ink are rebuilt in one
 * operation, from one plan, and either both land or neither does. This half is the ink; writing
 * the page tree is each platform's own PDF library.
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
object PagePlan {

    /** The document exactly as it is: the starting point for any plan. */
    fun identity(pageCount: Int): List<PlannedPage> =
        (0 until pageCount).map { PlannedPage(source = it, uid = it.toLong()) }

    /** True when applying [plan] would change nothing. */
    fun isUnchanged(plan: List<PlannedPage>, pageCount: Int): Boolean =
        plan.size == pageCount &&
            plan.withIndex().all { (i, p) -> p.source == i && p.quarterTurns == 0 }

    // ---- editing a plan ------------------------------------------------------

    fun moved(plan: List<PlannedPage>, from: Int, to: Int): List<PlannedPage> {
        if (from !in plan.indices) return plan
        val target = to.coerceIn(0, plan.size - 1)
        if (from == target) return plan
        return plan.toMutableList().also { it.add(target, it.removeAt(from)) }
    }

    fun removed(plan: List<PlannedPage>, at: Int): List<PlannedPage> {
        // A document with no pages is not a document, so the last one cannot be taken away.
        if (at !in plan.indices || plan.size <= 1) return plan
        return plan.toMutableList().also { it.removeAt(at) }
    }

    /** A copy sits straight after its original, which is where anyone expects to find it. */
    fun duplicated(plan: List<PlannedPage>, at: Int, uid: Long): List<PlannedPage> {
        if (at !in plan.indices) return plan
        return plan.toMutableList().also { it.add(at + 1, it[at].copy(uid = uid)) }
    }

    fun turned(plan: List<PlannedPage>, at: Int, quarterTurns: Int): List<PlannedPage> {
        if (at !in plan.indices) return plan
        return plan.toMutableList().also {
            it[at] = it[at].copy(
                quarterTurns = PageTurn.normalise(it[at].quarterTurns + quarterTurns)
            )
        }
    }

    fun inserted(plan: List<PlannedPage>, at: Int, pages: List<PlannedPage>): List<PlannedPage> {
        val target = at.coerceIn(0, plan.size)
        return plan.toMutableList().also { it.addAll(target, pages) }
    }

    // ---- the ink -------------------------------------------------------------

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
