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

    // ---- several pages at once ------------------------------------------------
    //
    // A selection is held as uids rather than positions, because positions are exactly what these
    // operations change: after a bulk copy the third page is somewhere else, and a selection of
    // indices would silently point at different pages than the ones that were picked.

    /** Positions of the selected pages, in document order. */
    fun indicesOf(plan: List<PlannedPage>, uids: Set<Long>): List<Int> =
        plan.indices.filter { plan[it].uid in uids }

    /** Every page from one to the other inclusive, whichever comes first - a shift-click. */
    fun rangeBetween(plan: List<PlannedPage>, fromUid: Long, toUid: Long): Set<Long> {
        val a = plan.indexOfFirst { it.uid == fromUid }
        val b = plan.indexOfFirst { it.uid == toUid }
        if (a < 0 || b < 0) return setOfNotNull(plan.firstOrNull { it.uid == toUid }?.uid)
        return (minOf(a, b)..maxOf(a, b)).map { plan[it].uid }.toSet()
    }

    /**
     * Take the selected pages out.
     *
     * Refused outright when it would leave nothing, rather than quietly keeping one of them: which
     * page survived would be an accident, and a document with none is not a document.
     */
    fun removedAll(plan: List<PlannedPage>, uids: Set<Long>): List<PlannedPage> {
        val kept = plan.filter { it.uid !in uids }
        return if (kept.isEmpty()) plan else kept
    }

    /**
     * Copy the selected pages, as one run straight after the last of them.
     *
     * A block rather than each copy beside its own original: copying pages 2-4 is asking for a
     * second go at that section, and 2 3 4 2 3 4 is that, where 2 2 3 3 4 4 is not. Returns the
     * new plan and the uids of the copies, so the selection can move onto what was just made.
     */
    fun duplicatedAll(
        plan: List<PlannedPage>,
        uids: Set<Long>,
        nextUid: () -> Long
    ): Pair<List<PlannedPage>, Set<Long>> {
        val at = indicesOf(plan, uids)
        if (at.isEmpty()) return plan to emptySet()
        val copies = at.map { plan[it].copy(uid = nextUid()) }
        return inserted(plan, at.last() + 1, copies) to copies.map { it.uid }.toSet()
    }

    fun turnedAll(plan: List<PlannedPage>, uids: Set<Long>, quarterTurns: Int): List<PlannedPage> =
        plan.map {
            if (it.uid in uids) {
                it.copy(quarterTurns = PageTurn.normalise(it.quarterTurns + quarterTurns))
            } else {
                it
            }
        }

    /**
     * Move every selected page one place, earlier ([step] < 0) or later.
     *
     * A selected page only swaps with an unselected neighbour, so a run of selected pages moves
     * as a block and a page already against the end waits there while the rest catch up - the
     * same thing a list of files does when several are nudged at once.
     */
    fun movedAll(plan: List<PlannedPage>, uids: Set<Long>, step: Int): List<PlannedPage> {
        if (step == 0 || uids.isEmpty()) return plan
        val out = plan.toMutableList()
        val order = if (step < 0) out.indices.toList() else out.indices.reversed()
        val pinned = BooleanArray(out.size)
        for (i in order) {
            if (out[i].uid !in uids) continue
            val j = if (step < 0) i - 1 else i + 1
            if (j !in out.indices || pinned[j] || out[j].uid in uids) {
                pinned[i] = true
                continue
            }
            val t = out[i]; out[i] = out[j]; out[j] = t
        }
        return out
    }

    // ---- the ink -------------------------------------------------------------

    /**
     * The handwriting rearranged to match [plan]. See [PageStructure] for how every device reaches
     * the same result, which is what lets a rearrangement travel.
     */
    fun remapInk(
        ink: InkDocument,
        plan: List<PlannedPage>,
        /** The document's real page sizes, which the recorded ones can lag behind. */
        pageSizeOf: (Int) -> Pair<Float, Float> = { i ->
            ink.pageSizes.getOrNull(i)?.let { it.w to it.h } ?: (612f to 792f)
        }
    ): InkDocument = PageStructure.restructure(ink, plan, pageSizeOf).first
}
