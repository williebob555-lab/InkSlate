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
 * A message a leader sends with one tap - "STOP", "Look up", "From the top" - from the strip
 * over the page or from a remote.
 */
@Serializable
data class MessagePreset(
    val text: String,
    /** Covers the music, big, until each player taps it away. */
    val urgent: Boolean = false,
    /** The colour it covers the music in (ARGB); null for the warning colour. */
    val color: Int? = null,
    /** Instrument ids it goes to; empty for everyone. */
    val instruments: List<String> = emptyList(),
    /** A button of its own on the strip while leading. */
    val onStrip: Boolean = true
) {
    companion object {
        val DEFAULTS = listOf(
            MessagePreset("STOP", urgent = true),
            MessagePreset("Look up"),
            MessagePreset("From the top", onStrip = false)
        )
    }
}

/**
 * One button on a remote. What it does is [kind] - a [com.inkslate.core.PerformAction] ([ACTION],
 * named by [id]), a song or setlist to go straight to, a list to pick from, a tempo, a message,
 * a sequence of other buttons ([MACRO]) - with [value] and [text] where it takes them ("page
 * [value]", "tempo + [value]"). [label] and [color] are the player's own for any button.
 */
@Serializable
data class RemoteButton(
    val kind: String,
    val id: String? = null,
    val title: String? = null,
    val value: Double? = null,
    val text: String? = null,
    val label: String? = null,
    val color: Int? = null,
    /** For [MACRO]: the buttons pressed in turn. */
    val steps: List<RemoteButton> = emptyList(),
    /** For a message: it covers the music until tapped away. */
    val urgent: Boolean = false
) {
    companion object {
        const val ACTION = "action"
        const val SONG = "song"
        const val SETLIST = "setlist"
        const val SONGS = "songs"
        const val SET = "set"
        const val PAGE = "page"
        const val PARTS = "parts"
        const val PROFILES = "profiles"
        const val BOOKMARKS = "bookmarks"
        const val TEMPO = "tempo"
        const val TEMPO_SET = "tempo-set"
        const val TAP = "tap"
        const val COUNT_IN = "count-in"
        const val COUNT_BARS = "count-bars"
        const val CLICK_RECORDING = "click-recording"
        const val CLICK_PLAYBACK = "click-playback"
        const val RECORD = "record"
        const val AUDIO_SEEK = "audio-seek"
        const val AUDIO_RESTART = "audio-restart"
        const val AUDIO_SPEED = "audio-speed"
        const val MESSAGE = "message"
        const val MESSAGE_TYPE = "message-type"
        const val STRIP = "strip"
        const val TOOLS = "tools"
        const val FIT = "fit"
        const val HOME = "home"
        const val LEADER = "leader"
        const val LEAD = "lead"
        const val MACRO = "macro"

        fun action(name: String) = RemoteButton(ACTION, name)

        val DEFAULT_DECK = listOf(
            action("PREVIOUS_PAGE"), action("NEXT_PAGE"),
            action("PREVIOUS_SONG"), action("NEXT_SONG"),
            RemoteButton(SET), RemoteButton(SONGS),
            action("BOOKMARK"), action("METRONOME"),
            RemoteButton(COUNT_IN), action("PLAY_AUDIO"),
            RemoteButton(STRIP), RemoteButton(TOOLS)
        )
    }
}

/**
 * A remote: a second device running InkSheets that turns this one's pages, changes its song and
 * sends its leader's messages, with buttons of its own choosing - a stream deck for the stand.
 *
 * Each player's device lets its own remotes in, whether it plays alone, leads or follows; a
 * remote is paired once by scanning the device's code (which carries a key), and reconnects on
 * its own after that. Like [CompanionLink]: plain TCP, one JSON line per message.
 */
object RemoteLink {
    const val PORT = 47_822
    const val ANNOUNCE_PORT = 47_823
    private const val TAG = "INKSHEETS-REMOTE"

    /** A song or setlist, as a remote lists it. */
    @Serializable
    data class Item(val id: String, val title: String, val color: Int? = null)

