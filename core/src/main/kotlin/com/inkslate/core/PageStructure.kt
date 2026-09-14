package com.inkslate.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** One page of a rearranged document: which page it came from (-1 for a new one) and its turn. */
@Serializable
data class StructurePage(
    @SerialName("s") val source: Int,
    @SerialName("q") val turns: Int = 0
)

/**
 * A change to a document's pages, kept in the document for good.
 *
 * Handwriting is stored per page index, so moving pages means moving every mark - and two devices
 * have to agree on the result, down to the id each moved mark ends up with. Recording the change
 * itself, rather than only its outcome, is what lets any copy of the document that is still laid
 * out the old way be brought forward exactly: the other device's live marks, a working copy that
 * was not written yet, a file from a device that was switched off.
 */
@Serializable
data class StructureChange(
    /** The layout this change starts from. "" is the document as it was made. */
    @SerialName("from") val from: String,
    @SerialName("to") val to: String,
    @SerialName("pages") val pages: List<StructurePage>,
    /** The size of each page before the change, which turning a page's marks needs. */
    @SerialName("sourceSizes") val sourceSizes: List<InkDocument.PageSize> = emptyList(),
    /** The size of each page after it. */
    @SerialName("sizes") val sizes: List<InkDocument.PageSize> = emptyList(),
    @SerialName("at") val at: Long = 0
)

/**
 * Rearranging a document's pages in a way every device reaches identically.
 *
 * ## Why ids are derived, not minted
 *
 * The old way gave every moved mark a fresh id from the device doing the moving. Only that device
 * could produce those ids, so a mark drawn on another device a moment before the rearrangement
 * reached it had nowhere to go: its old id was retired, and nothing said what it should become.
 * Deriving the new id from the old id and the change means every device that applies the same
 * change to the same mark produces the same new mark - and so the merge that has always kept
 * devices in step keeps doing so across a rearrangement.
 */
object PageStructure {

    /** Changes kept in a document. Older ones cannot be brought forward, and are very rare. */
    const val HISTORY_LIMIT = 32

