package com.inkslate.core.peer

import com.inkslate.core.InkDocument
import com.inkslate.core.Stroke
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey

/**
 * Keeps your own devices in step while they are both awake.
 *
 * The documents still travel as files, and that is still what makes them arrive on a device that
 * was switched off. This is the fast path for the other case: two of your devices running at the
 * same time and able to reach each other, with the same worksheet open. A mark leaves the pen on
 * one and appears on the other, without waiting for a folder to be scanned.
 *
 * Written once, here, because both builds are JVM programs and the sockets, the threads and the
 * cipher are identical on each. What differs is only which document is open and what to do when
 * marks arrive, which is what [Host] is for.
 *
 * A device is identified by its tag - the same one that labels its strokes - and not by its
 * address. Addresses move: a tailnet name, a home network, a hotspot. Pairing binds a secret to a
 * tag once, and after that either side may be found at whatever address it turns up on.
 */
class PeerService(private val host: Host) {

    /** What the app around this service has to answer, and what it wants to be told. */
    interface Host {
        /** This installation's stable id, the same one that labels its strokes. */
        fun deviceTag(): String

        /** What to call this device on the other one's screen. */
        fun deviceName(): String

        /** The document open right now, if any: its id, its name, and the marks in it. */
        fun openDocument(): Open?

        /** Marks arrived for a document. Called off the UI thread. */
        fun onMarks(docId: String, marks: PeerMessage.Marks)

        /** A peer wrote a file, or changed what is in its library ([fileName] null). */
        fun onRemoteWrite(peer: String, fileName: String?)

        /** A pairing completed. The app is expected to store this and include it next time. */
        fun onPaired(peer: Peer) {}

        /** Something about the peer list changed, for a screen showing it. */
        fun onPeersChanged() {}

        /** Somewhere to put a line about what happened. */
        fun log(level: String, message: String) {}
    }

    /**
     * The document open right now.
     *
     * Its identity is the one inside it - [InkDocument.docId] - and not something the app chooses
     * alongside. Two devices holding the same worksheet agree about that id because it travels in
     * the file; anything else would be two names for one thing, and the first bug it caused was a
     * catch-up that silently matched nothing.
     */
    data class Open(val fileName: String, val doc: InkDocument) {
        val docId: String get() = doc.docId
    }

    /**
     * A device you have paired with.
     *
     * [tag] is who it is; [host] is only where it was last seen, and is replaced whenever it turns
     * up somewhere else - by discovery on a local network, or by an incoming connection.
     */
    data class Peer(
        val tag: String,
        val name: String,
        val host: String,
        val port: Int = DEFAULT_PORT,
        val code: String
    )

    /** What a screen shows about a peer. */
    data class Status(val peer: Peer, val connected: Boolean, val lastSeenUtc: Long)

    private val connections = ConcurrentHashMap<String, Connection>()
    private val statuses = ConcurrentHashMap<String, Status>()
    private val known = ConcurrentHashMap<String, Peer>()

    /** A code offered for the next few minutes, so a new device can introduce itself once. */
    @Volatile private var pendingCode: String? = null
    @Volatile private var pendingUntil: Long = 0

    @Volatile private var listenPort: Int = DEFAULT_PORT
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private val threads = mutableListOf<Thread>()

    val port: Int get() = listenPort

    fun statuses(): List<Status> = known.values.map {
        statuses[it.tag] ?: Status(it, connected = false, lastSeenUtc = 0)
    }.sortedBy { it.peer.name.lowercase() }

    fun isConnected(tag: String): Boolean = connections[tag]?.closed == false