    /** Where the controlled device is and what it can do: sent whenever any of it changes. */
    @Serializable
    data class State(
        val kind: String = "state",
        val device: String = "",
        val songId: String? = null,
        val title: String? = null,
        /** 0-based page, and how many. */
        val page: Int = 0,
        val pages: Int = 0,
        /** The part showing: "Trumpet 2". */
        val part: String? = null,
        val setlistId: String? = null,
        val setlist: String? = null,
        /** The songs of the set being played, in order, and which of them is in front. */
        val set: List<Item> = emptyList(),
        val setIndex: Int = -1,
        val leading: Boolean = false,
        val followers: Int = 0,
        val following: String? = null,
        val presets: List<MessagePreset> = emptyList(),
        val metronome: Boolean = false,
        val bpm: Int = 0,
        val hasRecording: Boolean = false,
        val recordingPlaying: Boolean = false,
        val bookmarked: Boolean = false,
        val toolsShown: Boolean = false,
        val home: Boolean = false,
        /** The parts of the song in front, and which is showing. */
        val parts: List<Item> = emptyList(),
        val partId: String? = null,
        val beatsPerBar: Int = 4,
        /** Beats of count-in still to come. */
        val counting: Int = 0,
        val recording: Boolean = false,
        val recordingSeconds: Int = 0,
        val countInBars: Int = 0,
        val clickRecording: Boolean = false,
        val clickPlayback: Boolean = false,
        val stripOpen: Boolean = false,
        /** The instrument chosen for every song (a profile id). */
        val profileId: String? = null
    )

    /** The library to pick a song or setlist from; sent on joining and when it changes. */
    @Serializable
    data class Library(
        val kind: String = "library",
        val songs: List<Item> = emptyList(),
        val setlists: List<Item> = emptyList(),
        /** Every bookmark: id "songId|part|page|label", title "Song - Page 3". */
        val bookmarks: List<Item> = emptyList(),
        /** The instruments this player plays (profiles). */
        val profiles: List<Item> = emptyList()
    )

    /**
     * Something to do. [action] is a [com.inkslate.core.PerformAction] name, or one of
     * [SONG], [SETLIST], [SET_ENTRY], [PRESET], [NOTE], [HOME].
     */
    @Serializable
    data class Command(
        val kind: String = "do",
        val action: String,
        val id: String? = null,
        val index: Int? = null,
        val text: String? = null,
        val value: Double? = null,
        val color: Int? = null,
        val urgent: Boolean = false,
        /** Numbers each press, so the device can say which it got ([Line.Got]). 0 from an old remote. */
        val seq: Int = 0
    )

    /**
     * [id] is the remote's own, the same on every connection it makes: a remote connecting again
     * replaces its old connection instead of counting as another remote. [v] 2 and up pings the
     * device every [RemoteHost.PING_EVERY_MS], so a remote that goes quiet is let go.
     */
    @Serializable
    data class Hello(val kind: String = "hello", val name: String = "", val key: String = "", val id: String = "", val v: Int = 1)

    @Serializable
    data class Got(val kind: String = "got", val seq: Int)

    const val SONG = "song"
    const val SETLIST = "setlist"
    const val SET_ENTRY = "set-entry"
    const val PRESET = "preset"
    const val NOTE = "note"
    const val HOME = "home"

    sealed interface Line {
        data class Shows(val state: State) : Line
        data class Songs(val library: Library) : Line
        data class Do(val command: Command) : Line
        data class Joined(val hello: Hello) : Line
        data object Ping : Line
        /** The device has the press numbered [seq]. */
        data class Got(val seq: Int) : Line
        /** The key was wrong: this device was paired again, or the code was mistyped. */
        data object Refused : Line
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(s: State): String = json.encodeToString(State.serializer(), s)
    fun encode(s: Library): String = json.encodeToString(Library.serializer(), s)
    fun encode(c: Command): String = json.encodeToString(Command.serializer(), c)
    fun encode(h: Hello): String = json.encodeToString(Hello.serializer(), h)
    fun encode(g: Got): String = json.encodeToString(Got.serializer(), g)
    const val PING = """{"kind":"ping"}"""
    const val REFUSED = """{"kind":"refused"}"""

