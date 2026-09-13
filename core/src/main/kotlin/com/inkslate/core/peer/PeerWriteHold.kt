package com.inkslate.core.peer

import com.inkslate.core.InkDocument

/**
 * Keeping two connected devices from writing the same document at the same moment.
 *
 * That is what Syncthing cannot reconcile. Each device changes the file before the other's change
 * has reached it, so it keeps one and renames the other to a `.sync-conflict-` copy. The link made
 * it happen on every pause of the pen: marks arrived on the second device live, that device wrote
 * them into its own copy of the file a second later, and the device that drew them was writing the
 * same marks into the same file at the same time.
 *
 * Two rules, shared by the tablet and the laptop so that neither is the one still doing it:
 *
 *  - **Marks that came over the link are the drawing device's to write.** While everything unwritten
 *    here arrived from a peer, this device does not write the document; the working copy holds it.
 *  - **A peer's announced write is waited for.** Writing after it has arrived is an edit on top of
 *    theirs; writing before is a conflict.
 *
 * Pure bookkeeping - no clock, no thread, no file - so the decisions can be tested directly.
 */
class PeerWriteHold {
    /**
     * The document as last written here, plus everything the link has brought in since.
     *
     * While the ink on screen holds nothing beyond this, the only unwritten work is another
     * device's - and that device is writing it. Null when nothing has arrived since the last write.
     */
    private var covered: InkDocument? = null
    private var lastArrival = 0L

    /** When a peer said it had written this document, until that write is seen arriving. */
    var peerWroteAt = 0L

    /** The ink last put in the working copy while held, so a held tick does not rewrite it. */
    var journaled: InkDocument? = null

    // The last answer, by identity, so an idle tick does not walk every stroke again.
    private var askedAbout: InkDocument? = null
    private var answer = false

    /**
     * Marks came over the link.
     *
     * Only tracked against a document known to be on disk: with [written] unknown, there is no
     * saying what is unwritten, and the safe answer is to write.
     */
    fun arrived(written: InkDocument?, marks: PeerMessage.Marks, now: Long) {
        val base = covered ?: written ?: return
        covered = PeerSync.applied(base, marks)
        lastArrival = now
        askedAbout = null
    }

    /** This device has just written [written]; anything still arriving is measured from there. */
    fun afterWrite(written: InkDocument?, current: InkDocument) {
        val c = covered
        covered = if (c == null || written == null || current === written) null
        else written.mergeWith(c)
        askedAbout = null
    }

    /** Whether the unwritten work in [ink] is all another device's, which that device is writing. */
    fun leftToPeer(ink: InkDocument, now: Long): Boolean {
        val base = covered ?: return false
        // Not for ever. If the device that drew them never writes - it was closed, it had writes
        // frozen, its sync is paused - this one does, and the working copy has them meanwhile.
        if (now - lastArrival > GRACE_MS) return false
        if (askedAbout !== ink) {
            askedAbout = ink
            answer = PeerSync.holdsEverythingIn(base, ink)
        }
        return answer
    }

    /** Whether a peer's write of this document is on its way and ours should wait for it. */
    fun awaitingPeerWrite(now: Long): Boolean =
        peerWroteAt != 0L && now - peerWroteAt < AWAIT_PEER_WRITE_MS

    fun clear() {
        covered = null
        lastArrival = 0L
        askedAbout = null
        journaled = null
    }

    companion object {
        /** How long marks from another device are left for that device to write. */
        const val GRACE_MS = 120_000L

        /**
         * How long to hold our own write after a peer announces one of the same document.
         *
         * Long enough for Syncthing to notice the change, send it and put it in place, which is
         * usually a few seconds and occasionally tens.
         */
        const val AWAIT_PEER_WRITE_MS = 45_000L
    }
}
