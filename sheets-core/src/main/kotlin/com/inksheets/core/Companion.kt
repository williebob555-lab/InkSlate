package com.inksheets.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Companion mode: one tablet leads, the others follow.
 *
 * The leader says which song it is on and which page; each follower decides what that means for
 * it ([Follow]): the same page (a second copy on a second stand), the next page (two tablets as an
 * open book), or only the same song (a bandmate, whose own library opens their own part).
 *
 * Deliberately its own small link rather than the document sync: it carries where people are,
 * never what they wrote, so it cannot disturb anyone's annotations - and a bandmate's tablet can
 * follow without being paired to anything of yours.
 *
 * Plain TCP with one JSON line per message; leaders announce themselves by UDP broadcast on the
 * local network, and an address can be typed in for anything further away (a tailnet, say).
 */
object CompanionLink {
    const val PORT = 47_820
    const val ANNOUNCE_PORT = 47_821
    private const val TAG = "INKSHEETS-COMPANION"

    @Serializable
    data class Showing(
        val songId: String? = null,
        val title: String? = null,
        /** 0-based page in the leader's part. */
        val page: Int = 0,
        val leader: String = ""
    )

    enum class Follow(val label: String) {
        SAME_PAGE("The same page"),
        NEXT_PAGE("The next page, as a two-page spread"),
        SONG_ONLY("The same song, my own part")
    }

    data class Leader(val name: String, val host: String, val port: Int)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(s: Showing): String = json.encodeToString(Showing.serializer(), s)
    fun decode(line: String): Showing? = runCatching { json.decodeFromString(Showing.serializer(), line) }.getOrNull()

    /** The announcement a leader broadcasts, and reading one back. */
    fun announcement(name: String, port: Int) = "$TAG|${name.replace('|', ' ')}|$port"
    fun readAnnouncement(text: String, from: String): Leader? {
        val parts = text.split('|')
        if (parts.size != 3 || parts[0] != TAG) return null
        return Leader(parts[1], from, parts[2].toIntOrNull() ?: return null)
    }

    /**
     * Which page a follower should show for what the leader is on. Null means stay where it is -
     * a bandmate turns their own pages.
     */
    fun pageFor(follow: Follow, leaderPage: Int): Int? = when (follow) {
        Follow.SAME_PAGE -> leaderPage
        Follow.NEXT_PAGE -> leaderPage + 1
        Follow.SONG_ONLY -> null
    }

    /**
     * The song a follower should open: the very song when the libraries are the same one (your own
     * devices), else the one with the same title (a bandmate's library).
     */
    fun songFor(library: Library, showing: Showing): Song? {
        showing.songId?.let { id -> library.song(id)?.let { return it } }
        val title = showing.title?.let(Library::sortKey) ?: return null
        return library.songs.firstOrNull { Library.sortKey(it.title) == title }
    }
}

/** Leading: other tablets connect and are told where this one is. */
class CompanionLeader(private val name: String, private val port: Int = CompanionLink.PORT) {

    private val followers = CopyOnWriteArrayList<Socket>()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var last: CompanionLink.Showing? = null

    val followerCount: Int get() = followers.size

    /** Called whenever the number of followers changes, off the UI thread. */
    var onFollowers: ((Int) -> Unit)? = null

    fun start(): Boolean = runCatching {
        val socket = ServerSocket().apply {
            // Binding is quick and local; everything that waits on the network runs on threads.
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        server = socket
        running = true
        Thread({
            while (running) {
                val s = runCatching { socket.accept() }.getOrNull() ?: continue
                s.tcpNoDelay = true
                followers += s
                onFollowers?.invoke(followers.size)
                // A follower that joins mid-song is told at once where the leader is.
                last?.let { showing -> sender.execute { send(s, showing) } }
            }
        }, "companion-accept").apply { isDaemon = true; start() }
        Thread({
            val announce = DatagramSocket().apply { broadcast = true }
            val bytes = CompanionLink.announcement(name, port).toByteArray()
            while (running) {
                runCatching {
                    announce.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("255.255.255.255"), CompanionLink.ANNOUNCE_PORT))
                }
                Thread.sleep(1500)
            }
            announce.close()
        }, "companion-announce").apply { isDaemon = true; start() }
        true
    }.getOrDefault(false)

    /** Sends go out on a thread of their own: Android refuses network calls on the UI thread. */
    private val sender = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "companion-send").apply { isDaemon = true } }

    fun show(showing: CompanionLink.Showing) {
        val s = showing.copy(leader = name)
        last = s
        sender.execute { followers.forEach { send(it, s) } }
    }

    private fun send(socket: Socket, showing: CompanionLink.Showing) {
        val ok = runCatching {
            val out = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
            out.write(CompanionLink.encode(showing) + "\n")
            out.flush()
        }.isSuccess
        if (!ok) {
            followers.remove(socket)
            runCatching { socket.close() }
            onFollowers?.invoke(followers.size)
        }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        followers.forEach { runCatching { it.close() } }
        followers.clear()
    }
}

/** Following: connect to a leader and be told where it is. */
class CompanionFollower(private val onShowing: (CompanionLink.Showing) -> Unit) {

    @Volatile private var socket: Socket? = null
    @Volatile var connectedTo: String? = null
        private set

    /** Called with null when the leader goes away. */
    var onDisconnected: (() -> Unit)? = null

    fun connect(host: String, port: Int = CompanionLink.PORT): Boolean {
        disconnect()
        val s = runCatching { Socket().apply { connect(InetSocketAddress(host, port), 4000); tcpNoDelay = true } }.getOrNull()
            ?: return false
        socket = s
        connectedTo = host
        Thread({
            runCatching {
                BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        CompanionLink.decode(line)?.let(onShowing)
                    }
                }
            }
            if (socket === s) {
                socket = null
                connectedTo = null
                onDisconnected?.invoke()
            }
        }, "companion-follow").apply { isDaemon = true; start() }
        return true
    }

    fun disconnect() {
        val s = socket
        socket = null
        connectedTo = null
        runCatching { s?.close() }
    }
}

/** Listening for leaders announcing themselves on the local network. */
class CompanionScanner(private val onFound: (CompanionLink.Leader) -> Unit) {
    @Volatile private var socket: DatagramSocket? = null

    fun start(): Boolean = runCatching {
        val s = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(CompanionLink.ANNOUNCE_PORT))
        }
        socket = s
        Thread({
            val buffer = ByteArray(512)
            while (socket === s) {
                val packet = DatagramPacket(buffer, buffer.size)
                runCatching { s.receive(packet) }.onFailure { return@Thread }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                CompanionLink.readAnnouncement(text, packet.address.hostAddress)?.let(onFound)
            }
        }, "companion-scan").apply { isDaemon = true; start() }
        true
    }.getOrDefault(false)

    fun stop() {
        val s = socket
        socket = null
        runCatching { s?.close() }
    }
}
