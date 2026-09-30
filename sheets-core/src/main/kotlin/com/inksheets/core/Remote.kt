package com.inksheets.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
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
    val urgent: Boolean = false,
    /** Its cell on the remote's grid, column and row from 0; null until placed. */
    val x: Int? = null,
    val y: Int? = null
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
        const val AUDIO_VOLUME = "audio-volume"
        const val MESSAGE = "message"
        const val MESSAGE_TYPE = "message-type"
        const val STRIP = "strip"
        const val TOOLS = "tools"
        const val FIT = "fit"
        const val HOME = "home"
        const val LEADER = "leader"
        const val LEAD = "lead"
        const val MACRO = "macro"
        /** A square to pan and zoom the page on with two fingers; it sends [VIEW] commands. */
        const val TOUCHPAD = "touchpad"
        /** Listen and turn the pages (experimental): on or off for this run of the song. */
        const val LISTEN = "listen"
        const val VIEW = "view"

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
 * The remote's buttons on a grid of [columns] by [rows] cells, one button a cell, with cells left
 * empty where the player wants a gap. Buttons are dragged in from a library, between cells and
 * back out; nothing here is lost silently - a move that has nowhere to go is refused.
 */
data class DeckGrid(val columns: Int = 3, val rows: Int = 4) {
    init { require(columns in COLUMNS && rows in ROWS) }

    val cells get() = columns * rows

    private fun inside(b: RemoteButton) = b.x != null && b.y != null && b.x in 0 until columns && b.y in 0 until rows

    /**
     * Every button in a cell: its own where that is inside the grid and not taken, the first free
     * cells (row by row) for the rest - or null when they do not all fit.
     */
    fun place(buttons: List<RemoteButton>): List<RemoteButton>? {
        if (buttons.size > cells) return null
        val taken = HashSet<Pair<Int, Int>>()
        val out = buttons.map { b -> if (inside(b) && taken.add(b.x!! to b.y!!)) b else b.copy(x = null, y = null) }
        val free = (0 until rows).flatMap { y -> (0 until columns).map { x -> x to y } }.filter { it !in taken }.iterator()
        return out.map { b -> if (b.x != null) b else free.next().let { (x, y) -> b.copy(x = x, y = y) } }
    }

    /** The button in cell ([x], [y]), by its place in [buttons]; -1 for an empty cell. */
    fun at(buttons: List<RemoteButton>, x: Int, y: Int): Int = buttons.indexOfFirst { it.x == x && it.y == y }

    /** Button [from] moved to cell ([x], [y]); one already there takes its old cell. */
    fun move(buttons: List<RemoteButton>, from: Int, x: Int, y: Int): List<RemoteButton> {
        val moving = buttons.getOrNull(from) ?: return buttons
        val there = at(buttons, x, y)
        return buttons.mapIndexed { i, b ->
            when (i) {
                from -> b.copy(x = x, y = y)
                there -> b.copy(x = moving.x, y = moving.y)
                else -> b
            }
        }
    }

    /**
     * [button] put in cell ([x], [y]); one already there moves to the nearest free cell. Null when
     * the grid is full - make it bigger, or take one off first.
     */
    fun add(buttons: List<RemoteButton>, button: RemoteButton, x: Int, y: Int): List<RemoteButton>? {
        val placed = place(buttons) ?: return null
        if (placed.size >= cells) return null
        val there = at(placed, x, y)
        val moved = if (there < 0) placed else {
            val taken = placed.map { it.x to it.y }.toSet()
            val free = (0 until rows).flatMap { yy -> (0 until columns).map { xx -> xx to yy } }
                .filter { it !in taken }
                .minByOrNull { (xx, yy) -> kotlin.math.abs(xx - x) + kotlin.math.abs(yy - y) } ?: return null
            placed.mapIndexed { i, b -> if (i == there) b.copy(x = free.first, y = free.second) else b }
        }
        return moved + button.copy(x = x, y = y)
    }

    /** The first empty cell, row by row; null when full. */
    fun firstFree(buttons: List<RemoteButton>): Pair<Int, Int>? {
        val taken = buttons.map { it.x to it.y }.toSet()
        return (0 until rows).flatMap { y -> (0 until columns).map { x -> x to y } }.firstOrNull { it !in taken }
    }

    /**
     * The same buttons on a grid of another size: each keeps its cell where the new grid has it,
     * the rest go to free cells. Null when they would not all fit.
     */
    fun resized(buttons: List<RemoteButton>, to: DeckGrid): List<RemoteButton>? = to.place(buttons)

    override fun toString() = "${columns}x$rows"

