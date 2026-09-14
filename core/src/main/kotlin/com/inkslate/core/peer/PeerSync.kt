package com.inkslate.core.peer

import com.inkslate.core.InkDocument

/**
 * Working out what one device has to send another, and folding in what arrives.
 *
 * All of it is pure: no sockets, no threads, no clock. That is deliberate - the interesting part
 * of a sync protocol is the decisions, and decisions that can only be watched through a network
 * connection are decisions nobody checks. The transport is a few dozen lines on top of this.
 *
 * Nothing here invents a conflict rule. Everything ends in [InkDocument.mergeWith], the same
 * commutative, tombstone-aware merge the file sync has always used, so the fast path and the slow
 * path cannot reach different answers about the same document.
 */
object PeerSync {

    /** Every mark's id and when it last changed, plus the tombstones. */
    fun digestOf(doc: InkDocument): PeerMessage.Digest = PeerMessage.Digest(
        docId = doc.docId,
        marks = doc.pages.values.flatten().associate { it.id to it.updatedUtc },
        deleted = doc.deleted,
        meta = metaOf(doc),
        layout = doc.layout
    )

    /**
     * A fingerprint of what a document holds besides its marks.
     *
     * Bookmarks and the canvas are a handful of bytes, so rather than track their changes they are
     * sent whole whenever two devices' fingerprints differ - and a fingerprint that never changes
     * is what stops them being sent on every tick.
     */
    fun metaOf(doc: InkDocument): String {
        val text = buildString {
            doc.bookmarks.sortedBy { it.page }.forEach {
                append(it.page).append(':').append(it.label).append('|')
            }
            doc.bookmarksRemoved.keys.sorted().forEach { append('-').append(it).append('|') }
            append('#').append(doc.canvas?.toString().orEmpty())
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /** Whether a tombstone written at [tombstoned] still outranks a mark stamped [updated]. */
    private fun buried(tombstoned: Long?, updated: Long): Boolean =
        tombstoned != null && tombstoned >= updated

    /**
     * The ids worth asking a peer for: what they hold that we do not, or hold a newer copy of.
     *
     * Anything we have already erased is left out. The merge would drop it on arrival anyway, so
     * asking is only a way to spend the connection on marks that are going in the bin.
     */
    fun wantedFrom(ours: InkDocument, theirs: PeerMessage.Digest): List<String> {
        val mine = ours.pages.values.flatten().associate { it.id to it.updatedUtc }
        return theirs.marks.asSequence()
            .filter { (id, updated) ->
                !buried(ours.deleted[id], updated) && (mine[id]?.let { updated > it } ?: true)
            }
            .map { it.key }
            .sorted()
            .toList()
    }

    /**
     * What to send back: the marks they lack or hold an older copy of, and the deletions they
     * have not heard about.
     *
     * [only] narrows it to an explicit request. Without it this answers a digest directly, which
     * is what a freshly connected pair does before either has asked for anything.
     */
    fun answerFor(
        ours: InkDocument,
        theirs: PeerMessage.Digest,
        only: Collection<String>? = null
    ): PeerMessage.Marks {
        val wanted = only?.toSet()
        val strokes = ours.pages.values.flatten().filter { s ->
            if (wanted != null) {
                s.id in wanted
            } else {
                !buried(theirs.deleted[s.id], s.updatedUtc) &&
                    (theirs.marks[s.id]?.let { s.updatedUtc > it } ?: true)
            }
        }
        // Ours that they have not heard about, or heard an older version of.
        val deletions = ours.deleted.filter { (id, at) -> (theirs.deleted[id] ?: -1L) < at }
        // An explicit request is for marks and nothing else; a digest answer brings the rest along
        // when the two devices disagree about it.
        val metaDiffers = wanted == null && theirs.meta != metaOf(ours)
        return PeerMessage.Marks(
            ours.docId, strokes, deletions,
            bookmarks = if (metaDiffers) ours.bookmarks else null,
            bookmarksRemoved = if (metaDiffers) ours.bookmarksRemoved else null,
            canvas = if (metaDiffers) ours.canvas else null,
            layout = ours.layout
        )
    }

    /**
     * Fold an arriving batch into the document we hold.
     *
     * The batch is turned into a document of its own and merged, rather than being applied stroke
     * by stroke, so a single mark leaving a pen and a whole catch-up after a week apart travel the
     * same path - and that path is the one the file sync already trusts.
     */
    fun applied(ours: InkDocument, marks: PeerMessage.Marks): InkDocument {
        if (marks.isEmpty || !canPlace(ours, marks.layout)) return ours
        val incoming = InkDocument(
            docId = ours.docId,
            source = ours.source,
            // Marks laid out for an older arrangement are brought forward by the merge.
            layout = marks.layout,
            pages = marks.strokes.groupBy { it.pageIndex.toString() },
            deleted = marks.deleted,
            bookmarks = marks.bookmarks.orEmpty(),
            bookmarksRemoved = marks.bookmarksRemoved.orEmpty(),
            canvas = marks.canvas
        )
        return ours.mergeWith(incoming)
    }

    /**
     * Whether marks laid out for [layout] can be placed on [ours]: the same arrangement of pages, or
     * one [ours] was rearranged from. Marks for an arrangement [ours] has not reached cannot be -
     * page 2 there is not page 2 here - and wait until this device has the rearranged file.
     */
    fun canPlace(ours: InkDocument, layout: String): Boolean =
        layout == ours.layout ||
            com.inkslate.core.PageStructure.path(layout, ours.layout, ours.structureHistory) != null

    /**
     * Whether a copy of the document that turned up carries anything we do not already hold.
     *
     * For the file that changes under an open editor. Two devices connected to each other write
     * each other's marks to disk constantly, and every one of those writes used to raise "this
     * file changed elsewhere" - a dialog about handwriting that was already on the page, in the
     * middle of drawing. The question worth asking is not whether the file moved but whether
     * anything in it is new.
     */
    fun carriesSomethingNew(ours: InkDocument, arrived: InkDocument): Boolean {
        if (wantedFrom(ours, digestOf(arrived)).isNotEmpty()) return true
        return arrived.deleted.any { (id, at) -> (ours.deleted[id] ?: -1L) < at }
    }

    /**
     * Whether [ours] already holds everything [other] says - marks, erases, bookmarks and room.
     *
     * The stricter cousin of [carriesSomethingNew], for the decisions that give something up on
     * the strength of the answer: deleting a sync-conflict copy, or leaving the writing of a
     * document to the device that drew on it. Handwriting alone is not the whole of a document,
     * and a bookmark lost to a tidy-up is still lost.
     */
    fun holdsEverythingIn(ours: InkDocument, other: InkDocument): Boolean {
        if (wantedFrom(ours, digestOf(other)).isNotEmpty()) return false
        if (other.deleted.isNotEmpty()) {
            val mine = ours.pages.values.flatten().associate { it.id to it.updatedUtc }
            // An erase we have not recorded is still held when the mark was put back after it:
            // merging would change nothing. Without this, a copy that saw an erase which was later
            // undone here would look like news on every open, forever.
            val unheard = other.deleted.any { (id, at) ->
                (ours.deleted[id] ?: -1L) < at && (mine[id]?.let { it <= at } ?: true)
            }
            if (unheard) return false
        }
        val theirBookmarks = other.bookmarks.filter { it.key !in ours.bookmarksRemoved }
        if (!theirBookmarks.all { b -> ours.bookmarks.any { it.page == b.page } }) return false
        // A removal of a bookmark this copy still has is news; one of a bookmark it never had is not.
        if (other.bookmarksRemoved.keys.any { k -> ours.bookmarks.any { it.key == k } }) return false
        val theirs = other.canvas ?: return true
        val mine = ours.canvas ?: return false
        return mine.mergeWith(theirs) == mine
    }

    /** Whether an arriving batch would change anything, for deciding whether to repaint or save. */
    fun changesAnything(ours: InkDocument, marks: PeerMessage.Marks): Boolean {
        val mine = ours.pages.values.flatten().associate { it.id to it.updatedUtc }
        val newMark = marks.strokes.any { s ->
            !buried(ours.deleted[s.id], s.updatedUtc) &&
                (mine[s.id]?.let { s.updatedUtc > it } ?: true)
        }
        val newDeletion = marks.deleted.any { (id, at) ->
            buried(at, mine[id] ?: Long.MIN_VALUE) || (ours.deleted[id] ?: -1L) < at
        }
        if (newMark || newDeletion) return true
        if (marks.bookmarks == null && marks.bookmarksRemoved == null && marks.canvas == null) return false
        return metaOf(applied(ours, marks)) != metaOf(ours)
    }
}
