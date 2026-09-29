package com.inksheets.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.ViewSidebar
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkslate.core.Perform
import com.inkslate.core.PerformAction
import com.inksheets.core.MessagePreset
import com.inksheets.core.RemoteButton
import com.inksheets.core.RemoteClient
import com.inksheets.core.RemoteHost
import com.inksheets.core.RemoteLink
import com.inksheets.core.RemoteScanner
import com.inkslate.core.NetAddresses

/**
 * Remotes, both ways round. This device controlled from another - its pages, its song, its
 * leader's messages - while it plays alone, leads or follows; and this device as the remote of
 * another, a deck of buttons chosen by the player. One per app, like [Companion].
 */
class RemoteControl(private val state: SheetsState) {

    // ---- this device, controlled by remotes ------------------------------------------------

    /** Remotes may connect. Remembered, so a paired remote finds this device again after a restart. */
    var hosting by mutableStateOf(false)
        private set

    /** Remotes connected now. */
    var remotes by mutableStateOf(0)
        private set

    /** What a remote scans to pair; null while not hosting. */
    var pairLink by mutableStateOf<String?>(null)
        private set

    /** The key a remote must give - kept, so a paired remote stays paired. */
    var key: String = state.platform.pref(K_KEY) ?: RemoteLink.newKey().also { state.platform.setPref(K_KEY, it) }
        private set

    private var host: RemoteHost? = null
    private var timer: java.util.Timer? = null
    private var libraryVersion = -1L

    fun startHosting(): Boolean {
        if (host != null) return true
        val name = state.platform.deviceName
        val h = RemoteHost(name, key)
        h.onCommand = { c -> state.platform.onMain { perform(c) } }
        h.onLog = { line -> state.platform.log("Remote: $line") }
        h.onRemotes = { n -> state.platform.onMain { remotes = n; publish() } }
        if (!h.start()) return false
        host = h
        hosting = true
        state.platform.setPref(K_HOSTING, "true")
        pairLink = RemoteLink.pairLink(name, NetAddresses.mine(), key)
        libraryVersion = -1L
        // Where this device is, told to every remote whenever it changes: read a few times a
        // second (small, and sent only when different).
        timer = java.util.Timer("remote-state", true).apply {
            schedule(object : java.util.TimerTask() {
                override fun run() = state.platform.onMain { publish() }
            }, 0L, 250L)
        }
        return true
    }

    fun stopHosting() {
        timer?.cancel(); timer = null
        host?.stop(); host = null
        hosting = false
        remotes = 0
        pairLink = null
        state.platform.setPref(K_HOSTING, "false")
    }

    /** A new code: every remote paired so far has to scan again. */
    fun pairAgain() {
        key = RemoteLink.newKey().also { state.platform.setPref(K_KEY, it) }
        host?.rekey(key)
        pairLink = RemoteLink.pairLink(state.platform.deviceName, NetAddresses.mine(), key)
    }

    private var setCache: Triple<Long, String, List<RemoteLink.Item>>? = null

    private fun publish() {
        val h = host ?: return
        // Nobody to tell: nothing read, nothing built. A remote joining is told at once.
        if (h.remotes == 0) { libraryVersion = -1L; return }
        val lib = state.library
        if (lib != null && state.version != libraryVersion) {
            libraryVersion = state.version
            val songs = lib.songs.sortedBy { it.title.lowercase() }
            h.library(RemoteLink.Library(
                songs = songs.map { RemoteLink.Item(it.id, it.title, it.color) },
                setlists = lib.setlists.sortedBy { it.name.lowercase() }.map { RemoteLink.Item(it.id, it.name, it.color) },
                bookmarks = songs.flatMap { s ->
                    s.bookmarks.sortedWith(compareBy(nullsLast()) { it.rank }).map { b ->
                        RemoteLink.Item(listOf(s.id, b.part.orEmpty(), b.page, b.label).joinToString("|"), "${s.title} - ${b.label}", b.color ?: s.color)
                    }
                },
                profiles = state.profiles.map { RemoteLink.Item(it.id, it.name) }
            ))
        }
        val song = state.current
        val (page, pages) = state.pageShown
        val playing = state.playing
        val set = playing?.let { (id, _) ->
            setCache?.takeIf { it.first == state.version && it.second == id }?.third
                ?: lib?.setlist(id)?.entries?.map { e -> RemoteLink.Item(e.songId, lib.song(e.songId)?.title ?: "?", e.color) }.orEmpty()
                    .also { setCache = Triple(state.version, id, it) }
        }.orEmpty()
        val companion = state.companion
        h.show(RemoteLink.State(
            songId = song?.id,
            title = song?.title,
            page = page,
            pages = pages,
            part = if (song != null) state.partShown()?.let { com.inksheets.core.Instruments.partName(it) } else null,
            setlistId = playing?.first,
            setlist = playing?.let { lib?.setlist(it.first)?.name },
            set = set,
            setIndex = playing?.second ?: -1,
            leading = companion.leading,
            followers = companion.followers,
            following = companion.following,
            presets = if (companion.leading) state.presets else emptyList(),
            metronome = SharedMetronome.running,
            bpm = SharedMetronome.bpm.toInt(),
            hasRecording = song?.audio?.isNotEmpty() == true,
            recordingPlaying = Recording.playing,
            bookmarked = song != null && state.bookmarkHere() != null,
            toolsShown = !Perform.on(PerformAction.FULLSCREEN),
            home = state.homeInFront,
            parts = song?.parts.orEmpty().map { RemoteLink.Item(it.id, com.inksheets.core.Instruments.partName(it)) },
            partId = if (song != null) state.partShown()?.id else null,
            beatsPerBar = SharedMetronome.engine?.settings?.beatsPerBar ?: 4,
            counting = Click.counting,
            recording = SelfRecorder.recording,
            recordingSeconds = SelfRecorder.seconds,
            countInBars = Click.countInBars(state),
            clickRecording = Click.withRecording(state),
            clickPlayback = Click.withPlayback(state),
            stripOpen = !state.stripCollapsed,
            profileId = state.profileId
        ))
    }

