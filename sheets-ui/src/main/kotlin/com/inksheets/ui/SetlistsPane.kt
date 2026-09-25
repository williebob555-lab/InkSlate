package com.inksheets.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.inksheets.core.Folder
import com.inksheets.core.PartChoice
import com.inksheets.core.Setlist

/**
 * Setlists, kept in folders that can hold folders: an ensemble, its years, each year's concerts.
 */
@Composable
internal fun SetlistsPane(state: SheetsState) {
    // Held by the state, so leaving a set by Home can land back inside it.
    var folderId by state::setlistFolder
    var openSetlist by state::setlistShown
    var naming by remember { mutableStateOf<Naming?>(null) }
    var sharing by remember { mutableStateOf<String?>(null) }

    val version = state.version
    val library = state.library ?: return
    val shown = openSetlist?.let { id -> remember(version, id) { library.setlist(id) } }
    if (shown != null) {
        SetlistView(state, shown, onBack = { openSetlist = null })
        return
    }

    val path = remember(version, folderId) { library.pathTo(folderId) }
    val folders = remember(version, folderId) { library.foldersIn(folderId) }
    val setlists = remember(version, folderId) { library.setlistsIn(folderId) }

    Column(Modifier.fillMaxSize()) {
        // Where we are: Setlists > Wind Ensemble > 2025-26
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { folderId = null }) { Text("Setlists") }
            path.forEach { f ->
                Icon(Icons.Default.ChevronRight, null)
                TextButton(onClick = { folderId = f.id }) { Text(f.name) }
            }
            Spacer(Modifier.weight(1f))
            AddButton("New folder") { naming = Naming.NewFolder(folderId) }
            AddButton("New setlist") { naming = Naming.NewSetlist(folderId) }
        }
        HorizontalDivider()

        if (folders.isEmpty() && setlists.isEmpty()) {
            Text(
                if (folderId == null) "No setlists yet. A folder can hold an ensemble's years, each with its own setlists."
                else "This folder is empty.",
                modifier = Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(folders, key = { "f" + it.id }) { f ->
                ListRow(
                    icon = { Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                    title = f.name,
                    detail = library.setlistsUnder(f.id).size.let { n -> if (n == 1) "1 setlist" else "$n setlists" },
                    onClick = { folderId = f.id },
                    menu = listOf(
                        "Rename" to { naming = Naming.RenameFolder(f) },
                        "Delete (keeps its setlists)" to { state.change { deleteFolder(f.id) } }
                    )
                )
                HorizontalDivider()
            }
            items(setlists, key = { "s" + it.id }) { s ->
                ListRow(
                    icon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
                    title = s.name,
                    detail = listOfNotNull(s.date, "${s.entries.size} songs").joinToString("  ·  "),
                    onClick = { openSetlist = s.id },
                    menu = listOf(
                        "Share with bandmates..." to { sharing = s.id },
                        "Rename" to { naming = Naming.RenameSetlist(s) },
                        "Move to top level" to { state.change { editSetlist(s.id) { this.folderId = null } } },
                        "Delete" to { state.change { deleteSetlist(s.id) } }
                    ) + folders.map { target ->
                        "Move into ${target.name}" to { state.change { editSetlist(s.id) { this.folderId = target.id } } }
                    }
                )
                HorizontalDivider()
            }
        }
    }

    sharing?.let { id -> ShareSetlistDialog(state, id, onClose = { sharing = null }) }
    naming?.let { n ->
        NameDialog(
            title = n.title,
            initial = n.initial,
            onDone = { name ->
                when (n) {
                    is Naming.NewFolder -> state.change { addFolder(name, n.parent) }
                    is Naming.NewSetlist -> state.change { addSetlist(name, n.folder) }
                    is Naming.RenameFolder -> state.change { renameFolder(n.folder.id, name) }
                    is Naming.RenameSetlist -> state.change { editSetlist(n.setlist.id) { this.name = name } }
                }
                naming = null
            },
            onDismiss = { naming = null }
        )
    }
}

private sealed class Naming(val title: String, val initial: String = "") {
    class NewFolder(val parent: String?) : Naming("New folder")
    class NewSetlist(val folder: String?) : Naming("New setlist")
    class RenameFolder(val folder: Folder) : Naming("Rename folder", folder.name)
    class RenameSetlist(val setlist: Setlist) : Naming("Rename setlist", setlist.name)
}

