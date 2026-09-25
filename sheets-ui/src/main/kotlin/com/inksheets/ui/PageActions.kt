package com.inksheets.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * What the Pages panel offers for music: the picked pages of a band pack as one instrument's
 * part - of this song, or of a new one when the pack holds another piece too.
 */
@Composable
fun MusicPageActions(state: SheetsState, path: String, pages: List<Int>, close: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    TextButton(onClick = { asking = true }) { Text("Make a part") }
    if (!asking) return

    val song = remember(path, state.version) { state.songAt(path) }
    var instrument by remember { mutableStateOf<String?>(null) }
    var newSong by remember { mutableStateOf(song == null) }
    var title by remember { mutableStateOf(song?.title ?: File(path).nameWithoutExtension) }
    val first = pages.min() + 1
    val last = pages.max() + 1
    SheetDialog(
        title = if (first == last) "Page $first as a part" else "Pages $first-$last as a part",
        onDismiss = { asking = false },
        buttons = {
            TextButton(onClick = { asking = false }) { Text("Cancel") }
            TextButton(enabled = !newSong || title.isNotBlank(), onClick = {
                state.partFromPages(path, pages, instrument, if (newSong) title.trim() else null)
                asking = false
                close()
            }) { Text("Make it") }
        }
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("For", Modifier.padding(end = 8.dp))
                InstrumentPicker(instrument) { instrument = it }
            }
            if (song != null) {
                Row(Modifier.fillMaxWidth().clickable { newSong = false }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !newSong, onClick = { newSong = false })
                    Text("A part of “${song.title}”")
                }
            }
            Row(Modifier.fillMaxWidth().clickable { newSong = true }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = newSong, onClick = { newSong = true })
                Text("A new song")
            }
            if (newSong) {
                OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true, label = { Text("Its title") }, modifier = Modifier.fillMaxWidth())
            }
            Text(
                "The pages stay where they are in the file; the part is those pages of it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
