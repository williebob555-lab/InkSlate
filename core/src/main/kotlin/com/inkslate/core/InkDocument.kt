package com.inkslate.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The portable annotation document: `<original filename>.inkdoc`, sitting beside the file it
 * annotates.
 *
 * Why a sidecar rather than writing into the PDF on every save:
 *  - The original stays byte-identical, so Syncthing only ever ships the small sidecar.
 *  - Strokes stay fully editable across devices, with brush, pressure and z-order intact,
 *    which a flattened PDF throws away.
 *  - Two devices that edited offline can be *merged* rather than one silently winning.
 *
 * Exporting a real PDF (annotations or flattened) is a separate, deliberate step.
 *
 * ## Sync model
 *
 * This is a 2P-Set with last-writer-wins on individual objects:
 *  - Stroke ids are globally unique (`<deviceTag>-<counter>`), so concurrent additions merge
 *    cleanly as a union.
 *  - Deletions are recorded as [deleted] tombstones. Without them, a sync from a device that
 *    had not yet seen the delete would resurrect erased strokes.
 *  - Edits to an existing stroke (move, resize, restyle) keep the id, and the copy with the
 *    newer [Stroke.updatedUtc] wins.
 *
 * The result: no lost work when you write on the tablet, close it, and open the laptop before
 * Syncthing has caught up.
 */