    /** The id a mark takes on after [change]: the [occurrence]th copy of its page (0 unless duplicated). */
    fun remappedId(id: String, change: StructureChange, occurrence: Int): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$id|${change.to}|$occurrence".toByteArray(Charsets.UTF_8))
        // Never shaped like a device's own "tag-counter" ids, so it cannot collide with one.
        return "m" + digest.take(10).joinToString("") { "%02x".format(it) }
    }

    /**
     * Rearrange [ink] by [plan]: the document after, and the change that got it there.
     *
     * [sizeOf] gives each current page's real size; [to] names the new layout.
     */
    fun restructure(
        ink: InkDocument,
        plan: List<PlannedPage>,
        sizeOf: (Int) -> Pair<Float, Float>,
        at: Long = System.currentTimeMillis(),
        to: String = java.util.UUID.randomUUID().toString()
    ): Pair<InkDocument, StructureChange> {
        val sourceCount = maxOf(
            ink.source.pageCount,
            ink.pageSizes.size,
            (plan.maxOfOrNull { it.source } ?: -1) + 1
        )
        val sourceSizes = (0 until sourceCount).map { i ->
            val (w, h) = sizeOf(i)
            InkDocument.PageSize(w, h)
        }
        val sizes = plan.map { p ->
            val (w, h) = when {
                p.import != null -> p.import.width to p.import.height
                p.isNew -> p.blankWidth to p.blankHeight
                else -> sizeOf(p.source)
            }
            if (PageTurn.swapsDimensions(p.quarterTurns)) InkDocument.PageSize(h, w)
            else InkDocument.PageSize(w, h)
        }
        val change = StructureChange(
            from = ink.layout,
            to = to,
            pages = plan.map { StructurePage(if (it.isNew) -1 else it.source, PageTurn.normalise(it.quarterTurns)) },
            sourceSizes = sourceSizes,
            sizes = sizes,
            at = at
        )
        return apply(ink, change) to change
    }

    /**
     * How long before the layout it was made in an erase can still be dated: two devices' clocks
     * disagree by a second or two. Kept short, because an erase dated inside it is carried through
     * the next rearrangement as well - harmless, but two rearrangements within it double those.
     */
    private const val CLOCK_SLACK_MS = 5_000L

    /**
     * Apply [change] to [doc], which has to be laid out as [StructureChange.from]. [history] is
     * what is known of how the document got there.
     */
    fun apply(
        doc: InkDocument,
        change: StructureChange,
        history: List<StructureChange> = doc.structureHistory
    ): InkDocument {
        require(doc.layout == change.from) { "Cannot apply a change from ${change.from} to ${doc.layout}" }
        val copies = HashMap<Int, Int>()
        val pages = LinkedHashMap<String, List<Stroke>>()
        for ((index, page) in change.pages.withIndex()) {
            if (page.source < 0) continue
            val occurrence = copies[page.source] ?: 0
            copies[page.source] = occurrence + 1
            val strokes = doc.strokesOn(page.source)
            if (strokes.isEmpty()) continue
            val size = change.sourceSizes.getOrNull(page.source)
                ?: doc.pageSizes.getOrNull(page.source)
                ?: InkDocument.PageSize(612f, 792f)
            // Turning the page without turning what is written on it would leave the marks beside
            // the work rather than on it. The time a mark was last changed is kept, so that an edit
            // made anywhere after the rearrangement still outranks this copy of it.
            pages[index.toString()] = strokes.map {
                it.turnedWithPage(page.turns, size.w, size.h)
                    .copy(id = remappedId(it.id, change, occurrence), pageIndex = index)
            }
        }

        val mostCopies = maxOf(1, copies.values.maxOrNull() ?: 1)
        val deleted = HashMap(doc.deleted)
        // Erased before the change means erased in every copy made by it - a device that had not
        // heard of the erase may still hold the mark, and brings it across under its new id.
        //
        // Only erases made in the layout being left need carrying. Tombstones from further back are
        // for marks that were already gone when that layout was made, and the ids retired by it are
        // for marks that crossed it; carrying those too would double the tombstones with every
        // rearrangement.
        val entered = history.lastOrNull { it.to == change.from }
        val retiredAt = history.mapTo(HashSet()) { it.at }
        val carried = if (entered == null) doc.deleted else doc.deleted.filterValues {
            it >= entered.at - CLOCK_SLACK_MS && it !in retiredAt
        }
        for ((id, at) in carried) {
            for (o in 0 until mostCopies) {
                val moved = remappedId(id, change, o)
                deleted[moved] = maxOf(deleted[moved] ?: 0L, at)
            }
        }
        // And the ids that were live are retired as of the change - a safety net for anything that
        // merges a copy in without bringing it forward first.
        for (s in doc.pages.values.flatten()) {
            deleted[s.id] = maxOf(deleted[s.id] ?: 0L, change.at)
        }

        // A bookmark follows its page to wherever it went; one whose page was removed goes with it.
        val bookmarks = doc.bookmarks.mapNotNull { bm ->
            val at = change.pages.indexOfFirst { it.source == bm.page }
            if (at < 0) null else bm.copy(page = at)
        }.sortedWith(compareBy<InkDocument.Bookmark>({ it.page }, { it.createdUtc }, { it.label }))
            .distinctBy { it.page }

        return doc.copy(
            pages = pages,
            pageSizes = change.sizes.ifEmpty { doc.pageSizes },
            bookmarks = bookmarks,
            deleted = deleted,
            layout = change.to,
            structureHistory = withChange(merged(doc.structureHistory, history), change),
            // The pages deliberately changed, so the recorded geometry is stale by design. Blanking
            // it lets the next open re-stamp it silently instead of announcing a change the user
            // just made on purpose.
            source = doc.source.copy(pageCount = change.pages.size, geometry = ""),
            modifiedUtc = maxOf(doc.modifiedUtc, change.at)
        )
    }

    /** [history] with [change] in it, once, oldest first, and bounded. */
    fun withChange(history: List<StructureChange>, change: StructureChange): List<StructureChange> =
        merged(history, listOf(change))

    /** Two histories as one: each change once, in the order they happened. */
    fun merged(a: List<StructureChange>, b: List<StructureChange>): List<StructureChange> {
        if (b.isEmpty()) return a
        if (a.isEmpty()) return b
        return (a + b).distinctBy { it.to }
            .sortedWith(compareBy<StructureChange>({ it.at }, { it.to }))
            .takeLast(HISTORY_LIMIT)
    }

    /** The changes leading from layout [from] to [to], or null when [history] has no such path. */
    fun path(from: String, to: String, history: List<StructureChange>): List<StructureChange>? {
        if (from == to) return emptyList()
        val next = history.groupBy { it.from }
        // Breadth first: two devices that both rearranged while apart leave a branch, and the
        // wrong branch must not be taken just because it is listed first.
        val queue = ArrayDeque(listOf(from to emptyList<StructureChange>()))
        val seen = HashSet<String>()
        while (queue.isNotEmpty()) {
            val (at, route) = queue.removeFirst()
            if (!seen.add(at)) continue
            for (change in next[at].orEmpty()) {
                val longer = route + change
                if (change.to == to) return longer
                queue.addLast(change.to to longer)
            }
        }
        return null
    }

    /** [doc] brought forward to layout [target] through [history], or null when it cannot be. */
    fun bringTo(doc: InkDocument, target: String, history: List<StructureChange>): InkDocument? {
        val route = path(doc.layout, target, history) ?: return null
        var current = doc
        for (change in route) current = apply(current, change, history)
        return current
    }

    /**
     * Of two layouts reached separately, the one kept: the later rearrangement, and on a tie the
     * same one on every device.
     */
    fun laterLayout(a: String, b: String, history: List<StructureChange>): String =
        if (outranks(a, layoutAt(a, history), b, layoutAt(b, history))) a else b

    /** When [layout] was made, or -1 for the document as it was made or a layout not in [history]. */
    fun layoutAt(layout: String, history: List<StructureChange>): Long =
        history.lastOrNull { it.to == layout }?.at ?: -1L

    /** Whether layout [a], made at [atA], is kept over [b] made at [atB] when neither led to the other. */
    fun outranks(a: String, atA: Long, b: String, atB: Long): Boolean =
        if (atA != atB) atA > atB else a > b
}