    fun read(line: String): Line? = runCatching {
        val obj = json.decodeFromString(JsonObject.serializer(), line)
        when (obj["kind"]?.jsonPrimitive?.contentOrNull) {
            "state" -> Line.Shows(json.decodeFromJsonElement(State.serializer(), obj))
            "library" -> Line.Songs(json.decodeFromJsonElement(Library.serializer(), obj))
            "do" -> Line.Do(json.decodeFromJsonElement(Command.serializer(), obj))
            "hello" -> Line.Joined(json.decodeFromJsonElement(Hello.serializer(), obj))
            "ping" -> Line.Ping
            "got" -> Line.Got(json.decodeFromJsonElement(Got.serializer(), obj).seq)
            "refused" -> Line.Refused
            else -> null
        }
    }.getOrNull()

    /** A device to control: its name, every address it has, its port and its key. */
    data class Target(val name: String, val hosts: List<String>, val port: Int = PORT, val key: String)

    /** The text of a device's remote code: `inksheets://remote?name=Stand&hosts=192.168.1.4&port=47822&key=K7Q2PX`. */
    fun pairLink(name: String, hosts: List<String>, key: String, port: Int = PORT): String =
        "${CompanionLink.SCHEME}://remote?name=" + URLEncoder.encode(name, "UTF-8").replace("+", "%20") +
            "&hosts=" + hosts.joinToString(",") + "&port=$port&key=$key"

