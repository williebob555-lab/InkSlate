package com.inksheets.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.inkslate.core.InkDocument
import com.inkslate.core.NetAddresses
import com.inkslate.core.Perform
import com.inkslate.core.QrCodes
import com.inksheets.core.CompanionFollower
import com.inksheets.core.CompanionLeader
import com.inksheets.core.CompanionLink
import com.inksheets.core.CompanionScanner
import com.inksheets.core.Part
import com.inksheets.core.PartChoice
import com.inksheets.core.Song
import java.io.File

/**
 * Companion mode's state: leading, following, or neither. One per app, like [SheetsState].
 *
 * Built for a band room: a player joins by scanning the leader's code (or the phone camera opening
 * the link in it), rejoins on their own when the Wi-Fi drops or the app restarts, and can always get
 * back to where the leader is with one button. The leader sees a count, never a stream of pop-ups;
 * who joined and left is in the event log.
 */
class Companion(private val state: SheetsState) {

    var leading by mutableStateOf(false)
        private set
    var followers by mutableStateOf(0)
        private set

    /** The leader's name while following - connected or trying to reconnect. */
    var following by mutableStateOf<String?>(null)
        private set
    var connected by mutableStateOf(false)
        private set

    var follow: CompanionLink.Follow
        get() = followState
        set(v) { followState = v; state.platform.setPref(K_FOLLOW, v.name) }
    private var followState by mutableStateOf(
        CompanionLink.Follow.entries.firstOrNull { it.name == state.platform.pref(K_FOLLOW) } ?: CompanionLink.Follow.AUTO
    )

    /** Leading: whether marks go to followers on the same part. */
    var shareInk: Boolean
        get() = shareInkState
        set(v) {
            shareInkState = v
            state.platform.setPref(K_SHARE_INK, v.toString())
            lastInkSignature = 0L
            state.current?.let { announce(state.pageShown.first) }
        }
    private var shareInkState by mutableStateOf(state.platform.pref(K_SHARE_INK) == "true")

    /** Where the leader is, while following. */
    var leaderAt by mutableStateOf<CompanionLink.Showing?>(null)
        private set

    /** The leader's song, when this library has no such song (or not its file). */
    var missing by mutableStateOf<String?>(null)
        private set

    /** What a follower scans, while leading. */
    var joinLink by mutableStateOf<String?>(null)
        private set

    /** The leader last followed, to follow again in one tap. */
    val lastLeader: CompanionLink.Leader?
        get() = state.platform.pref(K_LAST)?.let(CompanionLink::parseJoin)

    internal var transport by mutableStateOf(ConnectionTransport.decode(state.platform.pref("sheets_companion_transport")))
        private set
    internal fun chooseTransport(value: ConnectionTransport) {
        val wasLeading = leading
        val last = if (following != null) lastLeader else null
        stopLeading(); stopFollowing()
        transport = value
        state.platform.setPref("sheets_companion_transport", value.name)
        if (wasLeading) lead() else if (last != null) followLeader(last) {}
    }
    internal val nearbyBluetooth = mutableStateListOf<CompanionLink.Leader>()
    private val nearbySeen = HashMap<Int, Long>()
    private var discovery = false
    internal fun discoverBluetooth() {
        discovery = true
        if (!leading && following == null && transport != ConnectionTransport.WIFI) startMesh("")
    }
    internal fun endDiscovery() { discovery = false; if (!leading && following == null) stopMesh() }
    internal fun retryBluetooth() {
        if (transport != ConnectionTransport.WIFI) startMesh(if (leading) state.platform.deviceName else following.orEmpty(), ask = true)
    }
    private var selectedSession: Int? = null
    private var beaconSeq = 0L
    private var heardBeaconSeq = 0L
    private var beaconCandidateSeq: Long? = null
    private var beaconCandidateAt = 0L
    private var meshGeneration = 0L
    internal var connectionClock: () -> Long = { System.nanoTime() / 1_000_000L }
    private var beaconAt = 0L
    internal val connectionDetail: String
        get() {
            val age = if (beaconAt > 0) ((connectionClock() - beaconAt) / 1000).coerceAtLeast(0) else null
            return when {
                connected && age != null && age < 4 -> "Connected · Bluetooth · heartbeat ${age}s ago"
                connected -> "Connected · Wi-Fi / network"
                age != null && age < 7 -> "Reconnecting · last Bluetooth heartbeat ${age}s ago"
                following != null -> "Waiting for the leader · reconnecting automatically"
                else -> "Ready"
            }
        }
    private val participant = com.inksheets.core.MeshFrames.participantOf(state.platform.deviceId)
    private var presenceSeq = 0L
    private var pendingJoin: ((Boolean) -> Unit)? = null
    private val participants = HashMap<Long, Pair<Long, Long>>()
    private val participantNames = HashMap<Long, String>()
    var connectedDevices by mutableStateOf<List<com.inksheets.core.ConnectedPeer>>(emptyList())
        private set
    private fun refreshConnectedDevices() {
        val now = connectionClock()
        val network = leader?.connectedDevices.orEmpty()
        val bluetooth = participants.map { (id, seen) ->
            val key = "participant:$id"
            com.inksheets.core.ConnectedPeer(key,
                network.firstOrNull { it.id == key }?.name ?: participantNames[id]?.takeIf { it.isNotBlank() } ?: "Bluetooth stand ${id.toString(16).takeLast(4)}",
                "Bluetooth", com.inksheets.core.peerStatus(seen.first, now),
                System.currentTimeMillis() - (now - seen.first).coerceAtLeast(0))
        }
        connectedDevices = com.inksheets.core.mergeConnectedPeers(network + bluetooth)
        followers = connectedDevices.count { it.status == com.inksheets.core.ConnectionStatus.CONNECTED }
    }
    private var leader: CompanionLeader? = null
    private var follower: CompanionFollower? = null
    private var lastApplied: Pair<String, Int?>? = null
    private val pendingInk = HashMap<String, CompanionLink.InkShare>()
    private var inkTimer: java.util.Timer? = null
    private var lastInkSignature = 0L

