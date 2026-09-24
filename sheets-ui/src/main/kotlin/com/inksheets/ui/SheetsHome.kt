package com.inksheets.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inksheets.core.Instruments
import com.inksheets.core.PartChoice
import com.inksheets.core.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * InkSheets' home: the library for the instrument being played, and the setlists.
 *
 * Shown in the Home tab of the same workspace InkSlate uses, so a song opens as a tab beside the
 * others with the full editor - pens, annotations, live sync - behind it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetsHome(state: SheetsState, onOpenSettings: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var showMetronome by remember { mutableStateOf(false) }
    var showTuner by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var chooseFolder by remember { mutableStateOf(false) }

    // Scans nobody has read yet are read in the background, a page at a time.
    var reading by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(state.library) {
        if (state.library == null || !state.platform.canRecognise) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching {
                state.readUnknownParts { done, of -> reading = if (done < of) done to of else null }
            }
        }
        reading = null
    }

    // Edits from other devices arrive through the synced folder; look for them now and then.
    LaunchedEffect(state.library) {
        while (true) {
            withContext(Dispatchers.IO) { runCatching { state.refresh() } }
            delay(3_000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("InkSheets") },
            actions = {
                InstrumentChooser(state)
                IconButton(onClick = { showMetronome = true }) { Icon(Icons.Default.Timer, "Metronome") }
                IconButton(onClick = { showTuner = true }) { Icon(Icons.Default.GraphicEq, "Tuner") }
                if (state.library != null) {
                    IconButton(onClick = { showImport = true }) { Icon(Icons.Default.LibraryAdd, "Add music") }
                }
                var more by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        DropdownMenuItem(
                            text = { Text("Music folder...") },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                            onClick = { more = false; chooseFolder = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            leadingIcon = { Icon(Icons.Default.Settings, null) },
                            onClick = { more = false; onOpenSettings() }
                        )
                    }
                }
            }
        )

        if (state.library == null) {
            Welcome(onChoose = { chooseFolder = true })
        } else {
            reading?.let { (done, of) ->
                Text(
                    "Reading the instrument off scanned parts: ${done + 1} of $of",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Songs") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Setlists") })
            }
            when (tab) {
                0 -> SongsPane(state)
                else -> SetlistsPane(state)
            }
        }
    }

    if (showMetronome) MetronomeDialog(state, onClose = { showMetronome = false })
    if (showTuner || state.tunerOpen) TunerDialog(state, onClose = { showTuner = false; state.tunerOpen = false })
    if (showImport) ImportDialog(state, onClose = { showImport = false })
    if (chooseFolder) {
        FolderPickerDialog(
            title = "Choose your music folder",
            start = state.root ?: state.platform.startFolder,
            onChosen = { state.open(it); chooseFolder = false },
            onDismiss = { chooseFolder = false }
        )
    }
}

@Composable
private fun Welcome(onChoose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Your music library", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.size(12.dp))
        Text(
            "Choose the folder your sheet music is in - ideally one Syncthing shares between " +
                "your devices. The library, setlists and annotations live in that folder, so " +
                "every device that has it sees the same music.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(20.dp))
        Button(onClick = onChoose) { Text("Choose folder") }
    }
}

/** The instrument being played: only its parts are shown, and its part opens first. */
@Composable
private fun InstrumentChooser(state: SheetsState) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(state.profile?.name ?: "All instruments")
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("All instruments") }, onClick = { state.chooseProfile(null); open = false })
            state.profiles.forEach { p ->
                DropdownMenuItem(text = { Text(p.name) }, onClick = { state.chooseProfile(p.id); open = false })
            }
        }
    }
}

@Composable
private fun SongsPane(state: SheetsState) {
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Song?>(null) }
    var addingToSetlist by remember { mutableStateOf<Song?>(null) }

    val version = state.version
    val songs = remember(version, state.profileId, query) {
        val all = state.library?.songs.orEmpty()
        val q = query.trim().lowercase()
        val matching = if (q.isEmpty()) all else all.filter { s ->
            (listOf(s.title) + s.composers + s.arrangers + s.artists + s.genres + s.tags)
                .any { it.lowercase().contains(q) }
        }
        PartChoice.songsFor(matching, state.profile)
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text("Title, composer, tag...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )
        if (songs.isEmpty()) {
            Text(
                if (state.library?.songs.isNullOrEmpty()) "No music yet. Add some with the button at the top."
                else "Nothing matches.",
                modifier = Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(songs, key = { it.first.id }) { (song, fit) ->
                SongRow(
                    song = song,
                    unsure = fit == PartChoice.Fit.UNKNOWN,
                    onOpen = { state.stopPlaying(); openSong(state, song) },
                    onEdit = { editing = song },
                    onAddToSetlist = { addingToSetlist = song },
                    onDelete = { state.change { deleteSong(song.id) } }
                )
                HorizontalDivider()
            }
        }
    }

    editing?.let { song -> SongEditorDialog(state, song, onClose = { editing = null }) }
    addingToSetlist?.let { song ->
        SetlistChooserDialog(
            state,
            onChosen = { setlist -> state.change { addToSetlist(setlist.id, song.id) }; addingToSetlist = null },
            onDismiss = { addingToSetlist = null }
        )
    }
}

/** Open the part of [song] for the instrument being played. */
internal fun openSong(state: SheetsState, song: Song) {
    val part = PartChoice.partFor(song, state.profile) ?: return
    val file = state.fileOf(part.file) ?: return
    state.platform.openPart(song, part, file)
}

@Composable
internal fun SongRow(
    song: Song,
    unsure: Boolean,
    onOpen: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onAddToSetlist: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = listOfNotNull(
                song.composers.joinToString(", ").takeIf { it.isNotEmpty() },
                song.key?.let { "in $it" },
                song.tempo?.let { "♩=$it" }
            ).joinToString("  ·  ")
            if (detail.isNotEmpty()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val instruments = song.instruments.mapNotNull { Instruments.byId[it]?.name }.sorted()
            if (instruments.isNotEmpty() || unsure) {
                Text(
                    (instruments + if (unsure) listOf("parts not yet named") else emptyList()).joinToString(", "),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (unsure) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        trailing?.invoke()
        if (onEdit != null || onAddToSetlist != null || onDelete != null) {
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Song options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    onEdit?.let { DropdownMenuItem(text = { Text("Details and parts") }, onClick = { menu = false; it() }) }
                    onAddToSetlist?.let { DropdownMenuItem(text = { Text("Add to setlist...") }, onClick = { menu = false; it() }) }
                    onDelete?.let {
                        var confirm by remember { mutableStateOf(false) }
                        DropdownMenuItem(
                            text = { Text(if (confirm) "Tap again to remove" else "Remove from library", fontWeight = if (confirm) FontWeight.Bold else null) },
                            onClick = { if (confirm) { menu = false; it() } else confirm = true }
                        )
                    }
                }
            }
        }
    }
}

/** A dialog's frame: a title, the body, and buttons along the bottom. */
@Composable
internal fun SheetDialog(
    title: String,
    onDismiss: () -> Unit,
    buttons: @Composable () -> Unit = { TextButton(onClick = onDismiss) { Text("Close") } },
    wide: Boolean = false,
    content: @Composable () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = Modifier.width(if (wide) 640.dp else 440.dp).padding(8.dp)
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.size(12.dp))
                Box(Modifier.weight(1f, fill = false)) { content() }
                Spacer(Modifier.size(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { buttons() }
            }
        }
    }
}

@Composable
internal fun AddButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(Icons.Default.Add, null)
        Spacer(Modifier.width(4.dp))
        Text(label)
    }
}