    fun parsePair(text: String): Target? {
        val t = text.trim()
        if (!t.startsWith("${CompanionLink.SCHEME}://remote", ignoreCase = true)) return null
        val params = t.substringAfter('?', "").split('&').mapNotNull { kv ->
            val k = kv.substringBefore('=', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            k to runCatching { URLDecoder.decode(kv.substringAfter('='), "UTF-8") }.getOrDefault("")
        }.toMap()
        val hosts = params["hosts"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val key = params["key"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (hosts.isEmpty()) return null
        return Target(params["name"]?.takeIf { it.isNotBlank() } ?: hosts.first(), hosts, params["port"]?.toIntOrNull() ?: PORT, key)
    }

    /** A pairing key: six letters and digits that read aloud without mix-ups (no O/0, I/1). */
    fun newKey(random: java.util.Random = java.security.SecureRandom()): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    /** A key typed by a person: spaces and dashes dropped, case ignored, O read as 0 read as O. */
    fun sameKey(typed: String, key: String): Boolean {
        fun clean(s: String) = s.uppercase().filter { it.isLetterOrDigit() }.replace('0', 'O').replace('1', 'I')
        return clean(typed) == clean(key)
    }

    fun announcement(name: String, port: Int) = "$TAG|${name.replace('|', ' ')}|$port"
    fun readAnnouncement(text: String, from: String): Pair<String, Pair<String, Int>>? {
        val parts = text.split('|')
        if (parts.size != 3 || parts[0] != TAG) return null
        return parts[1] to (from to (parts[2].toIntOrNull() ?: return null))
    }
}

/**
 * The controlled device's side: remotes connect, say the key, and are then sent where this device
 * is and may send it commands. Any number of remotes; one that gives the wrong key is told so and
 * let go. Nothing about remotes comes to the screen - it goes to [onLog].
 */
class RemoteHost(
    private val name: String,
    @Volatile var key: String,
    private val port: Int = RemoteLink.PORT,
    /** A remote that pings and then says nothing for this long is let go. */
    private val silentMs: Long = SILENT_FOR_MS,
    private val pingEveryMs: Long = PING_EVERY_MS
) {

    private class Client(val socket: Socket) {
        val queue = LinkedBlockingDeque<String>()
        @Volatile var open = true
        @Volatile var admitted = false
        /** Told its key is wrong: let go once that has been sent, not before. */
        @Volatile var refusing = false
        @Volatile var name = socket.inetAddress?.hostAddress ?: "?"
        val address: String = socket.inetAddress?.hostAddress ?: "?"
        /** The remote's own id, from its hello. */
        @Volatile var id = ""
        /** It pings, so silence means it is gone. */
        @Volatile var pings = false
        @Volatile var heard = System.currentTimeMillis()
    }

    private val clients = ConcurrentHashMap.newKeySet<Client>()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var lastState: String? = null
    @Volatile private var lastLibrary: String? = null

    /** A command from a remote, off the UI thread. */
    var onCommand: ((RemoteLink.Command) -> Unit)? = null

    /** Remotes connecting and leaving, and every press; off the UI thread. */
    var onLog: ((String) -> Unit)? = null

    /** The number of remotes connected changed; off the UI thread. */
    var onRemotes: ((Int) -> Unit)? = null

    /** Remotes connected now: one each, however many times one has connected. */
    val remotes: Int get() = clients.filter { it.admitted }.distinctBy { it.id.ifBlank { it.toString() } }.size

    fun start(): Boolean = runCatching {
        val socket = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) }
        server = socket
        running = true
        Thread({
            while (running) {
                val s = runCatching { socket.accept() }.getOrNull() ?: continue
                runCatching { s.tcpNoDelay = true }
                serve(Client(s))
            }
        }, "remote-accept").apply { isDaemon = true; start() }
        Thread({
            val announce = runCatching { DatagramSocket().apply { broadcast = true } }.getOrNull()
            val bytes = RemoteLink.announcement(name, port).toByteArray()
            var tick = 0
            while (running) {
                // Heard by a remote looking for devices nearby, every few seconds.
                if (tick++ % 2 == 0) runCatching {
                    announce?.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("255.255.255.255"), RemoteLink.ANNOUNCE_PORT))
                }
                val now = System.currentTimeMillis()
                clients.forEach { c ->
                    if (!c.admitted) return@forEach
                    // Gone without a word - out of the Wi-Fi, asleep, a connection the network
                    // lost: let go, rather than counted as a remote for the quarter of an hour
                    // it takes the network to say so. Closing it also frees a send stuck on it.
                    if (c.pings && now - c.heard > silentMs) {
                        onLog?.invoke("Remote ${c.name} (${c.address}) went quiet for ${(now - c.heard) / 1000}s - let go")
                        drop(c)
                        return@forEach
                    }
                    c.queue.removeIf { it == RemoteLink.PING }; c.queue.offer(RemoteLink.PING)
                }
                runCatching { Thread.sleep(pingEveryMs) }
            }
            announce?.close()
        }, "remote-ping").apply { isDaemon = true; start() }
        true
    }.getOrDefault(false)

