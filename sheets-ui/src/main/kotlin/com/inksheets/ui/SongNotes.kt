package com.inksheets.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.inksheets.core.Song

/**
 * A song's notes - "Solo at D, take the repeat", "Mute in bar 40" - written from a song's menu
 * or from More over the music. Kept with the song, so every one of your devices has them, and
 * shown beside the song's name in the lists.
 */
@Composable
internal fun NotesDialog(state: SheetsState, song: Song, onClose: () -> Unit) {
    var text by remember(song.id) { mutableStateOf(song.notes.orEmpty()) }
    SheetDialog(
        title = "Notes - ${song.title}",
        onDismiss = onClose,
        buttons = {
            if (song.notes != null) TextButton(onClick = { state.setNotes(song.id, ""); onClose() }) {
                Text("Clear", color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = onClose) { Text("Cancel") }
            TextButton(onClick = { state.setNotes(song.id, text); onClose() }) { Text("Save") }
        }
    ) {
        Column {
            Text(
                "Shown beside the song's name in your lists. Only on your own devices.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Notes") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
        }
    }
}
