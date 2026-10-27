package com.inksheets.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.inksheets.core.Bookmark
import com.inksheets.core.Instruments
import com.inksheets.core.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun validBookmarkRange(first: Int?, last: Int?, minimum: Int = 1, maximum: Int? = null): Boolean =
    first != null && first >= minimum && (last == null || last >= first) &&
        (maximum == null || first <= maximum && (last == null || last <= maximum))

/** Apply changed fields to the current bookmark, preserving concurrent colour/order changes. */
internal fun saveBookmarkDraft(state: SheetsState, songId: String, original: Bookmark?, draft: Bookmark) {
    state.change {
        val currentSong = song(songId) ?: return@change
        val current = original?.let { before -> currentSong.bookmarks.firstOrNull { it.samePlace(before) } }
        // A bookmark removed on another device while the editor was open stays removed.
        if (original != null && current == null) return@change
        val result = if (original == null) draft else current!!.copy(
            label = if (draft.label != original.label) draft.label else current.label,
            part = if (draft.part != original.part) draft.part else current.part,
            page = if (draft.page != original.page) draft.page else current.page,
            excerptTitle = if (draft.excerptTitle != original.excerptTitle) draft.excerptTitle else current.excerptTitle,
            endPage = if (draft.endPage != original.endPage) draft.endPage else current.endPage,
            reminder = if (draft.reminder != original.reminder) draft.reminder else current.reminder,
            notes = if (draft.notes != original.notes) draft.notes else current.notes,
            color = if (draft.color != original.color) draft.color else current.color
        )
        editSong(songId) {
            bookmarks = if (original == null) currentSong.bookmarks + result
                else currentSong.bookmarks.map { if (it.samePlace(original)) result else it }
        }
    }
}

/** A piece inside a book owns its title, reminder and notes; saving never edits the book itself. */
@Composable
internal fun BookmarkEditorDialog(state: SheetsState, song: Song, original: Bookmark?, onClose: () -> Unit) {
    val fromReader = original == null && state.current?.id == song.id
    val initialPart = if (fromReader) state.partShown() ?: song.parts.firstOrNull() else song.parts.firstOrNull()
    val initialPage = if (fromReader) state.pageShown.first + 1 else initialPart?.firstPage ?: 1
    var name by remember { mutableStateOf(original?.excerptTitle ?: original?.label.orEmpty()) }
    var partId by remember { mutableStateOf(original?.part ?: initialPart?.id) }
    var first by remember { mutableStateOf((original?.page ?: initialPage).toString()) }
    var last by remember { mutableStateOf(original?.endPage?.toString().orEmpty()) }
    var reminder by remember { mutableStateOf(original?.reminder.orEmpty()) }
    var notes by remember { mutableStateOf(original?.notes.orEmpty()) }
    var colour by remember { mutableStateOf(original?.color) }
    var choosingColour by remember { mutableStateOf(false) }
    var pickingPart by remember { mutableStateOf(false) }
    val part = song.parts.firstOrNull { it.id == partId }
    var pageCount by remember(partId) { mutableStateOf<Int?>(null) }
    LaunchedEffect(partId) {
        val file = part?.let { state.partFile(song, it) }
        pageCount = if (file == null) null else withContext(Dispatchers.IO) { runCatching { state.platform.pageCount(file) }.getOrNull() }
    }
    val maximum = listOfNotNull(part?.lastPage, pageCount).minOrNull()
    val minimum = part?.firstPage ?: 1
    val valid = name.isNotBlank() && part != null && (last.isEmpty() || last.toIntOrNull() != null) &&
        validBookmarkRange(first.toIntOrNull(), last.toIntOrNull(), minimum, maximum)
    SheetDialog(title = if (original == null) "New excerpt bookmark" else "Edit bookmark", wide = true, onDismiss = onClose, buttons = {
        TextButton(onClick = onClose) { Text("Cancel") }
        Button(enabled = valid, onClick = {
            val trimmedName = name.trim()
            val draft = (original ?: Bookmark(label = trimmedName, part = partId, page = first.toInt(), at = System.currentTimeMillis())).copy(
                label = trimmedName, excerptTitle = trimmedName, part = partId, page = first.toInt(), endPage = last.toIntOrNull(),
                reminder = reminder.trim().ifEmpty { null }, notes = notes.trim().ifEmpty { null }, color = colour)
            saveBookmarkDraft(state, song.id, original, draft)
            onClose()
        }) { Text("Save") }
    }) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Bookmark, null, tint = MaterialTheme.colorScheme.primary)
                Column {
                    Text(song.title, style = MaterialTheme.typography.titleMedium)
                    Text("An excerpt within this book or piece", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedTextField(name, { name = it }, label = { Text("Excerpt title") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("bookmark-title"))
            Box {
                OutlinedButton(onClick = { pickingPart = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Description, null)
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(part?.let { Instruments.partName(it) } ?: "Choose a file", maxLines = 1)
                        part?.let { Text(it.file.substringAfterLast('/'), style = MaterialTheme.typography.labelSmall, maxLines = 2) }
                    }
                    Icon(Icons.Default.ArrowDropDown, null)
                }
                DropdownMenu(expanded = pickingPart, onDismissRequest = { pickingPart = false }) {
                    song.parts.forEach { p -> DropdownMenuItem(text = { Text(Instruments.partName(p) + " · " + p.file.substringAfterLast('/')) }, onClick = {
                        partId = p.id; pickingPart = false
                    }) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(first, { if (it.all(Char::isDigit)) first = it }, label = { Text("First page") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f).testTag("bookmark-first"),
                    isError = first.isNotEmpty() && !validBookmarkRange(first.toIntOrNull(), null, minimum, maximum))
                OutlinedTextField(last, { if (it.all(Char::isDigit)) last = it }, label = { Text("Last page") }, placeholder = { Text("Same page") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f).testTag("bookmark-last"),
                    isError = !validBookmarkRange(first.toIntOrNull(), last.toIntOrNull(), minimum, maximum))
            }
            Text("Pages are numbered from 1" + (maximum?.let { " · available $minimum–$it" } ?: "") + ". Opens at the first page.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            OutlinedTextField(reminder, { reminder = it }, label = { Text("Excerpt reminder") },
                placeholder = { Text("A short cue to see in your bookmarks") }, modifier = Modifier.fillMaxWidth().testTag("bookmark-reminder"))
            OutlinedTextField(notes, { notes = it }, label = { Text("Excerpt notes") }, minLines = 3,
                modifier = Modifier.fillMaxWidth().testTag("bookmark-notes"))
            Text("These notes and this reminder belong to the excerpt, separately from ${song.title}.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bookmark colour", Modifier.weight(1f)); ColourBar(colour)
                TextButton(onClick = { choosingColour = true }) { Text("Choose") }
            }
        }
    }
    if (choosingColour) ColourDialog("Bookmark colour", colour, { colour = it; choosingColour = false }, { choosingColour = false })
}
