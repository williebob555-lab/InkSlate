package com.inksheets.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.inksheets.core.InstrumentSource
import com.inksheets.core.Instruments
import com.inksheets.core.Part
import com.inksheets.core.Song

/** A song's details, and which instrument each of its parts is for. */
@Composable
internal fun SongEditorDialog(state: SheetsState, song: Song, initialParts: Boolean = false, onClose: () -> Unit) {
    var assignments by remember(song.id) { mutableStateOf(initialParts) }
    var title by remember { mutableStateOf(song.title) }
    var composers by remember { mutableStateOf(song.composers.joinToString(", ")) }
    var arrangers by remember { mutableStateOf(song.arrangers.joinToString(", ")) }
    var key by remember { mutableStateOf(song.key.orEmpty()) }
    var time by remember { mutableStateOf(song.timeSignature.orEmpty()) }
    var tempo by remember { mutableStateOf(song.tempo?.toString().orEmpty()) }
    var tags by remember { mutableStateOf((song.genres + song.tags).joinToString(", ")) }
    var notes by remember { mutableStateOf(song.notes.orEmpty()) }
    val parts = remember { mutableStateListOf(*song.parts.toTypedArray()) }
    var colour by remember { mutableStateOf(song.color) }
    var choosingColour by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<Part?>(null) }
    var splitting by remember { mutableStateOf<Part?>(null) }
    val validRanges = parts.all { validPartPageRange(it.firstPage, it.lastPage) }

    fun list(text: String) = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    // A subtask replaces this frame rather than stacking another dialog on top.
    // Cancelling returns to the same draft, including unsaved assignment edits.
    moving?.let { part ->
        PickSongDialog(state, title = "Move this part to which song?", exclude = song.id, near = song.title,
            onChosen = { to -> state.movePart(part.id, to.id); moving = null; onClose() },
            onDismiss = { moving = null })
        return
    }
    splitting?.let { part ->
        AskName(title = "A song of its own, called", initial = song.title, confirm = "Make it",
            onDone = { name -> state.splitPart(part.id, name); splitting = null; onClose() },
            onDismiss = { splitting = null })
        return
    }

    SheetDialog(
        title = song.title,
        onDismiss = onClose,
        wide = true,
        buttons = {
            TextButton(onClick = onClose) { Text("Cancel") }
            Button(enabled = validRanges, onClick = {
                // Only what was changed here is written: the dialog shows the song as it was when
                // opened, and a part that arrived meanwhile (or an instrument read in the
                // background) must not be overwritten by that old picture of it.
                state.change {
                    editSong(song.id) {
                        val t = title.trim().ifEmpty { song.title }
                        if (t != song.title) this.title = t
                        if (list(composers) != song.composers) this.composers = list(composers)
                        if (list(arrangers) != song.arrangers) this.arrangers = list(arrangers)
                        if (key.trim().ifEmpty { null } != song.key) this.key = key.trim().ifEmpty { null }
                        if (time.trim().ifEmpty { null } != song.timeSignature) this.timeSignature = time.trim().ifEmpty { null }
                        val bpm = tempo.trim().toIntOrNull()
                        // A tempo typed or picked is the person's: reading again leaves it be.
                        if (bpm != song.tempo) { this.tempo = bpm; this.tempoRead = false }
                        if (list(tags) != song.genres + song.tags) this.tags = list(tags)
                        if (colour != song.color) this.color = colour
                        if (notes.trim().ifEmpty { null } != song.notes) this.notes = notes.trim().ifEmpty { null }
                    }
                    val before = song.parts.associateBy { it.id }
                    val now = this.song(song.id)?.parts.orEmpty().associateBy { it.id }
                    for (p in parts) {
                        val was = before[p.id] ?: continue
                        val current = now[p.id] ?: continue
                        if (p == was) continue
                        // Apply individual field differences. A fresh background assignment or a
                        // page range received from another device must survive an unrelated edit.
                        writePart(song.id, current.copy(
                            instrument = if (p.instrument != was.instrument) p.instrument else current.instrument,
                            chair = if (p.chair != was.chair) p.chair else current.chair,
                            also = if (p.also != was.also) p.also else current.also,
                            firstPage = if (p.firstPage != was.firstPage) p.firstPage else current.firstPage,
                            lastPage = if (p.lastPage != was.lastPage) p.lastPage else current.lastPage,
                            source = if (p.instrument != was.instrument || p.chair != was.chair || p.also != was.also) p.source else current.source
                        ))
                    }
                }
                onClose()
            }) { Text("Save") }
        }
    ) {
        BoxWithConstraints {
        val short = maxHeight < 360.dp
        Column {
            TabRow(selectedTabIndex = if (assignments) 1 else 0, containerColor = Color.Transparent) {
                Tab(selected = !assignments, onClick = { assignments = false }) {
                    Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Icon(Icons.Default.Description, null, Modifier.size(19.dp)); Text("Details")
                    }
                }
                Tab(selected = assignments, onClick = { assignments = true }) {
                    Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Icon(Icons.Default.LibraryMusic, null, Modifier.size(19.dp)); Text("Parts (${parts.size})")
                    }
                }
            }
            HorizontalDivider()
            if (!validRanges) Text("A part has an invalid page range. Check the Parts tab before saving.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                .testTag(if (assignments) "part-assignments-body" else "song-details-body")) {
            if (!assignments) {
            EditorSection(Icons.Default.Person, "Title & credits") {
                Field("Title", title) { title = it }
                AdaptiveFieldPair(
                    first = { Field("Composer", composers) { composers = it } },
                    second = { Field("Arranger", arrangers) { arrangers = it } })
            }
            EditorSection(Icons.Default.MusicNote, "Musical details", MaterialTheme.colorScheme.tertiary) {
            Row {
                Box(Modifier.weight(1f)) { Field("Key", key) { key = it } }
                Spacer(Modifier.size(8.dp))
                Box(Modifier.weight(1f)) { Field("Time", time) { time = it } }
                Spacer(Modifier.size(8.dp))
                Box(Modifier.weight(1f)) { Field("Tempo", tempo) { tempo = it.filter(Char::isDigit) } }
            }
            PracticeLog.summary(state, song.id)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 2.dp))
            }
            // What the music says, and the range a tempo word means - a baseline to pick from.
            val range = com.inksheets.core.TempoReader.rangeOf(song.tempoMark)
            if (song.tempoMark != null || song.tempoRead) {
                Column(Modifier.padding(vertical = 2.dp)) {
                    Text(
                        buildString {
                            append(if (song.tempoRead) "Read from the music" else "The music says")
                            song.tempoMark?.let { append(": $it") }
                            range?.let { append(" (${it.first}-${it.last})") }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (range != null) {
                        val steps = 5
                        val picks = (0 until steps).map { i -> range.first + (range.last - range.first) * i / (steps - 1) }.distinct()
                        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
                            for (bpm in picks) {
                                androidx.compose.material3.FilterChip(
                                    selected = tempo == bpm.toString(),
                                    onClick = { tempo = bpm.toString() },
                                    label = { Text("$bpm") },
                                    modifier = Modifier.padding(end = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
            }
            EditorSection(Icons.Default.Notes, "Rehearsal notes") {
                OutlinedTextField(notes, { notes = it }, placeholder = { Text("What to remember when playing this piece") },
                    minLines = 2, modifier = Modifier.fillMaxWidth().testTag("song-notes"))
            }
            EditorSection(Icons.Default.Style, "Organize", MaterialTheme.colorScheme.tertiary) {
                Field("Tags and genres", tags) { tags = it }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Icon(Icons.Default.Palette, null, tint = colour?.let { Color(it) } ?: MaterialTheme.colorScheme.primary)
                    Text("Colour", Modifier.weight(1f).padding(start = 8.dp))
                    ColourBar(colour)
                    TextButton(onClick = { choosingColour = true }) { Text(if (colour == null) "Choose" else "Change") }
                }
            }
            HorizontalDivider(Modifier.padding(top = 12.dp))
            TextButton(onClick = { assignments = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.LibraryMusic, null)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text("${parts.size} " + if (parts.size == 1) "part" else "parts", style = MaterialTheme.typography.titleSmall)
                    Text(parts.map { Instruments.partName(it) }.distinct().joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Default.ArrowForward, "Edit parts")
            }

            } else {
            if (!short) Text("Part assignments", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
            Text(if (short) "Edit files directly · manual assignments override detection" else "Edit directly in each row. Manual assignments override detection.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = if (short) 6.dp else 0.dp, bottom = if (short) 2.dp else 8.dp))
            // Two parts for one instrument are usually two versions of the piece, or two pieces
            // sharing a name: only the first opens, so say so and offer the way out.
            val doubled = parts.filter { it.instrument != null }.groupBy { it.instrument to it.chair }.filter { (_, v) -> v.size > 1 }.values.map { it.first() }
            if (doubled.isNotEmpty()) {
                Text(
                    "More than one part is for " + doubled.joinToString(", ") { Instruments.partName(it) } +
                        ". The first of them opens. Use a part's menu to open another first, or to make it a song of its own.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            parts.forEachIndexed { i, part ->
                PartAssignmentRow(state, song, part,
                    onInstrument = { parts[i] = part.copy(instrument = it, source = InstrumentSource.PERSON) },
                    onChair = { parts[i] = part.copy(chair = it, source = InstrumentSource.PERSON) },
                    onRange = { first, last -> parts[i] = part.copy(firstPage = first, lastPage = last) },
                    onAlso = { parts[i] = part.copy(also = it, source = InstrumentSource.PERSON) }) {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        androidx.compose.material3.IconButton(onClick = { menu = true }) {
                            Icon(androidx.compose.material.icons.Icons.Default.MoreVert, "Part options")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Arrange the pages...") },
                                onClick = { menu = false; onClose(); state.showPages(song, part) }
                            )
                            if (i > 0) DropdownMenuItem(text = { Text("Open this one first") }, onClick = {
                                menu = false
                                parts.removeAt(i); parts.add(0, part)
                                state.change { orderParts(parts.map { it.id }) }
                            })
                            DropdownMenuItem(text = { Text("Move to another song...") }, onClick = { menu = false; moving = part })
                            if (parts.size > 1) {
                                DropdownMenuItem(text = { Text("Make it a song of its own...") }, onClick = { menu = false; splitting = part })
                            }
                            // The file itself, gone from the library - to its Trash, for 30 days.
                            var confirm by remember { mutableStateOf(false) }
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (confirm) "Tap again: the file goes to the Trash for 30 days" else "Delete this file...",
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = if (confirm) androidx.compose.ui.text.font.FontWeight.Bold else null
                                    )
                                },
                                onClick = {
                                    if (!confirm) confirm = true
                                    else {
                                        menu = false
                                        parts.removeAt(i)
                                        state.removePart(song, part)
                                    }
                                }
                            )
                        }
                    }
                }
                HorizontalDivider()
            }
            if (!validRanges) Text("Page ranges must start at 1 or later and end at or after the first page.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
            }
            }
        }
        }
    }

    if (choosingColour) {
        ColourDialog("Colour", colour, onChosen = { colour = it; choosingColour = false }, onDismiss = { choosingColour = false })
    }
}

