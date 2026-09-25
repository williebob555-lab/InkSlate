package com.inksheets.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * Companion mode: one tablet leads, the others follow.
 *
 * The leader says which song it is on and which page; each follower decides what that means for
 * it ([Follow]): the same page (a second copy on a second stand), the next page (two tablets as an
 * open book), or only the same song (a bandmate, whose own library opens their own part).
 *
 * Deliberately its own small link rather than the document sync: it carries where people are,
 * and - only when the leader turns it on - the leader's marks for players on the same part. It
 * never needs pairing, so a whole band can join by scanning one code.
 *
 * Plain TCP with one JSON line per message; leaders announce themselves by UDP broadcast on the
 * local network, and a join link (the text of the leader's QR code) carries every address the
 * leader has, so a tablet on the tailnet reaches it too.
 */
object CompanionLink {
    const val PORT = 47_820
    const val ANNOUNCE_PORT = 47_821
    private const val TAG = "INKSHEETS-COMPANION"
    const val SCHEME = "inksheets"

    @Serializable
    data class Showing(
        val songId: String? = null,
        val title: String? = null,
        /** 0-based page in the leader's part. */
        val page: Int = 0,
        val leader: String = "",
        /** The leader's part: its instrument, its number ("2" of Trombone 2) and its page count. */
        val instrument: String? = null,
        val partNo: String? = null,
        val pages: Int = 0,
        /** Whether the leader is sharing its marks with players on the same part. */
        val shareInk: Boolean = false
    )

    /** The leader's marks on its part: an InkDocument, serialised. */
    @Serializable
    data class InkShare(
        val kind: String = "ink",
        val songId: String? = null,
        val title: String? = null,
        val instrument: String? = null,
        val partNo: String? = null,
        val pages: Int = 0,
        val ink: String
    )

    /** What a follower says on joining, so the leader's log can name it. */
    @Serializable
    data class Hello(val kind: String = "hello", val name: String = "")

    sealed interface Line {
        data class Show(val showing: Showing) : Line
        data class Ink(val share: InkShare) : Line
        data class Joined(val name: String) : Line
        data object Ping : Line
    }

    enum class Follow(val label: String) {
        /** The same page on your own devices and for a player on the same part; otherwise your own part. */
        AUTO("Automatically - the same page when it's the same part, otherwise my own part"),
        SAME_PAGE("The same page"),
        NEXT_PAGE("The next page, as a two-page spread"),
        SONG_ONLY("The same song, my own part")
    }

    /** A leader to follow: its name, every address it can be reached on, and its port. */
    data class Leader(val name: String, val hosts: List<String>, val port: Int = PORT) {
        constructor(name: String, host: String, port: Int) : this(name, listOf(host), port)
        val host: String get() = hosts.first()
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(s: Showing): String = json.encodeToString(Showing.serializer(), s)
    fun encode(s: InkShare): String = json.encodeToString(InkShare.serializer(), s)
    fun encode(s: Hello): String = json.encodeToString(Hello.serializer(), s)
    const val PING = """{"kind":"ping"}"""

    /** A position, for callers that only know that kind. Anything else reads as null. */
    fun decode(line: String): Showing? = (read(line) as? Line.Show)?.showing

    /** Any line of the link. Lines from a leader older than "kind" are all positions. */
    fun read(line: String): Line? = runCatching {
        val obj = json.decodeFromString(JsonObject.serializer(), line)
        when (obj["kind"]?.jsonPrimitive?.contentOrNull) {
            null, "show" -> Line.Show(json.decodeFromJsonElement(Showing.serializer(), obj))
            "ink" -> Line.Ink(json.decodeFromJsonElement(InkShare.serializer(), obj))
            "hello" -> Line.Joined(json.decodeFromJsonElement(Hello.serializer(), obj).name)
            "ping" -> Line.Ping
            else -> null
        }
    }.getOrNull()

    /** The announcement a leader broadcasts, and reading one back. */
    fun announcement(name: String, port: Int) = "$TAG|${name.replace('|', ' ')}|$port"
    fun readAnnouncement(text: String, from: String): Leader? {
        val parts = text.split('|')
        if (parts.size != 3 || parts[0] != TAG) return null
        return Leader(parts[1], from, parts[2].toIntOrNull() ?: return null)
    }

    /**
     * The text of a leader's QR code: `inksheets://join?name=Stand%201&hosts=192.168.1.4,100.70.1.2&port=47820`.
     * A phone's own camera app opens InkSheets with it; InkSheets' scanner and a pasted picture
     * read it the same way.
     */
    fun joinLink(name: String, hosts: List<String>, port: Int = PORT): String =
        "$SCHEME://join?name=" + URLEncoder.encode(name, "UTF-8").replace("+", "%20") +
            "&hosts=" + hosts.joinToString(",") + "&port=$port"

    /**
     * A leader from a join link - or from a bare address ("192.168.1.4", "stand1:47820") typed or
     * pasted in. Null for anything else.
     */
    fun parseJoin(text: String): Leader? {
        val t = text.trim()
        if (t.startsWith("$SCHEME://", ignoreCase = true)) {
            val query = t.substringAfter('?', "")
            val params = query.split('&').mapNotNull { kv ->
                val k = kv.substringBefore('=', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                k to runCatching { URLDecoder.decode(kv.substringAfter('='), "UTF-8") }.getOrDefault("")
            }.toMap()
            val hosts = params["hosts"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (hosts.isEmpty()) return null
            return Leader(params["name"]?.takeIf { it.isNotBlank() } ?: hosts.first(), hosts, params["port"]?.toIntOrNull() ?: PORT)
        }
        if (t.isEmpty() || t.any { it.isWhitespace() || it == '/' }) return null
        val host = t.substringBeforeLast(':').takeIf { t.count { it == ':' } == 1 } ?: t
        val port = if (t.count { it == ':' } == 1) t.substringAfterLast(':').toIntOrNull() ?: return null else PORT
        return Leader(host, listOf(host), port)
    }

    /**
     * Which page a follower should show for what the leader is on. Null means stay where it is -
     * a bandmate turns their own pages.
     */
    fun pageFor(follow: Follow, leaderPage: Int): Int? = when (follow) {
        Follow.AUTO, Follow.SAME_PAGE -> leaderPage
        Follow.NEXT_PAGE -> leaderPage + 1
        Follow.SONG_ONLY -> null
    }

    /**
     * The song a follower should open: the very song when the libraries are the same one (your own
     * devices), else the one with the same title (a bandmate's library).
     */
    fun songFor(library: Library, showing: Showing): Song? = songFor(library, showing.songId, showing.title)

    fun songFor(library: Library, songId: String?, title: String?): Song? {
        songId?.let { id -> library.song(id)?.let { return it } }
        val key = title?.let(Library::matchKey) ?: return null
        return library.songs.firstOrNull { Library.matchKey(it.title) == key }
            ?: library.songs.firstOrNull { Library.matchKey(ImportPlan.withoutTrailingInstrument(it.title)) == key }
    }

    /**
     * The part's number as printed - "2" of "Trombone 2", "2" of "2nd Trombone" or "Tbn. II" -
     * or null for a part with none. Two players on "the same part" have the same instrument and
     * the same number.
     */
    fun partNumber(label: String?): String? {
        val words = label?.lowercase()?.split(Regex("""[^a-z0-9]+"""))?.filter { it.isNotEmpty() } ?: return null
        fun number(w: String): String? {
            Regex("""^(\d{1,2})(st|nd|rd|th)?$""").find(w)?.let { return it.groupValues[1].trimStart('0').ifEmpty { "0" } }
            return ROMAN[w]
        }
        // Beside the instrument's name first: "76 Trombones - Trombone 1" is part 1, not 76.
        val named = words.indices.filter { i -> InstrumentReader.normalise(words[i]).any { it in INSTRUMENT_WORDS } }
        for (i in named) {
            words.getOrNull(i + 1)?.let(::number)?.let { return it }
            words.getOrNull(i - 1)?.let(::number)?.let { return it }
            Regex("""^[a-z]+(\d{1,2})$""").find(words[i])?.let { return it.groupValues[1] }
        }
        words.firstNotNullOfOrNull { Regex("""^[a-z]+(\d{1,2})$""").find(it)?.groupValues?.get(1) }?.let { return it }
        return words.lastOrNull { w -> w.all { it.isDigit() } && w.length <= 2 }?.let(::number)
    }

    private val INSTRUMENT_WORDS: Set<String> by lazy {
        Instruments.all.flatMap { i -> i.names.flatMap { it.split(' ') } }.toSet() - setOf("in", "f", "bb", "eb", "e", "tc", "bc")
    }

    private val ROMAN = mapOf("i" to "1", "ii" to "2", "iii" to "3", "iv" to "4", "v" to "5", "vi" to "6")

    /**
     * Whether marks shared for ([instrument], [partNo], [pages]) belong on [part], which has
     * [myPages] pages: the same instrument, the same number, and the same number of pages - the
     * same printed part, so a mark lands where it was made.
     */
    fun samePart(instrument: String?, partNo: String?, pages: Int, part: Part, myPages: Int): Boolean =
        instrument != null && instrument == part.instrument &&
            partNo == partNumber(part.label ?: part.file.substringAfterLast('/')) &&
            pages > 0 && pages == myPages
}

/**
 * Leading: other tablets connect and are told where this one is.
 *
 * Made for a whole band on one Wi-Fi: each follower has its own queue and writer thread, so one
 * tablet with a weak signal holds up nobody else, and a reader thread per follower notices at once
 * when one leaves. Joins and departures go to [onLog], never to the screen.
 */
class CompanionLeader(private val name: String, private val port: Int = CompanionLink.PORT) {

    private class Follower(val socket: Socket) {
        val queue = LinkedBlockingDeque<String>()
        @Volatile var name: String = socket.inetAddress?.hostAddress ?: "?"
        @Volatile var open = true
    }

    private val followers = ConcurrentHashMap.newKeySet<Follower>()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var last: CompanionLink.Showing? = null
    @Volatile private var lastInk: CompanionLink.InkShare? = null

    val followerCount: Int get() = followers.size

    /** Called whenever the number of followers changes, off the UI thread. */
    var onFollowers: ((Int) -> Unit)? = null

    /** A line for the event log: who joined, who left. Off the UI thread. */
    var onLog: ((String) -> Unit)? = null

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
                runCatching { s.tcpNoDelay = true }
                join(Follower(s))
            }
        }, "companion-accept").apply { isDaemon = true; start() }
        Thread({
            val announce = DatagramSocket().apply { broadcast = true }
            val bytes = CompanionLink.announcement(name, port).toByteArray()
            var tick = 0
            while (running) {
                runCatching {
                    announce.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("255.255.255.255"), CompanionLink.ANNOUNCE_PORT))
                }
                // A ping now and then, so a follower whose Wi-Fi dropped without a word finds out
                // and reconnects rather than waiting on a dead line.
                if (++tick % 3 == 0) followers.forEach { it.queue.offer(CompanionLink.PING) }
                Thread.sleep(1500)
            }
            announce.close()
        }, "companion-announce").apply { isDaemon = true; start() }
        true
    }.getOrDefault(false)

    private fun join(f: Follower) {
        followers += f
        onFollowers?.invoke(followers.size)
        // A follower that joins mid-song is told at once where the leader is, and given the marks.
        last?.let { f.queue.offer(CompanionLink.encode(it)) }
        lastInk?.let { share -> if (last?.shareInk == true) f.queue.offer(CompanionLink.encode(share)) }
        Thread({
            val out = runCatching { OutputStreamWriter(f.socket.getOutputStream(), Charsets.UTF_8) }.getOrNull()
            while (f.open && running && out != null) {
                val line = runCatching { f.queue.poll(2, TimeUnit.SECONDS) }.getOrNull() ?: continue
                val ok = runCatching { out.write(line + "\n"); out.flush() }.isSuccess
                if (!ok) break
            }
            leave(f, "the connection dropped")
        }, "companion-send").apply { isDaemon = true; start() }
        Thread({
            runCatching {
                BufferedReader(InputStreamReader(f.socket.getInputStream(), Charsets.UTF_8)).use { reader ->
                    while (f.open) {
                        val line = reader.readLine() ?: break
                        (CompanionLink.read(line) as? CompanionLink.Line.Joined)?.let { hello ->
                            if (hello.name.isNotBlank()) f.name = hello.name
                            onLog?.invoke("${f.name} is following (${followers.size} now)")
                        }
                    }
                }
            }
            leave(f, "left")
        }, "companion-hear").apply { isDaemon = true; start() }
    }

    private fun leave(f: Follower, why: String) {
        if (!followers.remove(f)) return
        f.open = false
        runCatching { f.socket.close() }
        onLog?.invoke("${f.name} $why (${followers.size} following)")
        onFollowers?.invoke(followers.size)
    }

    fun show(showing: CompanionLink.Showing) {
        val s = showing.copy(leader = name)
        if (s == last) return
        last = s
        val line = CompanionLink.encode(s)
        followers.forEach { it.queue.offer(line) }
    }

    /**
     * Share marks with every follower; each takes them only if it is on the same part. A newer
     * set replaces one still waiting to go to a slow follower rather than queueing behind it.
     */
    fun shareInk(share: CompanionLink.InkShare) {
        lastInk = share
        val line = CompanionLink.encode(share)
        followers.forEach { f ->
            f.queue.removeIf { it.startsWith("{\"kind\":\"ink\"") }
            f.queue.offer(line)
        }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        followers.forEach { it.open = false; runCatching { it.socket.close() } }
        followers.clear()
    }
}