    /** The port to lead on. Tests use a free one, so they never meet a real InkSheets on this machine. */
    var leadPort = CompanionLink.PORT
    private val reconnectCheck = java.util.Timer("companion-reconnect", true)

    /** What the strip shows: "Leading · 2", "Following Stand 1". */
    val status: String?
        get() = when {
            leading -> "Leading" + if (followers > 0) " · $followers" else ""
            following != null -> if (connected) "Following $following" else "Reconnecting…"
            else -> null
        }

    // ---- leading ----------------------------------------------------------------------

    /** The leader's message showing now, and a count that changes with each so a repeat shows again. */
    var notice by mutableStateOf<CompanionLink.Note?>(null)

    // ---- Bluetooth (experimental) ------------------------------------------------------

    /** Also keep in step over Bluetooth, device to device - for networks that block devices. */
    var meshOn: Boolean
        get() = meshOnState
        set(v) {
            meshOnState = v
            state.platform.setPref(K_MESH, v.toString())
            if (!v) { chooseTransport(ConnectionTransport.WIFI); stopMesh() } else when {
                leading -> startMesh(state.platform.deviceName)
                following != null -> startMesh(following!!)
            }
        }
    private var meshOnState by mutableStateOf(state.platform.pref(K_MESH) == "true")
    /** What the mesh is doing, for Settings. */
    var meshStatus by mutableStateOf<String?>(null)
    private var radio: MeshRadio? = null
    private var meshSession = 0
    private var meshState: com.inksheets.core.MeshFrames.State? = null
    private val meshNotes = ArrayList<Pair<Long, List<com.inksheets.core.MeshFrames.NotePiece>>>()
    private var lastSeq = 0L
    private val assembler = com.inksheets.core.MeshFrames.NoteAssembler()
    private var songKeys: Pair<Long, Map<Long, Song>>? = null

    private fun startMesh(leaderName: String, ask: Boolean = true) {
        if (transport == ConnectionTransport.WIFI) return
        val r = state.platform.meshRadio() ?: run { meshStatus = "Not on this device"; return }
        if (!r.ready(ask)) { meshStatus = "Waiting for Bluetooth permission, or Bluetooth to be on"; return }
        stopMesh()
        meshSession = selectedSession ?: com.inksheets.core.MeshFrames.sessionOf(leaderName)
        val generation = meshGeneration
        if (!r.start { bytes -> state.platform.onMain { if (generation == meshGeneration && radio === r) heardFrame(bytes) } }) { meshStatus = "Bluetooth could not start"; return }
        radio = r
        meshStatus = "On"
        state.platform.log("Companion: Bluetooth on, for $leaderName")
        rebroadcast()
    }

    private fun stopMesh() {
        meshGeneration++
        radio?.stop()
        radio = null
        meshState = null
        meshNotes.clear()
        meshStatus = null
    }

    /** Everything this device knows, sent round in turn: where the leader is, and recent messages. */
    private fun rebroadcast() {
        val r = radio ?: return
        val now = System.currentTimeMillis()
        meshNotes.removeAll { now - it.first > 20_000 }
        val heartbeat = when {
            leading -> com.inksheets.core.MeshFrames.Beacon(meshSession, beaconSeq, state.platform.deviceName)
            following != null && heardBeaconSeq > 0 -> com.inksheets.core.MeshFrames.Presence(meshSession, participant, presenceSeq, heardBeaconSeq, name = state.platform.deviceName)
            else -> null
        }
        val payload = listOfNotNull(meshState?.let { com.inksheets.core.MeshFrames.encode(it) }) +
            meshNotes.flatMap { (_, pieces) -> pieces.map { com.inksheets.core.MeshFrames.encode(it) } }
        r.broadcast(meshBroadcastSchedule(heartbeat?.let { com.inksheets.core.MeshFrames.encode(it) }, payload))
    }

    /** Where the leader is, passed on: from the leader itself (hop 0) or heard over Wi-Fi (hop 1). */
    private fun meshPublish(showing: CompanionLink.Showing, hops: Int) {
        if (radio == null || showing.seq <= 0) return
        val next = com.inksheets.core.MeshFrames.State(
            session = meshSession, seq = showing.seq,
            songKey = com.inksheets.core.MeshFrames.songKey(showing.title ?: return),
            page = showing.page,
            partKey = com.inksheets.core.MeshFrames.partKey(showing.instrument, showing.partNo, showing.pages),
            hops = hops
        )
        val had = meshState
        if (had != null && !com.inksheets.core.MeshFrames.isNewer(next.seq, had.seq)) return
        meshState = next
        rebroadcast()
    }

    private fun meshNote(note: CompanionLink.Note, hops: Int) {
        if (radio == null) return
        val colour = note.color?.let { c -> MARK_COLOURS.indexOf(c).takeIf { it >= 0 }?.plus(1) } ?: 0
        val pieces = com.inksheets.core.MeshFrames.notePieces(meshSession, com.inksheets.core.MeshFrames.idOf(note.at), note.text, note.urgent, colour)
            .map { it.copy(hops = hops) }
        meshNotes += System.currentTimeMillis() to pieces
        rebroadcast()
    }

