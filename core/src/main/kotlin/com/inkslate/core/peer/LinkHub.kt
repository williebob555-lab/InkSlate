package com.inkslate.core.peer

/**
 * Where an app plugs into the link: which devices are reachable, what writes have been heard of,
 * and the one document open right now.
 *
 * The transport calls in from socket threads; everything here is handed to [post], which runs it on
 * the one thread the editor uses. That is the whole of the threading story - [DocumentSync] never
 * sees two things at once - and it is shared by both apps, so neither has its own version of it to
 * get subtly wrong.
 */
class LinkHub(
    /** Run on the editor's thread, in the order given. */
    private val post: (() -> Unit) -> Unit,
    private val ledger: WriteLedger,
    /** Keep the ledger somewhere that outlives the process. Called on the editor's thread. */
    private val keepLedger: (WriteLedger) -> Unit
) {

    /** What an open document hands the hub. All calls arrive on the editor's thread. */
    interface Document {
        val docId: String
        fun onConnected(peer: String)
        fun onDisconnected(peer: String)
        fun onMessage(peer: String, message: PeerMessage)
    }

    private val reachable = LinkedHashSet<String>()
    private var open: Document? = null

    // ---- from the transport, on any thread -----------------------------------------

    fun connected(peer: String) = post {
        if (reachable.add(peer)) open?.onConnected(peer)
    }

    fun disconnected(peer: String) = post {
        if (reachable.remove(peer)) open?.onDisconnected(peer)
    }

    fun message(peer: String, message: PeerMessage) = post {
        // Heard whether or not the document is open here: it is what a document opened a moment
        // from now needs to know.
        if (ledger.heard(message)) keepLedger(ledger)
        open?.onMessage(peer, message)
    }

    // ---- from the editor, on its thread --------------------------------------------

    /** The latest write of [docId] this device has heard of. */
    fun lastWrite(docId: String): WriteRecord? = ledger[docId]

    /** A document opened. It is told about every device already on the link. */
    fun attach(document: Document) {
        open = document
        reachable.toList().forEach { document.onConnected(it) }
    }

    fun detach(document: Document) {
        if (open === document) open = null
    }

    /** This device wrote [docId]. */
    fun wrote(docId: String, write: WriteRecord?) {
        if (ledger.note(docId, write)) keepLedger(ledger)
    }

    /** Forget everything about who is reachable - the service stopped. */
    fun reset() = post {
        reachable.toList().forEach { open?.onDisconnected(it) }
        reachable.clear()
    }
}