@Serializable
data class InkDocument(
    @SerialName("format") val format: String = FORMAT,
    @SerialName("version") val version: Int = VERSION,
    @SerialName("docId") val docId: String,
    @SerialName("source") val source: SourceRef,
    /** Page size in PDF points, index-aligned with the source pages. */
    @SerialName("pageSizes") val pageSizes: List<PageSize> = emptyList(),
    /** Strokes per page index. Keys are strings so the JSON stays portable. */
    @SerialName("pages") val pages: Map<String, List<Stroke>> = emptyMap(),
    /** Tombstones: ids of strokes deleted somewhere, so a merge cannot resurrect them. */
    @SerialName("deleted") val deleted: Map<String, Long> = emptyMap(),
    /** Highest counter seen per device, so id generation never regresses after a merge. */
    @SerialName("clocks") val clocks: Map<String, Long> = emptyMap(),
    /**
     * Reader bookmarks. Kept in the sidecar rather than locally so they travel with the document:
     * marking a chapter on the tablet should mean it is marked on the laptop too.
     */
    @SerialName("bookmarks") val bookmarks: List<Bookmark> = emptyList(),
    /**
     * Set when this document is a canvas that grows to fit what is drawn on it.
     *
     * Null for an ordinary paginated document, which is nearly all of them - a worksheet has the
     * edges it has, and pretending otherwise would be wrong rather than generous.
     */
    @SerialName("canvas") val canvas: InkCanvas? = null,
    @SerialName("createdUtc") val createdUtc: Long = 0,
    @SerialName("modifiedUtc") val modifiedUtc: Long = 0,
    @SerialName("modifiedBy") val modifiedBy: String = "",
    @SerialName("app") val app: String = "InkSlate"
) {

    @Serializable
    data class SourceRef(
        @SerialName("name") val name: String,
        @SerialName("kind") val kind: String,          // "pdf" | "image" | "blank"
        @SerialName("sizeBytes") val sizeBytes: Long = 0,
        /**
         * Cheap fingerprint of the original bytes.
         *
         * Kept for diagnostics only. It cannot answer "did the document change under us",
         * because the handwriting now lives *inside* the document: every save rewrites the file
         * and so changes its length and timestamp. Using it for that is what made every single
         * open announce that the PDF had changed. See [geometry].
         */
        @SerialName("fingerprint") val fingerprint: String = "",
        /**
         * Page count and page sizes, which is what "the annotations may no longer line up"
         * actually depends on. Unchanged by embedding ink, so it stays true across saves.
         */
        @SerialName("geometry") val geometry: String = "",
        @SerialName("pageCount") val pageCount: Int = 1
    )

    @Serializable
    data class PageSize(@SerialName("w") val w: Float, @SerialName("h") val h: Float)

    @Serializable
    data class Bookmark(
        @SerialName("page") val page: Int,
        @SerialName("label") val label: String,
        @SerialName("createdUtc") val createdUtc: Long = 0
    )

    fun strokesOn(page: Int): List<Stroke> = pages[page.toString()].orEmpty()

    fun allStrokeIds(): List<String> = pages.values.flatten().map { it.id }

    val totalStrokes: Int get() = pages.values.sumOf { it.size }

    fun withPage(page: Int, strokes: List<Stroke>, byDevice: String): InkDocument {
        val now = System.currentTimeMillis()
        // anything that used to be on this page and is not any more becomes a tombstone
        val previous = strokesOn(page).map { it.id }.toSet()
        val remaining = strokes.map { it.id }.toSet()
        val newTombs = previous - remaining
        // ...and anything that is on the page now is not deleted, whatever it was a moment ago.
        //
        // A tombstone used to be permanent, so a stroke that came back under its own id - which
        // is exactly what undo does after an erase, and redo after an undo - was left both
        // present and deleted at once. Nothing looked wrong: the page is what gets drawn and
        // exported, so the stroke was on screen and in the file. It was [mergeWith] that dropped
        // it, on the next open, when the tombstone finally got a say. The handwriting was written
        // out correctly and then read back short.
        //
        // Clearing our own tombstone is not enough once another device has seen the erase: its
        // copy still carries one, and a merge with it would take the stroke away again. So a
        // stroke being restored is stamped as of now - it does exist, as of now - which is what
        // lets it outrank a tombstone written before it. See [mergeWith].
        val restored = remaining.filter { it in deleted }.toSet()
        val restamped = if (restored.isEmpty()) {
            strokes
        } else {
            // At least a millisecond past the tombstone it is overruling. Two operations can land
            // in the same millisecond, and on equal terms the tombstone wins - which would make an
            // undo that was quick enough silently fail to travel.
            strokes.map {
                if (it.id in restored) {
                    it.copy(updatedUtc = maxOf(now, (deleted[it.id] ?: 0L) + 1L))
                } else {
                    it
                }
            }
        }
        return copy(
            pages = pages + (page.toString() to restamped),
            deleted = deleted.filterKeys { it !in remaining } + newTombs.associateWith { now },
            clocks = clocks + (byDevice to maxOf(clocks[byDevice] ?: 0L, now)),
            modifiedUtc = now,
            modifiedBy = byDevice
        )
    }

    fun withBookmarkAdded(page: Int, label: String): InkDocument {
        if (bookmarks.any { it.page == page }) return this
        return copy(
            bookmarks = (bookmarks + Bookmark(page, label, System.currentTimeMillis()))
                .sortedBy { it.page },
            modifiedUtc = System.currentTimeMillis()
        )
    }

    fun withBookmarkRemoved(page: Int): InkDocument = copy(
        bookmarks = bookmarks.filterNot { it.page == page },
        modifiedUtc = System.currentTimeMillis()
    )

    fun isBookmarked(page: Int) = bookmarks.any { it.page == page }

    fun serialize(): String = JSON.encodeToString(this)

    /**
     * Merge another copy of this document into this one.
     *
     * Union the strokes, drop anything tombstoned on either side, and settle per-stroke
     * conflicts by [Stroke.updatedUtc]. Deliberately commutative, so it does not matter which
     * device runs it or in what order sync delivered the files.
     */
    fun mergeWith(other: InkDocument): InkDocument {
        // The later of the two, per stroke: an erase that happened after another device's erase
        // is still an erase, and the time of it is what a restore has to beat.
        val tombs = (deleted.keys + other.deleted.keys).associateWith {
            maxOf(deleted[it] ?: 0L, other.deleted[it] ?: 0L)
        }
        val pageKeys = pages.keys + other.pages.keys
        val mergedPages = pageKeys.associateWith { key ->
            val mine = pages[key].orEmpty()
            val theirs = other.pages[key].orEmpty()
            val byId = LinkedHashMap<String, Stroke>(mine.size + theirs.size)
            for (s in mine) byId[s.id] = s
            for (s in theirs) {
                val existing = byId[s.id]
                // last writer wins; ties keep the incoming copy so the merge stays deterministic
                if (existing == null || s.updatedUtc >= existing.updatedUtc) byId[s.id] = s
            }
            // A tombstone removes a stroke unless the stroke is newer than the tombstone. Erasing
            // therefore still sticks across every device that has not touched it since - and an
            // undo, which restamps what it puts back, still reaches a device that saw the erase.
            byId.values.filterNot { s -> tombs[s.id]?.let { it >= s.updatedUtc } ?: false }
        }.filterValues { it.isNotEmpty() }

        val mergedClocks = (clocks.keys + other.clocks.keys).associateWith {
            maxOf(clocks[it] ?: 0L, other.clocks[it] ?: 0L)
        }

        val mergedBookmarks = (bookmarks + other.bookmarks)
            .distinctBy { it.page }
            .sortedBy { it.page }

        // The union of two canvases, which cannot lose anyone's room to write and does not
        // depend on which device merged first. See [InkCanvas.mergeWith].
        val mergedCanvas = when {
            canvas == null -> other.canvas
            other.canvas == null -> canvas
            else -> canvas.mergeWith(other.canvas)
        }

        return copy(
            pages = mergedPages,
            canvas = mergedCanvas,
            bookmarks = mergedBookmarks,
            deleted = tombs,
            clocks = mergedClocks,
            pageSizes = if (pageSizes.size >= other.pageSizes.size) pageSizes else other.pageSizes,
            modifiedUtc = maxOf(modifiedUtc, other.modifiedUtc),
            modifiedBy = if (modifiedUtc >= other.modifiedUtc) modifiedBy else other.modifiedBy
        )
    }

    /**
     * Drop this document's tombstones for strokes it still holds.
     *
     * Within one document those two things cannot both be true, and a document written before
     * [withPage] cleared restored ids may say so anyway. Left alone, [mergeWith] believes the
     * tombstone and the stroke vanishes on the next open - which is how handwriting that was
     * written out correctly came back short.
     *
     * Applied to each source *before* they are merged, never after, and that ordering is the
     * whole of its safety. A device that erased a stroke holds a tombstone and no stroke, so
     * there is nothing here for it to clear; the tombstone survives into the merge and still
     * wins against a device that has not seen the erase yet. Repairing after the merge would
     * instead resurrect everything anyone had ever deleted.
     */
    fun withoutSelfContradiction(): InkDocument {
        if (deleted.isEmpty()) return this
        val present = HashSet<String>()
        for (page in pages.values) for (stroke in page) present.add(stroke.id)
        val kept = deleted.filterKeys { it !in present }
        return if (kept.size == deleted.size) this else copy(deleted = kept)
    }

    /**
     * Drop tombstones that every known device has certainly seen, so the file does not grow
     * without bound over a semester. Conservative: keeps anything recent.
     */
    fun compacted(olderThanMillis: Long = COMPACT_AFTER_MS): InkDocument {
        val cutoff = System.currentTimeMillis() - olderThanMillis
        return copy(deleted = deleted.filterValues { it >= cutoff })
    }

    companion object {
        const val FORMAT = "inkdoc"
        const val VERSION = 1
        const val EXTENSION = "inkdoc"

        /**
         * How long a tombstone is kept.
         *
         * Was thirty days, and applied only to the working copy. It is now applied to the copy
         * inside the document as well, so that the two are the same text and a save can serialise
         * once instead of twice - which is a quarter of a second on a marked-up assignment, paid
         * every time the pen pauses. Lengthened to match: a tombstone has to outlive the longest
         * a device might plausibly be away, or that device's stale copy of a deleted stroke comes
         * back on the next sync. Half a year covers a semester and a summer.
         */
        const val COMPACT_AFTER_MS = 180L * 24 * 3600 * 1000

        val JSON = Json {
            prettyPrint = false
            ignoreUnknownKeys = true      // forward compatibility with newer writers
            // Only write what differs from the default. A stroke has twenty-eight fields and a
            // handwritten one uses a handful; the rest were being spelled out in full, per
            // stroke, on every save - table rows on a pen mark, a font on a scribble. Readers
            // fill defaults in, and unknown keys are already ignored, so a file written this way
            // still opens on a build that predates it and vice versa.
            encodeDefaults = false
            isLenient = true
        }

        fun parse(text: String): InkDocument? = runCatching {
            JSON.decodeFromString<InkDocument>(text)
        }.getOrNull()?.takeIf { it.format == FORMAT }

        fun create(sourceName: String, kind: String, pageCount: Int, sizeBytes: Long, fingerprint: String) =
            InkDocument(
                docId = java.util.UUID.randomUUID().toString(),
                source = SourceRef(
                    name = sourceName, kind = kind, sizeBytes = sizeBytes,
                    fingerprint = fingerprint, pageCount = pageCount
                ),
                createdUtc = System.currentTimeMillis(),
                modifiedUtc = System.currentTimeMillis()
            )

        /** Sidecar path for a given source file: `homework.pdf` -> `homework.pdf.inkdoc`. */
        fun sidecarPathFor(sourcePath: String) = "$sourcePath.$EXTENSION"
    }
}
