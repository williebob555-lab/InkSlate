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
        val shareInk: Boolean = false,
        /** Numbered in the order sent, and stamped with the leader's clock, to measure delays. */
        val seq: Long = 0,
        val at: Long = 0
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

    /**
     * A message from the leader - "Next up: Fight Song", "Trumpets: second ending" - for everyone,
     * or only players of [instruments] (ids; empty for everyone).
     */
    @Serializable
    data class Note(
        val kind: String = "note",
        val text: String,
        val instruments: List<String> = emptyList(),
        val from: String = "",
        val at: Long = 0,
        /** Over the music, big, until tapped away: "Stop", or a word between songs. */
        val urgent: Boolean = false,
        /** The colour it covers the music in (ARGB), or null for the warning colour. */
        val color: Int? = null
    )

    /** Whether [note] is for a player of any of [mine]. */
    fun noteIsFor(note: Note, mine: Set<String>): Boolean =
        note.instruments.isEmpty() || note.instruments.any { it in mine }

    /** What a follower says on joining, so the leader's log can name it. */
    @Serializable
    data class Hello(val kind: String = "hello", val name: String = "")

    sealed interface Line {
        data class Show(val showing: Showing) : Line
        data class Ink(val share: InkShare) : Line
        data class Joined(val name: String) : Line
        data class Message(val note: Note) : Line
        /** The leader is still there. [seq] and [at] as for [Showing]; zero from older leaders. */
        data class Ping(val seq: Long = 0, val at: Long = 0) : Line
        /** A follower's answer to a [Ping], carrying the ping's own [at] back for the round trip. */
        data class Pong(val seq: Long, val at: Long) : Line
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
    fun encode(n: Note): String = json.encodeToString(Note.serializer(), n)
    fun ping(seq: Long, at: Long) = """{"kind":"ping","seq":$seq,"at":$at}"""
    fun pong(seq: Long, at: Long) = """{"kind":"pong","seq":$seq,"at":$at}"""

    /** A position, for callers that only know that kind. Anything else reads as null. */
    fun decode(line: String): Showing? = (read(line) as? Line.Show)?.showing

    /** Any line of the link. Lines from a leader older than "kind" are all positions. */
    fun read(line: String): Line? = runCatching {
        val obj = json.decodeFromString(JsonObject.serializer(), line)
        when (obj["kind"]?.jsonPrimitive?.contentOrNull) {
            null, "show" -> Line.Show(json.decodeFromJsonElement(Showing.serializer(), obj))
            "ink" -> Line.Ink(json.decodeFromJsonElement(InkShare.serializer(), obj))
            "hello" -> Line.Joined(json.decodeFromJsonElement(Hello.serializer(), obj).name)
            "note" -> Line.Message(json.decodeFromJsonElement(Note.serializer(), obj))
            "ping", "pong" -> {
                val seq = obj["seq"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0
                val at = obj["at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0
                if (obj["kind"]?.jsonPrimitive?.contentOrNull == "ping") Line.Ping(seq, at) else Line.Pong(seq, at)
            }
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
 * when one leaves. Joins, departures and slow links go to [onLog], never to the screen.
 */
class CompanionLeader(private val name: String, private val port: Int = CompanionLink.PORT) {

    private class Follower(val socket: Socket) {
        val queue = LinkedBlockingDeque<String>()
        @Volatile var name: String = socket.inetAddress?.hostAddress ?: "?"
        @Volatile var open = true
        val since = System.currentTimeMillis()
    }

    private val followers = ConcurrentHashMap.newKeySet<Follower>()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var last: CompanionLink.Showing? = null
    @Volatile private var lastInk: CompanionLink.InkShare? = null
    private val seq = java.util.concurrent.atomic.AtomicLong()
    private val recentNotes = ArrayList<Pair<Long, String>>()

    val followerCount: Int get() = followers.size

    /** Called whenever the number of followers changes, off the UI thread. */
    var onFollowers: ((Int) -> Unit)? = null

    /** A line for the event log: who joined, who left, which link is slow. Off the UI thread. */
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
            while (running) {
                runCatching {
                    announce.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("255.255.255.255"), CompanionLink.ANNOUNCE_PORT))
                }
                Thread.sleep(1500)
            }
            announce.close()
        }, "companion-announce").apply { isDaemon = true; start() }
        // A heartbeat of its own, so nothing else slows it: a follower that hears nothing for a
        // few beats knows the line is dead and reconnects, rather than waiting on it.
        Thread({
            while (running) {
                Thread.sleep(PING_EVERY_MS)
                val line = CompanionLink.ping(seq.incrementAndGet(), System.currentTimeMillis())
                followers.forEach { f ->
                    f.queue.removeIf { it.startsWith("{\"kind\":\"ping\"") }
                    f.queue.offer(line)
                }
            }
        }, "companion-ping").apply { isDaemon = true; start() }
        true
    }.getOrDefault(false)

    private fun join(f: Follower) {
        followers += f
        onFollowers?.invoke(followers.size)
        // A follower that joins mid-song is told at once where the leader is, and given the marks.
        last?.let { f.queue.offer(CompanionLink.encode(it)) }
        lastInk?.let { share -> if (last?.shareInk == true) f.queue.offer(CompanionLink.encode(share)) }
        val now = System.currentTimeMillis()
        synchronized(recentNotes) { recentNotes.filter { now - it.first <= NOTE_KEPT_MS }.forEach { f.queue.offer(it.second) } }
        Thread({
            val out = runCatching { OutputStreamWriter(f.socket.getOutputStream(), Charsets.UTF_8) }.getOrNull()
            var why = "left"
            while (f.open && running && out != null) {
                val line = runCatching { f.queue.poll(2, TimeUnit.SECONDS) }.getOrNull() ?: continue
                val began = System.currentTimeMillis()
                val failure = runCatching { out.write(line + "\n"); out.flush() }.exceptionOrNull()
                val took = System.currentTimeMillis() - began
                if (failure != null) { why = "dropped: sending failed (${failure.message ?: failure.javaClass.simpleName})"; break }
                // A write only waits when the follower has stopped taking what is sent.
                if (took > 1000) onLog?.invoke("${f.name}: sending took ${took / 100 / 10.0} s - a weak or busy link")
            }
            leave(f, why)
        }, "companion-send").apply { isDaemon = true; start() }
        Thread({
            val outcome = runCatching {
                BufferedReader(InputStreamReader(f.socket.getInputStream(), Charsets.UTF_8)).use { reader ->
                    while (f.open) {
                        val line = reader.readLine() ?: break
                        when (val got = CompanionLink.read(line)) {
                            is CompanionLink.Line.Joined -> {
                                if (got.name.isNotBlank()) f.name = got.name
                                onLog?.invoke("${f.name} is following (${followers.size} now)")
                            }
                            is CompanionLink.Line.Pong -> {
                                // This follower answers heartbeats, so one that stops answering is
                                // gone - even if its goodbye never arrived - and is let go.
                                if (f.socket.soTimeout == 0) f.socket.soTimeout = CompanionFollower.SILENT_FOR_MS.toInt()
                                val trip = System.currentTimeMillis() - got.at
                                if (got.at > 0 && trip > 1500) onLog?.invoke("${f.name}: a heartbeat took ${trip / 100 / 10.0} s there and back")
                            }
                            else -> Unit
                        }
                    }
                }
            }
            leave(f, outcome.exceptionOrNull()?.let { e ->
                if (e is java.net.SocketTimeoutException) "went silent for ${CompanionFollower.SILENT_FOR_MS / 1000} s"
                else "dropped (${e.message ?: e.javaClass.simpleName})"
            } ?: "left")
        }, "companion-hear").apply { isDaemon = true; start() }
    }

    private fun leave(f: Follower, why: String) {
        if (!followers.remove(f)) return
        f.open = false
        runCatching { f.socket.close() }
        val secs = (System.currentTimeMillis() - f.since) / 1000
        onLog?.invoke("${f.name} $why after ${secs} s (${followers.size} following)")
        onFollowers?.invoke(followers.size)
    }

    fun show(showing: CompanionLink.Showing) {
        val bare = showing.copy(leader = name, seq = 0, at = 0)
        if (bare == last?.copy(seq = 0, at = 0)) return
        val s = bare.copy(seq = seq.incrementAndGet(), at = System.currentTimeMillis())
        last = s
        val line = CompanionLink.encode(s)
        followers.forEach { it.queue.offer(line) }
    }

    /** A message to every follower; each shows it only if it is for its instrument. */
    fun note(note: CompanionLink.Note) {
        val sent = note.copy(from = name, at = System.currentTimeMillis())
        val line = CompanionLink.encode(sent)
        // Kept a while and given again to anyone who joins meanwhile: a tablet whose link dropped
        // for a moment still gets told. Each follower shows a message once, however often it comes.
        synchronized(recentNotes) {
            recentNotes += sent.at to line
            recentNotes.removeAll { sent.at - it.first > NOTE_KEPT_MS }
        }
        followers.forEach { it.queue.offer(line) }
        onLog?.invoke("Sent \"${note.text}\" to ${followers.size}" + if (note.instruments.isEmpty()) "" else " (for ${note.instruments.joinToString(", ")})")
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

    companion object {
        const val PING_EVERY_MS = 2_000L

        /** How long a message is given again to followers that join (or come back) after it. */
        const val NOTE_KEPT_MS = 30_000L
    }
}

/**
 * Following: connect to a leader and be told where it is - and stay connected. A dropped
 * connection (the leader's screen went off, the Wi-Fi hiccuped) is noticed within a few missed
 * heartbeats and tried again at once, then every couple of seconds, on each of the leader's
 * addresses until [stop], so nobody in the band has to rejoin.
 *
 * Everything that goes wrong is said to [onLog] with its reason and timing - a gap in what arrives,
 * a message that came late, a line that went quiet - so a flaky link can be read afterwards.
 */
class CompanionFollower(
    private val myName: String,
    private val onLine: (CompanionLink.Line) -> Unit
) {
    @Volatile private var socket: Socket? = null
    @Volatile private var wanted: CompanionLink.Leader? = null
    @Volatile private var out: OutputStreamWriter? = null

    /** Told true on connecting and false on losing the connection. Off the UI thread. */
    var onConnected: ((Boolean) -> Unit)? = null

    /** A line for the event log. Off the UI thread. */
    var onLog: ((String) -> Unit)? = null

    /** When something last arrived from the leader (this device's clock), 0 before anything has. */
    @Volatile var lastHeard: Long = 0L
        private set

    /**
     * Connect to [leader], trying each address. Blocks until the first attempt is decided and
     * returns whether it worked; either way, it keeps trying in the background until [stop].
     */
    fun start(leader: CompanionLink.Leader, retryMs: Long = 2_000): Boolean {
        stop()
        wanted = leader
        val first = java.util.concurrent.CompletableFuture<Boolean>()
        Thread({
            var failures = 0
            while (wanted === leader) {
                val tried = ArrayList<String>()
                val s = leader.hosts.firstNotNullOfOrNull { host ->
                    val began = System.currentTimeMillis()
                    runCatching {
                        Socket().apply {
                            connect(InetSocketAddress(host, leader.port), 3000)
                            tcpNoDelay = true
                        }
                    }.onFailure { tried += "$host: ${it.message ?: it.javaClass.simpleName} after ${System.currentTimeMillis() - began} ms" }
                        .getOrNull()
                }
                if (s == null) {
                    first.complete(false)
                    if (failures++ % 5 == 0) onLog?.invoke("Could not reach ${leader.name} (${tried.joinToString("; ")})")
                    sleepWhileWanted(leader, retryMs)
                    continue
                }
                failures = 0
                if (wanted !== leader) { runCatching { s.close() }; break }
                socket = s
                out = runCatching {
                    OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8).apply {
                        write(CompanionLink.encode(CompanionLink.Hello(name = myName)) + "\n"); flush()
                    }
                }.getOrNull()
                first.complete(true)
                val connectedAt = System.currentTimeMillis()
                onConnected?.invoke(true)
                val why = read(s, leader)
                runCatching { s.close() }
                if (socket === s) socket = null
                if (wanted === leader) {
                    onLog?.invoke("Lost ${leader.name} after ${(System.currentTimeMillis() - connectedAt) / 1000} s: $why")
                }
                onConnected?.invoke(false)
                // Straight back on the first try: most drops are momentary.
                sleepWhileWanted(leader, 300)
            }
            first.complete(false)
        }, "companion-follow").apply { isDaemon = true; start() }
        return runCatching { first.get(10, TimeUnit.SECONDS) }.getOrDefault(false)
    }

    /** Read until the line ends; says why it ended. */
    private fun read(s: Socket, leader: CompanionLink.Leader): String {
        var lastSeq = 0L
        // The smallest (arrival - sent) seen: the two clocks' difference plus the quickest the
        // link has ever been. Anything well above it arrived late.
        var fastest = Long.MAX_VALUE
        return runCatching {
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: return "the leader closed the connection"
                val now = System.currentTimeMillis()
                val quiet = if (lastHeard > 0) now - lastHeard else 0
                lastHeard = now
                val got = CompanionLink.read(line) ?: continue
                val (seq, at) = when (got) {
                    is CompanionLink.Line.Ping -> got.seq to got.at
                    is CompanionLink.Line.Show -> got.showing.seq to got.showing.at
                    else -> 0L to 0L
                }
                if (got is CompanionLink.Line.Ping && s.soTimeout == 0) {
                    // This leader sends a heartbeat, so silence now means a dead line. (One too old
                    // to send them is never timed out.)
                    s.soTimeout = SILENT_FOR_MS.toInt()
                }
                if (at > 0) {
                    val transit = now - at
                    if (transit < fastest) fastest = transit
                    val late = transit - fastest
                    if (late > 1500) onLog?.invoke("Message ${seq} from ${leader.name} arrived ${late / 100 / 10.0} s late")
                }
                if (quiet > 3 * CompanionLeader.PING_EVERY_MS) onLog?.invoke("Nothing from ${leader.name} for ${quiet / 100 / 10.0} s")
                if (seq > 0 && got is CompanionLink.Line.Show) {
                    if (lastSeq > 0 && seq > lastSeq + 1) onLog?.invoke("Skipped from message $lastSeq to $seq")
                }
                if (seq > 0) lastSeq = maxOf(lastSeq, seq)
                if (got is CompanionLink.Line.Ping) {
                    runCatching { out?.apply { write(CompanionLink.pong(got.seq, got.at) + "\n"); flush() } }
                } else onLine(got)
            }
            @Suppress("UNREACHABLE_CODE") ""
        }.fold(
            onSuccess = { it },
            onFailure = { e ->
                when (e) {
                    is java.net.SocketTimeoutException -> "nothing arrived for ${SILENT_FOR_MS / 1000} s"
                    else -> if (wanted == null) "stopped" else (e.message ?: e.javaClass.simpleName)
                }
            }
        )
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

    companion object {
        /** Four missed heartbeats. */
        const val SILENT_FOR_MS = 5_000L
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