    private fun serve(c: Client) {
        clients += c
        Thread({
            val out = runCatching { OutputStreamWriter(c.socket.getOutputStream(), Charsets.UTF_8) }.getOrNull()
            var why = if (out == null) "could not send to it" else "closed"
            while (c.open && running && out != null) {
                val line = runCatching { c.queue.poll(2, TimeUnit.SECONDS) }.getOrNull() ?: continue
                val sent = runCatching { out.write(line + "\n"); out.flush() }
                if (sent.isFailure) { why = "sending failed: ${sent.exceptionOrNull()?.message}"; break }
                if (line == RemoteLink.REFUSED) break
            }
            if (c.open && c.admitted) onLog?.invoke("Remote ${c.name} (${c.address}): $why")
            drop(c)
        }, "remote-send").apply { isDaemon = true; start() }
        Thread({
            val heard = runCatching {
                // A remote that says nothing (not even the key) in a while is let go.
                c.socket.soTimeout = HELLO_WITHIN_MS
                val reader = BufferedReader(InputStreamReader(c.socket.getInputStream(), Charsets.UTF_8))
                while (c.open) {
                    val line = reader.readLine() ?: break
                    c.heard = System.currentTimeMillis()
                    when (val got = RemoteLink.read(line)) {
                        is RemoteLink.Line.Joined -> {
                            if (!RemoteLink.sameKey(got.hello.key, key)) {
                                onLog?.invoke("A remote (${got.hello.name.ifBlank { c.name }}, ${c.address}) gave the wrong key - turned away")
                                c.refusing = true
                                c.queue.offer(RemoteLink.REFUSED)
                                return@runCatching
                            }
                            c.socket.soTimeout = 0
                            if (got.hello.name.isNotBlank()) c.name = got.hello.name
                            c.id = got.hello.id
                            c.pings = got.hello.v >= 2
                            // The same remote connecting again: its old connection is dead, or
                            // about to be. Let it go, so one remote is never counted twice.
                            if (c.id.isNotBlank()) clients.filter { it !== c && it.id == c.id }.forEach { old ->
                                onLog?.invoke("Remote ${c.name} connected again from ${c.address} - its old connection (${old.address}) let go")
                                drop(old, quietly = true)
                            }
                            c.admitted = true
                            lastLibrary?.let(c.queue::offer)
                            lastState?.let(c.queue::offer)
                            onLog?.invoke("Remote ${c.name} connected from ${c.address}")
                            onRemotes?.invoke(remotes)
                        }
                        is RemoteLink.Line.Do -> if (c.admitted) {
                            val cmd = got.command
                            if (cmd.seq != 0) c.queue.offerFirst(RemoteLink.encode(RemoteLink.Got(seq = cmd.seq)))
                            onLog?.invoke("Remote ${c.name}: ${cmd.action}" + listOfNotNull(cmd.id, cmd.index, cmd.value).joinToString("") { " $it" })
                            onCommand?.invoke(cmd)
                        }
                        else -> Unit
                    }
                }
            }
            if (c.open && c.admitted) onLog?.invoke("Remote ${c.name} (${c.address}) " + (heard.exceptionOrNull()?.let { "stopped: ${it.message}" } ?: "hung up"))
            // A remote being turned away is let go by the sender, once it has been told.
            if (!c.refusing) drop(c)
        }, "remote-hear").apply { isDaemon = true; start() }
    }

    private fun drop(c: Client, quietly: Boolean = false) {
        if (!clients.remove(c)) return
        c.open = false
        runCatching { c.socket.close() }
        if (c.admitted) {
            if (!quietly) onLog?.invoke("Remote ${c.name} disconnected")
            onRemotes?.invoke(remotes)
        }
    }

    /** Where this device is now; sent to every remote if it changed. */
    fun show(state: RemoteLink.State) {
        val line = RemoteLink.encode(state.copy(device = name))
        if (line == lastState) return
        lastState = line
        clients.forEach { c -> if (c.admitted) { c.queue.removeIf { it.startsWith("{\"kind\":\"state\"") }; c.queue.offer(line) } }
    }

    fun library(library: RemoteLink.Library) {
        val line = RemoteLink.encode(library)
        if (line == lastLibrary) return
        lastLibrary = line
        clients.forEach { c -> if (c.admitted) { c.queue.removeIf { it.startsWith("{\"kind\":\"library\"") }; c.queue.offer(line) } }
    }

    /** A new key: every remote paired so far is let go and has to scan again. */
    fun rekey(newKey: String) {
        key = newKey
        clients.forEach { c -> c.refusing = true; c.queue.offer(RemoteLink.REFUSED) }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        clients.forEach { it.open = false; runCatching { it.socket.close() } }
        clients.clear()
    }

    companion object {
        const val PING_EVERY_MS = 2_000L
        const val HELLO_WITHIN_MS = 10_000
        /** Three missed pings. */
        const val SILENT_FOR_MS = 7_000L
    }
}

/**
 * The remote's side: connect to [RemoteLink.Target], and keep connected - a dropped link is tried
 * again at once, then every couple of seconds, on each of the device's addresses, until [stop].
 *
 * Connected means the device has been heard from, not only that a connection opened: a network
 * that lets a connection open but carries nothing back shows as not connected, and is given up on.
 */