    /** A broadcast heard from a nearby device. UI thread. */
    private fun heardFrame(bytes: ByteArray) {
        val frame = com.inksheets.core.MeshFrames.decode(bytes) ?: return
        if (radio == null) return
        if (frame is com.inksheets.core.MeshFrames.Beacon && frame.hops == 0 && frame.name.isNotBlank() && !leading) {
            nearbySeen[frame.session] = connectionClock()
            nearbyBluetooth.removeAll { it.bluetoothSession == frame.session }
            nearbyBluetooth += CompanionLink.Leader(frame.name, emptyList(), CompanionLink.PORT, frame.session)
        }
        if (frame.session != meshSession) return
        when (frame) {
            is com.inksheets.core.MeshFrames.Beacon -> {
                if (!leading && following != null && frame.hops == 0) {
                    val now = connectionClock()
                    val disconnected = beaconAt == 0L || now - beaconAt >= 7000L
                    val candidate = beaconCandidateSeq
                    val fresh = if (disconnected) {
                        if (candidate == null || now - beaconCandidateAt > 4000L) {
                            // A cached advertisement cannot reconnect a stand by itself. Observe
                            // a baseline, then require the leader's sequence to actually advance.
                            beaconCandidateSeq = frame.seq; beaconCandidateAt = now
                            false
                        } else com.inksheets.core.MeshFrames.isNewer(frame.seq, candidate)
                    } else com.inksheets.core.MeshFrames.isNewer(frame.seq, heardBeaconSeq)
                    if (fresh) {
                        if (disconnected) lastSeq = 0L // New process may restart its page sequence.
                        beaconCandidateSeq = null
                        heardBeaconSeq = frame.seq; beaconAt = now; connected = true
                        pendingJoin?.invoke(true); pendingJoin = null
                    }
                }
            }
            is com.inksheets.core.MeshFrames.Presence -> {
                val previous = participants[frame.participant]
                if (leading && frame.hops == 0 && frame.leaderSeq > 0 && beaconSeq - frame.leaderSeq in 0L..3L &&
                    (previous == null || com.inksheets.core.MeshFrames.isNewer(frame.seq, previous.second))) {
                    participants[frame.participant] = connectionClock() to frame.seq
                    participantNames[frame.participant] = frame.name
                    refreshConnectedDevices()
                }
            }
            is com.inksheets.core.MeshFrames.State -> {
                if (leading) return
                if (!com.inksheets.core.MeshFrames.isNewer(frame.seq, lastSeq)) return
                lastSeq = frame.seq
                val song = songByKey(frame.songKey)
                if (song != null && following != null) {
                    val mine = state.partFor(song)
                    val myKey = mine?.let { com.inksheets.core.MeshFrames.partKey(it.instrument, partNo(it), state.pageShown.second) }
                    val same = myKey != null && myKey == frame.partKey
                    show(CompanionLink.Showing(
                        title = song.title, page = frame.page, leader = following.orEmpty(),
                        instrument = if (same) mine?.instrument else "?", partNo = if (same) mine?.let(::partNo) else null,
                        seq = frame.seq
                    ))
                }
                // Passed on, a hop further, so it reaches the far side of the room.
                if (frame.hops < com.inksheets.core.MeshFrames.MAX_HOPS) {
                    meshState = frame.copy(hops = frame.hops + 1)
                    rebroadcast()
                }
            }
            is com.inksheets.core.MeshFrames.NotePiece -> {
                if (frame.hops < com.inksheets.core.MeshFrames.MAX_HOPS && meshNotes.none { (_, p) -> p.any { it.noteId == frame.noteId && it.index == frame.index } }) {
                    meshNotes += System.currentTimeMillis() to listOf(frame.copy(hops = frame.hops + 1))
                    rebroadcast()
                }
                if (leading) return
                val text = assembler.take(frame) ?: return
                val colour = frame.colour.takeIf { it > 0 }?.let { MARK_COLOURS.getOrNull(it - 1) }
                heard(CompanionLink.Line.Message(CompanionLink.Note(
                    text = text, from = following.orEmpty(), at = System.currentTimeMillis(), urgent = frame.urgent, color = colour
                )))
            }
        }
    }

    /** A song in this library by the key its title makes. */
    private fun songByKey(key: Long): Song? {
        val version = state.version
        val cached = songKeys?.takeIf { it.first == version }?.second
            ?: state.library?.songs.orEmpty().associateBy { com.inksheets.core.MeshFrames.songKey(it.title) }.also { songKeys = version to it }
        return cached[key]
    }
    private val REFRESH_MS = 3_000L
    private val seenNotes = HashSet<String>()

    /**
     * Set when the links keep dying a few seconds after they are made - what a school or
     * eduroam network does to devices talking to each other. Said once, where it can be acted on.
     */
    var networkBlocks by mutableStateOf(false)
    private val quickDrops = ArrayList<Long>()

    /** A link that lasted [secs] seconds went silent: several in a row means the network. */
    internal fun linkDied(secs: Long) {
        val now = System.currentTimeMillis()
        if (secs > 30) { quickDrops.clear(); return }
        quickDrops += now
        quickDrops.removeAll { now - it > 120_000 }
        if (quickDrops.size >= 3 && !networkBlocks) {
            networkBlocks = true
            // Following: from now on a fresh link every few seconds, before the network cuts one.
            follower?.refreshEveryMs = REFRESH_MS
            state.platform.log("Companion: links keep dropping a few seconds after connecting - the network is likely blocking devices from reaching each other")
        }
    }
    var noticeCount by mutableStateOf(0)

    /**
     * The leader's message is on screen now. Once it has been up - timed out or tapped away - it
     * never comes back: the popup belongs to each page, and a song change makes a new one.
     */
    var noticeShowing: Boolean
        get() = noticeUp
        set(v) {
            noticeUp = v
            if (!v) showNextNotice()
        }
    private var noticeUp by mutableStateOf(false)

    /** When the message in front was put up, and whether a page has shown it yet. One that nobody saw in time is not shown late. */
    var noticeAt = 0L
        private set
    var noticeSeen = false

    /** Messages that came while an urgent one was up: shown when it is tapped away, in order. */
    private val waitingNotes = ArrayList<Pair<Long, CompanionLink.Note>>()

    private fun putUp(n: CompanionLink.Note) {
        notice = n
        noticeAt = System.currentTimeMillis()
        noticeSeen = false
        noticeCount++
        noticeUp = true
    }