/** Inclusive, 1-based pages; a missing end means the remainder of the file. */
internal fun validPartPageRange(first: Int?, last: Int?): Boolean =
    (first == null || first >= 1) && (last == null || last >= 1) && (last == null || last >= (first ?: 1))

@Composable
private fun EditorSection(icon: ImageVector, title: String, accent: Color = MaterialTheme.colorScheme.primary,
                          content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(20.dp), tint = accent)
            Text(title, color = accent, style = MaterialTheme.typography.titleSmall)
            HorizontalDivider(Modifier.weight(1f), color = accent.copy(alpha = .2f))
        }
        content()
    }
}

@Composable
private fun AdaptiveFieldPair(first: @Composable () -> Unit, second: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 500.dp) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) { first() }; Box(Modifier.weight(1f)) { second() }
        } else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { first(); second() }
    }
}

/** The file/source and its controls keep the same columns across rows; phones reflow as a unit. */
@Composable
private fun PartAssignmentRow(state: SheetsState, song: Song, part: Part,
                              onInstrument: (String?) -> Unit, onChair: (Int?) -> Unit,
                              onRange: (Int?, Int?) -> Unit, onAlso: (List<String>) -> Unit,
                              options: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        val wide = maxWidth >= 760.dp
        val medium = maxWidth >= 530.dp
        val accent = when (part.source) {
            InstrumentSource.PERSON -> Color(0xFF2C8A6B)
            InstrumentSource.UNKNOWN -> if (part.instrument == null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.primary
        }
        val source = when (part.source) {
            InstrumentSource.PERSON -> "Set by you"
            InstrumentSource.FILE_NAME -> "Detected from filename"
            InstrumentSource.TEXT, InstrumentSource.OCR -> "Detected from page" + part.label?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
            InstrumentSource.UNKNOWN -> if (part.instrument == null) "Needs assignment" else "Assignment source unavailable"
        }
        val file: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (part.instrument == null) PartThumbnail(state, song, part) { state.peeking = song to part }
                else Surface(color = accent.copy(alpha = .1f), shape = RoundedCornerShape(8.dp)) {
                    Icon(Icons.Default.Description, null, Modifier.padding(9.dp).size(20.dp), tint = accent)
                }
                Column(Modifier.weight(1f)) {
                    Text(part.file.substringAfterLast('/').substringAfterLast('\\'), style = MaterialTheme.typography.titleSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(if (part.source == InstrumentSource.PERSON) Icons.Default.CheckCircle else if (part.source == InstrumentSource.UNKNOWN) Icons.Default.HelpOutline else Icons.Default.AutoAwesome,
                            null, Modifier.size(12.dp), tint = accent)
                        Text(source, style = MaterialTheme.typography.labelSmall, color = accent, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        val instrument: @Composable () -> Unit = {
            Column {
                Text("Instrument", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                InstrumentPicker(part.instrument, Modifier.fillMaxWidth(), outlined = true, onChosen = onInstrument)
            }
        }
        val chair: @Composable () -> Unit = {
            Column {
                Text("Chair", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChairPicker(part.chair, outlined = true, onChosen = onChair)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (wide) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { file() }
                Box(Modifier.width(174.dp)) { instrument() }
                Box(Modifier.width(70.dp)) { chair() }
                PartPageRange(part, onChange = onRange)
                options()
            } else {
                file()
                if (medium) Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { instrument() }
                    chair()
                    PartPageRange(part, onChange = onRange)
                    options()
                } else {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f)) { instrument() }; chair()
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PartPageRange(part, onChange = onRange)
                        Spacer(Modifier.weight(1f))
                        options()
                    }
                }
            }
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
                TextButton(onClick = { state.peeking = song to part }) {
                    Icon(Icons.Default.Visibility, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("Preview file")
                }
                if (part.instrument != null) AlsoFor(part, onAlso)
            }
        }
    }
}

@Composable
private fun PartPageRange(part: Part, onChange: (Int?, Int?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(value = part.firstPage?.toString().orEmpty(),
            onValueChange = { value -> if (value.all(Char::isDigit) && (value.isEmpty() || value.toIntOrNull() != null)) onChange(value.toIntOrNull(), part.lastPage) },
            label = { Text("First") }, placeholder = { Text("1") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = !validPartPageRange(part.firstPage, part.lastPage),
            modifier = Modifier.size(width = 76.dp, height = 64.dp).testTag("part-first-${part.id}"))
        OutlinedTextField(value = part.lastPage?.toString().orEmpty(),
            onValueChange = { value -> if (value.all(Char::isDigit) && (value.isEmpty() || value.toIntOrNull() != null)) onChange(part.firstPage, value.toIntOrNull()) },
            label = { Text("Last") }, placeholder = { Text("End") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = !validPartPageRange(part.firstPage, part.lastPage),
            modifier = Modifier.size(width = 76.dp, height = 64.dp).testTag("part-last-${part.id}"))
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    )
}

/**
 * The other instruments one file is for - a duo, a "Trombone / Euphonium" part - each shown as a
 * chip to take off, and "Also for..." to add one.
 */
@Composable
private fun AlsoFor(part: Part, onChange: (List<String>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (id in part.also) {
            androidx.compose.material3.InputChip(
                selected = false,
                onClick = { onChange(part.also - id) },
                label = { Text("also " + (Instruments.byId[id]?.name ?: id)) },
                trailingIcon = { Icon(androidx.compose.material.icons.Icons.Default.Close, "Take off", Modifier.size(16.dp)) }
            )
        }
        Box {
            TextButton(onClick = { open = true }) { Text("Also for...") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false; query = "" }) {
                ListSearch(Instruments.all.size, query, { query = it }, "Find an instrument", Modifier.padding(horizontal = 8.dp))
                Instruments.all.filter { it.id != part.instrument && it.id !in part.also && matches(query, it) }.forEach { inst ->
                    DropdownMenuItem(text = { Text(inst.name) }, onClick = { open = false; query = ""; onChange(part.also + inst.id) })
                }
            }
        }
    }
}

/** Which of several parts for one instrument: 1st, 2nd, 3rd... or just the one. */
@Composable
internal fun ChairPicker(current: Int?, outlined: Boolean = false, onChosen: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        val label: @Composable () -> Unit = {
            Text(current?.toString() ?: "Any")
            Icon(Icons.Default.ArrowDropDown, null)
        }
        if (outlined) OutlinedButton(onClick = { open = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) { label() }
        else TextButton(onClick = { open = true }) { label() }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Just the one") }, onClick = { open = false; onChosen(null) })
            for (n in 1..6) DropdownMenuItem(text = { Text("Part $n") }, onClick = { open = false; onChosen(n) })
        }
    }
}

@Composable
internal fun InstrumentPicker(current: String?, modifier: Modifier = Modifier, outlined: Boolean = false, onChosen: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        val label: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
            Text(current?.let { Instruments.byId[it]?.name ?: it } ?: "Unassigned",
                modifier = if (outlined) Modifier.weight(1f) else Modifier,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Default.ArrowDropDown, null)
        }
        if (outlined) OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp), content = label)
        else TextButton(onClick = { open = true }, content = label)
        var query by remember { mutableStateOf("") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false; query = "" }) {
            ListSearch(Instruments.all.size, query, { query = it }, "Find an instrument", Modifier.padding(horizontal = 8.dp))
            if (query.isBlank()) DropdownMenuItem(text = { Text("Not known") }, onClick = { open = false; onChosen(null) })
            Instruments.all.filter { matches(query, it) }.forEach { inst ->
                DropdownMenuItem(text = { Text(inst.name) }, onClick = { open = false; query = ""; onChosen(inst.id) })
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
