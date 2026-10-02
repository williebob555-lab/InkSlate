package com.inksheets.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.DocumentScanner
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
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.DragIndicator
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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
import com.inksheets.core.RemoteBluetooth
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.inksheets.core.DeckGrid
import kotlin.math.roundToInt
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

    /** Remotes can also connect over Bluetooth (the platform has it, it is allowed, and it is listening). */
    var bluetoothOn by mutableStateOf(false)
        private set
    private var btListening: RemoteBluetooth.Listening? = null

    private fun link() = RemoteLink.pairLink(
        state.platform.deviceName, NetAddresses.mine(), key, port,
        bt = btListening?.address, channel = btListening?.channel
    )

    /**
     * Take remotes over Bluetooth as well - round a Wi-Fi that will not carry the connection. Asks
     * for the permission when it is missing; call again once it is given.
     */
    fun startBluetooth() {
        val bt = state.platform.remoteBluetooth ?: return
        if (host == null || bluetoothOn) return
        if (!bt.ready()) return
        Thread({
            val l = runCatching { bt.listen { pipe -> host?.take(pipe) ?: pipe.close() } }
                .onFailure { state.platform.log("Remote: Bluetooth could not start - ${it.message}") }
                .getOrNull()
            state.platform.onMain {
                if (host == null) { if (l != null) bt.stopListening(); return@onMain }
                btListening = l
                bluetoothOn = l != null
                if (l != null) state.platform.log("Remote: taking remotes over Bluetooth too" + (l.address?.let { " ($it)" } ?: ""))
                pairLink = link()
            }
        }, "remote-bluetooth").apply { isDaemon = true; start() }
    }

    fun startHosting(): Boolean {
        if (host != null) return true
        val name = state.platform.deviceName
        val h = RemoteHost(name, key, port)
        h.onCommand = { c ->
            state.platform.onMain {
                runCatching { perform(c) }.onFailure { state.platform.log("Remote: ${c.action} failed - ${it.message}") }
            }
        }
        h.onLog = { line -> state.platform.log("Remote: $line") }
        h.onRemotes = { n -> state.platform.onMain { remotes = n; publish() } }
        if (!h.start()) return false
        host = h
        hosting = true
        state.platform.setPref(K_HOSTING, "true")
        pairLink = link()
        startBluetooth()
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
        if (bluetoothOn) state.platform.remoteBluetooth?.let { bt -> Thread({ bt.stopListening() }, "remote-bluetooth").apply { isDaemon = true; start() } }
        bluetoothOn = false
        btListening = null
        hosting = false
        remotes = 0
        pairLink = null
        state.platform.setPref(K_HOSTING, "false")
    }

    /** A new code: every remote paired so far has to scan again. */
    fun pairAgain() {
        key = RemoteLink.newKey().also { state.platform.setPref(K_KEY, it) }
        host?.rekey(key)
        pairLink = link()
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
        // The music as read of the part in front (cached where it is read; cheap to ask often).
        val score = if (song != null) runCatching { ScoreTools.scoreHere(state) }.getOrNull() else null
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
            profileId = state.profileId,
            windows = PerformAction.entries.filter { state.windowOpen(it) }.map { it.name },
            listening = Listener.active,
            listenStatus = if (state.listenTurns) Listener.summary(state) else "Listen is off in Settings",
            audioSeconds = Recording.player?.let { (it.positionMs / 1000).toInt() } ?: 0,
            audioLength = Recording.player?.let { (it.durationMs / 1000).toInt() } ?: 0,
            audioSpeed = Recording.player?.let { (it.speed * 100).roundToInt() } ?: 100,
            audioVolume = Recording.player?.let { (it.volume * 100).roundToInt() } ?: 100,
            readBusy = Transcriber.busy,
            readBars = score?.measures?.size ?: 0,
            readSure = score?.measures?.count { it.sure } ?: 0,
            readThisPage = score?.hasRead(page) == true,
            readAny = score != null,
            cleanShown = ScoreTools.underlay,
            fixing = ScoreTools.checking,
            scorePlaying = ScoreTools.playing != null,
            musicTools = ScoreTools.open
        ))
    }

    /** A command made here - by a controller (a pedal, a fader) - done just as a remote's is. On the UI thread. */
    internal fun performHere(c: RemoteLink.Command) = runCatching { perform(c) }.onFailure { state.platform.log("Controller: ${c.action} failed - ${it.message}") }

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
            RemoteButton.AUDIO_VOLUME -> Recording.player?.let { p -> p.volume = ((p.volume * 100 + (c.value ?: 0.0)) / 100).coerceIn(0.0, 1.0) }
            RemoteButton.AUDIO_VOLUME_SET -> Recording.player?.let { p -> c.value?.let { p.volume = (it / 100).coerceIn(0.0, 1.0) } }
            RemoteButton.AUDIO_SPEED_SET -> Recording.player?.let { p -> c.value?.let { p.speed = (it / 100).coerceIn(0.5, 1.25) } }
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
            RemoteButton.LISTEN -> if (state.listenTurns) Listener.toggle(state)
            RemoteButton.VIEW -> Perform.viewBy?.invoke(c.dx.toFloat(), c.dy.toFloat(), c.zoom.toFloat(), c.fx.toFloat(), c.fy.toFloat())
            RemoteButton.HOME -> Perform.showHome?.invoke()
            RemoteButton.LEADER -> state.companion.goToLeader()
            RemoteButton.LEAD -> if (state.companion.leading) state.companion.stopLeading() else state.companion.lead()
            // Reading the music: just what the music tools' own buttons do.
            RemoteButton.READ_PAGE, RemoteButton.READ_PART -> state.currentPath?.let { path ->
                ScoreTools.open = true
                if (Transcriber.busy == null) Transcriber.read(state, java.io.File(path),
                    pages = if (c.action == RemoteButton.READ_PAGE) setOf(state.pageShown.first) else null) { Perform.marksChanged() }
            }
            RemoteButton.CLEAN -> ScoreTools.showUnderlay(!ScoreTools.underlay)
            RemoteButton.FIX -> if (ScoreTools.checking) ScoreTools.endCheck() else { ScoreTools.open = true; ScoreTools.startCheck(state) }
            RemoteButton.SCORE_PLAY -> if (ScoreTools.playing != null) ScoreTools.stop(state) else if (ScoreTools.band) ScoreTools.playBand(state) else ScoreTools.play(state)
            RemoteButton.MUSIC_TOOLS -> ScoreTools.open = !ScoreTools.open
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

    /**
     * The address a connection opened on and then carried nothing: the network between the two
     * devices is stopping it (school and work Wi-Fi such as eduroam do). Null once connected.
     */
    var blocked by mutableStateOf<String?>(null)
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
        answered.clear()
        blocked = null
        refused = null
        shown = null
        hostLibrary = null
        target = t
        state.platform.setPref(K_LAST, RemoteLink.pairLink(t))
        val c = RemoteClient(state.platform.deviceName, myId) { line ->
            if (line is RemoteLink.Line.Got) answered += line.seq
            state.platform.onMain {
                when (line) {
                    is RemoteLink.Line.Got -> lastPress?.takeIf { it.seq == line.seq }?.let { lastPress = it.copy(got = true) }
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
        c.onConnected = { on -> state.platform.onMain { connected = on; if (on) blocked = null } }
        c.onBlocked = { at -> state.platform.onMain { blocked = at } }
        // Bluetooth, where the device said it has it: asked for here, on the UI thread.
        if (t.bt != null) state.platform.remoteBluetooth?.takeIf { it.ready() }?.let { c.bluetooth = it }
        c.onLog = { line -> state.platform.log("Remote: $line") }
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

    /** The last press on this remote: [got] null while waiting, then whether the device got it. */
    data class Press(val name: String, val seq: Int, val got: Boolean?, val unanswered: Boolean = false)

    var lastPress by mutableStateOf<Press?>(null)
        private set

    /** Presses the device has said it got. */
    private val answered: MutableSet<Int> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    fun send(command: RemoteLink.Command, name: String = command.action) {
        val c = client ?: run { lastPress = Press(name, 0, false); return }
        Thread({
            val seq = c.send(command)
            state.platform.onMain {
                val press = Press(name, seq, if (seq == 0) false else if (seq in answered) true else null)
                lastPress = press
                if (seq != 0) java.util.Timer("remote-press", true).schedule(object : java.util.TimerTask() {
                    override fun run() = state.platform.onMain {
                        if (lastPress?.seq != seq || lastPress?.got != null) return@onMain
                        val got = seq in answered
                        // A device on an older version never answers: it was sent, and that is all that is known.
                        if (!got && answered.isEmpty() && shown != null) { lastPress = lastPress?.copy(unanswered = true); return@onMain }
                        lastPress = lastPress?.copy(got = got)
                        // Sent but never answered: this connection carries nothing back. A new one.
                        if (!got) { state.platform.log("Remote: $name was not answered - connecting again"); client?.reconnect() }
                    }
                }, PRESS_ANSWER_MS)
            }
        }, "remote-send").apply { isDaemon = true; start() }
    }

    /** One thread for a stream of small commands (the touchpad), so they arrive in the order made. */
    private val streamer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "remote-stream").apply { isDaemon = true } }

    /** A command sent as part of a stream: no press shown, not answered. */
    fun stream(command: RemoteLink.Command) {
        val c = client ?: return
        streamer.execute { c.send(command) }
    }

    /** This remote's own id, kept: the device counts it once however often it connects. */
    private val myId: String = state.platform.pref(K_ID) ?: java.util.UUID.randomUUID().toString().also { state.platform.setPref(K_ID, it) }

    /** The shape of this remote's grid. Kept on this device. */
    var grid by mutableStateOf(DeckGrid())
        private set

    /** The buttons on this remote, each in its cell of [grid]. Kept on this device. */
    var deck by mutableStateOf(emptyList<RemoteButton>())
        private set

    private fun loadDeck() {
        val saved = state.platform.pref(K_DECK)?.let { runCatching { DECK_JSON.decodeFromString(DECK_LIST, it) }.getOrNull() }
            ?: RemoteButton.DEFAULT_DECK
        // A deck from before the grid: three across, as many rows as it needs.
        var g = DeckGrid.parse(state.platform.pref(K_GRID)) ?: DeckGrid(3, ((saved.size + 2) / 3).coerceIn(4, DeckGrid.ROWS.last))
        var placed = g.place(saved)
        while (placed == null && g.rows < DeckGrid.ROWS.last) { g = DeckGrid(g.columns, g.rows + 1); placed = g.place(saved) }
        grid = g
        deck = placed ?: g.place(saved.take(g.cells))!!
    }

    fun saveDeck(buttons: List<RemoteButton>) {
        deck = grid.place(buttons) ?: return
        state.platform.setPref(K_DECK, DECK_JSON.encodeToString(DECK_LIST, deck))
    }

    /** A new shape, with [buttons] already placed on it. */
    fun saveGrid(to: DeckGrid, buttons: List<RemoteButton>) {
        grid = to
        state.platform.setPref(K_GRID, to.toString())
        saveDeck(buttons)
    }

    init {
        loadDeck()
        if (state.platform.pref(K_HOSTING) == "true") startHosting()
    }

    companion object {
        private const val K_KEY = "sheets_remote_key"
        private const val K_HOSTING = "sheets_remote_hosting"
        private const val K_LAST = "sheets_remote_last"
        private const val K_DECK = "sheets_remote_deck"
        private const val K_GRID = "sheets_remote_grid"
        private const val K_ID = "sheets_remote_id"

        /** The port remotes connect to; another in tests, beside a real app already using this one. */
        @Volatile var port = RemoteLink.PORT
        /** A press the device has not answered in this long did not reach it. */
        private const val PRESS_ANSWER_MS = 2_500L
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
            if (remote.target != null) {
                // The player's buttons in the middle, four pages round them (see RemotePages); no
                // swiping between them while the buttons are changed or the touchpad is out.
                val editing = remember { mutableStateOf(false) }
                val touchpad = remember { mutableStateOf(false) }
                RemotePager(state, enabled = !editing.value && !touchpad.value) { RemoteDeck(state, editing, touchpad) }
            } else RemoteSetup(state)
        }
    }
}