    private fun showNextNotice() {
        val now = System.currentTimeMillis()
        while (waitingNotes.isNotEmpty()) {
            val (at, n) = waitingNotes.removeAt(0)
            if (n.urgent || now - at <= com.inksheets.core.CompanionLeader.NOTE_KEPT_MS) { putUp(n); return }
        }
    }

    /** The instruments this player reads: the part in front, and those chosen to play. */
    private fun myInstruments(): Set<String> {
        val ids = HashSet<String>()
        state.partShown()?.let { p -> p.instrument?.let(ids::add); ids += p.also }
        state.profile?.instruments?.forEach { ids += com.inksheets.core.PartChoice.seat(it).first }
        return ids.flatMap { listOf(it) + com.inksheets.core.Instruments.sisters(it) }.toSet()
    }

    /** Send [text] to every follower, or only those playing [instruments]. */
    fun sendNote(text: String, instruments: List<String>, urgent: Boolean = false, color: Int? = null) {
        val l = leader ?: return
        if (text.isBlank()) return
        val note = CompanionLink.Note(text = text.trim(), instruments = instruments, urgent = urgent, color = if (urgent) color else null, at = System.currentTimeMillis())
        l.note(note)
        // Over Bluetooth it goes to everyone: there is no room in a broadcast to say for whom.
        meshNote(note, hops = 0)
    }

    fun lead(): Boolean {
        stopFollowing()
        val name = state.platform.deviceName
        val l = CompanionLeader(name, leadPort)
        l.onFollowers = { _ -> state.platform.onMain { refreshConnectedDevices() } }
        l.onLog = { line ->
            state.platform.log("Companion: $line")
            Regex("""went silent for \d+ s after (\d+) s""").find(line)?.let { m -> state.platform.onMain { linkDied(m.groupValues[1].toLong()) } }
        }
        if (!l.start(tcp = transport != ConnectionTransport.BLUETOOTH)) return false
        leader = l
        leading = true
        state.platform.holdNetwork(true)
        selectedSession = com.inksheets.core.MeshFrames.sessionOf(name)
        joinLink = CompanionLink.joinLink(name, if (transport == ConnectionTransport.BLUETOOTH) emptyList() else NetAddresses.mine(), leadPort,
            if (transport != ConnectionTransport.WIFI && state.platform.meshRadio() != null) selectedSession else null)
        startMesh(name)
        state.platform.log("Companion: leading as $name")
        state.current?.let { announce(state.pageShown.first) }
        lastInkSignature = 0L
        inkTimer = java.util.Timer("companion-ink", true).apply {
            schedule(object : java.util.TimerTask() {
                override fun run() = state.platform.onMain { shareInkIfChanged() }
            }, 1500L, 1500L)
        }
        return true
    }

    /** The song in front turned to [page] (or was just opened): tell anyone following. */
    fun pageTurned(page: Int) {
        announce(page)
        applyPendingInk()
        // Following: how long going where the leader went took to reach the screen.
        goingTo?.let { (path, since) ->
            if (state.currentPath == path) {
                val took = System.currentTimeMillis() - since
                if (took > 700) state.platform.log("Companion: showing the leader's page took ${took} ms")
                goingTo = null
            }
        }
    }

    /** Where this follower is on its way to, and since when - to find slow moves. */
    private var goingTo: Pair<String, Long>? = null

    private fun announce(page: Int) {
        val l = leader ?: return
        val song = state.current ?: return
        val part = state.partShown() ?: state.partFor(song)
        l.show(
            CompanionLink.Showing(
                songId = song.id, title = song.title, page = page,
                instrument = part?.instrument, partNo = part?.let(::partNo),
                pages = state.pageShown.second, shareInk = shareInk, partId = part?.id
            )
        )
        l.current?.let { meshPublish(it, hops = 0) }
    }

    /** Send the marks on the part in front, when sharing them and they have changed. UI thread. */
    private fun shareInkIfChanged() {
        val l = leader ?: return
        if (!shareInk || followers == 0 || state.homeInFront) return
        val path = state.currentPath ?: return
        val song = state.current ?: return
        val part = state.partFor(song) ?: return
        if (part.instrument == null) return
        val ink = Perform.inkOf?.invoke(path) ?: return
        var signature = ink.deleted.size.toLong() * 7919 + path.hashCode()
        ink.pages.keys.forEach { key -> key.toIntOrNull()?.let { signature = signature * 31 + ink.pageSignature(it) } }
        if (signature == lastInkSignature) return
        lastInkSignature = signature
        l.shareInk(
            CompanionLink.InkShare(
                songId = song.id, title = song.title, instrument = part.instrument, partNo = partNo(part),
                pages = state.pageShown.second,
                ink = ink.copy(bookmarks = emptyList(), bookmarksRemoved = emptyMap()).serialize()
            )
        )
    }

    fun stopLeading() {
        if (leading) stopMesh()
        inkTimer?.cancel()
        inkTimer = null
        leader?.stop()
        if (leader != null) state.platform.log("Companion: stopped leading")
        if (leading) state.platform.holdNetwork(false)
        leader = null
        leading = false
        followers = 0
        participants.clear(); participantNames.clear(); connectedDevices = emptyList()
        joinLink = null
    }

    // ---- following --------------------------------------------------------------------

