package com.inksheets.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import com.inksheets.core.ImportPlan
import com.inksheets.core.Instruments
import com.inksheets.core.Library
import com.inksheets.core.Part
import java.io.File

/** What to do with one file handed in. */
internal sealed interface Fate {
    data class NewSong(val title: String) : Fate
    data class AddTo(val songId: String) : Fate
    data class Replace(val songId: String, val partId: String) : Fate
    data object Skip : Fate
}

/**
 * Files handed in from outside - picked, shared, dropped - and what becomes of each: a new song,
 * another part of a song already here (a match is suggested), a new edition replacing a part, or
 * nothing. Inside a setlist, the songs can go straight into it.
 */
@Composable
internal fun IncomingDialog(state: SheetsState) {
    val files = state.incoming ?: return
    val songs = remember(state.version) { state.library?.songs.orEmpty() }
    val planned = remember(files) { files.map { ImportPlan.readPart(it.name) } }
    val suggested = remember(files, state.version) {
        files.map { f ->
            val key = Library.matchKey(ImportPlan.titleOf(f.name))
            songs.firstOrNull { !it.apart && Library.matchKey(it.title) == key }
        }
    }
    val fates = remember(files) {
        mutableStateListOf(*files.indices.map { i ->
            suggested[i]?.let { Fate.AddTo(it.id) } ?: Fate.NewSong(ImportPlan.titleOf(files[i].name))
        }.toTypedArray())
    }
    val setlist = (state.setlistShown ?: state.playing?.first)?.let { id -> state.library?.setlist(id) }
    var intoSetlist by remember { mutableStateOf(setlist != null) }
    var choosingFor by remember { mutableStateOf<Int?>(null) }
    var replacingFor by remember { mutableStateOf<Int?>(null) }
    var renaming by remember { mutableStateOf<Int?>(null) }
    var working by remember { mutableStateOf(false) }

    SheetDialog(
        title = if (files.size == 1) "Add ${files.first().name}" else "Add ${files.size} files",
        onDismiss = { state.incoming = null },
        wide = true,
        buttons = {
            TextButton(onClick = { state.incoming = null }) { Text("Cancel") }
            TextButton(enabled = !working && fates.any { it != Fate.Skip }, onClick = {
                working = true
                val chosen = fates.toList()
                val list = if (intoSetlist) setlist?.id else null
                Thread({
                    state.takeIn(files, chosen, list)
                    state.platform.onMain { state.incoming = null }
                }, "take-in").apply { isDaemon = true; start() }
            }) { Text(if (working) "Adding..." else "Add") }
        }
    ) {
        Column {
            // All at once.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (files.size > 1) {
                    AssistChip(onClick = {
                        // One song: the suggested one if any file has a match, else a new one.
                        val title = ImportPlan.titleOf(files.first().name)
                        val target = suggested.firstNotNullOfOrNull { it }
                        fates.indices.forEach { i -> fates[i] = target?.let { Fate.AddTo(it.id) } ?: Fate.NewSong(title) }
                    }, label = { Text("All one song, as its parts") })
                    AssistChip(onClick = {
                        fates.indices.forEach { i -> fates[i] = Fate.NewSong(ImportPlan.readableName(files[i].name)) }
                    }, label = { Text("Each its own song") })
                    AssistChip(onClick = {
                        fates.indices.forEach { i -> fates[i] = suggested[i]?.let { Fate.AddTo(it.id) } ?: Fate.NewSong(ImportPlan.titleOf(files[i].name)) }
                    }, label = { Text("Suggested") })
                }
            }
            if (setlist != null) {
                Row(Modifier.fillMaxWidth().clickable { intoSetlist = !intoSetlist }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = intoSetlist, onCheckedChange = { intoSetlist = it })
                    Text("Also add them to the setlist “${setlist.name}”")
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            var query by remember { mutableStateOf("") }
            ListSearch(files.size, query, { query = it }, "Find a file")
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(files.withIndex().filter { (_, f) -> matches(query, f.name) }) { (i, f) ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        val instrument = planned[i].instrument?.let { Instruments.byId[it]?.name }?.let { n -> planned[i].chair?.let { "$n $it" } ?: n } ?: "instrument to be read"
                        Text(f.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(instrument, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val fate = fates[i]
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val newTitle = (fate as? Fate.NewSong)?.title ?: ImportPlan.titleOf(f.name)
                            FilterChip(selected = fate is Fate.NewSong, onClick = {
                                if (fate is Fate.NewSong) renaming = i else fates[i] = Fate.NewSong(newTitle)
                            }, label = { Text(if (fate is Fate.NewSong) "New song: ${fate.title}" else "New song") })
                            val target = (fate as? Fate.AddTo)?.songId?.let { id -> songs.firstOrNull { it.id == id } }
                            FilterChip(selected = fate is Fate.AddTo, onClick = { choosingFor = i },
                                label = { Text(if (target != null) "Part of ${target.title}" else "Add to a song...") })
                            FilterChip(selected = fate is Fate.Replace, onClick = { replacingFor = i },
                                label = { Text(if (fate is Fate.Replace) "Replaces a part" else "Replace a part...") })
                            FilterChip(selected = fate == Fate.Skip, onClick = { fates[i] = Fate.Skip }, label = { Text("Skip") })
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    choosingFor?.let { i ->
        PickSongDialog(
            state, title = "Add as a part of which song?", exclude = null, near = ImportPlan.titleOf(files[i].name),
            onChosen = { s -> fates[i] = Fate.AddTo(s.id); choosingFor = null },
            onDismiss = { choosingFor = null }
        )
    }
    replacingFor?.let { i ->
        var song by remember { mutableStateOf<com.inksheets.core.Song?>(null) }
        val chosen = song
        if (chosen == null) {
            PickSongDialog(
                state, title = "Replace a part of which song?", exclude = null, near = ImportPlan.titleOf(files[i].name),
                onChosen = { s -> if (s.parts.size == 1) { fates[i] = Fate.Replace(s.id, s.parts.first().id); replacingFor = null } else song = s },
                onDismiss = { replacingFor = null }
            )
        } else {
            SheetDialog(title = "Replace which part of ${chosen.title}?", onDismiss = { replacingFor = null }) {
                Column {
                    chosen.parts.forEach { p ->
                        Text(
                            Instruments.partName(p) + " - " + p.file.substringAfterLast('/'),
                            modifier = Modifier.fillMaxWidth().clickable { fates[i] = Fate.Replace(chosen.id, p.id); replacingFor = null }.padding(vertical = 10.dp)
                        )
                    }
                    Text(
                        "The new file takes the old one's place. Markings on the old one do not carry over.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
    renaming?.let { i ->
        val current = (fates[i] as? Fate.NewSong)?.title ?: ImportPlan.titleOf(files[i].name)
        AskName("Song title", current, "Use it", onDone = { fates[i] = Fate.NewSong(it); renaming = null }, onDismiss = { renaming = null })
    }
}
