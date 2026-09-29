package com.inksheets.ui

import androidx.compose.foundation.background
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
            h.library(RemoteLink.Library(
                songs = lib.songs.sortedBy { it.title.lowercase() }.map { RemoteLink.Item(it.id, it.title, it.color) },
                setlists = lib.setlists.sortedBy { it.name.lowercase() }.map { RemoteLink.Item(it.id, it.name, it.color) }
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
            home = state.homeInFront
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
            RemoteLink.NOTE -> c.text?.takeIf { it.isNotBlank() }?.let { state.companion.sendNote(it, emptyList()) }
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

        /** Actions a remote can offer: those that do something on the other device, not open a window there. */
        val REMOTE_ACTIONS = listOf(
            PerformAction.PREVIOUS_PAGE, PerformAction.NEXT_PAGE,
            PerformAction.PREVIOUS_SONG, PerformAction.NEXT_SONG,
            PerformAction.HALF_PAGE_BACK, PerformAction.HALF_PAGE_FORWARD,
            PerformAction.FIRST_PAGE, PerformAction.LAST_PAGE,
            PerformAction.BOOKMARK, PerformAction.METRONOME, PerformAction.PLAY_AUDIO,
            PerformAction.UNDO, PerformAction.REDO, PerformAction.FULLSCREEN
        )
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
    var picking by remember { mutableStateOf<String?>(null) }
    fun send(action: String, id: String? = null, index: Int? = null) = remote.send(RemoteLink.Command(action = action, id = id, index = index))

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
        // What the other device is on: big enough to read on a music stand.
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                if (shown == null) {
                    Text(if (remote.connected) "Waiting for it to say where it is..." else "Not connected yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(
                        shown.title ?: if (shown.home) "On Home" else "No song open",
                        style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    val line = listOfNotNull(
                        shown.part,
                        if (shown.pages > 0) "page ${shown.page + 1} of ${shown.pages}" else null,
                        shown.setlist?.let { "song ${shown.setIndex + 1} of ${shown.set.size} in $it" }
                    ).joinToString("  ·  ")
                    if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val group = when {
                        shown.leading -> "Leading" + if (shown.followers > 0) " · ${shown.followers} following" else ""
                        shown.following != null -> "Following ${shown.following}"
                        else -> null
                    }
                    group?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        // The buttons: as many across as fit, each big enough to hit without looking.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val across = (maxWidth / 150.dp).toInt().coerceIn(2, 6)
            val deck = remote.deck
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                deck.chunked(across).forEachIndexed { rowIndex, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEachIndexed { i, b ->
                            val at = rowIndex * across + i
                            DeckButton(state, b, shown, editing, Modifier.weight(1f),
                                onPress = {
                                    when (b.kind) {
                                        RemoteButton.ACTION -> b.id?.let { send(it) }
                                        RemoteButton.SONG -> send(RemoteLink.SONG, id = b.id)
                                        RemoteButton.SETLIST -> send(RemoteLink.SETLIST, id = b.id)
                                        RemoteButton.SONGS, RemoteButton.SET -> picking = b.kind
                                    }
                                },
                                onMove = { by ->
                                    val list = deck.toMutableList()
                                    val to = (at + by).coerceIn(0, list.lastIndex)
                                    list.add(to, list.removeAt(at))
                                    remote.saveDeck(list)
                                },
                                onRemove = { remote.saveDeck(deck.filterIndexed { j, _ -> j != at }) }
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
                            row.forEach { (i, p) -> PresetTile(p, Modifier.weight(1f)) { send(RemoteLink.PRESET, index = i) } }
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

    if (adding) AddDeckButtonDialog(state, onAdd = { remote.saveDeck(remote.deck + it); adding = false }, onDismiss = { adding = false })
    when (picking) {
        RemoteButton.SONGS -> PickRemoteItem(
            "Go to a song", remote.hostLibrary?.songs.orEmpty(),
            onChosen = { send(RemoteLink.SONG, id = it.id); picking = null }, onDismiss = { picking = null }
        )
        RemoteButton.SET -> {
            val set = shown?.set.orEmpty()
            PickRemoteItem(
                shown?.setlist ?: "The set", set, numbered = true, current = shown?.setIndex ?: -1,
                empty = "No set is being played there. Open a setlist on it, or use Songs.",
                onChosenAt = { i -> send(RemoteLink.SET_ENTRY, index = i); picking = null }, onDismiss = { picking = null }
            )
        }
    }
}

@Composable
private fun DeckButton(
    state: SheetsState,
    b: RemoteButton,
    shown: RemoteLink.State?,
    editing: Boolean,
    modifier: Modifier,
    onPress: () -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit
) {
    val action = if (b.kind == RemoteButton.ACTION) PerformAction.entries.firstOrNull { it.name == b.id } else null
    val tools = shown?.toolsShown == true
    val (icon, label) = when (b.kind) {
        RemoteButton.ACTION -> (action?.let { iconOf(it, !tools) } ?: Icons.Default.MusicNote) to (action?.let { a ->
            when (a) {
                PerformAction.FULLSCREEN -> if (tools) "Hide tools" else "Tools"
                PerformAction.NEXT_PAGE -> "Next page"
                PerformAction.PREVIOUS_PAGE -> "Previous page"
                PerformAction.NEXT_SONG -> "Next song"
                PerformAction.PREVIOUS_SONG -> "Previous song"
                else -> shortName(a, !tools)
            }
        } ?: "?")
        RemoteButton.SONG -> Icons.Default.MusicNote to (b.title ?: "Song")
        RemoteButton.SETLIST -> Icons.AutoMirrored.Filled.QueueMusic to (b.title ?: "Setlist")
        RemoteButton.SONGS -> Icons.Default.LibraryMusic to "Songs..."
        else -> Icons.AutoMirrored.Filled.QueueMusic to "The set..."
    }
    val lit = when (action) {
        PerformAction.METRONOME -> shown?.metronome == true
        PerformAction.BOOKMARK -> shown?.bookmarked == true
        PerformAction.PLAY_AUDIO -> shown?.recordingPlaying == true
        PerformAction.FULLSCREEN -> tools
        else -> false
    }
    val fill = if (lit) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = fill,
        modifier = modifier.height(104.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = !editing, onClick = onPress)
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.align(Alignment.Center).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, null, modifier = Modifier.size(32.dp))
                Text(label, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (action == PerformAction.METRONOME && (shown?.bpm ?: 0) > 0) {
                    Text("♩ ${shown?.bpm}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (editing) {
                Row(Modifier.align(Alignment.BottomCenter)) {
                    IconButton(onClick = { onMove(-1) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Move earlier") }
                    IconButton(onClick = { onMove(1) }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Move later") }
                }
                IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd)) { Icon(Icons.Default.Close, "Take this button off") }
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

/** A song or setlist of the other device's library, picked from a list that searches. */
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

/** Adding a button to the remote: an action, a song or setlist to go straight to, or a list. */
@Composable
private fun AddDeckButtonDialog(state: SheetsState, onAdd: (RemoteButton) -> Unit, onDismiss: () -> Unit) {
    val remote = state.remote
    var choosing by remember { mutableStateOf<String?>(null) }
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
    SheetDialog(title = "Add a button", onDismiss = onDismiss) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            val items: List<Triple<ImageVector, String, () -> Unit>> =
                listOf(
                    Triple(Icons.Default.MusicNote, "Go to a song you choose now", { choosing = RemoteButton.SONG }),
                    Triple(Icons.AutoMirrored.Filled.QueueMusic, "Play a setlist you choose now", { choosing = RemoteButton.SETLIST }),
                    Triple(Icons.Default.LibraryMusic, "Songs... (pick any song each time)", { onAdd(RemoteButton(RemoteButton.SONGS)) }),
                    Triple(Icons.AutoMirrored.Filled.QueueMusic, "The set... (pick a song of the set being played)", { onAdd(RemoteButton(RemoteButton.SET)) })
                ) + RemoteControl.REMOTE_ACTIONS.map { a -> Triple(iconOf(a, true), a.label, { onAdd(RemoteButton.action(a.name)) }) }
            items.forEach { (icon, text, go) ->
                Row(Modifier.fillMaxWidth().clickable(onClick = go).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null)
                    Spacer(Modifier.width(12.dp))
                    Text(text)
                }
            }
        }
    }
}