    /**
     * Follow [target], and keep following it through dropped connections until [stopFollowing].
     * [done] hears, on the UI thread, whether the first attempt reached it.
     */
    fun followLeader(target: CompanionLink.Leader, done: (Boolean) -> Unit) {
        stopLeading()
        follower?.stop()
        val f = CompanionFollower(state.platform.deviceName, state.platform.deviceId) { line -> state.platform.onMain { heard(line) } }
        if (networkBlocks) f.refreshEveryMs = REFRESH_MS
        f.onLog = { line ->
            state.platform.log("Companion: $line")
            Regex("""^Lost .* after (\d+) s: nothing arrived""").find(line)?.let { m -> state.platform.onMain { linkDied(m.groupValues[1].toLong()) } }
        }
        f.onConnected = { on ->
            state.platform.onMain {
                if (follower === f) {
                    if (on) {
                        if (!connected) state.platform.log("Companion: connected to ${target.name}")
                        connected = true
                    } else {
                        // Most drops are back within a second; "Reconnecting" only if this one is not.
                        val lostAt = System.currentTimeMillis()
                        reconnectCheck.schedule(object : java.util.TimerTask() {
                            override fun run() = state.platform.onMain {
                                if (follower === f && (f.lastHeard < lostAt) && connectionClock() - beaconAt >= 4000) connected = false
                            }
                        }, 2_000L)
                    }
                }
            }
        }
        if (follower == null && transport != ConnectionTransport.BLUETOOTH && target.hosts.isNotEmpty()) state.platform.holdNetwork(true)
        follower = f
        following = target.name
        selectedSession = target.bluetoothSession
        heardBeaconSeq = 0; beaconAt = 0; beaconCandidateSeq = null
        lastSeq = 0
        startMesh(target.name)
        connected = false
        lastApplied = null
        state.platform.setPref(K_LAST, CompanionLink.joinLink(target.name, target.hosts, target.port, target.bluetoothSession))
        state.platform.setPref(K_FOLLOWING_SINCE, System.currentTimeMillis().toString())
        if (transport == ConnectionTransport.BLUETOOTH || target.hosts.isEmpty()) {
            pendingJoin = done
            return
        }
        Thread({
            val ok = f.start(target)
            state.platform.onMain { done(ok) }
        }, "companion-connect").apply { isDaemon = true; start() }
    }

    fun stopFollowing() {
        if (following != null) stopMesh()
        follower?.stop()
        if (follower != null) {
            state.platform.log("Companion: stopped following")
            state.platform.holdNetwork(false)
        }
        follower = null
        following = null
        connected = false
        pendingJoin = null; beaconAt = 0; beaconCandidateSeq = null; selectedSession = null
        leaderAt = null
        missing = null
        pendingInk.clear()
        state.platform.setPref(K_FOLLOWING_SINCE, null)
    }

    private fun heard(line: CompanionLink.Line) {
        if (following == null) return
        when (line) {
            is CompanionLink.Line.Show -> {
                state.platform.setPref(K_FOLLOWING_SINCE, System.currentTimeMillis().toString())
                val seq = line.showing.seq
                if (seq > 0) {
                    if (!com.inksheets.core.MeshFrames.isNewer(seq, lastSeq) && seq != lastSeq) return
                    lastSeq = seq
                }
                show(line.showing)
                meshPublish(line.showing, hops = 1)
            }
            is CompanionLink.Line.Ink -> takeInk(line.share)
            is CompanionLink.Line.Message -> {
                // Given again after a dropped link, or heard over both Wi-Fi and Bluetooth: shown once.
                // A message has its own id; one without (an older leader, Bluetooth) is known by
                // its second and words.
                val n = line.note
                val loose = (n.at / 1000).toString() + "|" + n.text
                val withId = n.id.isNotBlank()
                val fresh = "m$loose" !in seenNotes && if (withId) "i${n.id}" !in seenNotes else "w$loose" !in seenNotes
                if (withId) { seenNotes += "i${n.id}"; seenNotes += "w$loose" } else seenNotes += "m$loose"
                if (!fresh) return
                meshNote(n, hops = 1)
                if (CompanionLink.noteIsFor(n, myInstruments())) {
                    // Never over an urgent one that has not been tapped away: after it.
                    if (noticeUp && notice?.urgent == true) waitingNotes += System.currentTimeMillis() to n
                    else putUp(n)
                }
            }
            else -> Unit
        }
    }

    /**
     * Whether this tablet is somewhere other than where following the leader would put it - on
     * Home, in another song, or on another page - which is when "Go to the leader" is offered.
     */
    val offLeader: Boolean
        get() {
            val at = leaderAt ?: return false
            if (following == null) return false
            val library = state.library ?: return false
            val song = CompanionLink.songFor(library, at) ?: return false
            if (state.homeInFront) return true
            if (state.current?.id != song.id) return true
            val part = state.partFor(song) ?: return false
            val page = pageFor(at, song, part) ?: return false
            return state.pageShown.second > 0 && state.pageShown.first != page.coerceAtMost(state.pageShown.second - 1)
        }

    /** Go to where the leader is now, whatever this tablet was doing. */
    fun goToLeader() {
        leaderAt?.let { show(it, force = true) }
    }

    /** Go where the leader is, as this tablet follows it. */
    private fun show(showing: CompanionLink.Showing, force: Boolean = false) {
        leaderAt = showing
        val library = state.library ?: return
        val song = CompanionLink.songFor(library, showing)
        if (song == null) { missing = showing.title; return }
        val part = state.partFor(song)
        if (part == null) { missing = showing.title; return }
        // Where the library says, a quick look; a file that has moved means searching the whole
        // music folder, which is never done on the screen's own thread.
        val file = state.fileOf(part.file)?.takeIf { it.isFile } ?: run {
            Thread({
                val found = runCatching { state.partFile(song, part) }.getOrNull()
                state.platform.onMain {
                    if (found == null) missing = showing.title
                    else if (leaderAt == showing) show(showing, force)
                }
            }, "companion-find").apply { isDaemon = true; start() }
            return
        }
        missing = null
        val page = pageFor(showing, song, part)
        val now = song.id to page
        val songChanged = lastApplied?.first != song.id
        if (!force && now == lastApplied) return
        lastApplied = now
        // A page turn while this tablet is on Home is left for the banner to offer; a new song
        // is gone to, which is what following means.
        if (!force && !songChanged && state.homeInFront) return
        goingTo = file.absolutePath to System.currentTimeMillis()
        if (state.current?.id == song.id && !state.homeInFront && state.currentPath == file.absolutePath) {
            if (page != null) Perform.jumpTo?.invoke(file.absolutePath, page)
            return
        }
        val start = page ?: ((part.firstPage ?: 1) - 1)
        Perform.requestPage(file.absolutePath, start)
        state.current = song
        state.platform.openPart(song, part, file)
    }

