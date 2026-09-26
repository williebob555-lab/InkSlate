package com.inksheets.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** InkSheets' part of Settings: the library's health, and things done once. */
@Composable
fun SheetsSettings(state: SheetsState) {
    var importing by remember { mutableStateOf(false) }
    Heading("Library")
    TextButton(
        onClick = { importing = true },
        enabled = state.library != null,
        modifier = Modifier.padding(horizontal = 12.dp)
    ) { Text("Import from MobileSheets...") }
    if (importing) MobileSheetsDialog(state, onClose = { importing = false })
    if (state.library != null) LibraryHealth(state)
}

@Composable
private fun Heading(text: String) = Text(
    text,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    fontWeight = FontWeight.SemiBold,
    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp)
)

/**
 * What the folder scan has been doing, and the things it will not decide alone: files that seem
 * to be missing, and the Trash.
 */
@Composable
private fun LibraryHealth(state: SheetsState) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var missing by remember { mutableStateOf<List<Pair<com.inksheets.core.Song, com.inksheets.core.Part>>>(emptyList()) }
    var trash by remember { mutableStateOf<List<com.inksheets.core.LibraryTrash.Entry>>(emptyList()) }
    var confirmMissing by remember { mutableStateOf(false) }
    var allHistory by remember { mutableStateOf(false) }

    suspend fun look() {
        missing = withContext(Dispatchers.IO) { state.missingParts() }
        trash = withContext(Dispatchers.IO) { state.trash()?.entries().orEmpty() }
    }
    LaunchedEffect(state.version) { look() }

    Heading("Library health")
    Column(Modifier.padding(horizontal = 16.dp)) {
        ReassignRow(state)
        val lib = state.library
        val songs = lib?.songs.orEmpty()
        Text(
            "${songs.size} songs, ${songs.sumOf { it.parts.size }} parts. The music folder is checked every few " +
                "seconds; what is added, moved or deleted there - on any device - is followed here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // Whose changes have arrived here. A device missing, or long out of date, means the file
        // sync is not bringing its changes to this one - worth checking before anything else.
        val heard = remember(state.version) { state.devicesHeard() }
        if (heard.isNotEmpty()) {
            Text("Changes received from", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
            heard.forEach { (name, at, me) ->
                Text(
                    "  " + name + (if (me) " (this device)" else "") + " - last change " + howLongAgo(at, "never").lowercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (heard.size == 1) {
                Text(
                    "Only this device's changes are here. If you use InkSheets on others, the file sync is not " +
                        "bringing their changes - check the folder is set to send and receive on each device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch { withContext(Dispatchers.IO) { state.scanFolder() }; look(); busy = false }
            }) { Text(if (busy) "Checking..." else "Check the folder now") }
            state.lastScan?.let { r ->
                Text(
                    if (r.changed) "Last check: ${r.added.size} added, ${r.moved.size} followed, ${r.removed.size} removed, ${r.merged.size} put together"
                    else "Last check: nothing to change",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Held back: too much gone at once to be sure it was meant.
        state.lastScan?.heldBack?.takeIf { it > 0 }?.let { n ->
            Text(
                "$n parts' files disappeared at once, so nothing was removed. If the music folder is all " +
                    "there and they really were deleted, remove them.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = {
                scope.launch { withContext(Dispatchers.IO) { state.scanFolder(allowMassRemoval = true) }; look() }
            }) { Text("Remove those $n") }
        }

        if (missing.isNotEmpty()) {
            Text(
                "${missing.size} parts point at files that are not on this device - left behind by an older " +
                    "version, or still on their way from another device.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp)
            )
            missing.take(5).forEach { (s, p) ->
                Text("  ${s.title}: ${p.file}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (missing.size > 5) Text("  and ${missing.size - 5} more", style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = {
                if (!confirmMissing) { confirmMissing = true; return@TextButton }
                confirmMissing = false
                scope.launch { withContext(Dispatchers.IO) { state.removeMissing() }; look() }
            }) { Text(if (confirmMissing) "Tap again to remove them from the library" else "Remove missing (${missing.size})") }
        }

        if (trash.isNotEmpty()) {
            Text("Trash", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            Text(
                "Removed songs stay here for ${com.inksheets.core.LibraryTrash.KEEP_DAYS} days, on every device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            trash.forEach { e ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(howLongAgo(e.removedAt, "Just now"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { state.restore(e) }; look() } }) { Text("Restore") }
                }
            }
        }

        if (state.scanHistory.isNotEmpty()) {
            Text("Recent changes", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            val shown = if (allHistory) state.scanHistory else state.scanHistory.take(8)
            shown.forEach { line ->
                Text(line, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (state.scanHistory.size > 8) {
                TextButton(onClick = { allHistory = !allHistory }) { Text(if (allHistory) "Show fewer" else "Show all ${state.scanHistory.size}") }
            }
        }
    }
}
