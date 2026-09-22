package com.inkslate.core.peer

/**
 * Where an app plugs into the link: which devices are reachable, what writes have been heard of,
 * and every document open right now.
 *
 * The transport calls in from socket threads; everything here is handed to [post], which runs it on
 * the one thread the editor uses. That is the whole of the threading story - [DocumentSync] never
 * sees two things at once - and it is shared by both apps, so neither has its own version of it to
 * get subtly wrong.
 *
 * More than one document can be attached at a time - a workspace with several tabs open attaches
 * one per tab - and every message already names the document it is about, so each is routed to the
 * matching tab rather than to whichever happened to attach last.
 */
class LinkHub(
    /** Run on the editor's thread, in the order given. */
    private val post: (() -> Unit) -> Unit,
    private val ledger: WriteLedger,
    /** Keep the ledger somewhere that outlives the process. Called on the editor's thread. */
    private val keepLedger: (WriteLedger) -> Unit,
    /** Say something to one device. */
    private val send: (peer: String, message: PeerMessage) -> Unit
) {

    /** What an open document hands the hub. All calls arrive on the editor's thread. */
    interface Document {
        val docId: String
        fun onConnected(peer: String)
        fun onDisconnected(peer: String)
        fun onMessage(peer: String, message: PeerMessage)
    }

    private val reachable = LinkedHashSet<String>()
    private val open = LinkedHashMap<String, Document>()

    // ---- from the transport, on any thread -----------------------------------------

    fun connected(peer: String) = post {
        if (reachable.add(peer)) open.values.toList().forEach { it.onConnected(peer) }
    }

    fun disconnected(peer: String) = post {
        if (reachable.remove(peer)) open.values.toList().forEach { it.onDisconnected(peer) }
    }

    fun message(peer: String, message: PeerMessage) = post {
        // Heard whether or not the document is open here: it is what a document opened a moment
        // from now needs to know.
        if (ledger.heard(message)) keepLedger(ledger)
        val docId = message.routedDocId()
        // A device that has just opened a document waits to hear whether this one has it open
        // before it writes. When it is not open here, that has to be said - silence would leave it
        // waiting, and guessing would leave it writing beside a write it did not know about.
        if (message is PeerMessage.Editing && message.docId !in open) {
            send(peer, PeerMessage.Closed(message.docId, ledger[message.docId]))
        }
        // A message that names no document - a bare "wrote" about a file neither side has open as
        // a tab - is nobody's in particular, so every open document is allowed to look at it and
        // decide for itself whether it cares.
        if (docId != null) open[docId]?.onMessage(peer, message)
        else open.values.toList().forEach { it.onMessage(peer, message) }
    }

    /** The document a message concerns, or null for the handful of message types with none. */
    private fun PeerMessage.routedDocId(): String? = when (this) {
        is PeerMessage.Editing -> docId
        is PeerMessage.Closed -> docId
        is PeerMessage.LeaseRequest -> docId
        is PeerMessage.LeaseGrant -> docId
        is PeerMessage.LeaseState -> docId
        is PeerMessage.Digest -> docId
        is PeerMessage.Want -> docId
        is PeerMessage.Marks -> docId
        is PeerMessage.ImageWant -> docId
        is PeerMessage.ImageData -> docId
        is PeerMessage.Wrote -> docId
        else -> null
    }

    // ---- from the editor, on its thread --------------------------------------------

    /** The latest write of [docId] this device has heard of. */
    fun lastWrite(docId: String): WriteRecord? = ledger[docId]

    /** A document opened. It is told about every device already on the link. */
    fun attach(document: Document) {
        open[document.docId] = document
        reachable.toList().forEach { document.onConnected(it) }
    }

    fun detach(document: Document) {
        if (open[document.docId] === document) open.remove(document.docId)
    }

    /** This device wrote [docId]. */
    fun wrote(docId: String, write: WriteRecord?) {
        if (ledger.note(docId, write)) keepLedger(ledger)
    }

    /** Forget everything about who is reachable - the service stopped. */
    fun reset() = post {
        val peers = reachable.toList()
        reachable.clear()
        val documents = open.values.toList()
        for (peer in peers) documents.forEach { it.onDisconnected(peer) }
    }
}