    /** The page to show for what the leader is on, or null to turn one's own pages. */
    private fun pageFor(showing: CompanionLink.Showing, song: Song, part: Part): Int? {
        val mode = when (follow) {
            CompanionLink.Follow.AUTO -> CompanionLink.automaticFollow(showing, song.id, part)
            else -> follow
        }
        return CompanionLink.pageFor(mode, showing.page)
    }

    private fun takeInk(share: CompanionLink.InkShare) {
        val library = state.library ?: return
        val song = CompanionLink.songFor(library, share.songId, share.title) ?: return
        pendingInk[song.id] = share
        applyPendingInk()
    }

    /** Put the leader's marks on the part in front, if they were made on the same part. UI thread. */
    fun applyPendingInk() {
        if (pendingInk.isEmpty() || state.homeInFront) return
        val song = state.current ?: return
        val share = pendingInk[song.id] ?: return
        val part = state.partFor(song) ?: return
        val file = state.fileOf(part.file)?.takeIf { it.isFile } ?: return
        if (state.currentPath != file.absolutePath) return
        if (!CompanionLink.samePart(share.instrument, share.partNo, share.pages, part, state.pageShown.second)) return
        val ink = InkDocument.parse(share.ink) ?: return
        if (Perform.mergeInk?.invoke(file.absolutePath, ink) == true) {
            pendingInk.remove(song.id)
        }
    }

    private fun partNo(part: Part): String? = CompanionLink.partNumber(part.label ?: part.file.substringAfterLast('/'))

    // Last in the class, so everything it touches is already set up: resuming follows a leader,
    // and that reads state declared all through the class.
    internal fun tickBluetooth() {
                val now = connectionClock()
                if (radio?.ready(ask = false) == false) { stopMesh(); meshStatus = "Bluetooth is off or permission is unavailable" }
                if (radio == null && (leading || following != null || discovery)) startMesh(if (leading) state.platform.deviceName else following.orEmpty(), ask = false)
                nearbyBluetooth.removeAll { now - (nearbySeen[it.bluetoothSession] ?: 0L) > 7000 }
                participants.entries.removeAll { now - it.value.first >= 7000 }
                if (leading) { refreshConnectedDevices(); beaconSeq++ }
                if (following != null) {
                    presenceSeq++
                    if (beaconAt > 0) connected = now - beaconAt < 4000 || follower?.connectionStatus == com.inksheets.core.ConnectionStatus.CONNECTED
                }
                rebroadcast()
    }

    init {
        reconnectCheck.schedule(object : java.util.TimerTask() {
            override fun run() = state.platform.onMain {
                tickBluetooth()
            }
        }, 1000L, 1000L)
        // Following when the app was closed, not long ago: carry on, so a tablet that restarted
        // mid-rehearsal is back with the band without anyone touching it.
        val since = state.platform.pref(K_FOLLOWING_SINCE)?.toLongOrNull()
        val last = lastLeader
        if (since != null && last != null && System.currentTimeMillis() - since < RESUME_WITHIN_MS) {
            followLeader(last) { }
        }
    }

    companion object {
        private const val K_FOLLOW = "sheets_companion_follow"
        private const val K_SHARE_INK = "sheets_companion_share_ink"
        private const val K_MESH = "sheets_companion_bluetooth"
        private const val K_LAST = "sheets_companion_last"
        private const val K_FOLLOWING_SINCE = "sheets_companion_following_since"
        private const val RESUME_WITHIN_MS = 3L * 60 * 60 * 1000
    }
}

/** A QR code, drawn square on white with the border scanners need. */
@Composable
internal fun QrImage(text: String, size: Dp, modifier: Modifier = Modifier) {
    val matrix = remember(text) { runCatching { QrCodes.encode(text) }.getOrNull() } ?: return
    Canvas(
        modifier
            .size(size)
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(size / (matrix.size + 8) * 4)
    ) {
        val cell = this.size.minDimension / matrix.size
        for (y in 0 until matrix.size) for (x in 0 until matrix.size) {
            if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.6f, cell + 0.6f))
        }
    }
}

/**
 * A code to scan, small beside its words so it covers nothing; a tap shows it big, over everything,
 * for the other device's camera - and another tap puts it back.
 */
@Composable
internal fun ScanCode(text: String, size: Dp = 128.dp, modifier: Modifier = Modifier) {
    var big by remember { mutableStateOf(false) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        QrImage(text, size, Modifier.clickable { big = true })
        Text("Tap to enlarge", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (big) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { big = false }) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.clickable { big = false }) {
                QrImage(text, minOf(maxWidth, maxHeight, 420.dp))
            }
        }
    }
}

/** A code's picture as a PNG file, for copying or sharing; null where one cannot be written. */
internal fun qrPicture(state: SheetsState, text: String): File? {
    val (w, h, px) = QrCodes.pixels(text, scale = 10)
    val file = File(state.platform.cacheFolder, "InkSheets join code.png")
    return if (state.platform.writePng(w, h, px, file)) file else null
}

@Composable
private fun TogetherDeviceRow(name: String, detail: String, bluetooth: Boolean, action: String, onAction: () -> Unit) {
    val accent = if (bluetooth) Color(0xFF8054AD) else MaterialTheme.colorScheme.primary
    Surface(color = accent.copy(alpha = 0.07f), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = accent.copy(alpha = 0.13f), shape = RoundedCornerShape(10.dp)) {
                Icon(if (bluetooth) Icons.Default.Bluetooth else Icons.Default.Devices, null, tint = accent, modifier = Modifier.padding(10.dp).size(24.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onAction) { Text(action) }
        }
    }
}

