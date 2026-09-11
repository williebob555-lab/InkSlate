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
        deleted = doc.deleted
    )

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
        return PeerMessage.Marks(ours.docId, strokes, deletions)
    }

    /**
     * Fold an arriving batch into the document we hold.
     *
     * The batch is turned into a document of its own and merged, rather than being applied stroke
     * by stroke, so a single mark leaving a pen and a whole catch-up after a week apart travel the
     * same path - and that path is the one the file sync already trusts.
     */
    fun applied(ours: InkDocument, marks: PeerMessage.Marks): InkDocument {
        if (marks.strokes.isEmpty() && marks.deleted.isEmpty()) return ours
        val incoming = InkDocument(
            docId = ours.docId,
            source = ours.source,
            pages = marks.strokes.groupBy { it.pageIndex.toString() },
            deleted = marks.deleted
        )
        return ours.mergeWith(incoming)
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
        return newMark || newDeletion
    }
}
