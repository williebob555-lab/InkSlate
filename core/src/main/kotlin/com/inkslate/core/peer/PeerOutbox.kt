package com.inkslate.core.peer

import com.inkslate.core.InkDocument

/**
 * What this device has not told the others yet.
 *
 * The obvious way to stream marks is to send each one from the place it is made, and it is wrong:
 * a mark is made in a dozen places. Drawing one, erasing one, undoing that erase, moving a
 * selection, pasting, tidying a shape into a circle, a page rearranged - every one of those would
 * have to remember to speak, and the one that forgot would be a mark that silently never left the
 * device.
 *
 * So nothing announces anything. This holds what was last sent and works out the difference from
 * the document as it now stands, which catches every one of those cases including the ones nobody
 * thought of. It is the same comparison a peer makes when it asks to catch up, against a digest of
 * our own making rather than theirs.
 */
class PeerOutbox {

    private var sent: PeerMessage.Digest? = null

    /**
     * The marks and deletions this device still owes its peers, or null when it owes nothing.
     *
     * The first call after [reset] owes everything, which is what makes a freshly opened document
     * offer itself to a peer that has been editing it elsewhere.
     */
    fun pending(doc: InkDocument): PeerMessage.Marks? {
        val against = sent?.takeIf { it.docId == doc.docId } ?: PeerMessage.Digest(doc.docId)
        val marks = PeerSync.answerFor(doc, against)
        return marks.takeIf { it.strokes.isNotEmpty() || it.deleted.isNotEmpty() }
    }

    /**
     * Remember what has gone out.
     *
     * Taken from the document rather than from the batch: anything that changed while the batch
     * was in flight is then still owed, rather than being recorded as sent because a message
     * carrying an older copy of it happened to leave.
     */
    fun sent(doc: InkDocument) {
        sent = PeerSync.digestOf(doc)
    }

    /** Start again - a different document, or a connection that may have missed something. */
    fun reset() {
        sent = null
    }
}