@Composable
private fun SetlistView(state: SheetsState, setlist: Setlist, onBack: () -> Unit) {
    val library = state.library ?: return
    var adding by remember { mutableStateOf(false) }
    val version = state.version
    val songs = remember(version) { library.songs.associateBy { it.id } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(setlist.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (state.playing?.first == setlist.id) {
                TextButton(onClick = { state.closeSetlist() }) {
                    Icon(Icons.Default.Close, null)
                    Text("Close set")
                }
            }
            if (setlist.entries.isNotEmpty()) {
                TextButton(onClick = { state.playSetlist(setlist.id, 0) }) {
                    Icon(Icons.Default.PlayArrow, null)
                    Text("Start")
                }
            }
            AddButton("Songs") { adding = true }
        }
        HorizontalDivider()
        // Grab a song by its handle and drag it to its new place; it is written when let go.
        val listState = rememberLazyListState()
        val order = remember(setlist.entries) { setlist.entries.toMutableStateList() }
        var dragging by remember { mutableStateOf<String?>(null) }
        var dragBy by remember { mutableStateOf(0f) }
        fun dragTo(delta: Float) {
            val id = dragging ?: return
            dragBy += delta
            val items = listState.layoutInfo.visibleItemsInfo
            val me = items.firstOrNull { it.key == id } ?: return
            val centre = me.offset + me.size / 2 + dragBy
            val over = items.firstOrNull { it.key != id && it.index in order.indices && centre > it.offset && centre < it.offset + it.size } ?: return
            val from = order.indexOfFirst { it.id == id }
            if (from < 0) return
            order.add(over.index, order.removeAt(from))
            dragBy -= (over.offset - me.offset)
        }
        fun dropped() {
            if (dragging != null) {
                state.reorderSetlist(setlist.id, order.map { it.id })
                state.setlistReordered(setlist.id)
            }
            dragging = null
            dragBy = 0f
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            itemsIndexed(order, key = { _, e -> e.id }) { index, entry ->
                val song = songs[entry.songId]
                val lifted = dragging == entry.id
                Column(
                    if (lifted) Modifier.zIndex(1f).graphicsLayer { translationY = dragBy }
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    else Modifier
                ) {
                    val handle: @Composable () -> Unit = {
                        Icon(
                            Icons.Default.DragHandle, "Drag to reorder",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .pointerInput(entry.id) {
                                    detectDragGestures(
                                        onDragStart = { dragging = entry.id; dragBy = 0f },
                                        onDragEnd = { dropped() },
                                        onDragCancel = { dropped() }
                                    ) { change, amount -> change.consume(); dragTo(amount.y) }
                                }
                                .padding(12.dp)
                        )
                    }
                    if (song == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                ListRow(icon = {}, title = "(removed from the library)", detail = null, onClick = {}, menu = listOf(
                                    "Take out of setlist" to { state.change { removeFromSetlist(setlist.id, entry.id) } }
                                ))
                            }
                            handle()
                        }
                    } else {
                        SongRow(
                            song = song,
                            unsure = PartChoice.fit(song, state.profile) == PartChoice.Fit.UNKNOWN,
                            onOpen = { state.playSetlist(setlist.id, index) },
                            trailing = {
                                Text("${index + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 4.dp))
                                IconButton(onClick = { state.change { removeFromSetlist(setlist.id, entry.id) } }) {
                                    Icon(Icons.Default.Close, "Take out of setlist")
                                }
                                handle()
                            }
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (adding) {
        SongChooserDialog(
            state,
            onChosen = { chosen -> state.change { chosen.forEach { addToSetlist(setlist.id, it.id) } }; adding = false },
            onDismiss = { adding = false }
        )
    }
}

@Composable
internal fun ListRow(
    icon: @Composable () -> Unit,
    title: String,
    detail: String?,
    onClick: () -> Unit,
    menu: List<Pair<String, () -> Unit>> = emptyList()
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (menu.isNotEmpty()) {
            var open by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "Options") }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    menu.forEach { (label, action) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() })
                    }
                }
            }
        }
    }
}

@Composable
internal fun NameDialog(title: String, initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    SheetDialog(
        title = title,
        onDismiss = onDismiss,
        buttons = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            TextButton(onClick = { if (text.isNotBlank()) onDone(text.trim()) }, enabled = text.isNotBlank()) { Text("OK") }
        }
    ) {
        OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}

/** Pick a setlist anywhere in the folders, for "Add to setlist". */
@Composable
internal fun SetlistChooserDialog(state: SheetsState, onChosen: (Setlist) -> Unit, onDismiss: () -> Unit) {
    val library = state.library ?: return
    var creating by remember { mutableStateOf(false) }
    SheetDialog(
        title = "Add to setlist",
        onDismiss = onDismiss,
        buttons = {
            TextButton(onClick = { creating = true }) { Text("New setlist") }
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    ) {
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(library.setlists, key = { it.id }) { s ->
                val where = library.pathTo(s.folderId).joinToString(" › ") { it.name }
                ListRow(
                    icon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
                    title = s.name,
                    detail = where.ifEmpty { null },
                    onClick = { onChosen(s) }
                )
            }
        }
    }
    if (creating) {
        NameDialog("New setlist", "", onDone = { name ->
            creating = false
            var made: Setlist? = null
            state.change { made = addSetlist(name) }
            made?.let(onChosen)
        }, onDismiss = { creating = false })
    }
}