/**
 * Following: connect to a leader and be told where it is - and stay connected. A dropped
 * connection (the leader's screen went off, the Wi-Fi hiccuped) is tried again every couple of
 * seconds on each of the leader's addresses until [stop], so nobody in the band has to rejoin.
 */
class CompanionFollower(
    private val myName: String,
    private val onLine: (CompanionLink.Line) -> Unit
) {
    @Volatile private var socket: Socket? = null
    @Volatile private var wanted: CompanionLink.Leader? = null

    /** Told true on connecting and false on losing the connection. Off the UI thread. */
    var onConnected: ((Boolean) -> Unit)? = null

    /**
     * Connect to [leader], trying each address. Blocks until the first attempt is decided and
     * returns whether it worked; either way, it keeps trying in the background until [stop].
     */
    fun start(leader: CompanionLink.Leader, retryMs: Long = 2_000): Boolean {
        stop()
        wanted = leader
        val first = java.util.concurrent.CompletableFuture<Boolean>()
        Thread({
            var announced = false
            while (wanted === leader) {
                val s = leader.hosts.firstNotNullOfOrNull { host ->
                    runCatching {
                        Socket().apply {
                            connect(InetSocketAddress(host, leader.port), 3000)
                            tcpNoDelay = true
                            // The leader pings every few seconds; silence this long is a dead line.
                            soTimeout = 15_000
                        }
                    }.getOrNull()
                }
                if (s == null) {
                    first.complete(false)
                    sleepWhileWanted(leader, retryMs)
                    continue
                }
                if (wanted !== leader) { runCatching { s.close() }; break }
                socket = s
                runCatching {
                    OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8).apply {
                        write(CompanionLink.encode(CompanionLink.Hello(name = myName)) + "\n"); flush()
                    }
                }
                first.complete(true)
                onConnected?.invoke(true)
                announced = true
                runCatching {
                    BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8)).let { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            CompanionLink.read(line)?.let { if (it != CompanionLink.Line.Ping) onLine(it) }
                        }
                    }
                }
                runCatching { s.close() }
                if (socket === s) socket = null
                if (announced) { onConnected?.invoke(false); announced = false }
                sleepWhileWanted(leader, retryMs)
            }
            first.complete(false)
        }, "companion-follow").apply { isDaemon = true; start() }
        return runCatching { first.get(10, TimeUnit.SECONDS) }.getOrDefault(false)
    }

    private fun sleepWhileWanted(leader: CompanionLink.Leader, ms: Long) {
        var left = ms
        while (left > 0 && wanted === leader) { Thread.sleep(100); left -= 100 }
    }

    fun stop() {
        wanted = null
        val s = socket
        socket = null
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
