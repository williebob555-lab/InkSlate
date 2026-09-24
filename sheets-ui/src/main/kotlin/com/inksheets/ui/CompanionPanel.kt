package com.inksheets.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.inkslate.core.Perform
import com.inksheets.core.CompanionFollower
import com.inksheets.core.CompanionLeader
import com.inksheets.core.CompanionLink
import com.inksheets.core.CompanionScanner
import com.inksheets.core.PartChoice

/**
 * Companion mode's state: leading, following, or neither. One per app, like [SheetsState].
 */
class Companion(private val state: SheetsState) {

    var leading by mutableStateOf(false)
        private set
    var followers by mutableStateOf(0)
        private set
    var following by mutableStateOf<String?>(null)
        private set
    var follow by mutableStateOf(CompanionLink.Follow.SAME_PAGE)

    private var leader: CompanionLeader? = null
    private var follower: CompanionFollower? = null

    /** What the strip shows: "Leading, 2 following", "Following Stand 1". */
    val status: String?
        get() = when {
            leading -> "Leading" + if (followers > 0) " · $followers" else ""
            following != null -> "Following $following"
            else -> null
        }

    fun lead(): Boolean {
        stopFollowing()
        val l = CompanionLeader(state.platform.deviceName)
        l.onFollowers = { n -> state.platform.onMain { followers = n } }
        if (!l.start()) return false
        leader = l
        leading = true
        state.current?.let { l.show(CompanionLink.Showing(songId = it.id, title = it.title, page = 0)) }
        return true
    }

    /** The song in front turned to [page] (or was just opened): tell anyone following. */
    fun pageTurned(page: Int) {
        val l = leader ?: return
        val song = state.current ?: return
        l.show(CompanionLink.Showing(songId = song.id, title = song.title, page = page))
    }

    fun stopLeading() {
        leader?.stop()
        leader = null
        leading = false
        followers = 0
    }

    /** Connect in the background; [done] hears whether it worked, on the UI thread. */
    fun followLeader(host: String, port: Int = CompanionLink.PORT, name: String = host, done: (Boolean) -> Unit) {
        stopLeading()
        val f = CompanionFollower { showing -> state.platform.onMain { show(showing) } }
        f.onDisconnected = { state.platform.onMain { following = null } }
        Thread({
            val ok = f.connect(host, port)
            state.platform.onMain {
                if (ok) {
                    follower?.disconnect()
                    follower = f
                    following = name
                }
                done(ok)
            }
        }, "companion-connect").apply { isDaemon = true; start() }
    }

    fun stopFollowing() {
        follower?.disconnect()
        follower = null
        following = null
    }

    /** Go where the leader is, as this tablet follows it. */
    private fun show(showing: CompanionLink.Showing) {
        val library = state.library ?: return
        val song = CompanionLink.songFor(library, showing) ?: return
        val part = PartChoice.partFor(song, state.profile) ?: return
        val file = state.partFile(song, part) ?: return
        val page = CompanionLink.pageFor(follow, showing.page)
        if (state.current?.id == song.id) {
            if (page != null) Perform.jumpTo?.invoke(file.absolutePath, page)
            return
        }
        // A new song: open it at the right page (or its own start, for a bandmate).
        val start = page ?: ((part.firstPage ?: 1) - 1)
        Perform.requestPage(file.absolutePath, start)
        state.current = song
        state.platform.openPart(song, part, file)
    }
}

/** Leading and following, and how to follow. */
@Composable
internal fun CompanionDialog(state: SheetsState, onClose: () -> Unit) {
    val companion = state.companion
    var address by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    val found = remember { mutableStateListOf<CompanionLink.Leader>() }

    DisposableEffect(Unit) {
        val scanner = CompanionScanner { leader ->
            state.platform.onMain {
                if (found.none { it.host == leader.host && it.port == leader.port }) found += leader
            }
        }
        scanner.start()
        onDispose { scanner.stop() }
    }

    SheetDialog(title = "Companion", onDismiss = onClose, wide = true) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Lead", style = MaterialTheme.typography.titleMedium)
            Text(
                "Other tablets follow the song and page this one is on. Everything you write stays " +
                    "on your own part; only where you are is shared.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                if (companion.leading) {
                    Text("Leading as ${state.platform.deviceName} · ${companion.followers} following", modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { companion.stopLeading() }) { Text("Stop") }
                } else {
                    Button(onClick = {
                        problem = if (companion.lead()) null else "Could not start leading. Is another app using port ${CompanionLink.PORT}?"
                    }) { Text("Lead from this tablet") }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Follow", style = MaterialTheme.typography.titleMedium)
            CompanionLink.Follow.entries.forEach { mode ->
                Row(
                    Modifier.fillMaxWidth().clickable { companion.follow = mode },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = companion.follow == mode, onClick = { companion.follow = mode })
                    Text(mode.label)
                }
            }
            if (companion.following != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                    Text("Following ${companion.following}", modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { companion.stopFollowing() }) { Text("Stop") }
                }
            }
            Text("Leaders on this network", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
            if (found.isEmpty()) {
                Text("Looking...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            found.forEach { leader ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("${leader.name}  (${leader.host})", modifier = Modifier.weight(1f))
                    Button(onClick = {
                        companion.followLeader(leader.host, leader.port, leader.name) { ok ->
                            problem = if (ok) null else "Could not reach ${leader.name}."
                        }
                    }) { Text("Follow") }
                }
            }
            Spacer(Modifier.size(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = address, onValueChange = { address = it.trim() },
                    label = { Text("Or an address (a Tailscale one works)") },
                    singleLine = true, modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.size(8.dp))
                OutlinedButton(onClick = {
                    if (address.isNotEmpty()) companion.followLeader(address) { ok ->
                        problem = if (ok) null else "Nothing answered at $address."
                    }
                }) { Text("Follow") }
            }
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}