/** Roles and real nearby devices stay visible; detail controls keep their existing behavior. */
@Composable
internal fun CompanionDialog(state: SheetsState, onClose: () -> Unit) {
    val companion = state.companion
    var problem by remember { mutableStateOf<String?>(null) }
    var said by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    val found = remember { mutableStateListOf<CompanionLink.Leader>() }
    DisposableEffect(companion.transport) {
        companion.discoverBluetooth()
        val scanner = CompanionScanner { leader -> state.platform.onMain {
            if (leader.host !in com.inksheets.core.RemoteClient.localAddresses() && found.none { it.host == leader.host && it.port == leader.port }) found += leader
        } }
        if (companion.transport != ConnectionTransport.BLUETOOTH) scanner.start()
        onDispose { scanner.stop(); companion.endDiscovery() }
    }
    fun join(leader: CompanionLink.Leader) {
        problem = null
        companion.followLeader(leader) { ok ->
            if (ok) onClose() else problem = "Still trying ${leader.name}. Check the selected connection and keep the leader open."
        }
    }
    androidx.compose.foundation.layout.BoxWithConstraints {
    val compact = maxHeight < 440.dp
    SheetDialog(title = "Play together", onDismiss = onClose, wide = true, movable = true) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp)) {
            if (compact) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { TransportChoice(companion.transport, state.platform.meshRadio() != null, companion::chooseTransport) }
                when {
                    companion.leading -> OutlinedButton(onClick = companion::stopLeading) { Text("Stop leading") }
                    companion.following != null -> OutlinedButton(onClick = companion::stopFollowing) { Text("Stop following") }
                    else -> Button(onClick = { problem = if (companion.lead()) null else "Could not start leading." }) { Text("Lead") }
                }
            }
            if (compact && companion.transport != ConnectionTransport.WIFI) companion.meshStatus?.let { status ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Bluetooth · $status", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    if (status != "On" && state.platform.meshRadio() != null) TextButton(onClick = companion::retryBluetooth) { Text("Retry Bluetooth") }
                }
            }
            if (!compact) Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .5f), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.Groups, null, tint = MaterialTheme.colorScheme.primary)
                        Text("One group. Everyone's own part.", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    }
                    Text("Lead the music, follow another stand, or connect a remote.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TransportChoice(companion.transport, state.platform.meshRadio() != null, companion::chooseTransport)
                    companion.meshStatus?.let { status ->
                        Text("Bluetooth · $status", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        if (status != "On" && companion.transport != ConnectionTransport.WIFI && state.platform.meshRadio() != null) {
                            TextButton(onClick = companion::retryBluetooth) { Text("Retry Bluetooth") }
                        }
                    }
                }
            }
            when {
                companion.leading -> LeadingSection(state, onSaid = { said = it }, showStop = !compact)
                companion.following != null -> FollowingSection(state, onClose, showStop = !compact)
                else -> {
                    if (!compact) TogetherDeviceRow(state.platform.deviceName, "This device · lead the song and page", false, "Lead") {
                        problem = if (companion.lead()) null else "Could not start leading. Try Bluetooth only or check the network port."
                    }
                    Text("Find your group", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(value = search, onValueChange = { search = it }, singleLine = true,
                        label = { Text("Search nearby devices") }, leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth())
                    val nearby = (companion.nearbyBluetooth + found.filter { companion.transport != ConnectionTransport.BLUETOOTH })
                        .filter { it.name.contains(search, ignoreCase = true) }
                    if (nearby.isEmpty()) {
                        Text(if (search.isBlank()) "Looking for leaders nearby…" else "No devices match “$search”.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    nearby.forEach { leader ->
                        val bt = leader.hosts.isEmpty() && leader.bluetoothSession != null
                        TogetherDeviceRow(leader.name, if (bt) "Bluetooth · no Wi-Fi needed" else "Wi-Fi / network · nearby leader", bt, "Follow") { join(leader) }
                    }
                    companion.lastLeader?.let { last ->
                        TogetherDeviceRow(last.name, "Last group · reconnect", last.hosts.isEmpty(), "Follow again") { join(last) }
                    }
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.platform.canScanQr) OutlinedButton(onClick = {
                            state.platform.scanQr { text -> if (text != null) {
                                CompanionLink.parseJoin(text)?.let(::join) ?: run { problem = "That code isn't an InkSheets code." }
                            } }
                        }) { Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(6.dp)); Text("Scan code") }
                        OutlinedButton(onClick = {
                            val leader = state.platform.readClipboard()?.let(CompanionLink::parseJoin)
                            if (leader != null) join(leader) else problem = "Copy the leader's InkSheets code first."
                        }) { Icon(Icons.Default.ContentPaste, null); Spacer(Modifier.width(6.dp)); Text("Paste code") }
                    }
                    TypedAddress(onJoin = ::join)
                    FollowModes(companion)
                }
            }
            said?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.SettingsRemote, null, tint = Color(0xFF28775B))
                Text("Your remote", style = MaterialTheme.typography.titleMedium)
            }
            HostSection(state)
        }
    }
}
}