    /** A remote's command, on the UI thread: just what a button here would do. */
    private fun perform(c: RemoteLink.Command) {
        val lib = state.library
        when (c.action) {
            RemoteLink.SONG -> {
                val song = c.id?.let { lib?.song(it) } ?: return
                // A song of the set being played is played in the set; any other on its own.
                val playing = state.playing
                val inSet = playing?.let { (id, _) -> lib?.setlist(id)?.entries?.indexOfFirst { it.songId == song.id } }?.takeIf { it >= 0 }
                if (playing != null && inSet != null) state.playSetlist(playing.first, inSet)
                else { state.stopPlaying(); openSong(state, song) }
            }
            RemoteLink.SETLIST -> c.id?.let { state.playSetlist(it, c.index ?: 0) }
            RemoteLink.SET_ENTRY -> state.playing?.let { (id, _) -> c.index?.let { state.playSetlist(id, it) } }
            RemoteLink.PRESET -> c.index?.let { state.presets.getOrNull(it) }?.let { state.sendPreset(it) }
            RemoteLink.NOTE, RemoteButton.MESSAGE -> c.text?.takeIf { it.isNotBlank() }?.let {
                if (state.companion.leading) state.companion.sendNote(it, emptyList(), c.urgent, c.color)
            }
            RemoteButton.PAGE -> {
                val path = state.currentPath
                val n = c.value?.toInt()
                if (path != null && n != null) Perform.jumpTo?.invoke(path, (n - 1).coerceIn(0, (state.pageShown.second - 1).coerceAtLeast(0)))
            }
            RemoteButton.PARTS -> state.current?.let { s -> lib?.song(s.id) ?: s }?.parts?.firstOrNull { it.id == c.id }?.let { state.switchThisSong(it) }
            RemoteButton.PROFILES -> c.id?.let { state.switchAllSongs(it) }
            RemoteButton.BOOKMARKS -> c.id?.split('|')?.takeIf { it.size >= 4 }?.let { (songId, part, page, label) ->
                val song = lib?.song(songId) ?: return@let
                song.bookmarks.firstOrNull { it.part.orEmpty() == part && it.page.toString() == page && it.label == label }
                    ?.let { state.openBookmark(song, it) }
            }
            RemoteButton.TEMPO -> Click.setBpm(state, SharedMetronome.bpm + (c.value ?: 0.0))
            RemoteButton.TEMPO_SET, RemoteButton.TAP -> c.value?.let { Click.setBpm(state, it) }
            RemoteButton.COUNT_IN -> Click.countOff(state, c.value?.toInt())
            RemoteButton.COUNT_BARS -> c.value?.let { Click.setCountInBars(state, it.toInt()) }
            RemoteButton.CLICK_RECORDING -> Click.setWithRecording(state, !Click.withRecording(state))
            RemoteButton.CLICK_PLAYBACK -> Click.setWithPlayback(state, !Click.withPlayback(state))
            RemoteButton.RECORD -> if (SelfRecorder.recording) SelfRecorder.stop(state) else state.current?.let { SelfRecorder.start(state, it) }
            RemoteButton.AUDIO_SEEK -> Recording.player?.let { p ->
                val to = p.positionMs + ((c.value ?: 0.0) * 1000).toLong()
                p.seek(to.coerceIn(0L, p.durationMs.coerceAtLeast(0L)))
            }
            RemoteButton.AUDIO_RESTART -> Recording.player?.seek(0L)
            RemoteButton.AUDIO_SPEED -> Recording.player?.let { p -> p.speed = ((p.speed * 100 + (c.value ?: 0.0)) / 100).coerceIn(0.5, 1.25) }
            RemoteButton.STRIP -> {
                state.stripCollapsed = !state.stripCollapsed
                if (state.stripCollapsed) Perform.recentre?.invoke()
            }
            RemoteButton.TOOLS -> {
                val shown = !Perform.on(PerformAction.FULLSCREEN)
                if (shown) {
                    Perform.run(PerformAction.FULLSCREEN)
                    state.stripCollapsed = true
                    Perform.recentre?.invoke()
                } else {
                    state.stripCollapsed = false
                    Perform.run(PerformAction.FULLSCREEN)
                }
            }
            RemoteButton.FIT -> Perform.recentre?.invoke()
            RemoteButton.HOME -> Perform.showHome?.invoke()
            RemoteButton.LEADER -> state.companion.goToLeader()
            RemoteButton.LEAD -> if (state.companion.leading) state.companion.stopLeading() else state.companion.lead()
            else -> PerformAction.entries.firstOrNull { it.name == c.action }?.let { Perform.run(it) }
        }
        publish()
    }

    // ---- this device, as a remote ------------------------------------------------------------

    /** The Remote screen is up (on this device, as a remote, or to let one in). */
    var remoteOpen by mutableStateOf(false)

    /** The device this one controls, while it is a remote. */
    var target by mutableStateOf<RemoteLink.Target?>(null)
        private set

    var connected by mutableStateOf(false)
        private set

    /** Where the controlled device is, as it last said. */
    var shown by mutableStateOf<RemoteLink.State?>(null)
        private set

    var hostLibrary by mutableStateOf<RemoteLink.Library?>(null)
        private set

    /** The device turned this remote away: its code changed, or the code typed was wrong. */
    var refused by mutableStateOf<String?>(null)
        private set

    /** The last device controlled, to connect to again in one tap. */
    val lastTarget: RemoteLink.Target?
        get() = state.platform.pref(K_LAST)?.let(RemoteLink::parsePair)

    private var client: RemoteClient? = null

    fun connect(t: RemoteLink.Target) {
        client?.stop()
        refused = null
        shown = null
        hostLibrary = null
        target = t
        state.platform.setPref(K_LAST, RemoteLink.pairLink(t.name, t.hosts, t.key, t.port))
        val c = RemoteClient(state.platform.deviceName) { line ->
            state.platform.onMain {
                when (line) {
                    is RemoteLink.Line.Shows -> shown = line.state
                    is RemoteLink.Line.Songs -> hostLibrary = line.library
                    RemoteLink.Line.Refused -> {
                        refused = "${t.name} turned this remote away - its code has changed, or the code was mistyped. Scan its code again."
                        state.platform.setPref(K_LAST, null)
                        target = null
                        client = null
                    }
                    else -> Unit
                }
            }
        }
        c.onConnected = { on -> state.platform.onMain { connected = on } }
        client = c
        c.start(t)
        state.platform.log("Remote: controlling ${t.name}")
    }