class RemoteClient(
    private val myName: String,
    /** This remote's own id: the device counts a remote by it. */
    private val myId: String = java.util.UUID.randomUUID().toString(),
    private val silentMs: Int = SILENT_FOR_MS,
    private val pingEveryMs: Long = RemoteHost.PING_EVERY_MS,
    /** Addresses of this device itself, never connected to (unless they are all there is). */
    private val ownAddresses: () -> Collection<String> = ::localAddresses,
    private val onLine: (RemoteLink.Line) -> Unit
) {
    constructor(myName: String, onLine: (RemoteLink.Line) -> Unit) : this(myName, java.util.UUID.randomUUID().toString(), onLine = onLine)

    @Volatile private var wanted: RemoteLink.Target? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStreamWriter? = null
    private var seq = 0
    /** The address that answered last, tried first next time. */
    @Volatile private var lastGood: String? = null

    /** Told true once the device is heard from, and false on losing it. Off the UI thread. */
    var onConnected: ((Boolean) -> Unit)? = null

    /** What happened, for the log; off the UI thread. */
    var onLog: ((String) -> Unit)? = null

    fun start(target: RemoteLink.Target) {
        stop()
        wanted = target
        Thread({
            var failures = 0
            while (wanted === target) {
                val s = open(target)
                if (s == null) {
                    if (failures++ % 15 == 0) onLog?.invoke("Could not reach ${target.name} on ${target.hosts.joinToString()} port ${target.port}")
                    sleepWhileWanted(target, 2_000)
                    continue
                }
                failures = 0
                if (wanted !== target) { runCatching { s.close() }; break }
                val at = s.inetAddress?.hostAddress
                socket = s
                val o = runCatching {
                    OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8).apply {
                        write(RemoteLink.encode(RemoteLink.Hello(name = myName, key = target.key, id = myId, v = 2)) + "\n"); flush()
                    }
                }.getOrNull()
                out = o
                // Pings, so the device can tell this remote from one that has gone.
                val pinger = Thread({
                    while (socket === s && o != null) {
                        try { Thread.sleep(pingEveryMs) } catch (_: InterruptedException) { break }
                        if (socket !== s) break
                        if (runCatching { synchronized(o) { o.write(RemoteLink.PING + "\n"); o.flush() } }.isFailure) break
                    }
                }, "remote-client-ping").apply { isDaemon = true; start() }
                var refused = false
                var heard = false
                val ended = runCatching {
                    s.soTimeout = silentMs
                    val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                    while (wanted === target) {
                        val line = reader.readLine() ?: break
                        if (!heard) {
                            heard = true
                            lastGood = at
                            onLog?.invoke("Connected to ${target.name} at $at")
                            onConnected?.invoke(true)
                        }
                        val got = RemoteLink.read(line) ?: continue
                        if (got is RemoteLink.Line.Refused) { refused = true; onLine(got); break }
                        if (got !is RemoteLink.Line.Ping) onLine(got)
                    }
                }.exceptionOrNull()
                if (socket === s) { socket = null; out = null }
                runCatching { s.close() }
                pinger.interrupt()
                if (wanted === target) onLog?.invoke(
                    when {
                        refused -> "${target.name} turned this remote away"
                        !heard -> "${target.name} at $at let this remote connect but sent nothing back" + (ended?.message?.let { " ($it)" } ?: "")
                        ended != null -> "Lost ${target.name}: ${ended.message}"
                        else -> "${target.name} hung up"
                    }
                )
                if (heard) onConnected?.invoke(false)
                // Told the key is wrong: trying again would be told the same.
                if (refused) { if (wanted === target) wanted = null; break }
                // Heard nothing at all: another of its addresses may carry more than this one.
                if (!heard) lastGood = target.hosts.firstOrNull { it != at && it !in ownOrNone() }
                sleepWhileWanted(target, 300)
            }
        }, "remote-client").apply { isDaemon = true; start() }
    }

    private fun ownOrNone(): Collection<String> = runCatching { ownAddresses() }.getOrDefault(emptyList())

    /**
     * A connection to the device: every address it gave tried at once, the first to answer kept -
     * an address that goes nowhere costs nothing then. Never this device's own address: a device
     * letting remotes in itself would answer there, and turn this remote away.
     */
    private fun open(target: RemoteLink.Target): Socket? {
        val mine = ownOrNone().toSet()
        val hosts = target.hosts.filter { it !in mine }.ifEmpty { target.hosts }
        lastGood?.takeIf { it in hosts }?.let { first -> connectTo(first, target.port)?.let { return it } }
        val won = java.util.concurrent.LinkedBlockingQueue<Socket>()
        val left = java.util.concurrent.CountDownLatch(hosts.size)
        hosts.forEach { host ->
            Thread({
                connectTo(host, target.port)?.let(won::offer)
                left.countDown()
            }, "remote-connect").apply { isDaemon = true; start() }
        }
        var s: Socket? = null
        val until = System.currentTimeMillis() + CONNECT_MS + 500
        while (s == null && System.currentTimeMillis() < until) {
            s = won.poll(100, TimeUnit.MILLISECONDS)
            if (s == null && left.count == 0L) { s = won.poll(); break }
        }
        // Any other address that answered too is not needed.
        Thread({
            runCatching { left.await(CONNECT_MS + 1_000L, TimeUnit.MILLISECONDS) }
            while (true) { val extra = won.poll() ?: break; runCatching { extra.close() } }
        }, "remote-connect-tidy").apply { isDaemon = true; start() }
        return s
    }

    private fun connectTo(host: String, port: Int): Socket? = runCatching {
        Socket().apply { connect(InetSocketAddress(host, port), CONNECT_MS); tcpNoDelay = true }
    }.getOrNull()

    /**
     * Send [command], numbered; the number (the device answers [RemoteLink.Line.Got] with it), or
     * 0 when not connected. Off the UI thread - it writes to the network.
     */
    fun send(command: RemoteLink.Command): Int {
        val o = out ?: return 0
        val n = synchronized(this) { ++seq }
        val line = RemoteLink.encode(command.copy(seq = n)) + "\n"
        return if (runCatching { synchronized(o) { o.write(line); o.flush() } }.isSuccess) n else 0
    }

    private fun sleepWhileWanted(target: RemoteLink.Target, ms: Long) {
        var left = ms
        while (left > 0 && wanted === target) { Thread.sleep(100); left -= 100 }
    }

    /** Drop this connection and make a new one - it has stopped carrying anything back. */
    fun reconnect() {
        val s = socket ?: return
        Thread({ runCatching { s.close() } }, "remote-reconnect").apply { isDaemon = true; start() }
    }

    fun stop() {
        wanted = null
        val s = socket
        socket = null
        out = null
        runCatching { s?.close() }
    }

    companion object {
        /** Three missed heartbeats. */
        const val SILENT_FOR_MS = 7_000
        const val CONNECT_MS = 3_000

        /** Every IPv4 address of this device. */
        fun localAddresses(): List<String> = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { nic -> nic.inetAddresses.toList().filterIsInstance<java.net.Inet4Address>().map { it.hostAddress } }
        }.getOrDefault(emptyList())
    }
}

/** Listening for devices that let remotes in, on the local network. */
class RemoteScanner(private val onFound: (name: String, host: String, port: Int) -> Unit) {
    @Volatile private var socket: DatagramSocket? = null

    fun start(): Boolean = runCatching {
        val s = DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(RemoteLink.ANNOUNCE_PORT)) }
        socket = s
        Thread({
            val buffer = ByteArray(512)
            while (socket === s) {
                val packet = DatagramPacket(buffer, buffer.size)
                runCatching { s.receive(packet) }.onFailure { return@Thread }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                RemoteLink.readAnnouncement(text, packet.address.hostAddress)?.let { (name, at) -> onFound(name, at.first, at.second) }
            }
        }, "remote-scan").apply { isDaemon = true; start() }
        true
    }.getOrDefault(false)

    fun stop() {
        val s = socket
        socket = null
        runCatching { s?.close() }
    }
}
