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

    init {
        // Following when the app was closed, not long ago: carry on, so a tablet that restarted
        // mid-rehearsal is back with the band without anyone touching it.
        val since = state.platform.pref(K_FOLLOWING_SINCE)?.toLongOrNull()
        val last = lastLeader
        if (since != null && last != null && System.currentTimeMillis() - since < RESUME_WITHIN_MS) {
            followLeader(last) { }
        }
    }

    // ---- leading ----------------------------------------------------------------------

    /** The leader's message showing now, and a count that changes with each so a repeat shows again. */
    var notice by mutableStateOf<CompanionLink.Note?>(null)
    var noticeCount by mutableStateOf(0)

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
        l.note(CompanionLink.Note(text = text.trim(), instruments = instruments, urgent = urgent, color = if (urgent) color else null))
    }

    fun lead(): Boolean {
        stopFollowing()
        val name = state.platform.deviceName
        val l = CompanionLeader(name, leadPort)
        l.onFollowers = { n -> state.platform.onMain { followers = n } }
        l.onLog = { line -> state.platform.log("Companion: $line") }
        if (!l.start()) return false
        leader = l
        leading = true
        state.platform.holdNetwork(true)
        joinLink = CompanionLink.joinLink(name, NetAddresses.mine(), leadPort)
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
        val part = state.partFor(song)
        l.show(
            CompanionLink.Showing(
                songId = song.id, title = song.title, page = page,
                instrument = part?.instrument, partNo = part?.let(::partNo),
                pages = state.pageShown.second, shareInk = shareInk
            )
        )
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
        inkTimer?.cancel()
        inkTimer = null
        leader?.stop()
        if (leader != null) state.platform.log("Companion: stopped leading")
        if (leading) state.platform.holdNetwork(false)
        leader = null
        leading = false
        followers = 0
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
        val f = CompanionFollower(state.platform.deviceName) { line -> state.platform.onMain { heard(line) } }
        f.onLog = { line -> state.platform.log("Companion: $line") }
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
                                if (follower === f && (f.lastHeard < lostAt)) connected = false
                            }
                        }, 2_000L)
                    }
                }
            }
        }
        if (follower == null) state.platform.holdNetwork(true)
        follower = f
        following = target.name
        connected = false
        lastApplied = null
        state.platform.setPref(K_LAST, CompanionLink.joinLink(target.name, target.hosts, target.port))
        state.platform.setPref(K_FOLLOWING_SINCE, System.currentTimeMillis().toString())
        Thread({
            val ok = f.start(target)
            state.platform.onMain { done(ok) }
        }, "companion-connect").apply { isDaemon = true; start() }
    }

    fun stopFollowing() {
        follower?.stop()
        if (follower != null) {
            state.platform.log("Companion: stopped following")
            state.platform.holdNetwork(false)
        }
        follower = null
        following = null
        connected = false
        leaderAt = null
        missing = null
        pendingInk.clear()
        state.platform.setPref(K_FOLLOWING_SINCE, null)
    }

    private fun heard(line: CompanionLink.Line) {
        if (follower == null) return
        when (line) {
            is CompanionLink.Line.Show -> {
                state.platform.setPref(K_FOLLOWING_SINCE, System.currentTimeMillis().toString())
                show(line.showing)
            }
            is CompanionLink.Line.Ink -> takeInk(line.share)
            is CompanionLink.Line.Message -> {
                if (CompanionLink.noteIsFor(line.note, myInstruments())) {
                    notice = line.note
                    noticeCount++
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
            CompanionLink.Follow.AUTO -> when {
                showing.songId == song.id -> CompanionLink.Follow.SAME_PAGE
                showing.instrument != null && showing.instrument == part.instrument && showing.partNo == partNo(part) -> CompanionLink.Follow.SAME_PAGE
                else -> CompanionLink.Follow.SONG_ONLY
            }
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

    companion object {
        private const val K_FOLLOW = "sheets_companion_follow"
        private const val K_SHARE_INK = "sheets_companion_share_ink"
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

/** A code's picture as a PNG file, for copying or sharing; null where one cannot be written. */
internal fun qrPicture(state: SheetsState, text: String): File? {
    val (w, h, px) = QrCodes.pixels(text, scale = 10)
    val file = File(state.platform.cacheFolder, "InkSheets join code.png")
    return if (state.platform.writePng(w, h, px, file)) file else null
}

/** Leading and following, and how to follow. */
@Composable
internal fun CompanionDialog(state: SheetsState, onClose: () -> Unit) {
    val companion = state.companion
    var problem by remember { mutableStateOf<String?>(null) }
    var said by remember { mutableStateOf<String?>(null) }
    val found = remember { mutableStateListOf<CompanionLink.Leader>() }

    DisposableEffect(Unit) {
        val scanner = CompanionScanner { leader ->
            state.platform.onMain {
                if (leader.name != state.platform.deviceName && found.none { it.name == leader.name && it.port == leader.port }) found += leader
            }
        }
        scanner.start()
        onDispose { scanner.stop() }
    }

    fun join(leader: CompanionLink.Leader) {
        problem = null
        companion.followLeader(leader) { ok ->
            if (ok) onClose()
            else problem = "Can't reach ${leader.name} yet - still trying. Both need to be on the same Wi-Fi."
        }
    }

    SheetDialog(title = "Play together", onDismiss = onClose, wide = true) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            when {
                companion.leading -> LeadingSection(state, onSaid = { said = it })
                companion.following != null -> FollowingSection(state, onClose)
                else -> {
                    Text("Lead", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Everyone who follows goes to the song you open. Only where you are is shared, " +
                            "unless you choose to share your markings too.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { problem = if (companion.lead()) null else "Could not start leading. Is another app using port ${CompanionLink.PORT}?" },
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) { Text("Lead from this device") }

                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("Follow", style = MaterialTheme.typography.titleMedium)
                    companion.lastLeader?.let { last ->
                        Button(onClick = { join(last) }, modifier = Modifier.padding(top = 6.dp)) { Text("Follow ${last.name} again") }
                    }
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                        if (state.platform.canScanQr) {
                            Button(onClick = {
                                state.platform.scanQr { text ->
                                    if (text != null) {
                                        CompanionLink.parseJoin(text)?.let(::join) ?: run { problem = "That code isn't an InkSheets code." }
                                    }
                                }
                            }) {
                                Icon(Icons.Default.QrCodeScanner, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Scan the leader's code")
                            }
                        }
                        OutlinedButton(onClick = {
                            val text = state.platform.readClipboard()
                            val leader = text?.let(CompanionLink::parseJoin)
                            if (leader != null) join(leader)
                            else problem = "There's no InkSheets code on the clipboard. Copy the leader's code (the picture or the link) first."
                        }) {
                            Icon(Icons.Default.ContentPaste, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Paste a code")
                        }
                    }
                    Text("Leading nearby", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                    if (found.isEmpty()) {
                        Text("Looking on this Wi-Fi...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    found.forEach { leader ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(leader.name, modifier = Modifier.weight(1f))
                            Button(onClick = { join(leader) }) { Text("Follow") }
                        }
                    }
                    FollowModes(companion)
                    TypedAddress(onJoin = ::join)
                }
            }
            said?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)) }
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}

@Composable
private fun LeadingSection(state: SheetsState, onSaid: (String) -> Unit) {
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
                    "A phone or tablet's own camera works too. Send the picture to a group chat and anyone can join from it.",
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
        if (narrow) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (link != null) QrImage(link, codeSize)
                Spacer(Modifier.size(12.dp))
                details()
            }
        } else {
            Row(verticalAlignment = Alignment.Top) {
                if (link != null) QrImage(link, 200.dp)
                Spacer(Modifier.width(16.dp))
                Box(Modifier.weight(1f)) { details() }
            }
        }
    }
    if (link != null) {
        SelectionContainer {
            Text(link, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        }
    }
    if (NetAddresses.mine().isEmpty()) {
        Text("This device isn't on a network, so nobody can reach it.", color = MaterialTheme.colorScheme.error)
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
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    SendNote(state, onSaid)
    OutlinedButton(onClick = { companion.stopLeading() }, modifier = Modifier.padding(top = 8.dp)) { Text("Stop leading") }
}

@Composable
private fun FollowingSection(state: SheetsState, onClose: () -> Unit) {
    val companion = state.companion
    Text(
        "Following ${companion.following}" + if (companion.connected) "" else " - reconnecting...",
        style = MaterialTheme.typography.titleMedium
    )
    LeaderWhere(state, onGo = onClose)
    FollowModes(companion)
    OutlinedButton(onClick = { companion.stopFollowing() }, modifier = Modifier.padding(top = 8.dp)) { Text("Stop following") }
}

@Composable
private fun FollowModes(companion: Companion) {
    Text("Show me", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 10.dp))
    CompanionLink.Follow.entries.forEach { mode ->
        Row(
            Modifier.fillMaxWidth().clickable { companion.follow = mode },
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = companion.follow == mode, onClick = { companion.follow = mode })
            Text(mode.label, style = MaterialTheme.typography.bodyMedium)
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
                    "Following ${companion.following}" + if (companion.connected) "" else " - reconnecting...",
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