@Composable
private fun LeadingSection(state: SheetsState, onSaid: (String) -> Unit, showStop: Boolean = true) {
    val companion = state.companion
    val link = companion.joinLink
    // Side by side where there is room; on a phone the code above, everything else below it.
    androidx.compose.foundation.layout.BoxWithConstraints {
        val narrow = maxWidth < 460.dp
        val codeSize = minOf(maxWidth, 240.dp)
        val details: @Composable () -> Unit = {
            Column {
                Text("Scan to follow ${state.platform.deviceName}", style = MaterialTheme.typography.titleMedium)
                Text(
                    when (companion.followers) { 0 -> "Nobody following yet"; 1 -> "1 following"; else -> "${companion.followers} following" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                Text(
                    "Scan this code from the other device, or choose this stand in Nearby.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (link != null) {
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        OutlinedButton(onClick = {
                            val png = qrPicture(state, link)
                            onSaid(if (png != null && state.platform.copyImage(png)) "Copied - paste it into a message." else "Couldn't copy the picture here; use Share.")
                        }) {
                            Icon(Icons.Default.ContentCopy, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Copy picture")
                        }
                        OutlinedButton(onClick = { qrPicture(state, link)?.let { state.platform.shareImage(it) } }) {
                            Icon(Icons.Default.Share, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Share")
                        }
                    }
                }
            }
        }
        // Beside the words, small: the code never pushes them away. Tapped, it shows big to scan.
        if (narrow) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                details()
                if (link != null) ScanCode(link, 128.dp)
            }
        } else Row(verticalAlignment = Alignment.Top) {
            if (link != null) ScanCode(link, 136.dp)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) { details() }
        }
    }
    if (link != null) {
        SelectionContainer {
            Text(link, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        }
    }
    if (NetAddresses.mine().isEmpty() && companion.transport != ConnectionTransport.BLUETOOTH) {
        Text("No Wi-Fi address. Bluetooth discovery remains available when enabled.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    companion.connectedDevices.forEach { peer ->
        val green = peer.status == com.inksheets.core.ConnectionStatus.CONNECTED
        Surface(color = (if (green) Color(0xFF28775B) else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = .07f),
            shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Devices, null, tint = if (green) Color(0xFF28775B) else MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text(peer.name, style = MaterialTheme.typography.titleSmall)
                    Text("${peer.transport} · ${peer.status.name.lowercase().replaceFirstChar { it.uppercase() }} · heartbeat ${((System.currentTimeMillis() - peer.lastHeard).coerceAtLeast(0) / 1000)}s ago",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Row(
        Modifier.fillMaxWidth().clickable { companion.shareInk = !companion.shareInk }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Share my markings with players on my part")
            Text(
                "Goes to followers whose part is the same instrument, number and length as yours.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = companion.shareInk, onCheckedChange = { companion.shareInk = it })
    }
    NetworkHint(state)
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    SendNote(state, onSaid)
    if (showStop) OutlinedButton(onClick = { companion.stopLeading() }, modifier = Modifier.padding(top = 8.dp)) { Text("Stop leading") }
}

@Composable
private fun FollowingSection(state: SheetsState, onClose: () -> Unit, showStop: Boolean = true) {
    val companion = state.companion
    NetworkHint(state)
    Text(
        "Following ${companion.following}",
        style = MaterialTheme.typography.titleMedium
    )
    Text(companion.connectionDetail, style = MaterialTheme.typography.bodySmall, color = if (companion.connected) Color(0xFF28775B) else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
    LeaderWhere(state, onGo = onClose)
    FollowModes(companion)
    if (showStop) OutlinedButton(onClick = { companion.stopFollowing() }, modifier = Modifier.padding(top = 8.dp)) { Text("Stop following") }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FollowModes(companion: Companion) {
    Text("When the leader moves", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CompanionLink.Follow.entries.forEach { mode ->
            androidx.compose.material3.FilterChip(selected = companion.follow == mode, onClick = { companion.follow = mode }, label = { Text(mode.label) })
        }
    }
}
@Composable
private fun TypedAddress(onJoin: (CompanionLink.Leader) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    if (!open) {
        TextButton(onClick = { open = true }) { Text("Type an address instead") }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        OutlinedTextField(
            value = address, onValueChange = { address = it.trim() },
            label = { Text("Address (a Tailscale one works)") },
            singleLine = true, modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.size(8.dp))
        OutlinedButton(onClick = { CompanionLink.parseJoin(address)?.let(onJoin) }) { Text("Follow") }
    }
}

/** Where the leader is, and the way there. */
@Composable
private fun LeaderWhere(state: SheetsState, onGo: () -> Unit = {}) {
    val companion = state.companion
    val at = companion.leaderAt
    when {
        at == null -> Text(
            if (companion.connected) "Waiting for the leader to open a song." else "Waiting for the leader...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        companion.missing != null -> Text(
            "They're on \"${companion.missing}\", which isn't in your library. Ask them to share the setlist.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
        else -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Text("On ${at.title ?: "a song"}, page ${at.page + 1}", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (companion.offLeader) Button(onClick = { companion.goToLeader(); onGo() }) { Text("Go there") }
        }
    }
}

/** On Home while following: where the leader is, and one tap to go there. */
@Composable
internal fun FollowBanner(state: SheetsState) {
    val companion = state.companion
    if (companion.following == null) return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Following ${companion.following}",
                    style = MaterialTheme.typography.labelLarge
                )
                LeaderWhere(state)
            }
            TextButton(onClick = { companion.stopFollowing() }) { Text("Stop") }
        }
    }
}

/** Over a song while following, when this tablet has wandered off: the way back. */
@Composable
internal fun BackToLeader(state: SheetsState, modifier: Modifier = Modifier) {
    val companion = state.companion
    if (companion.following == null || !companion.offLeader) return
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primary,
        shadowElevation = 4.dp,
        modifier = modifier.clickable { companion.goToLeader() }
    ) {
        Text(
            "Go to ${companion.following}: ${companion.leaderAt?.title ?: ""}",
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

/**
 * The network is cutting devices off from each other (school Wi-Fi, eduroam): what to do instead.
 * Only once it has been seen happening, and only here, never over the music.
 */
@Composable
internal fun NetworkHint(state: SheetsState) {
    if (!state.companion.networkBlocks) return
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("This Wi-Fi keeps cutting the link", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                "School and eduroam Wi-Fi stops devices reaching each other. Use Bluetooth here, or put everyone on one hotspot.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            // The answer where the problem shows: Bluetooth on in one tap (on every device playing together).
            val companion = state.companion
            if (state.platform.meshRadio() != null && !companion.meshOn) {
                androidx.compose.material3.TextButton(onClick = { companion.meshOn = true }) { Text("Use Bluetooth as well") }
            } else if (companion.meshOn) {
                Text(companion.meshStatus ?: "Bluetooth on", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}