    fun disconnect() {
        client?.stop(); client = null
        target = null
        connected = false
        shown = null
    }

    fun send(command: RemoteLink.Command) {
        val c = client ?: return
        Thread({ c.send(command) }, "remote-send").apply { isDaemon = true; start() }
    }

    /** The buttons on this remote, in order. Kept on this device. */
    var deck by mutableStateOf(loadDeck())
        private set

    private fun loadDeck(): List<RemoteButton> = state.platform.pref(K_DECK)?.let { saved ->
        runCatching { DECK_JSON.decodeFromString(DECK_LIST, saved) }.getOrNull()
    } ?: RemoteButton.DEFAULT_DECK

    fun saveDeck(buttons: List<RemoteButton>) {
        deck = buttons
        state.platform.setPref(K_DECK, DECK_JSON.encodeToString(DECK_LIST, buttons))
    }

    init {
        if (state.platform.pref(K_HOSTING) == "true") startHosting()
    }

    companion object {
        private const val K_KEY = "sheets_remote_key"
        private const val K_HOSTING = "sheets_remote_hosting"
        private const val K_LAST = "sheets_remote_last"
        private const val K_DECK = "sheets_remote_deck"
        private val DECK_JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        private val DECK_LIST = kotlinx.serialization.builtins.ListSerializer(RemoteButton.serializer())

    }
}

/**
 * The Remote screen, over Home. Connected to a device: its deck of buttons, what that device is
 * showing, and the leader's one-tap messages when it leads. Otherwise: connecting to a device,
 * and letting remotes control this one.
 */
@Composable
internal fun RemoteScreen(state: SheetsState, onClose: () -> Unit) {
    val remote = state.remote
    DisposableEffect(Unit) {
        state.platform.keepAwake(true)
        onDispose { state.platform.keepAwake(false) }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Remote", style = MaterialTheme.typography.titleLarge)
                    remote.target?.let { t ->
                        Text(
                            if (remote.connected) "Controlling ${t.name}" else "Reaching ${t.name}... (both on the same Wi-Fi)",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (remote.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close the remote") }
            }
            if (remote.target != null) RemoteDeck(state) else RemoteSetup(state)
        }
    }
}

@Composable
private fun RemoteSetup(state: SheetsState) {
    val remote = state.remote
    var problem by remember { mutableStateOf<String?>(null) }
    val nearby = remember { mutableStateListOf<Triple<String, String, Int>>() }
    var askingCode by remember { mutableStateOf<Triple<String, String, Int>?>(null) }
    DisposableEffect(Unit) {
        val scanner = RemoteScanner { name, host, port ->
            state.platform.onMain {
                if (name != state.platform.deviceName && nearby.none { it.first == name && it.second == host }) nearby += Triple(name, host, port)
            }
        }
        scanner.start()
        onDispose { scanner.stop() }
    }
    fun take(text: String?) {
        if (text == null) return
        val t = RemoteLink.parsePair(text)
        if (t == null) problem = "That isn't a remote code. On the other device: Home, More, Remote - its code is under “Control this device from a remote”."
        else { problem = null; remote.connect(t) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        remote.refused?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
        Text("Use this device as a remote", style = MaterialTheme.typography.titleMedium)
        Text(
            "Turn another device's pages, change its song and send its messages from here - whether it plays alone, leads or follows. " +
                "Each player can have a remote of their own.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        remote.lastTarget?.let { last ->
            Button(onClick = { remote.connect(last) }, modifier = Modifier.padding(top = 8.dp)) { Text("Control ${last.name} again") }
        }
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            if (state.platform.canScanQr) {
                Button(onClick = { state.platform.scanQr(::take) }) {
                    Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(6.dp)); Text("Scan its code")
                }
            }
            OutlinedButton(onClick = {
                val text = state.platform.readClipboard()
                if (text == null || RemoteLink.parsePair(text) == null) problem = "There's no remote code on the clipboard. Copy the other device's remote link first."
                else take(text)
            }) {
                Icon(Icons.Default.ContentPaste, null); Spacer(Modifier.width(6.dp)); Text("Paste its code")
            }
        }
        Text("Nearby, letting remotes in", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
        if (nearby.isEmpty()) Text("Looking on this Wi-Fi...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        nearby.forEach { d ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(d.first, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { askingCode = d }) { Text("Enter its code") }
            }
        }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }

        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        HostSection(state)
    }

    askingCode?.let { (name, host, port) ->
        AskName(
            title = "The code on $name", initial = "", confirm = "Connect",
            onDone = { code -> askingCode = null; remote.connect(RemoteLink.Target(name, listOf(host), port, code.trim().uppercase())) },
            onDismiss = { askingCode = null }
        )
    }
}

