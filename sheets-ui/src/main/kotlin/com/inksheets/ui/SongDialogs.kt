package com.inksheets.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inksheets.core.InstrumentSource
import com.inksheets.core.Instruments
import com.inksheets.core.Part
import com.inksheets.core.Song

/** A song's details, and which instrument each of its parts is for. */
@Composable
internal fun SongEditorDialog(state: SheetsState, song: Song, onClose: () -> Unit) {
    var title by remember { mutableStateOf(song.title) }
    var composers by remember { mutableStateOf(song.composers.joinToString(", ")) }
    var arrangers by remember { mutableStateOf(song.arrangers.joinToString(", ")) }
    var key by remember { mutableStateOf(song.key.orEmpty()) }
    var time by remember { mutableStateOf(song.timeSignature.orEmpty()) }
    var tempo by remember { mutableStateOf(song.tempo?.toString().orEmpty()) }
    var tags by remember { mutableStateOf((song.genres + song.tags).joinToString(", ")) }
    val parts = remember { mutableStateListOf(*song.parts.toTypedArray()) }

    fun list(text: String) = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    SheetDialog(
        title = "Song",
        onDismiss = onClose,
        wide = true,
        buttons = {
            TextButton(onClick = onClose) { Text("Cancel") }
            TextButton(onClick = {
                state.change {
                    editSong(song.id) {
                        this.title = title.trim().ifEmpty { song.title }
                        this.composers = list(composers)
                        this.arrangers = list(arrangers)
                        this.key = key.trim().ifEmpty { null }
                        this.timeSignature = time.trim().ifEmpty { null }
                        this.tempo = tempo.trim().toIntOrNull()
                        this.tags = list(tags)
                        this.parts = parts.toList()
                    }
                }
                onClose()
            }) { Text("Save") }
        }
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Field("Title", title) { title = it }
            Field("Composer", composers) { composers = it }
            Field("Arranger", arrangers) { arrangers = it }
            Row {
                Box(Modifier.weight(1f)) { Field("Key", key) { key = it } }
                Spacer(Modifier.size(8.dp))
                Box(Modifier.weight(1f)) { Field("Time", time) { time = it } }
                Spacer(Modifier.size(8.dp))
                Box(Modifier.weight(1f)) { Field("Tempo", tempo) { tempo = it.filter(Char::isDigit) } }
            }
            Field("Tags and genres", tags) { tags = it }

            Spacer(Modifier.size(12.dp))
            Text("Parts", style = MaterialTheme.typography.titleMedium)
            parts.forEachIndexed { i, part ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(part.file.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val how = when (part.source) {
                            InstrumentSource.TEXT, InstrumentSource.OCR -> "read from the page: ${part.label.orEmpty()}"
                            InstrumentSource.FILE_NAME -> "read from the file name"
                            InstrumentSource.PERSON -> "set by you"
                            InstrumentSource.UNKNOWN -> "not known"
                        }
                        Text(how, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    InstrumentPicker(part.instrument) { chosen ->
                        parts[i] = part.copy(instrument = chosen, source = InstrumentSource.PERSON)
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    )
}

@Composable
internal fun InstrumentPicker(current: String?, onChosen: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(current?.let { Instruments.byId[it]?.name } ?: "Which instrument?")
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Not known") }, onClick = { open = false; onChosen(null) })
            Instruments.all.forEach { inst ->
                DropdownMenuItem(text = { Text(inst.name) }, onClick = { open = false; onChosen(inst.id) })
            }
        }
    }
}

/** Pick songs to add to a setlist, filtered to the instrument being played. */
@Composable
internal fun SongChooserDialog(state: SheetsState, onChosen: (List<Song>) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<String>() }
    val all = remember(state.version, state.profileId) {
        com.inksheets.core.PartChoice.songsFor(state.library?.songs.orEmpty(), state.profile).map { it.first }
    }
    val shown = all.filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
    SheetDialog(
        title = "Add songs",
        onDismiss = onDismiss,
        buttons = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            TextButton(onClick = { onChosen(all.filter { it.id in picked }) }, enabled = picked.isNotEmpty()) {
                Text(if (picked.isEmpty()) "Add" else "Add ${picked.size}")
            }
        }
    ) {
        Column {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(shown, key = { it.id }) { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { if (s.id in picked) picked.remove(s.id) else picked.add(s.id) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = s.id in picked, onCheckedChange = null)
                        Text(s.title, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

/** Parts of [song] the person could switch to, for a quick change of instrument inside a song. */
internal fun Song.partsFor(profileInstruments: List<String>?): List<Part> =
    if (profileInstruments == null) parts else parts.filter { it.instrument == null || it.instrument in profileInstruments }