    /**
     * Start listening, and start reaching out to each known peer.
     *
     * Safe to call again with a new list: connections are rebuilt, which is what adding a device
     * from the settings screen does.
     */
    @Synchronized
    fun start(peers: List<Peer>, port: Int = DEFAULT_PORT) {
        stop()
        known.clear()
        peers.forEach { known[it.tag] = it }
        listenPort = port
        running = true

        // Bound here rather than on the thread: a caller that has just been told the service is
        // running should be able to be connected to, and a port already in use is worth knowing
        // about now rather than as a silence later.
        val socket = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
            }
        }.getOrElse {
            host.log("warn", "Could not listen on $port: ${it.message}")
            null
        }
        server = socket
        if (socket != null) {
            host.log("info", "Listening for your other devices on port $port")
            thread("inkslate-peer-listen") {
                while (running) {
                    val client = runCatching { socket.accept() }.getOrElse { return@thread }
                    thread("inkslate-peer-in") { serve(client, expecting = null) }
                }
            }
        }

        thread("inkslate-peer-out") {
            while (running) {
                for (peer in known.values) {
                    if (connections[peer.tag]?.closed == false) continue
                    runCatching {
                        val socket = Socket()
                        socket.connect(InetSocketAddress(peer.host, peer.port), CONNECT_MS)
                        thread("inkslate-peer-conn") { serve(socket, expecting = peer) }
                    }.onFailure {
                        // Expected and frequent: the other device is asleep or out of reach.
                        noteStatus(peer, connected = false)
                    }
                }
                sleep(RETRY_MS)
            }
        }
    }

    /**
     * Take on a peer without restarting.
     *
     * Pairing happens while the service is running, and dropping every other connection to welcome
     * a new device would be a strange way to do it.
     */
    fun addPeer(peer: Peer) {
        known[peer.tag] = peer
        host.onPeersChanged()
    }

    fun forgetPeer(tag: String) {
        known.remove(tag)
        connections.remove(tag)?.close()
        statuses.remove(tag)
        host.onPeersChanged()
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        connections.values.forEach { it.close() }
        connections.clear()
        threads.forEach { it.interrupt() }
        threads.clear()
    }

    // ---- pairing ---------------------------------------------------------------

    /**
     * Offer a code for a few minutes, so another device can introduce itself.
     *
     * Shown on the device being paired *to*. An unknown device that can prove it knows this code
     * becomes a peer; anything else is disconnected without a word.
     */
    fun offerPairing(code: String, forMs: Long = PAIRING_WINDOW_MS) {
        pendingCode = code
        pendingUntil = System.currentTimeMillis() + forMs
        host.log("info", "Pairing code offered for ${forMs / 60_000} minutes")
    }

    fun stopOfferingPairing() {
        pendingCode = null
        pendingUntil = 0
    }

    /**
     * Introduce this device to one at [address], using the code showing on its screen.
     *
     * Returns the peer on success. Runs on the calling thread and is expected to be called off the
     * user interface: it opens a socket and derives a key, which is slow on purpose.
     */
    fun pairWith(address: String, port: Int = DEFAULT_PORT, code: String): Result<Peer> =
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address, port), CONNECT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
                val out = DataOutputStream(socket.getOutputStream().buffered())
                val input = DataInputStream(socket.getInputStream().buffered())
                val theirTag = exchangeTags(out, input)
                require(theirTag != host.deviceTag()) { "That address is this device" }
                val key = PeerFrames.keyFrom(code, PeerFrames.saltFor(host.deviceTag(), theirTag))
                PeerFrames.write(out, key, hello())
                val theirs = PeerFrames.read(input, key) as? PeerMessage.Hello
                    ?: error("That device did not answer as InkSlate")
                require(theirs.protocol == PeerMessage.PROTOCOL) {
                    "That device is running a different version of InkSlate"
                }
                val peer = Peer(
                    tag = theirs.deviceTag,
                    name = theirs.deviceName,
                    host = address,
                    port = theirs.port.takeIf { it > 0 } ?: port,
                    code = code
                )
                addPeer(peer)
                host.onPaired(peer)
                peer
            }
        }

    // ---- what the app tells its peers -----------------------------------------

    /** Marks just made here. Sent as they happen, which is the whole point of the connection. */
    fun sendMarks(docId: String, strokes: List<Stroke>, deleted: Map<String, Long> = emptyMap()) {
        if (strokes.isEmpty() && deleted.isEmpty()) return
        broadcast(PeerMessage.Marks(docId, strokes, deleted))
    }

    /** "I have this open now", so the other side knows to stream to us, and to catch us up. */
    fun announceOpen() {
        val open = host.openDocument() ?: return
        broadcast(PeerMessage.Editing(open.docId, open.fileName))
        broadcast(PeerSync.digestOf(open.doc))
    }

    fun announceClosed(docId: String) = broadcast(PeerMessage.Closed(docId))

    /** "I have written this file" - not the bytes, just the news, so the other side looks again. */
    fun announceWrote(fileName: String, sizeBytes: Long, modifiedUtc: Long) =
        broadcast(PeerMessage.Wrote(fileName, sizeBytes, modifiedUtc))

    fun announceLibraryChanged() = broadcast(PeerMessage.LibraryChanged)

    private fun broadcast(message: PeerMessage) {
        for (c in connections.values) c.send(message)
    }

    /** Told by discovery that a known device is answering at a new address. */
    fun noteSeenAt(tag: String, address: String, port: Int) {
        val peer = known[tag] ?: return
        if (peer.host == address && peer.port == port) return
        known[tag] = peer.copy(host = address, port = port)
        host.onPeersChanged()
    }

    // ---- one connection --------------------------------------------------------

    private fun hello() = PeerMessage.Hello(
        deviceTag = host.deviceTag(),
        deviceName = host.deviceName(),
        port = listenPort
    )

    /**
     * Say who we are, and hear who they are, before anything is encrypted.
     *
     * A device tag is not a secret - it appears in every stroke id in the document - and both ends
     * need the other's before either can derive the key, because the salt is the pair of them.
     */
    private fun exchangeTags(out: DataOutputStream, input: DataInputStream): String {
        out.writeUTF(HANDSHAKE)
        out.writeUTF(host.deviceTag())
        out.flush()
        require(input.readUTF() == HANDSHAKE) { "Not an InkSlate device" }
        return input.readUTF()
    }

    private fun serve(socket: Socket, expecting: Peer?) {
        socket.tcpNoDelay = true
        socket.soTimeout = READ_TIMEOUT_MS
        var registered: String? = null
        try {
            val out = DataOutputStream(socket.getOutputStream().buffered())
            val input = DataInputStream(socket.getInputStream().buffered())
            val theirTag = exchangeTags(out, input)
            if (expecting != null && expecting.tag != theirTag) return
            // A peer's address can end up pointing back here - a stale record, or a loopback name.
            // Talking to yourself is harmless but it is not a peer, and it would sit in the list
            // looking like one.
            if (theirTag == host.deviceTag()) return

            // A device we know, or one presenting itself with a code we are currently offering.
            val offered = pendingCode?.takeIf { System.currentTimeMillis() < pendingUntil }
            val secret = known[theirTag]?.code ?: offered ?: return
            val key = PeerFrames.keyFrom(secret, PeerFrames.saltFor(host.deviceTag(), theirTag))

            PeerFrames.write(out, key, hello())
            val theirs = PeerFrames.read(input, key) as? PeerMessage.Hello ?: return
            if (theirs.protocol != PeerMessage.PROTOCOL) {
                host.log("warn", "${theirs.deviceName} is running a different version of InkSlate")
                return
            }

            val address = socket.inetAddress?.hostAddress.orEmpty()
            // Their listening port, not the one this socket happens to have come from.
            val theirPort = theirs.port.takeIf { it > 0 } ?: known[theirTag]?.port ?: DEFAULT_PORT
            val peer = known[theirTag]?.copy(
                name = theirs.deviceName,
                host = address.ifBlank { known[theirTag]?.host.orEmpty() },
                port = theirPort
            ) ?: Peer(theirTag, theirs.deviceName, address, theirPort, secret).also {
                // First time: this was the pairing. Remember it, and stop offering the code.
                stopOfferingPairing()
                host.onPaired(it)
                host.log("info", "Paired with ${it.name}")
            }
            known[theirTag] = peer

            val connection = Connection(socket, out, key, outgoing = expecting != null)
            if (!register(theirTag, connection)) return
            registered = theirTag
            noteStatus(peer, connected = true)

            // Offer what is open here, so a device that has just woken catches up at once.
            host.openDocument()?.let {
                connection.send(PeerMessage.Editing(it.docId, it.fileName))
                connection.send(PeerSync.digestOf(it.doc))
            }
            pump(connection, input, peer)
        } catch (_: Exception) {
            // A dropped or refused connection is ordinary. The retry loop picks it back up.
        } finally {
            registered?.let { tag -> connections[tag]?.takeIf { !it.alive() }?.close() }
            registered?.let { tag ->
                // Only if it is still ours: a reconnection may already have taken the slot, and
                // removing that one would drop a live connection on the way out of a dead one.
                connections.computeIfPresent(tag) { _, current ->
                    if (current.closed) null else current
                }
                if (connections[tag] == null) {
                    known[tag]?.let { noteStatus(it, connected = false) }
                }
            }
            runCatching { socket.close() }
        }
    }

    private fun pump(connection: Connection, input: DataInputStream, peer: Peer) {
        var lastPing = System.currentTimeMillis()
        while (running && !connection.closed) {
            val message = try {
                PeerFrames.read(input, connection.key) ?: break
            } catch (_: SocketTimeoutException) {
                // Nothing said for a while: say something, so a silent connection is known to be
                // alive rather than assumed to be.
                if (System.currentTimeMillis() - lastPing > PING_MS) {
                    connection.send(PeerMessage.Ping)
                    lastPing = System.currentTimeMillis()
                }
                continue
            }
            noteStatus(peer, connected = true)
            handle(message, connection, peer)
        }
    }

    private fun handle(message: PeerMessage, connection: Connection, peer: Peer) {
        when (message) {
            is PeerMessage.Hello, PeerMessage.Ping, is PeerMessage.Closed -> Unit

            is PeerMessage.Editing -> host.openDocument()?.let { open ->
                // Both holding the same document: offer ours, so whoever is behind catches up.
                if (open.docId == message.docId) connection.send(PeerSync.digestOf(open.doc))
            }

            is PeerMessage.Digest -> host.openDocument()?.let { open ->
                if (open.docId != message.docId) return@let
                val answer = PeerSync.answerFor(open.doc, message)
                if (answer.strokes.isNotEmpty() || answer.deleted.isNotEmpty()) {
                    connection.send(answer)
                }
                val wanted = PeerSync.wantedFrom(open.doc, message)
                if (wanted.isNotEmpty()) connection.send(PeerMessage.Want(message.docId, wanted))
            }

            is PeerMessage.Want -> host.openDocument()?.let { open ->
                if (open.docId != message.docId) return@let
                connection.send(
                    PeerSync.answerFor(open.doc, PeerMessage.Digest(message.docId), message.ids)
                )
            }

            is PeerMessage.Marks -> host.onMarks(message.docId, message)

            is PeerMessage.Wrote -> host.onRemoteWrite(peer.name, message.name)

            PeerMessage.LibraryChanged -> host.onRemoteWrite(peer.name, null)
        }
    }

    /**
     * Settle which socket survives when both devices dialled each other at once.
     *
     * Two devices that reach for each other in the same second end up with two connections, and
     * each closing "the old one" closes a different one - which leaves both dead and the pair
     * trying again forever. So the rule is one both sides can apply to reach the same answer
     * without discussing it: the device with the lower tag is the one whose call is kept.
     *
     * Returns false when this connection is the one to drop.
     */
    private fun register(theirTag: String, connection: Connection): Boolean {
        val existing = connections[theirTag]
        if (existing != null && !existing.closed) {
            val keepOutgoing = host.deviceTag() < theirTag
            if (existing.outgoing == keepOutgoing) {
                connection.close()
                return false
            }
            existing.close()
        }
        connections[theirTag] = connection
        return true
    }

    private fun noteStatus(peer: Peer, connected: Boolean) {
        val before = statuses[peer.tag]
        statuses[peer.tag] = Status(
            peer = peer,
            connected = connected,
            lastSeenUtc = if (connected) System.currentTimeMillis() else before?.lastSeenUtc ?: 0
        )
        if (before?.connected != connected) host.onPeersChanged()
    }

    private inner class Connection(
        private val socket: Socket,
        private val out: DataOutputStream,
        val key: SecretKey,
        /** Whether this device placed the call, which is how a crossed pair is settled. */
        val outgoing: Boolean
    ) {
        @Volatile var closed = false
            private set

        fun send(message: PeerMessage) {
            if (closed) return
            runCatching { PeerFrames.write(out, key, message) }.onFailure { close() }
        }

        fun close() {
            closed = true
            runCatching { socket.close() }
        }

        /** Whether the socket underneath is still usable, as opposed to merely not closed by us. */
        fun alive(): Boolean = !closed && !socket.isClosed && socket.isConnected
    }

    private fun thread(name: String, block: () -> Unit) {
        val t = Thread(block, name).apply { isDaemon = true }
        synchronized(threads) { threads.add(t) }
        t.start()
    }

    private fun sleep(ms: Long) = runCatching { Thread.sleep(ms) }

    companion object {
        /** Nothing else answers here, and it is easy to type into a firewall rule. */
        const val DEFAULT_PORT = 47_806

        private const val HANDSHAKE = "INKSLATE-PEER-1"
        private const val CONNECT_MS = 4_000
        private const val RETRY_MS = 2_000L
        private const val READ_TIMEOUT_MS = 20_000
        private const val PING_MS = 15_000L
        private const val PAIRING_WINDOW_MS = 5 * 60_000L
    }
}
