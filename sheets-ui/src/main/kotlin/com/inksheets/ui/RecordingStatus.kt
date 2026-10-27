package com.inksheets.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** A recoverable failure remains visible where Play was pressed, including pedal-triggered failures. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlaybackProblem(state: SheetsState) {
    val issue = Recording.issue?.takeIf { it.root == state.root?.absolutePath } ?: return
    val song = issue.songId?.let { state.library?.song(it) } ?: state.current
    var locating by remember(issue) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ErrorOutline, null, Modifier.size(20.dp))
                Text(issue.message + " · " + (issue.track.label ?: issue.track.file.substringAfterLast('/')),
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                IconButton(onClick = { Recording.dismissIssue() }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Close, "Dismiss recording error") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { Recording.retry(state) }) { Text("Retry playback") }
                if (song != null) {
                    TextButton(onClick = { locating = true }) { Text("Locate recording") }
                    TextButton(onClick = { state.current = song; state.audioOpen = true; Recording.dismissIssue() }) { Text("Choose another recording") }
                }
            }
        }
    }
    if (locating && song != null) AudioFilePicker(state, song,
        onChosen = { replacement -> Recording.locate(state, replacement); locating = false },
        onDismiss = { locating = false })
}

@Composable
internal fun RecoveredRecordingNotice(state: SheetsState) {
    val songs = state.recoveredRecordingSongs.mapNotNull { state.library?.song(it) }
    if (songs.isEmpty()) return
    var reviewing by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Restore, null, Modifier.size(20.dp))
            Text("Recovered an interrupted recording", style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            TextButton(onClick = { reviewing = true }) { Text("Review take") }
            IconButton(onClick = { state.recoveredRecordingSongs = emptyList() }) { Icon(Icons.Default.Close, "Dismiss recovered recording notice") }
        }
    }
    if (reviewing) AudioDialog(state, songs.first()) {
        reviewing = false
        state.recoveredRecordingSongs = state.recoveredRecordingSongs - songs.first().id
    }
}
