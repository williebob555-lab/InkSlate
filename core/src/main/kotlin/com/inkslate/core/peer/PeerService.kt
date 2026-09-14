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
class PeerService(
    private val host: Host,
    /**
     * Wraps each connection's outgoing stream. The apps leave it alone; tests use it to hold the
     * transport to a platform's rules - Android refuses network writes on its main thread, which a
     * desktop test would otherwise never notice.
     */
    private val guardOutput: (java.io.OutputStream) -> java.io.OutputStream = { it }
) {

    /** What the app around this service has to answer, and what it wants to be told. */
    interface Host {
        /** This installation's stable id, the same one that labels its strokes. */
        fun deviceTag(): String

        /** What to call this device on the other one's screen. */
        fun deviceName(): String

        /** This build's version, as a person reads it. Said to the other device, for diagnosis. */
        fun appVersion(): String = ""

        /** A paired device is now reachable. Called off the UI thread, before any of its messages. */
        fun onConnected(peer: String) {}

        /** The last connection to a device closed. Called off the UI thread. */
        fun onDisconnected(peer: String) {}

        /**
         * Anything a device said about documents. Called off the UI thread, in the order it was
         * said. What to make of it is [DocumentSync]'s business, not this transport's.
         */
        fun onMessage(peer: String, message: PeerMessage) {}

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

    /**
     * What a screen shows about a peer.
     *
     * [problem] is why the link is not up, in words a person can act on - the address did not
     * answer, the other device runs a different build, the pairing no longer matches. Null while
     * connected, and until an attempt has actually failed.
     */
    data class Status(
        val peer: Peer,
        val connected: Boolean,
        val lastSeenUtc: Long,
        val problem: String? = null,
        /** The other device's build, once it has said. */
        val version: String = ""
    )

    /**
     * The outcome of the link test in Settings.
     *
     * [headline] is the answer; [detail] is what it means and, when it failed, what to do next.
     */
    data class LinkCheck(val ok: Boolean, val headline: String, val detail: String)

    /**
     * Live connections, by peer tag - possibly two to the same device.
     *
     * Two devices that reach for each other in the same second end up with a socket each. The
     * obvious tidy-up is to keep one and close the other, and it does not work: each side decides
     * at the moment its own socket registers, when the other may not exist yet, so both can end up
     * holding the one the other has closed and nothing is said again. Keeping both is what avoids
     * that, and it costs only a duplicate message - which this protocol is built to shrug off,
     * because every message is idempotent and ends in the same merge.
     */
    private val connections = ConcurrentHashMap<String, MutableSet<Connection>>()
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

    fun isConnected(tag: String): Boolean = connections[tag]?.any { !it.closed } == true

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
                    if (isConnected(peer.tag)) continue
                    runCatching {
                        val socket = Socket()
                        socket.connect(InetSocketAddress(peer.host, peer.port), CONNECT_MS)
                        // Stopping happens while a connect is in flight, and a connection that
                        // completes after it would be a service that was asked to stop and did
                        // not. Cheap to check, and it is the difference between "stopped" and
                        // "stopped except for the one that was already dialling".
                        if (!running) socket.close()
                        else thread("inkslate-peer-conn") { serve(socket, expecting = peer) }
                    }.onFailure {
                        // Expected and frequent: the other device is asleep or out of reach. Said
                        // plainly all the same, so that "not connected" comes with a reason.
                        if (!isConnected(peer.tag)) {
                            noteStatus(peer, connected = false, problem = unreachable(peer, it))
                        }
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
        connections.remove(tag)?.forEach { it.close() }
        statuses.remove(tag)
        host.onPeersChanged()
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        connections.values.flatten().forEach { it.close() }
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
    fun pairWith(address: String, port: Int = DEFAULT_PORT, code: String): Result<Peer> {
        // One retry, and only for a connection that broke rather than a code that was wrong. The
        // first reach for a device that is also reaching back can land on a socket being torn
        // down, and reporting "could not pair" for that would send someone hunting for a problem
        // that was over before they read it. A wrong code fails the same way twice, quickly.
        val first = pairOnce(address, port, code)
        val failure = first.exceptionOrNull() ?: return first
        if (failure !is java.io.IOException) return first
        Thread.sleep(400)
        return pairOnce(address, port, code)
    }

    private fun pairOnce(address: String, port: Int, code: String): Result<Peer> =
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

    /**
     * Say something to one device.
     *
     * On one of its connections, not all: there can be two to the same device for a moment, and
     * saying everything twice is merely wasteful, but a request answered twice is a question the
     * other side has to think about.
     */
    fun send(tag: String, message: PeerMessage) {
        connections[tag]?.firstOrNull { !it.closed }?.send(message)
    }

    /** The devices with a live connection right now. */
    fun connectedPeers(): Set<String> =
        connections.filterValues { set -> set.any { !it.closed } }.keys.toSet()

    /** "I have written this file" - not the bytes, just the news, so the other side looks again. */
    fun announceWrote(fileName: String, sizeBytes: Long, modifiedUtc: Long) =
        broadcast(PeerMessage.Wrote(fileName, sizeBytes, modifiedUtc))

    fun announceLibraryChanged() = broadcast(PeerMessage.LibraryChanged)

    private fun broadcast(message: PeerMessage) {
        for (c in connections.values.flatten()) c.send(message)
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
        port = listenPort,
        version = host.appVersion()
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
        if (!running) {
            runCatching { socket.close() }
            return
        }
        socket.tcpNoDelay = true
        socket.soTimeout = READ_TIMEOUT_MS
        var registered: Pair<String, Connection>? = null
        try {
            val out = DataOutputStream(guardOutput(socket.getOutputStream()).buffered())
            val input = DataInputStream(socket.getInputStream().buffered())
            val theirTag = exchangeTags(out, input)
            if (expecting != null && expecting.tag != theirTag) {
                noteStatus(
                    expecting, connected = false,
                    problem = "Something else answers at ${expecting.host}:${expecting.port}, not " +
                        "${expecting.name}. If its address changed, pair again."
                )
                return
            }
            // A peer's address can end up pointing back here - a stale record, or a loopback name.
            // Talking to yourself is harmless but it is not a peer, and it would sit in the list
            // looking like one.
            if (theirTag == host.deviceTag()) return

            // A device we know, or one presenting itself with a code we are currently offering.
            val offered = pendingCode?.takeIf { System.currentTimeMillis() < pendingUntil }
            val secret = known[theirTag]?.code ?: offered ?: run {
                // Silence here once hid a lost pairing for an afternoon: the other device kept
                // calling, this one kept hanging up, and neither said why.
                host.log("warn", "Refused a connection from an unpaired device ($theirTag)")
                return
            }
            val key = PeerFrames.keyFrom(secret, PeerFrames.saltFor(host.deviceTag(), theirTag))

            PeerFrames.write(out, key, hello())
            val theirs = try {
                PeerFrames.read(input, key) as? PeerMessage.Hello ?: return
            } catch (_: java.security.GeneralSecurityException) {
                // What a pairing code that no longer matches looks like from here: the other device
                // answered, and what it said will not decrypt.
                known[theirTag]?.let {
                    noteStatus(
                        it, connected = false,
                        problem = "${it.name} answered, but the pairing no longer matches. Forget " +
                            "it on both devices and pair again."
                    )
                }
                host.log("warn", "A device answered with a pairing that does not match ($theirTag)")
                return
            }
            if (theirs.protocol != PeerMessage.PROTOCOL) {
                val theirBuild = theirs.version.ifBlank { "an older build" }
                host.log("warn", "${theirs.deviceName} is running a different version of InkSlate ($theirBuild)")
                known[theirTag]?.let {
                    noteStatus(
                        it, connected = false,
                        problem = "${it.name} runs $theirBuild, which cannot link with this " +
                            "one (${host.appVersion().ifBlank { "this build" }}). Update both to " +
                            "the same build.",
                        version = theirs.version
                    )
                }
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

            val connection = Connection(socket, out, key)
            // Registered and announced in one step, so that two sockets to the same device arriving
            // together announce it exactly once.
            val first = synchronized(connections) {
                val set = connections.computeIfAbsent(theirTag) {
                    java.util.Collections.newSetFromMap(ConcurrentHashMap())
                }
                val none = set.none { !it.closed }
                set.add(connection)
                none
            }
            registered = theirTag to connection
            noteStatus(peer, connected = true, version = theirs.version)
            if (first) {
                host.log("info", "Connected to ${peer.name} at ${peer.host}:${peer.port}")
                host.onConnected(theirTag)
            }
            pump(connection, input, peer)
        } catch (e: Exception) {
            // A dropped or refused connection is ordinary, and the retry loop picks it back up -
            // but a connection that was up and then failed says why, because "lost the
            // connection" with no reason, forty times a minute, is not something anyone can fix.
            if (registered != null && running) {
                host.log(
                    "warn",
                    "Connection to ${known[registered.first]?.name ?: registered.first} failed: " +
                        "${e::class.simpleName}: ${e.message}"
                )
            }
        } finally {
            registered?.let { (tag, connection) ->
                connection.close()
                val gone = synchronized(connections) {
                    connections[tag]?.remove(connection)
                    !isConnected(tag)
                }
                if (gone) {
                    known[tag]?.let { noteStatus(it, connected = false) }
                    host.log("info", "Lost the connection to ${known[tag]?.name ?: tag}")
                    host.onDisconnected(tag)
                }
            }
            runCatching { socket.close() }
        }
    }

    private fun pump(connection: Connection, input: DataInputStream, peer: Peer) {
        var lastPing = System.currentTimeMillis()
        while (running && !connection.closed) {
            val message = try {
                PeerFrames.read(input, connection.key) ?: run {
                    if (running && !connection.closed) {
                        host.log("info", "${peer.name} closed the connection")
                    }
                    null
                } ?: break
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
            is PeerMessage.Hello, PeerMessage.Ping -> Unit

            is PeerMessage.Probe -> connection.send(PeerMessage.ProbeReply(message.nonce, host.appVersion()))

            is PeerMessage.ProbeReply -> probes.remove(message.nonce)?.let { waiting ->
                waiting.version = message.version
                waiting.latch.countDown()
            }

            is PeerMessage.Wrote -> {
                host.onRemoteWrite(peer.name, message.name)
                host.onMessage(peer.tag, message)
            }

            PeerMessage.LibraryChanged -> host.onRemoteWrite(peer.name, null)

            else -> host.onMessage(peer.tag, message)
        }
    }

    private fun noteStatus(
        peer: Peer,
        connected: Boolean,
        problem: String? = null,
        version: String? = null
    ) {
        val before = statuses[peer.tag]
        val next = Status(
            peer = peer,
            connected = connected,
            lastSeenUtc = if (connected) System.currentTimeMillis() else before?.lastSeenUtc ?: 0,
            // Connected clears it; a disconnect with nothing new to say keeps the last reason.
            problem = if (connected) null else problem ?: before?.problem,
            version = version ?: before?.version.orEmpty()
        )
        statuses[peer.tag] = next
        if (before?.connected != connected || before.problem != next.problem ||
            before.version != next.version
        ) {
            host.onPeersChanged()
        }
    }

    /** Why dialling a device failed, in words that say what to check. */
    private fun unreachable(peer: Peer, failure: Throwable): String {
        val where = "${peer.host}:${peer.port}"
        return when (failure) {
            is java.net.SocketTimeoutException ->
                "Nothing answered at $where. Check both devices are on the same network or " +
                    "Tailscale, and that InkSlate is open on ${peer.name}."
            is java.net.ConnectException ->
                "$where refused the connection: InkSlate is not open on ${peer.name}, or " +
                    "\u201CTalk to my other devices\u201D is off there."
            is java.net.NoRouteToHostException ->
                "No route to $where. The two devices are not on a network that joins them."
            is java.net.UnknownHostException ->
                "The address ${peer.host} was not found."
            else -> "Could not reach $where: ${failure.message ?: failure::class.simpleName}"
        }
    }

    /** A probe on its way, and what came back. */
    private class Waiting {
        val latch = java.util.concurrent.CountDownLatch(1)
        @Volatile var version: String = ""
    }

    private val probes = ConcurrentHashMap<String, Waiting>()

    /**
     * Test the link to [tag], for the button in Settings. Blocking - call it off the interface.
     *
     * Connected: a probe goes over the live connection and the reply is timed, which proves the
     * whole path - the socket, the cipher, the other app reading and answering - not merely that
     * a connection object exists. Not connected: the next few dial attempts are waited on, and if
     * none gets through, the reason the last one failed is the answer.
     */
    fun check(tag: String, timeoutMs: Long = 5_000): LinkCheck {
        val peer = known[tag] ?: return LinkCheck(
            false, "Not paired", "This device is not paired with that one any more."
        )
        if (!running) {
            return LinkCheck(
                false, "The link is off",
                "Turn on \u201CTalk to my other devices\u201D on this device first."
            )
        }

        // Give the dialler a moment when it is not up: it tries every couple of seconds.
        val waitUntil = System.currentTimeMillis() + RETRY_MS * 3 + CONNECT_MS
        while (!isConnected(tag) && System.currentTimeMillis() < waitUntil) sleep(200)
        if (!isConnected(tag)) {
            val why = statuses[tag]?.problem
                ?: "It has not answered at ${peer.host}:${peer.port}."
            return LinkCheck(false, "Not linked to ${peer.name}", why)
        }

        val nonce = java.util.UUID.randomUUID().toString()
        val waiting = Waiting()
        probes[nonce] = waiting
        val started = System.nanoTime()
        send(tag, PeerMessage.Probe(nonce))
        val answered = waiting.latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        probes.remove(nonce)
        if (!answered) {
            // Open on paper and dead in fact. Closing it is what gets a fresh one dialled.
            connections[tag]?.forEach { it.close() }
            return LinkCheck(
                false, "${peer.name} stopped answering",
                "The connection looked open but a test message got no reply within " +
                    "${timeoutMs / 1000} seconds. It has been reset - test again in a few seconds."
            )
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        known[tag]?.let { noteStatus(it, connected = true, version = waiting.version) }
        val build = waiting.version.takeIf { it.isNotBlank() }?.let { " It runs InkSlate $it." }.orEmpty()
        return LinkCheck(
            true, "Linked to ${peer.name}",
            "Answered in $millis ms over ${peer.host}.$build Marks drawn on either device appear " +
                "on the other as they are drawn."
        )
    }

    /**
     * One live connection, and the only thread that writes to it.
     *
     * Messages are queued and written by the connection's own writer, never by whoever called
     * [send]. The apps call from their interface thread - that is where the open document lives -
     * and Android refuses network writes there outright: the throw was caught as a failed send, the
     * connection closed, and it happened again on every reconnect. That was the link dropping the
     * moment a document was opened. Queuing also keeps a slow network from ever stalling a pen.
     * Order is kept: one writer, one queue.
     */
    private inner class Connection(
        private val socket: Socket,
        private val out: DataOutputStream,
        val key: SecretKey
    ) {
        @Volatile var closed = false
            private set

        private val queue = java.util.concurrent.LinkedBlockingQueue<PeerMessage>()

        private val writer = Thread({
            try {
                while (!closed) {
                    val message = queue.poll(1, java.util.concurrent.TimeUnit.SECONDS) ?: continue
                    try {
                        PeerFrames.write(out, key, message)
                    } catch (e: Exception) {
                        if (!closed) {
                            host.log(
                                "warn",
                                "Could not send ${message::class.simpleName}: " +
                                    "${e::class.simpleName}: ${e.message}"
                            )
                        }
                        close()
                    }
                }
            } catch (_: InterruptedException) {
                // closing
            }
        }, "inkslate-peer-write").apply {
            isDaemon = true
            start()
        }

        fun send(message: PeerMessage) {
            if (closed) return
            queue.offer(message)
        }

        fun close() {
            closed = true
            writer.interrupt()
            runCatching { socket.close() }
        }
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