    companion object {
        val COLUMNS = 1..6
        val ROWS = 1..10
        fun parse(text: String?): DeckGrid? = text?.split('x')?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.size == 2 && it[0] in COLUMNS && it[1] in ROWS }?.let { DeckGrid(it[0], it[1]) }
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
        val profileId: String? = null,
        /** The windows open over the music: [com.inkslate.core.PerformAction] names (TUNER, RECORDINGS, ...). */
        val windows: List<String> = emptyList(),
        /** Listen is on, and what it is doing in a few words (it hears nothing, when it turns). */
        val listening: Boolean = false,
        val listenStatus: String? = null
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
        val seq: Int = 0,
        /**
         * For [RemoteButton.VIEW], the touchpad: moved by [dx], [dy] (fractions of the view's
         * shorter side), zoomed by [zoom] about ([fx], [fy]) (fractions of the view).
         */
        val dx: Double = 0.0,
        val dy: Double = 0.0,
        val zoom: Double = 1.0,
        val fx: Double = 0.5,
        val fy: Double = 0.5
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

    /**
     * A device to control: its name, every address it has, its port and its key - and its
     * Bluetooth address ([bt], "AA:BB:CC:DD:EE:FF") where it takes remotes over Bluetooth too, with
     * the [channel] to use where its service cannot be looked up.
     */
    data class Target(
        val name: String,
        val hosts: List<String>,
        val port: Int = PORT,
        val key: String,
        val bt: String? = null,
        val channel: Int? = null
    )

    /** The text of a device's remote code: `inksheets://remote?name=Stand&hosts=192.168.1.4&port=47822&key=K7Q2PX&bt=AA:BB:CC:DD:EE:FF`. */
    fun pairLink(name: String, hosts: List<String>, key: String, port: Int = PORT, bt: String? = null, channel: Int? = null): String =
        "${CompanionLink.SCHEME}://remote?name=" + URLEncoder.encode(name, "UTF-8").replace("+", "%20") +
            "&hosts=" + hosts.joinToString(",") + "&port=$port&key=$key" +
            (bt?.let { "&bt=$it" } ?: "") + (channel?.let { "&ch=$it" } ?: "")

    fun pairLink(t: Target): String = pairLink(t.name, t.hosts, t.key, t.port, t.bt, t.channel)