@Composable
private fun RemoteSetup(state: SheetsState) {
    val remote = state.remote
    var problem by remember { mutableStateOf<String?>(null) }
    val nearby = remember { mutableStateListOf<Triple<String, String, Int>>() }
    var askingCode by remember { mutableStateOf<Triple<String, String, Int>?>(null) }
    var paired by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var askingBt by remember { mutableStateOf<Pair<String, String>?>(null) }
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
        // Bluetooth: no Wi-Fi needed at all - for networks that will not carry the connection.
        state.platform.remoteBluetooth?.let { bt ->
            Text("Over Bluetooth", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 10.dp))
            if (paired == null) {
                OutlinedButton(onClick = { paired = if (bt.ready()) bt.paired() else null }) { Text("Devices paired with this one") }
                Text(
                    "Pair the two in the system's Bluetooth settings first. Works where the Wi-Fi blocks the remote (school and work networks).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (paired!!.isEmpty()) {
                Text("No devices are paired with this one yet - pair them in the system's Bluetooth settings.", style = MaterialTheme.typography.bodySmall)
            }
            paired?.forEach { (name, address) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(name, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { askingBt = name to address }) { Text("Enter its code") }
                }
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
    askingBt?.let { (name, address) ->
        AskName(
            title = "The code on $name", initial = "", confirm = "Connect",
            onDone = { code -> askingBt = null; remote.connect(RemoteLink.Target(name, emptyList(), RemoteLink.PORT, code.trim().uppercase(), bt = address)) },
            onDismiss = { askingBt = null }
        )
    }
}

/** Letting remotes control this device: on or off, and the code a remote scans. */
@Composable
internal fun HostSection(state: SheetsState) {
    val remote = state.remote
    var problem by remember { mutableStateOf<String?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
        problem = if (remote.hosting) { remote.stopHosting(); null } else if (remote.startHosting()) null else "Could not let remotes in. Is another app using port ${RemoteControl.port}?"
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
    if (remote.hosting && state.platform.remoteBluetooth != null) {
        Text(
            if (remote.bluetoothOn) "Over Bluetooth too - for a Wi-Fi that blocks remotes. Pair the remote with this device in the system's Bluetooth settings."
            else "Bluetooth is off for remotes - tap to use it too (for a Wi-Fi that blocks them)",
            style = MaterialTheme.typography.bodySmall,
            color = if (remote.bluetoothOn) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth()
                .then(if (remote.bluetoothOn) Modifier else Modifier.clickable { remote.startBluetooth() })
                .padding(vertical = 4.dp)
        )
    }
    val link = remote.pairLink
    if (remote.hosting && link != null) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val narrow = maxWidth < 460.dp
            val code: @Composable () -> Unit = { ScanCode(link, if (narrow) 104.dp else 136.dp) }
            val words: @Composable () -> Unit = {
                Column(Modifier.padding(start = 12.dp)) {
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
            // Beside the words, small; tapped, it shows big to scan.
            Row(verticalAlignment = Alignment.Top) { code(); Box(Modifier.weight(1f)) { words() } }
        }
    }
}

/** Connected: what the other device shows, and the buttons. */
@Composable
private fun RemoteDeck(state: SheetsState, editingState: androidx.compose.runtime.MutableState<Boolean>, touchpadState: androidx.compose.runtime.MutableState<Boolean>) {
    val remote = state.remote
    val shown = remote.shown
    var editing by editingState
    var changing by remember { mutableStateOf<Int?>(null) }
    var picking by remember { mutableStateOf<String?>(null) }
    var typing by remember { mutableStateOf(false) }
    var touchpad by touchpadState
    val taps = remember { mutableStateListOf<Long>() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun send(b: RemoteButton) = remote.send(RemoteLink.Command(
        action = if (b.kind == RemoteButton.ACTION) b.id.orEmpty() else b.kind,
        id = b.id, text = b.text, value = b.value, color = b.color, urgent = b.urgent
    ), b.label ?: defaultName(b, shown, remote.hostLibrary))

    /** What a press does: most go straight across; lists open here; a sequence goes step by step. */
    fun press(b: RemoteButton) {
        when (b.kind) {
            RemoteButton.SONGS, RemoteButton.SET, RemoteButton.PARTS, RemoteButton.PROFILES, RemoteButton.BOOKMARKS -> picking = b.kind
            RemoteButton.MESSAGE_TYPE -> typing = true
            RemoteButton.TOUCHPAD -> touchpad = true
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

    val grid = remote.grid
    val deck = remote.deck
    // A button being dragged - from the grid (its place in the deck) or the library (-1) - and
    // where the finger is, in the screen's own coordinates.
    var drag by remember { mutableStateOf<Pair<RemoteButton, Int>?>(null) }
    var finger by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val cells = remember { HashMap<Pair<Int, Int>, androidx.compose.ui.geometry.Rect>() }
    var tray by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var screenAt by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var cellSize by remember { mutableStateOf(androidx.compose.ui.unit.DpSize(96.dp, 96.dp)) }
    var notice by remember { mutableStateOf<String?>(null) }
    // A button dropped that needs more first - a song, a number, words - and the cell it goes in.
    var configuring by remember { mutableStateOf<Pair<RemoteButton, Pair<Int, Int>>?>(null) }

    fun addAt(b: RemoteButton, cell: Pair<Int, Int>) {
        val next = grid.add(deck, b, cell.first, cell.second)
        if (next == null) notice = "The grid is full - make it bigger above, or drag a button back to the library."
        else { remote.saveDeck(next); notice = null }
    }

    fun dropped(b: RemoteButton, cell: Pair<Int, Int>) {
        if (b.kind == RemoteButton.SONG || b.kind == RemoteButton.SETLIST || needsMore(b)) configuring = b to cell
        else addAt(b, cell)
    }

    fun drop() {
        val (b, from) = drag ?: return
        drag = null
        val cell = cells.entries.firstOrNull { it.value.contains(finger) }?.key
        when {
            from >= 0 && tray?.contains(finger) == true -> remote.saveDeck(deck.filterIndexed { i, _ -> i != from })
            from >= 0 && cell != null -> remote.saveDeck(grid.move(deck, from, cell.first, cell.second))
            from < 0 && cell != null -> dropped(b, cell)
        }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxSize().onGloballyPositioned { screenAt = it.positionInRoot() }
    ) {
        val tall = maxHeight
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
            if (!editing) {
                // What the other device is on: short, so the buttons get the screen.
                Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        val blockedAt = remote.blocked
                        if (!remote.connected && blockedAt != null) {
                            Text("This Wi-Fi is stopping the connection", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                            Text(
                                "It lets the two devices start talking at $blockedAt, then drops everything after - school and work " +
                                    "networks such as eduroam do. Pair the two over Bluetooth, use a phone's hotspot, or run Tailscale on both.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else if (shown == null) {
                            Text(if (remote.connected) "Waiting for it to say where it is..." else "Not connected yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    shown.title ?: if (shown.home) "On Home" else "No song open",
                                    style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (shown.counting > 0) Text("${shown.counting}", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                            }
                            val line = listOfNotNull(
                                shown.part,
                                if (shown.pages > 0) "p. ${shown.page + 1}/${shown.pages}" else null,
                                shown.setlist?.let { "song ${shown.setIndex + 1}/${shown.set.size}" },
                                when {
                                    shown.leading -> "Leading" + if (shown.followers > 0) " (${shown.followers})" else ""
                                    shown.following != null -> "Following ${shown.following}"
                                    else -> null
                                },
                                if (shown.metronome) "♩ ${shown.bpm}" else null,
                                if (shown.recording) "● ${shown.recordingSeconds / 60}:${"%02d".format(shown.recordingSeconds % 60)}" else null
                            ).joinToString("  ·  ")
                            if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                    }
                }
                // Each press says whether it reached the other device - never a button that silently does nothing.
                remote.lastPress?.let { p ->
                    Text(
                        when (p.got) {
                            true -> "${p.name} ✓"
                            null -> if (p.unanswered) "${p.name} - sent (update the other device to see it arrive)" else "${p.name}..."
                            false -> if (!remote.connected) "${p.name} - not sent: not connected" else "${p.name} - not received. Reconnecting..."
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (p.got == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                    )
                }
            } else {
                // The grid's shape, and done.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    fun resize(to: DeckGrid) {
                        val placed = grid.resized(deck, to)
                        if (placed == null) notice = "Those buttons would not fit - drag some back to the library first."
                        else { remote.saveGrid(to, placed); notice = null }
                    }
                    GridStepper("Across", grid.columns, DeckGrid.COLUMNS) { resize(DeckGrid(it, grid.rows)) }
                    Spacer(Modifier.width(8.dp))
                    GridStepper("Down", grid.rows, DeckGrid.ROWS) { resize(DeckGrid(grid.columns, it)) }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { editing = false; notice = null }) { Text("Done") }
                }
            }
            notice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
            Spacer(Modifier.height(8.dp))

            // The grid: the rest of the screen, every cell the same size.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val gap = 8.dp
                val w = (maxWidth - gap * (grid.columns - 1)) / grid.columns
                val fit = (maxHeight - gap * (grid.rows - 1)) / grid.rows
                val h = if (fit < 56.dp) 56.dp else fit
                cellSize = androidx.compose.ui.unit.DpSize(w, if (h > w * 1.4f) w * 1.4f else h)
                val scroll = rememberScrollState()
                Column(
                    Modifier.fillMaxSize().then(if (fit < 56.dp) Modifier.verticalScroll(scroll) else Modifier),
                    verticalArrangement = Arrangement.spacedBy(gap, if (fit < 56.dp) Alignment.Top else Alignment.CenterVertically)
                ) {
                    for (y in 0 until grid.rows) {
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            for (x in 0 until grid.columns) {
                                val at = grid.at(deck, x, y)
                                val b = deck.getOrNull(at)
                                val over = drag != null && cells[x to y]?.contains(finger) == true
                                Box(
                                    Modifier.size(cellSize)
                                        .onGloballyPositioned { cells[x to y] = it.boundsInRoot() }
                                ) {
                                    when {
                                        // The one being dragged stays, faded: taking it away would end the drag.
                                        b != null -> DeckButton(
                                            b, shown, remote.hostLibrary, editing,
                                            Modifier.fillMaxSize().alpha(if (drag?.second == at) 0.3f else 1f).then(
                                                if (!editing) Modifier else Modifier.pointerInput(at, deck) {
                                                    detectDragGestures(
                                                        onDragStart = { o -> drag = b to at; finger = cells[x to y]!!.topLeft + o },
                                                        onDrag = { change, amount -> change.consume(); finger += amount },
                                                        onDragEnd = { drop() },
                                                        onDragCancel = { drag = null }
                                                    )
                                                }
                                            ).then(if (over) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp)) else Modifier),
                                            onPress = { if (editing) changing = at else press(b) }
                                        )
                                        editing -> Box(
                                            Modifier.fillMaxSize()
                                                .border(
                                                    if (over) 3.dp else 1.dp,
                                                    if (over) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                                    RoundedCornerShape(18.dp)
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) { Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.outlineVariant) }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (!editing) {
                // Leading: the leader's one-tap messages, in a row that scrolls.
                if (shown?.leading == true && shown.presets.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        shown.presets.forEachIndexed { i, p ->
                            PresetTile(p, Modifier.width(120.dp)) { remote.send(RemoteLink.Command(action = RemoteLink.PRESET, index = i)) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, null); Spacer(Modifier.width(6.dp)); Text("Change buttons") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { remote.disconnect() }) { Text("Disconnect") }
                }
            } else {
                // The library: every button there is. Held and dragged onto the grid, or tapped
                // for the first gap; a button dragged back here comes off.
                val removing = drag?.second?.let { it >= 0 } == true
                val overTray = removing && tray?.contains(finger) == true
                Surface(
                    shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
                    tonalElevation = 4.dp,
                    color = if (overTray) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().height(tall * 0.42f).padding(top = 8.dp)
                        .onGloballyPositioned { tray = it.boundsInRoot() }
                ) {
                    if (removing) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(if (overTray) "Let go to take it off" else "Drag here to take it off", style = MaterialTheme.typography.titleMedium)
                        }
                    } else {
                        DeckLibrary(
                            state,
                            onTap = { b -> grid.firstFree(deck)?.let { dropped(b, it) } ?: run { notice = "The grid is full - make it bigger above, or drag a button back to the library." } },
                            onDragStart = { b, at -> drag = b to -1; finger = at },
                            onDrag = { by -> finger += by },
                            onDragEnd = { drop() },
                            // Only its own: the library's tiles go away when a grid button is picked up,
                            // and a tile going away reports its gesture cancelled.
                            onDragCancel = { if (drag?.second == -1) drag = null },
                            onReset = { remote.saveGrid(DeckGrid(), DeckGrid().place(RemoteButton.DEFAULT_DECK)!!) }
                        )
                    }
                }
            }
        }

        // The touchpad: over everything, until the fingers leave it or it is tapped.
        if (touchpad) Touchpad(onMove = { dx, dy, zoom, fx, fy ->
            remote.stream(RemoteLink.Command(action = RemoteButton.VIEW, dx = dx, dy = dy, zoom = zoom, fx = fx, fy = fy))
        }, onDone = { touchpad = false })

        // The button under the finger while it is dragged.
        drag?.let { (b, _) ->
            val d = androidx.compose.ui.platform.LocalDensity.current
            val half = with(d) { androidx.compose.ui.geometry.Offset(cellSize.width.toPx() / 2, cellSize.height.toPx() / 2) }
            val at = finger - screenAt - half
            DeckButton(
                b, shown, remote.hostLibrary, true,
                Modifier.offset { androidx.compose.ui.unit.IntOffset(at.x.roundToInt(), at.y.roundToInt()) }
                    .size(cellSize).alpha(0.85f),
                onPress = {}
            )
        }
    }

    configuring?.let { (b, cell) ->
        when (b.kind) {
            RemoteButton.SONG -> PickRemoteItem("A button for which song?", remote.hostLibrary?.songs.orEmpty(),
                onChosen = { addAt(RemoteButton(RemoteButton.SONG, it.id, it.title), cell); configuring = null }, onDismiss = { configuring = null })
            RemoteButton.SETLIST -> PickRemoteItem("A button for which setlist?", remote.hostLibrary?.setlists.orEmpty(),
                onChosen = { addAt(RemoteButton(RemoteButton.SETLIST, it.id, it.title), cell); configuring = null }, onDismiss = { configuring = null })
            else -> ButtonEditor(state, b, isNew = true, onSave = { addAt(it, cell); configuring = null }, onRemove = null, onDismiss = { configuring = null })
        }
    }
    changing?.let { at ->
        val b = remote.deck.getOrNull(at)
        if (b == null) changing = null else ButtonEditor(
            state, b, isNew = false,
            onSave = { changed -> remote.saveDeck(remote.deck.toMutableList().also { it[at] = changed.copy(x = b.x, y = b.y) }); changing = null },
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
    RemoteButton.AUDIO_VOLUME -> "Percent (a minus makes it quieter)" to 10.0
    else -> null
}

private fun num(v: Double?): String = v?.let { if (it == kotlin.math.floor(it)) it.toLong().toString() else it.toString() } ?: ""

/** A button's own name, when the player has given it none. */
internal fun defaultName(b: RemoteButton, shown: RemoteLink.State?, lib: RemoteLink.Library?): String {
    val v = b.value
    return when (b.kind) {
        RemoteButton.ACTION -> PerformAction.entries.firstOrNull { it.name == b.id }?.let { a ->
            val tools = shown?.toolsShown == true
            when (a) {
                PerformAction.FULLSCREEN -> if (tools) "Hide tools" else "Show tools"
                PerformAction.NEXT_PAGE -> "Next page"
                PerformAction.PREVIOUS_PAGE -> "Previous page"
                PerformAction.FIRST_PAGE -> "First page"
                PerformAction.LAST_PAGE -> "Last page"
                PerformAction.HALF_PAGE_FORWARD -> "Half a page on"
                PerformAction.HALF_PAGE_BACK -> "Half a page back"
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
        RemoteButton.AUDIO_VOLUME -> if ((v ?: 0.0) < 0) "Quieter ${num(-(v ?: 0.0))}%" else "Louder ${num(v)}%"
        RemoteButton.MESSAGE -> b.text ?: "Message"
        RemoteButton.MESSAGE_TYPE -> "Write a message..."
        RemoteButton.STRIP -> if (shown?.stripOpen == true) "Hide toolbar" else "Toolbar"
        RemoteButton.TOOLS -> if (shown?.toolsShown == true) "Put tools away" else "All tools"
        RemoteButton.FIT -> "Fit the page"
        RemoteButton.TOUCHPAD -> "Pan and zoom"
        RemoteButton.LISTEN -> "Listen"
        RemoteButton.HOME -> "Home"
        RemoteButton.LEADER -> "Back to the leader"
        RemoteButton.LEAD -> if (shown?.leading == true) "Stop leading" else "Lead"
        RemoteButton.MACRO -> "${b.steps.size} steps"
        RemoteButton.READ_PAGE -> "Read this page"
        RemoteButton.READ_PART -> "Read the part"
        RemoteButton.CLEAN -> if (shown?.cleanShown == true) "Hide clean view" else "Clean view"
        RemoteButton.FIX -> if (shown?.fixing == true) "Stop fixing" else "Fix bars in doubt"
        RemoteButton.SCORE_PLAY -> if (shown?.scorePlaying == true) "Stop the music" else "Play the music read"
        RemoteButton.MUSIC_TOOLS -> if (shown?.musicTools == true) "Put music tools away" else "Music tools"
        RemoteButton.AUDIO_VOLUME_SET -> "Recording volume"
        RemoteButton.AUDIO_SPEED_SET -> "Recording speed"
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
    RemoteButton.AUDIO_VOLUME -> Icons.AutoMirrored.Filled.VolumeUp
    RemoteButton.MESSAGE -> Icons.Default.Campaign
    RemoteButton.MESSAGE_TYPE -> Icons.Default.Edit
    RemoteButton.STRIP -> Icons.Default.ViewSidebar
    RemoteButton.TOOLS -> Icons.Default.Construction
    RemoteButton.FIT -> Icons.Default.CenterFocusStrong
    RemoteButton.TOUCHPAD -> Icons.Default.PanTool
    RemoteButton.LISTEN -> Icons.Default.Hearing
    RemoteButton.HOME -> Icons.Default.Home
    RemoteButton.LEADER, RemoteButton.LEAD -> Icons.Default.Groups
    RemoteButton.MACRO -> Icons.Default.AutoAwesome
    RemoteButton.READ_PAGE, RemoteButton.READ_PART -> Icons.Default.DocumentScanner
    RemoteButton.CLEAN -> Icons.Default.Visibility
    RemoteButton.FIX -> Icons.Default.Build
    RemoteButton.SCORE_PLAY -> if (shown?.scorePlaying == true) Icons.Default.Stop else Icons.Default.PlayArrow
    RemoteButton.MUSIC_TOOLS -> Icons.Default.LibraryMusic
    else -> Icons.Default.MusicNote
}

@Composable
private fun DeckButton(
    b: RemoteButton,
    shown: RemoteLink.State?,
    lib: RemoteLink.Library?,
    editing: Boolean,
    modifier: Modifier,
    onPress: () -> Unit
) {
    val action = if (b.kind == RemoteButton.ACTION) PerformAction.entries.firstOrNull { it.name == b.id } else null
    val lit = when {
        action == PerformAction.METRONOME -> shown?.metronome == true
        action == PerformAction.BOOKMARK -> shown?.bookmarked == true
        action == PerformAction.PLAY_AUDIO -> shown?.recordingPlaying == true
        action == PerformAction.FULLSCREEN -> shown?.toolsShown == true
        action != null && shown?.windows?.contains(action.name) == true -> true
        b.kind == RemoteButton.RECORD -> shown?.recording == true
        b.kind == RemoteButton.CLICK_RECORDING -> shown?.clickRecording == true
        b.kind == RemoteButton.CLICK_PLAYBACK -> shown?.clickPlayback == true
        b.kind == RemoteButton.STRIP -> shown?.stripOpen == true
        b.kind == RemoteButton.TOOLS -> shown?.toolsShown == true
        b.kind == RemoteButton.LEAD -> shown?.leading == true
        b.kind == RemoteButton.COUNT_IN -> (shown?.counting ?: 0) > 0
        b.kind == RemoteButton.COUNT_BARS -> shown?.countInBars == b.value?.toInt()
        b.kind == RemoteButton.PROFILES -> false
        b.kind == RemoteButton.LISTEN -> shown?.listening == true
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
        b.kind == RemoteButton.LISTEN -> shown?.listenStatus
        else -> null
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = fill,
        contentColor = ink,
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onPress)
    ) {
        // Small cells (a phone, a big grid) drop the detail and shrink the icon, never the words.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val small = maxHeight < 80.dp || maxWidth < 80.dp
            Column(Modifier.align(Alignment.Center).padding(if (small) 4.dp else 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(iconFor(b, shown), null, modifier = Modifier.size(if (small) 22.dp else 30.dp))
                Text(
                    b.label ?: defaultName(b, shown, lib),
                    style = if (small) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                // Listen's state is shown however small the button: it is how you know it hears.
                if (!small || b.kind == RemoteButton.LISTEN) detail?.let {
                    val alarm = b.kind == RemoteButton.LISTEN && it.startsWith("Hears nothing")
                    Text(it, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        color = if (alarm) MaterialTheme.colorScheme.error else Color.Unspecified, fontWeight = if (alarm) androidx.compose.ui.text.font.FontWeight.Bold else null)
                }
            }
            if (editing) Icon(
                Icons.Default.DragIndicator, null,
                tint = ink.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp)
            )
        }
    }
}

/**
 * A big square for moving the other device's page: two fingers drag and pinch it, one finger drags.
 * Moves go out a few dozen times a second, as fractions of the square, so the page follows the
 * fingers whatever the two screens' sizes. Letting go, or a tap, puts it away.
 */
@Composable
private fun Touchpad(onMove: (dx: Double, dy: Double, zoom: Double, fx: Double, fy: Double) -> Unit, onDone: () -> Unit) {
    val move by androidx.compose.runtime.rememberUpdatedState(onMove)
    val done by androidx.compose.runtime.rememberUpdatedState(onDone)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
            .pointerInput(Unit) { detectTapGestures { done() } },
        contentAlignment = Alignment.Center
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
            val side = minOf(maxWidth, maxHeight)
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                tonalElevation = 6.dp,
                modifier = Modifier.size(side).pointerInput(Unit) {
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val first = awaitFirstDown(requireUnconsumed = false)
                        var moved = false
                        var lastC: androidx.compose.ui.geometry.Offset? = null
                        var lastSpan = 0f
                        var lastCount = 0
                        var dx = 0.0; var dy = 0.0; var zoom = 1.0; var fx = 0.5; var fy = 0.5
                        var sentAt = 0L
                        fun flush(force: Boolean) {
                            val now = System.currentTimeMillis()
                            if ((dx != 0.0 || dy != 0.0 || zoom != 1.0) && (force || now - sentAt >= 30)) {
                                move(dx, dy, zoom, fx, fy)
                                dx = 0.0; dy = 0.0; zoom = 1.0; sentAt = now
                            }
                        }
                        while (true) {
                            val event = awaitPointerEvent()
                            val down = event.changes.filter { it.pressed }
                            if (down.isEmpty()) break
                            val c = down.fold(androidx.compose.ui.geometry.Offset.Zero) { a, p -> a + p.position } / down.size.toFloat()
                            val span = if (down.size > 1) down.map { (it.position - c).getDistance() }.average().toFloat() else 0f
                            // A finger added or lifted: start measuring again from here, no jump.
                            if (down.size != lastCount || lastC == null) {
                                lastC = c; lastSpan = span; lastCount = down.size
                            } else {
                                val step = c - lastC!!
                                if (!moved && ((c - first.position).getDistance() > slop || down.size > 1 && kotlin.math.abs(span - lastSpan) > 2f)) moved = true
                                if (moved) {
                                    dx += step.x / minOf(w, h)
                                    dy += step.y / minOf(w, h)
                                    if (down.size > 1 && lastSpan > 8f && span > 8f) zoom *= (span / lastSpan).toDouble()
                                    fx = (c.x / w).toDouble().coerceIn(0.0, 1.0)
                                    fy = (c.y / h).toDouble().coerceIn(0.0, 1.0)
                                    flush(false)
                                }
                                lastC = c; lastSpan = span
                            }
                            event.changes.forEach { it.consume() }
                        }
                        flush(true)
                        // Let go after moving, or tapped: either way, it is done.
                        done()
                    }
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.PanTool, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Two fingers: move and zoom the page", style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
                        Text("Let go or tap to close", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** A number of cells across or down, one more or one fewer. */
@Composable
private fun GridStepper(name: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    val icon = if (name == "Across") Icons.Default.ViewColumn else Icons.Default.TableRows
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, name, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = { onChange(value - 1) }, enabled = value > range.first, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Remove, "Fewer ${if (name == "Across") "columns" else "rows"}")
        }
        Text("$value", style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = { onChange(value + 1) }, enabled = value < range.last, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Add, "More ${if (name == "Across") "columns" else "rows"}")
        }
    }
}

/**
 * Every button there is, a section at a time: held and dragged onto the grid, or tapped to go in
 * the first gap. Short names, big enough to take hold of on a phone.
 */
@Composable
private fun DeckLibrary(
    state: SheetsState,
    onTap: (RemoteButton) -> Unit,
    onDragStart: (RemoteButton, androidx.compose.ui.geometry.Offset) -> Unit,
    onDrag: (androidx.compose.ui.geometry.Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onReset: () -> Unit
) {
    val remote = state.remote
    val all = offers(remote.shown)
    val sections = all.map { it.section }.distinct()
    var section by remember { mutableStateOf(sections.first()) }
    Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Library", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onReset) { Text("Start again") }
        }
        Text(
            "Hold one and drag it onto the grid, or tap it for the first gap. Drag a button from the grid back here to take it off.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (s in sections) androidx.compose.material3.FilterChip(selected = s == section, onClick = { section = s }, label = { Text(s) })
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val across = (maxWidth / 100.dp).toInt().coerceIn(3, 6)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                all.filter { it.section == section }.chunked(across).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { o -> LibraryTile(o, remote.shown, Modifier.weight(1f), onTap, onDragStart, onDrag, onDragEnd, onDragCancel) }
                        repeat(across - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryTile(
    o: Offer,
    shown: RemoteLink.State?,
    modifier: Modifier,
    onTap: (RemoteButton) -> Unit,
    onDragStart: (RemoteButton, androidx.compose.ui.geometry.Offset) -> Unit,
    onDrag: (androidx.compose.ui.geometry.Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    var at by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.height(76.dp)
            .onGloballyPositioned { at = it }
            .pointerInput(o.name) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { p -> at?.let { onDragStart(o.button, it.localToRoot(p)) } },
                    onDrag = { change, amount -> change.consume(); onDrag(amount) },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragCancel
                )
            }
            .clip(RoundedCornerShape(12.dp))
            .clickable { onTap(o.button) }
    ) {
        Column(Modifier.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(iconFor(o.button, shown), null, modifier = Modifier.size(22.dp))
            Text(
                o.name.removeSuffix("...").substringBefore(" (").substringBefore("... ("),
                style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
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
internal class Offer(val section: String, val name: String, val button: RemoteButton)

internal fun offers(shown: RemoteLink.State?): List<Offer> {
    fun a(section: String, action: PerformAction, name: String = action.label) = Offer(section, name, RemoteButton.action(action.name))
    fun k(section: String, name: String, kind: String, value: Double? = null) = Offer(section, name, RemoteButton(kind, value = value))
    val pages = "Pages"; val songs = "Songs and sets"; val parts = "Parts"; val click = "Metronome and count-in"
    val rec = "Recordings"; val marks = "Marking"; val view = "The screen"; val band = "Playing together"; val more = "More than one thing"
    val reading = "Reading the music"
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
        k(rec, "Louder or quieter by...", RemoteButton.AUDIO_VOLUME, 10.0),
        a(rec, PerformAction.RECORDINGS, "Open the recordings there"),
        k(rec, "Listen and turn pages (experimental; on in its Settings)", RemoteButton.LISTEN),
        a(marks, PerformAction.BOOKMARK), a(marks, PerformAction.PEN), a(marks, PerformAction.HIGHLIGHTER),
        a(marks, PerformAction.ERASER), a(marks, PerformAction.UNDO), a(marks, PerformAction.REDO),
        k(view, "The toolbar (show/hide)", RemoteButton.STRIP),
        k(view, "All tools and the toolbar (show/hide)", RemoteButton.TOOLS),
        a(view, PerformAction.FULLSCREEN, "Only the tools (show/hide)"),
        k(view, "Fit the page to the screen", RemoteButton.FIT),
        k(view, "Pan and zoom (a touchpad for two fingers)", RemoteButton.TOUCHPAD),
        k(band, "Lead (start/stop)", RemoteButton.LEAD),
        k(band, "Back to the leader", RemoteButton.LEADER),
        k(band, "A message you write now", RemoteButton.MESSAGE),
        k(band, "Write a message each time...", RemoteButton.MESSAGE_TYPE),
        a(band, PerformAction.PLAY_TOGETHER, "Open Play together there"),
        k(reading, "Read this page's music", RemoteButton.READ_PAGE),
        k(reading, "Read the whole part's music", RemoteButton.READ_PART),
        k(reading, "The clean view (show/hide)", RemoteButton.CLEAN),
        k(reading, "Fix the bars in doubt (start/stop)", RemoteButton.FIX),
        k(reading, "Play the music read (start/stop)", RemoteButton.SCORE_PLAY),
        k(reading, "The music tools (show/hide)", RemoteButton.MUSIC_TOOLS)
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