/** Letting remotes control this device: on or off, and the code a remote scans. */
@Composable
internal fun HostSection(state: SheetsState) {
    val remote = state.remote
    var problem by remember { mutableStateOf<String?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
        problem = if (remote.hosting) { remote.stopHosting(); null } else if (remote.startHosting()) null else "Could not let remotes in. Is another app using port ${RemoteLink.PORT}?"
    }) {
        Column(Modifier.weight(1f)) {
            Text("Control this device from a remote", style = MaterialTheme.typography.titleMedium)
            Text(
                if (!remote.hosting) "Off" else when (remote.remotes) { 0 -> "On - no remote connected"; 1 -> "On - 1 remote connected"; else -> "On - ${remote.remotes} remotes connected" },
                style = MaterialTheme.typography.bodySmall,
                color = if (remote.hosting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = remote.hosting, onCheckedChange = null)
    }
    problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    val link = remote.pairLink
    if (remote.hosting && link != null) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val narrow = maxWidth < 460.dp
            val code: @Composable () -> Unit = { QrImage(link, minOf(maxWidth, 200.dp)) }
            val words: @Composable () -> Unit = {
                Column(Modifier.padding(start = if (narrow) 0.dp else 16.dp, top = if (narrow) 8.dp else 0.dp)) {
                    Text("On the remote: Home, More, Remote, then scan this.", style = MaterialTheme.typography.bodyMedium)
                    Text("Or pick ${state.platform.deviceName} under Nearby and enter", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(remote.key.chunked(3).joinToString(" "), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        "A remote stays paired, and connects again by itself. A phone's camera app opens InkSheets from the code too.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { remote.pairAgain() }) { Text("New code (disconnects every remote)") }
                }
            }
            if (narrow) Column { code(); words() } else Row(verticalAlignment = Alignment.CenterVertically) { code(); words() }
        }
    }
}

/** Connected: what the other device shows, and the buttons. */
@Composable
private fun RemoteDeck(state: SheetsState) {
    val remote = state.remote
    val shown = remote.shown
    var editing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var changing by remember { mutableStateOf<Int?>(null) }
    var picking by remember { mutableStateOf<String?>(null) }
    var typing by remember { mutableStateOf(false) }
    val taps = remember { mutableStateListOf<Long>() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun send(b: RemoteButton) = remote.send(RemoteLink.Command(
        action = if (b.kind == RemoteButton.ACTION) b.id.orEmpty() else b.kind,
        id = b.id, text = b.text, value = b.value, color = b.color, urgent = b.urgent
    ))

    /** What a press does: most go straight across; lists open here; a sequence goes step by step. */
    fun press(b: RemoteButton) {
        when (b.kind) {
            RemoteButton.SONGS, RemoteButton.SET, RemoteButton.PARTS, RemoteButton.PROFILES, RemoteButton.BOOKMARKS -> picking = b.kind
            RemoteButton.MESSAGE_TYPE -> typing = true
            RemoteButton.TAP -> {
                taps += System.currentTimeMillis()
                while (taps.size > 12) taps.removeAt(0)
                com.inksheets.core.Metronome.tapTempo(taps)?.let { bpm ->
                    remote.send(RemoteLink.Command(action = RemoteButton.TAP, value = kotlin.math.round(bpm).coerceIn(20.0, 300.0)))
                }
            }
            RemoteButton.MACRO -> scope.launch {
                // A step at a time, with a moment between: a song has to open before its page turns.
                for (step in b.steps) {
                    send(step)
                    kotlinx.coroutines.delay(if (step.kind == RemoteButton.SONG || step.kind == RemoteButton.SETLIST) 900 else 300)
                }
            }
            else -> send(b)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
        // What the other device is on: big enough to read on a music stand.
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                if (shown == null) {
                    Text(if (remote.connected) "Waiting for it to say where it is..." else "Not connected yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            shown.title ?: if (shown.home) "On Home" else "No song open",
                            style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (shown.counting > 0) Text("${shown.counting}", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    val line = listOfNotNull(
                        shown.part,
                        if (shown.pages > 0) "page ${shown.page + 1} of ${shown.pages}" else null,
                        shown.setlist?.let { "song ${shown.setIndex + 1} of ${shown.set.size} in $it" }
                    ).joinToString("  ·  ")
                    if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val extra = listOfNotNull(
                        when {
                            shown.leading -> "Leading" + if (shown.followers > 0) " · ${shown.followers} following" else ""
                            shown.following != null -> "Following ${shown.following}"
                            else -> null
                        },
                        if (shown.metronome) "♩ ${shown.bpm}" else null,
                        if (shown.recording) "● Recording ${shown.recordingSeconds / 60}:${"%02d".format(shown.recordingSeconds % 60)}" else null
                    ).joinToString("  ·  ")
                    if (extra.isNotEmpty()) Text(extra, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (editing) {
            Text("Tap a button to change what it does, its name or its colour.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        }

        // The buttons: as many across as fit, each big enough to hit without looking.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val across = (maxWidth / 150.dp).toInt().coerceIn(2, 6)
            val deck = remote.deck
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                deck.chunked(across).forEachIndexed { rowIndex, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEachIndexed { i, b ->
                            val at = rowIndex * across + i
                            DeckButton(b, shown, remote.hostLibrary, editing, Modifier.weight(1f),
                                onPress = { if (editing) changing = at else press(b) },
                                onMove = { by ->
                                    val list = deck.toMutableList()
                                    val to = (at + by).coerceIn(0, list.lastIndex)
                                    list.add(to, list.removeAt(at))
                                    remote.saveDeck(list)
                                }
                            )
                        }
                        repeat(across - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        // Leading: the leader's one-tap messages, whatever the deck holds.
        if (shown?.leading == true && shown.presets.isNotEmpty()) {
            Text("Message the band", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val across = (maxWidth / 150.dp).toInt().coerceIn(2, 6)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    shown.presets.withIndex().chunked(across).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { (i, p) -> PresetTile(p, Modifier.weight(1f)) { remote.send(RemoteLink.Command(action = RemoteLink.PRESET, index = i)) } }
                            repeat(across - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (editing) {
                Button(onClick = { adding = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add a button") }
                TextButton(onClick = { remote.saveDeck(RemoteButton.DEFAULT_DECK) }) { Text("Start again") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { editing = false }) { Text("Done") }
            } else {
                OutlinedButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, null); Spacer(Modifier.width(6.dp)); Text("Change buttons") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { remote.disconnect() }) { Text("Disconnect") }
            }
        }
    }

    if (adding) AddDeckButtonDialog(state, forStep = false, onAdd = { remote.saveDeck(remote.deck + it); adding = false }, onDismiss = { adding = false })
    changing?.let { at ->
        val b = remote.deck.getOrNull(at)
        if (b == null) changing = null else ButtonEditor(
            state, b, isNew = false,
            onSave = { changed -> remote.saveDeck(remote.deck.toMutableList().also { it[at] = changed }); changing = null },
            onRemove = { remote.saveDeck(remote.deck.filterIndexed { j, _ -> j != at }); changing = null },
            onDismiss = { changing = null }
        )
    }
    if (typing) AskName(
        title = "A message for the band", initial = "", confirm = "Send",
        onDone = { text -> typing = false; remote.send(RemoteLink.Command(action = RemoteButton.MESSAGE, text = text)) },
        onDismiss = { typing = false }
    )
    fun pick(id: String) = remote.send(RemoteLink.Command(action = picking.orEmpty(), id = id))
    when (picking) {
        RemoteButton.SONGS -> PickRemoteItem(
            "Go to a song", remote.hostLibrary?.songs.orEmpty(),
            onChosen = { remote.send(RemoteLink.Command(action = RemoteLink.SONG, id = it.id)); picking = null }, onDismiss = { picking = null }
        )
        RemoteButton.SET -> PickRemoteItem(
            shown?.setlist ?: "The set", shown?.set.orEmpty(), numbered = true, current = shown?.setIndex ?: -1,
            empty = "No set is being played there. Open a setlist on it, or use Songs.",
            onChosenAt = { i -> remote.send(RemoteLink.Command(action = RemoteLink.SET_ENTRY, index = i)); picking = null }, onDismiss = { picking = null }
        )
        RemoteButton.PARTS -> PickRemoteItem(
            "Show which part?", shown?.parts.orEmpty(), current = shown?.parts.orEmpty().indexOfFirst { it.id == shown?.partId },
            empty = "No song is open there.",
            onChosen = { pick(it.id); picking = null }, onDismiss = { picking = null }
        )
        RemoteButton.PROFILES -> PickRemoteItem(
            "Play which instrument, in every song?", remote.hostLibrary?.profiles.orEmpty(),
            current = remote.hostLibrary?.profiles.orEmpty().indexOfFirst { it.id == shown?.profileId },
            onChosen = { pick(it.id); picking = null }, onDismiss = { picking = null }
        )
        RemoteButton.BOOKMARKS -> PickRemoteItem(
            "Go to a bookmark", remote.hostLibrary?.bookmarks.orEmpty(), empty = "No bookmarks there yet.",
            onChosen = { pick(it.id); picking = null }, onDismiss = { picking = null }
        )
    }
}

/** How much a button that takes a number wants, and what it is called: null for none. */
private fun valueHint(kind: String): Pair<String, Double?>? = when (kind) {
    RemoteButton.PAGE -> "Page number" to 1.0
    RemoteButton.TEMPO -> "Change the tempo by (a minus slows it)" to 5.0
    RemoteButton.TEMPO_SET -> "Tempo (beats a minute)" to 120.0
    RemoteButton.COUNT_IN -> "Bars to count (blank: the count-in setting)" to null
    RemoteButton.COUNT_BARS -> "Count-in bars, 0 to 4" to 1.0
    RemoteButton.AUDIO_SEEK -> "Seconds (a minus goes back)" to -5.0
    RemoteButton.AUDIO_SPEED -> "Percent (a minus slows it)" to -5.0
    else -> null
}

private fun num(v: Double?): String = v?.let { if (it == kotlin.math.floor(it)) it.toLong().toString() else it.toString() } ?: ""

/** A button's own name, when the player has given it none. */
private fun defaultName(b: RemoteButton, shown: RemoteLink.State?, lib: RemoteLink.Library?): String {
    val v = b.value
    return when (b.kind) {
        RemoteButton.ACTION -> PerformAction.entries.firstOrNull { it.name == b.id }?.let { a ->
            val tools = shown?.toolsShown == true
            when (a) {
                PerformAction.FULLSCREEN -> if (tools) "Hide tools" else "Show tools"
                PerformAction.NEXT_PAGE -> "Next page"
                PerformAction.PREVIOUS_PAGE -> "Previous page"
                PerformAction.NEXT_SONG -> "Next song"
                PerformAction.PREVIOUS_SONG -> "Previous song"
                PerformAction.METRONOME -> if (shown?.metronome == true) "Stop metronome" else "Metronome"
                else -> shortName(a, !tools)
            }
        } ?: "?"
        RemoteButton.SONG -> b.title ?: "Song"
        RemoteButton.SETLIST -> b.title ?: "Setlist"
        RemoteButton.SONGS -> "Songs..."
        RemoteButton.SET -> "The set..."
        RemoteButton.PAGE -> "Page ${num(v)}"
        RemoteButton.PARTS -> "Part..."
        RemoteButton.PROFILES -> "Instrument..."
        RemoteButton.BOOKMARKS -> "Bookmarks..."
        RemoteButton.TEMPO -> if ((v ?: 0.0) < 0) "Tempo −${num(-(v ?: 0.0))}" else "Tempo +${num(v)}"
        RemoteButton.TEMPO_SET -> "♩ = ${num(v)}"
        RemoteButton.TAP -> "Tap tempo"
        RemoteButton.COUNT_IN -> if (v != null) "Count in ${num(v)} bar${if (v == 1.0) "" else "s"}" else "Count in"
        RemoteButton.COUNT_BARS -> "Count-in: ${num(v)} bar${if (v == 1.0) "" else "s"}"
        RemoteButton.CLICK_RECORDING -> "Click when recording"
        RemoteButton.CLICK_PLAYBACK -> "Click with recordings"
        RemoteButton.RECORD -> if (shown?.recording == true) "Stop recording" else "Record yourself"
        RemoteButton.AUDIO_SEEK -> if ((v ?: 0.0) < 0) "Back ${num(-(v ?: 0.0))} s" else "On ${num(v)} s"
        RemoteButton.AUDIO_RESTART -> "Recording from the start"
        RemoteButton.AUDIO_SPEED -> if ((v ?: 0.0) < 0) "Slower ${num(-(v ?: 0.0))}%" else "Faster ${num(v)}%"
        RemoteButton.MESSAGE -> b.text ?: "Message"
        RemoteButton.MESSAGE_TYPE -> "Write a message..."
        RemoteButton.STRIP -> if (shown?.stripOpen == true) "Hide toolbar" else "Toolbar"
        RemoteButton.TOOLS -> if (shown?.toolsShown == true) "Put tools away" else "All tools"
        RemoteButton.FIT -> "Fit the page"
        RemoteButton.HOME -> "Home"
        RemoteButton.LEADER -> "Back to the leader"
        RemoteButton.LEAD -> if (shown?.leading == true) "Stop leading" else "Lead"
        RemoteButton.MACRO -> "${b.steps.size} steps"
        else -> b.kind
    }
}

private fun iconFor(b: RemoteButton, shown: RemoteLink.State?): ImageVector = when (b.kind) {
    RemoteButton.ACTION -> PerformAction.entries.firstOrNull { it.name == b.id }?.let { iconOf(it, shown?.toolsShown != true) } ?: Icons.Default.MusicNote
    RemoteButton.SONG -> Icons.Default.MusicNote
    RemoteButton.SETLIST, RemoteButton.SET -> Icons.AutoMirrored.Filled.QueueMusic
    RemoteButton.SONGS -> Icons.Default.LibraryMusic
    RemoteButton.PAGE -> Icons.Default.FormatListNumbered
    RemoteButton.PARTS -> Icons.Default.SwapHoriz
    RemoteButton.PROFILES -> Icons.Default.Person
    RemoteButton.BOOKMARKS -> Icons.Default.Bookmarks
    RemoteButton.TEMPO, RemoteButton.TEMPO_SET -> Icons.Default.Speed
    RemoteButton.TAP -> Icons.Default.TouchApp
    RemoteButton.COUNT_IN, RemoteButton.COUNT_BARS -> Icons.Default.HourglassTop
    RemoteButton.CLICK_RECORDING, RemoteButton.CLICK_PLAYBACK -> Icons.Default.GraphicEq
    RemoteButton.RECORD -> Icons.Default.FiberManualRecord
    RemoteButton.AUDIO_SEEK -> if ((b.value ?: 0.0) < 0) Icons.Default.FastRewind else Icons.Default.FastForward
    RemoteButton.AUDIO_RESTART -> Icons.Default.SkipPrevious
    RemoteButton.AUDIO_SPEED -> Icons.Default.SlowMotionVideo
    RemoteButton.MESSAGE -> Icons.Default.Campaign
    RemoteButton.MESSAGE_TYPE -> Icons.Default.Edit
    RemoteButton.STRIP -> Icons.Default.ViewSidebar
    RemoteButton.TOOLS -> Icons.Default.Construction
    RemoteButton.FIT -> Icons.Default.CenterFocusStrong
    RemoteButton.HOME -> Icons.Default.Home
    RemoteButton.LEADER, RemoteButton.LEAD -> Icons.Default.Groups
    RemoteButton.MACRO -> Icons.Default.AutoAwesome
    else -> Icons.Default.MusicNote
}

@Composable
private fun DeckButton(
    b: RemoteButton,
    shown: RemoteLink.State?,
    lib: RemoteLink.Library?,
    editing: Boolean,
    modifier: Modifier,
    onPress: () -> Unit,
    onMove: (Int) -> Unit
) {
    val action = if (b.kind == RemoteButton.ACTION) PerformAction.entries.firstOrNull { it.name == b.id } else null
    val lit = when {
        action == PerformAction.METRONOME -> shown?.metronome == true
        action == PerformAction.BOOKMARK -> shown?.bookmarked == true
        action == PerformAction.PLAY_AUDIO -> shown?.recordingPlaying == true
        action == PerformAction.FULLSCREEN -> shown?.toolsShown == true
        b.kind == RemoteButton.RECORD -> shown?.recording == true
        b.kind == RemoteButton.CLICK_RECORDING -> shown?.clickRecording == true
        b.kind == RemoteButton.CLICK_PLAYBACK -> shown?.clickPlayback == true
        b.kind == RemoteButton.STRIP -> shown?.stripOpen == true
        b.kind == RemoteButton.TOOLS -> shown?.toolsShown == true
        b.kind == RemoteButton.LEAD -> shown?.leading == true
        b.kind == RemoteButton.COUNT_IN -> (shown?.counting ?: 0) > 0
        b.kind == RemoteButton.COUNT_BARS -> shown?.countInBars == b.value?.toInt()
        b.kind == RemoteButton.PROFILES -> false
        else -> false
    }
    val own = b.color?.let { Color(it) }
    val fill = when {
        lit -> MaterialTheme.colorScheme.primaryContainer
        own != null -> own
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val ink = if (!lit && own != null) (if (own.luminance() > 0.45f) Color.Black else Color.White) else MaterialTheme.colorScheme.onSurface
    val detail = when {
        action == PerformAction.METRONOME && (shown?.bpm ?: 0) > 0 -> "♩ ${shown?.bpm}"
        b.kind == RemoteButton.PARTS -> shown?.part
        b.kind == RemoteButton.COUNT_IN && (shown?.counting ?: 0) > 0 -> "${shown?.counting}"
        b.kind == RemoteButton.MACRO && b.label != null -> "${b.steps.size} steps"
        else -> null
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = fill,
        contentColor = ink,
        modifier = modifier.height(104.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onPress)
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.align(Alignment.Center).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(iconFor(b, shown), null, modifier = Modifier.size(30.dp))
                Text(b.label ?: defaultName(b, shown, lib), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                detail?.let { Text(it, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (editing) {
                IconButton(onClick = { onMove(-1) }, modifier = Modifier.align(Alignment.BottomStart)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Move earlier") }
                IconButton(onClick = { onMove(1) }, modifier = Modifier.align(Alignment.BottomEnd)) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Move later") }
            }
        }
    }
}

@Composable
private fun PresetTile(p: MessagePreset, modifier: Modifier, onSend: () -> Unit) {
    var sent by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(sent) { if (sent > 0) { kotlinx.coroutines.delay(1500); sent = 0 } }
    val fill = when {
        sent > 0 -> MaterialTheme.colorScheme.primary
        p.color != null -> Color(p.color!!)
        p.urgent -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val ink = when {
        sent > 0 -> MaterialTheme.colorScheme.onPrimary
        p.color != null -> if (Color(p.color!!).luminance() > 0.45f) Color.Black else Color.White
        p.urgent -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = fill,
        modifier = modifier.height(80.dp).clip(RoundedCornerShape(18.dp)).clickable { onSend(); sent++ }
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (sent > 0) "Sent" else p.text, color = ink, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp))
        }
    }
}

/** A song, setlist, part, bookmark or instrument of the other device's, picked from a list that searches. */
@Composable
private fun PickRemoteItem(
    title: String,
    items: List<RemoteLink.Item>,
    numbered: Boolean = false,
    current: Int = -1,
    empty: String = "Nothing to pick from yet.",
    onChosen: ((RemoteLink.Item) -> Unit)? = null,
    onChosenAt: ((Int) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    SheetDialog(title = title, onDismiss = onDismiss) {
        Column {
            if (items.size > 8) {
                OutlinedTextField(query, { query = it }, placeholder = { Text("Find") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            if (items.isEmpty()) Text(empty, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val shownItems = items.withIndex().filter { (_, it) -> query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                shownItems.forEach { (i, item) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (i == current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .clickable { onChosen?.invoke(item); onChosenAt?.invoke(i) }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ColourBar(item.color)
                        if (numbered) Text("${i + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(32.dp))
                        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** One thing a remote button can do, as offered in Add a button. */
private class Offer(val section: String, val name: String, val button: RemoteButton)

private fun offers(shown: RemoteLink.State?): List<Offer> {
    fun a(section: String, action: PerformAction, name: String = action.label) = Offer(section, name, RemoteButton.action(action.name))
    fun k(section: String, name: String, kind: String, value: Double? = null) = Offer(section, name, RemoteButton(kind, value = value))
    val pages = "Pages"; val songs = "Songs and sets"; val parts = "Parts"; val click = "Metronome and count-in"
    val rec = "Recordings"; val marks = "Marking"; val view = "The screen"; val band = "Playing together"; val more = "More than one thing"
    return listOf(
        a(pages, PerformAction.NEXT_PAGE), a(pages, PerformAction.PREVIOUS_PAGE),
        a(pages, PerformAction.HALF_PAGE_FORWARD), a(pages, PerformAction.HALF_PAGE_BACK),
        a(pages, PerformAction.FIRST_PAGE), a(pages, PerformAction.LAST_PAGE),
        k(pages, "Go to a page...", RemoteButton.PAGE, 1.0),
        a(songs, PerformAction.NEXT_SONG), a(songs, PerformAction.PREVIOUS_SONG),
        k(songs, "Go to a song you choose now", RemoteButton.SONG),
        k(songs, "Play a setlist you choose now", RemoteButton.SETLIST),
        k(songs, "Songs... (pick any song each time)", RemoteButton.SONGS),
        k(songs, "The set... (pick a song of the set being played)", RemoteButton.SET),
        k(songs, "Bookmarks... (pick a bookmark)", RemoteButton.BOOKMARKS),
        k(songs, "Home", RemoteButton.HOME),
        k(parts, "Part... (pick a part of the song in front)", RemoteButton.PARTS),
        k(parts, "Instrument... (for every song)", RemoteButton.PROFILES),
        a(parts, PerformAction.SWITCH_PART, "Open the part picker there"),
        a(click, PerformAction.METRONOME),
        k(click, "Count in now, then quiet", RemoteButton.COUNT_IN),
        k(click, "Tempo up or down by...", RemoteButton.TEMPO, 5.0),
        k(click, "Set the tempo to...", RemoteButton.TEMPO_SET, 120.0),
        k(click, "Tap tempo", RemoteButton.TAP),
        k(click, "Count-in length...", RemoteButton.COUNT_BARS, 1.0),
        k(click, "Click when recording yourself (on/off)", RemoteButton.CLICK_RECORDING),
        k(click, "Click with recordings (on/off)", RemoteButton.CLICK_PLAYBACK),
        a(click, PerformAction.TUNER, "Open the tuner there"),
        a(rec, PerformAction.PLAY_AUDIO),
        k(rec, "Record yourself (start/stop)", RemoteButton.RECORD),
        k(rec, "Back or on by seconds...", RemoteButton.AUDIO_SEEK, -5.0),
        k(rec, "The recording from the start", RemoteButton.AUDIO_RESTART),
        k(rec, "Slower or faster by...", RemoteButton.AUDIO_SPEED, -5.0),
        a(rec, PerformAction.RECORDINGS, "Open the recordings there"),
        a(marks, PerformAction.BOOKMARK), a(marks, PerformAction.PEN), a(marks, PerformAction.HIGHLIGHTER),
        a(marks, PerformAction.ERASER), a(marks, PerformAction.UNDO), a(marks, PerformAction.REDO),
        k(view, "The toolbar (show/hide)", RemoteButton.STRIP),
        k(view, "All tools and the toolbar (show/hide)", RemoteButton.TOOLS),
        a(view, PerformAction.FULLSCREEN, "Only the tools (show/hide)"),
        k(view, "Fit the page to the screen", RemoteButton.FIT),
        k(band, "Lead (start/stop)", RemoteButton.LEAD),
        k(band, "Back to the leader", RemoteButton.LEADER),
        k(band, "A message you write now", RemoteButton.MESSAGE),
        k(band, "Write a message each time...", RemoteButton.MESSAGE_TYPE),
        a(band, PerformAction.PLAY_TOGETHER, "Open Play together there")
    ) + shown?.presets.orEmpty().map { p ->
        Offer(band, "One-tap: ${p.text}", RemoteButton(RemoteButton.MESSAGE, text = p.text, urgent = p.urgent, color = p.color))
    } + Offer(more, "A sequence: several of these in one press", RemoteButton(RemoteButton.MACRO))
}

/** Kinds that need something more before they are added: a number, words, a choice or steps. */
private fun needsMore(b: RemoteButton) =
    valueHint(b.kind) != null || b.kind == RemoteButton.MACRO || (b.kind == RemoteButton.MESSAGE && b.text == null)

/** Adding a button to the remote - or, [forStep], a step of a sequence (no lists, no sequences). */
@Composable
private fun AddDeckButtonDialog(state: SheetsState, forStep: Boolean, onAdd: (RemoteButton) -> Unit, onDismiss: () -> Unit) {
    val remote = state.remote
    var choosing by remember { mutableStateOf<String?>(null) }
    var configuring by remember { mutableStateOf<RemoteButton?>(null) }
    when (choosing) {
        RemoteButton.SONG -> {
            PickRemoteItem("A button for which song?", remote.hostLibrary?.songs.orEmpty(),
                onChosen = { onAdd(RemoteButton(RemoteButton.SONG, it.id, it.title)) }, onDismiss = { choosing = null })
            return
        }
        RemoteButton.SETLIST -> {
            PickRemoteItem("A button for which setlist?", remote.hostLibrary?.setlists.orEmpty(),
                onChosen = { onAdd(RemoteButton(RemoteButton.SETLIST, it.id, it.title)) }, onDismiss = { choosing = null })
            return
        }
    }
    configuring?.let { b ->
        ButtonEditor(state, b, isNew = true, onSave = onAdd, onRemove = null, onDismiss = { configuring = null })
        return
    }
    val pickers = setOf(RemoteButton.SONGS, RemoteButton.SET, RemoteButton.PARTS, RemoteButton.PROFILES, RemoteButton.BOOKMARKS,
        RemoteButton.MESSAGE_TYPE, RemoteButton.TAP, RemoteButton.MACRO)
    val all = offers(remote.shown).filter { !forStep || it.button.kind !in pickers }
    var query by remember { mutableStateOf("") }
    SheetDialog(title = if (forStep) "Add a step" else "Add a button", onDismiss = onDismiss) {
        Column {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Find") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                var section = ""
                for (o in all.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }) {
                    if (o.section != section) {
                        section = o.section
                        Text(section, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            when {
                                o.button.kind == RemoteButton.SONG || o.button.kind == RemoteButton.SETLIST -> choosing = o.button.kind
                                needsMore(o.button) -> configuring = o.button
                                else -> onAdd(o.button)
                            }
                        }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(iconFor(o.button, remote.shown), null)
                        Spacer(Modifier.width(12.dp))
                        Text(o.name)
                    }
                }
            }
        }
    }
}

/**
 * One button, changed: its name and colour, the number or words it sends, and for a sequence
 * its steps in order. [onRemove] takes it off the remote (null while adding).
 */
@Composable
private fun ButtonEditor(
    state: SheetsState,
    button: RemoteButton,
    isNew: Boolean,
    onSave: (RemoteButton) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val remote = state.remote
    var label by remember { mutableStateOf(button.label.orEmpty()) }
    var value by remember { mutableStateOf(num(button.value ?: valueHint(button.kind)?.second)) }
    var text by remember { mutableStateOf(button.text.orEmpty()) }
    var urgent by remember { mutableStateOf(button.urgent) }
    var colour by remember { mutableStateOf(button.color) }
    val steps = remember { mutableStateListOf(*button.steps.toTypedArray()) }
    var addingStep by remember { mutableStateOf(false) }
    fun built() = button.copy(
        label = label.trim().ifEmpty { null },
        value = if (valueHint(button.kind) != null) value.trim().replace(',', '.').toDoubleOrNull() else button.value,
        text = if (button.kind == RemoteButton.MESSAGE) text.trim().ifEmpty { null } else button.text,
        urgent = urgent,
        color = colour,
        steps = steps.toList()
    )
    if (addingStep) {
        AddDeckButtonDialog(state, forStep = true, onAdd = { steps += it; addingStep = false }, onDismiss = { addingStep = false })
        return
    }
    val preview = built()
    val ready = (button.kind != RemoteButton.MESSAGE || text.isNotBlank()) && (button.kind != RemoteButton.MACRO || steps.isNotEmpty()) &&
        (valueHint(button.kind)?.second == null || preview.value != null)
    SheetDialog(title = if (isNew) "New button" else "This button", onDismiss = onDismiss, buttons = {
        onRemove?.let { TextButton(onClick = it) { Text("Take it off", color = MaterialTheme.colorScheme.error) } }
        TextButton(onClick = onDismiss) { Text("Cancel") }
        TextButton(onClick = { onSave(built()) }, enabled = ready) { Text(if (isNew) "Add" else "Save") }
    }) {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            Text("Does: " + defaultName(preview.copy(label = null), remote.shown, remote.hostLibrary), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(label, { label = it }, label = { Text("Name on the button (blank: as above)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            valueHint(button.kind)?.let { (hint, _) ->
                OutlinedTextField(value, { value = it }, label = { Text(hint) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
            if (button.kind == RemoteButton.MESSAGE) {
                OutlinedTextField(text, { text = it }, label = { Text("The message") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { urgent = !urgent }) {
                    Text("Cover the music until tapped away", modifier = Modifier.weight(1f))
                    Switch(checked = urgent, onCheckedChange = { urgent = it })
                }
                Text("Sent only while that device is leading.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Colour", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.FilterChip(selected = colour == null, onClick = { colour = null }, label = { Text("None") })
                for (c in MARK_COLOURS) {
                    Box(
                        Modifier.size(30.dp).background(Color(c), androidx.compose.foundation.shape.CircleShape)
                            .then(if (c == colour) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, androidx.compose.foundation.shape.CircleShape) else Modifier)
                            .clickable { colour = c }
                    )
                }
            }
            if (button.kind == RemoteButton.MACRO) {
                Text("Steps, in order", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
                if (steps.isEmpty()) Text("None yet - for example: a song, then Count in, then Record yourself.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                steps.forEachIndexed { i, step ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("${i + 1}.", modifier = Modifier.width(28.dp))
                        Icon(iconFor(step, remote.shown), null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(step.label ?: defaultName(step, remote.shown, remote.hostLibrary), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { if (i > 0) steps.add(i - 1, steps.removeAt(i)) }, enabled = i > 0) { Icon(Icons.Default.ArrowUpward, "Earlier") }
                        IconButton(onClick = { steps.removeAt(i) }) { Icon(Icons.Default.Close, "Take this step out") }
                    }
                }
                TextButton(onClick = { addingStep = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add a step") }
            }
        }
    }
}