    fun parsePair(text: String): Target? {
        val t = text.trim()
        if (!t.startsWith("${CompanionLink.SCHEME}://remote", ignoreCase = true)) return null
        val params = t.substringAfter('?', "").split('&').mapNotNull { kv ->
            val k = kv.substringBefore('=', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            k to runCatching { URLDecoder.decode(kv.substringAfter('='), "UTF-8") }.getOrDefault("")
        }.toMap()
        val hosts = params["hosts"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val key = params["key"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
        val bt = params["bt"]?.trim()?.uppercase()?.takeIf { it.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")) }
        if (hosts.isEmpty() && bt == null) return null
        return Target(
            params["name"]?.takeIf { it.isNotBlank() } ?: hosts.firstOrNull() ?: bt!!,
            hosts, params["port"]?.toIntOrNull() ?: PORT, key, bt, params["ch"]?.toIntOrNull()
        )
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
/** A connection to or from a remote, whatever carries it: a TCP socket, or Bluetooth. */
interface RemotePipe {
    val input: InputStream
    val output: OutputStream
    /** Where it is: an IP address, or `bt:AA:BB:CC:DD:EE:FF`. */
    val address: String
    fun close()
}

class SocketPipe(val socket: Socket) : RemotePipe {
    override val input: InputStream get() = socket.getInputStream()
    override val output: OutputStream get() = socket.getOutputStream()
    override val address: String = socket.inetAddress?.hostAddress ?: "?"
    override fun close() { runCatching { socket.close() } }
}

/**
 * Remotes over Bluetooth: classic RFCOMM, one link a Wi-Fi that drops connections (eduroam) cannot
 * touch. The platform's, where it has one - Android's own, Winsock's on Windows, BlueZ's on Linux.
 */
interface RemoteBluetooth {
    /** Where remotes reach this device: its address where it can be read, and the channel when not looked up by [UUID]. */
    data class Listening(val address: String?, val channel: Int?)

    /** Take remotes until [stopListening], each connection handed to [take]; null when it could not start. */
    fun listen(take: (RemotePipe) -> Unit): Listening?
    fun stopListening()

    /** A connection to [address], found by [UUID] - or on [channel] where given. Blocks; off the UI thread. */
    fun connect(address: String, channel: Int?): RemotePipe?

    /** Devices paired with this one in the system's Bluetooth settings: name to address. */
    fun paired(): List<Pair<String, String>>

    /** Asks for anything missing (permission, Bluetooth off); true when it can be used. */
    fun ready(): Boolean

    companion object {
        /** InkSheets' remote service, as Bluetooth looks it up. */
        val UUID: java.util.UUID = java.util.UUID.fromString("7a1c3e52-0d4b-4f6e-9b8a-5e2f1c6d4b21")
        const val NAME = "InkSheets remote"
        /** The channel used where a service cannot be put in the device's list (Linux). */
        const val CHANNEL = 23
    }
}

class RemoteHost(
    private val name: String,
    @Volatile var key: String,
    private val port: Int = RemoteLink.PORT,
    /** A remote that pings and then says nothing for this long is let go. */
    private val silentMs: Long = SILENT_FOR_MS,
    private val pingEveryMs: Long = PING_EVERY_MS
) {

    private class Client(val pipe: RemotePipe) {
        val queue = LinkedBlockingDeque<String>()
        @Volatile var open = true
        @Volatile var admitted = false
        /** Told its key is wrong: let go once that has been sent, not before. */
        @Volatile var refusing = false
        val address: String = pipe.address
        @Volatile var name = address
        /** The remote's own id, from its hello. */
        @Volatile var id = ""
        /** It pings, so silence means it is gone. */
        @Volatile var pings = false
        val since = System.currentTimeMillis()
        @Volatile var heard = since
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
                take(SocketPipe(s))
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
                    if (!c.admitted) {
                        // Never said the key: let go.
                        if (!c.refusing && now - c.since > HELLO_WITHIN_MS) drop(c)
                        return@forEach
                    }
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

    /** A connection from a remote, however it came - the TCP port, or Bluetooth. */
    fun take(pipe: RemotePipe) {
        if (!running) { pipe.close(); return }
        val c = Client(pipe)
        clients += c
        Thread({
            val out = runCatching { OutputStreamWriter(c.pipe.output, Charsets.UTF_8) }.getOrNull()
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
                val reader = BufferedReader(InputStreamReader(c.pipe.input, Charsets.UTF_8))
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
                            // The touchpad sends a stream of these: not answered, not logged.
                            val stream = cmd.action == RemoteButton.VIEW
                            if (cmd.seq != 0 && !stream) c.queue.offerFirst(RemoteLink.encode(RemoteLink.Got(seq = cmd.seq)))
                            if (!stream) onLog?.invoke("Remote ${c.name}: ${cmd.action}" + listOfNotNull(cmd.id, cmd.index, cmd.value).joinToString("") { " $it" })
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
        c.pipe.close()
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
        clients.forEach { it.open = false; it.pipe.close() }
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
 * again at once, then every couple of seconds, on each of the device's addresses and over
 * Bluetooth, until [stop].
 *
 * Connected means the device has been heard from, not only that a connection opened: a network
 * that lets a connection open but carries nothing back shows as not connected, and that address
 * is tried last from then on.
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

    /** Bluetooth, where this device has it: a way round a Wi-Fi that will not carry the link. */
    var bluetooth: RemoteBluetooth? = null

    @Volatile private var wanted: RemoteLink.Target? = null
    @Volatile private var pipe: RemotePipe? = null
    @Volatile private var out: OutputStreamWriter? = null
    private var seq = 0
    /** The address that answered last, tried first next time. */
    @Volatile private var lastGood: String? = null

    /** Told true once the device is heard from, and false on losing it. Off the UI thread. */
    var onConnected: ((Boolean) -> Unit)? = null

    /** What happened, for the log; off the UI thread. */
    var onLog: ((String) -> Unit)? = null

    /** A connection to this address opened, then carried nothing: the network is in the way. Off the UI thread. */
    var onBlocked: ((address: String) -> Unit)? = null

    /** How the device is reached now: an IP address, or `bt:...`. */
    @Volatile var via: String? = null
        private set

    /** Addresses that let a connection open and then carried nothing. */
    private val silent: MutableSet<String> = java.util.Collections.newSetFromMap(ConcurrentHashMap())

    fun start(target: RemoteLink.Target) {
        stop()
        wanted = target
        Thread({
            var failures = 0
            while (wanted === target) {
                val p = open(target)
                if (p == null) {
                    if (failures++ % 15 == 0) onLog?.invoke(
                        "Could not reach ${target.name} on ${target.hosts.joinToString()} port ${target.port}" +
                            (target.bt?.let { if (bluetooth != null) " or Bluetooth $it" else " (Bluetooth not available here)" } ?: "")
                    )
                    sleepWhileWanted(target, 2_000)
                    continue
                }
                failures = 0
                if (wanted !== target) { p.close(); break }
                val at = p.address
                pipe = p
                val o = runCatching {
                    OutputStreamWriter(p.output, Charsets.UTF_8).apply {
                        write(RemoteLink.encode(RemoteLink.Hello(name = myName, key = target.key, id = myId, v = 2)) + "\n"); flush()
                    }
                }.getOrNull()
                out = o
                val lastRead = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
                // Pings, so the device can tell this remote from one that has gone; and silence
                // from the device ends the connection - Bluetooth has no read timeout of its own.
                val keeper = Thread({
                    while (pipe === p && o != null) {
                        try { Thread.sleep(pingEveryMs) } catch (_: InterruptedException) { break }
                        if (pipe !== p) break
                        if (System.currentTimeMillis() - lastRead.get() > silentMs) { p.close(); break }
                        if (runCatching { synchronized(o) { o.write(RemoteLink.PING + "\n"); o.flush() } }.isFailure) break
                    }
                }, "remote-client-ping").apply { isDaemon = true; start() }
                var refused = false
                var heard = false
                val ended = runCatching {
                    val reader = BufferedReader(InputStreamReader(p.input, Charsets.UTF_8))
                    while (wanted === target) {
                        val line = reader.readLine() ?: break
                        lastRead.set(System.currentTimeMillis())
                        if (!heard) {
                            heard = true
                            lastGood = at
                            via = at
                            silent -= at
                            onLog?.invoke("Connected to ${target.name} " + if (at.startsWith("bt:")) "over Bluetooth" else "at $at")
                            onConnected?.invoke(true)
                        }
                        val got = RemoteLink.read(line) ?: continue
                        if (got is RemoteLink.Line.Refused) { refused = true; onLine(got); break }
                        if (got !is RemoteLink.Line.Ping) onLine(got)
                    }
                }.exceptionOrNull()
                if (pipe === p) { pipe = null; out = null; via = null }
                p.close()
                keeper.interrupt()
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
                // Heard nothing at all: a network that lets a connection open and then carries
                // nothing (seen on eduroam: the key arrived, not one byte after it, either way).
                // Every other way - Bluetooth, a tailnet - is tried before this one from now on.
                if (!heard && wanted === target) {
                    silent += at
                    if (lastGood == at) lastGood = null
                    onBlocked?.invoke(at)
                }
                sleepWhileWanted(target, 300)
            }
        }, "remote-client").apply { isDaemon = true; start() }
    }

    private fun ownOrNone(): Collection<String> = runCatching { ownAddresses() }.getOrDefault(emptyList())

    /**
     * A connection to the device: every address it gave tried at once, the first to answer kept -
     * an address that goes nowhere costs nothing then - and Bluetooth when none will do. Never
     * this device's own address: a device letting remotes in itself would answer there.
     */
    private fun open(target: RemoteLink.Target): RemotePipe? {
        val mine = ownOrNone().toSet()
        val all = target.hosts.filter { it !in mine }.ifEmpty { target.hosts }
        val bt = target.bt?.takeIf { bluetooth != null }?.let { "bt:$it" }
        lastGood?.let { first ->
            if (first == bt) bluetoothTo(target)?.let { return it }
            else if (first in all) connectTo(first, target.port)?.let { return SocketPipe(it) }
        }
        // An address that went silent is tried only when no other way answers.
        val (quiet, rest) = all.partition { it in silent }
        if (rest.isNotEmpty()) race(rest, target.port)?.let { return SocketPipe(it) }
        if (bt != null && bt !in silent) bluetoothTo(target)?.let { return it }
        if (quiet.isNotEmpty()) race(quiet, target.port)?.let { return SocketPipe(it) }
        if (bt != null && bt in silent) bluetoothTo(target)?.let { return it }
        return null
    }

    private fun bluetoothTo(target: RemoteLink.Target): RemotePipe? {
        val b = bluetooth ?: return null
        val address = target.bt ?: return null
        return runCatching { b.connect(address, target.channel) }
            .onFailure { onLog?.invoke("Bluetooth to ${target.name} failed: ${it.message}") }
            .getOrNull()
    }

    private fun race(hosts: List<String>, port: Int): Socket? {
        val won = java.util.concurrent.LinkedBlockingQueue<Socket>()
        val left = java.util.concurrent.CountDownLatch(hosts.size)
        hosts.forEach { host ->
            Thread({
                connectTo(host, port)?.let(won::offer)
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
        val p = pipe ?: return
        Thread({ p.close() }, "remote-reconnect").apply { isDaemon = true; start() }
    }

    fun stop() {
        wanted = null
        val p = pipe
        pipe = null
        out = null
        via = null
        p?.let { Thread({ it.close() }, "remote-stop").apply { isDaemon = true; start() } }
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
