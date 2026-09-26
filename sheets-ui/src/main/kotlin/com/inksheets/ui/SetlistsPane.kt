package com.inksheets.ui

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
    var colouring by remember { mutableStateOf<Setlist?>(null) }
    var colouringFolder by remember { mutableStateOf<Folder?>(null) }
    var clearing by remember { mutableStateOf<Folder?>(null) }
    var joiningFolder by remember { mutableStateOf<Folder?>(null) }
    var joiningInto by remember { mutableStateOf<Setlist?>(null) }

    val version = state.version
    val library = state.library ?: return
    val shown = openSetlist?.let { id -> remember(version, id) { library.setlist(id) } }
    if (shown != null) {
        SetlistView(state, shown, onBack = { openSetlist = null })
        return
    }

    val path = remember(version, folderId) { library.pathTo(folderId) }
    var query by remember { mutableStateOf("") }
    // Searching looks in every folder: the setlists found, wherever they are.
    val folders = remember(version, folderId, query) { if (query.isBlank()) library.foldersIn(folderId) else library.folders.filter { matches(query, it.name) } }
    val setlists = remember(version, folderId, query) { if (query.isBlank()) library.setlistsIn(folderId) else library.setlists.filter { matches(query, it.name) } }

    // Dragging a setlist or folder by its handle: dropped on a folder it goes in, dropped on the
    // path at the top ("Setlists", or a folder above this one) it goes back out to there.
    val targets = remember(folderId, version) { mutableStateMapOf<String, androidx.compose.ui.geometry.Rect>() }
    var carrying by remember { mutableStateOf<Carried?>(null) }
    var carriedBy by remember { mutableStateOf(Offset.Zero) }
    var carriedAt by remember { mutableStateOf(Offset.Zero) }
    // Worked out when asked, not when composed: the gesture outlives the composition it began in.
    fun overNow() = carrying?.let { c -> targets.entries.firstOrNull { (key, r) -> r.contains(carriedAt) && key != c.targetKey }?.key }
    val over = overNow()
    fun drop() {
        val c = carrying
        val key = overNow()
        carrying = null
        carriedBy = Offset.Zero
        if (c == null || key == null) return
        val into = key.removePrefix(TARGET).takeIf { it.isNotEmpty() }
        when (c) {
            is Carried.Set -> state.change { editSetlist(c.id) { this.folderId = into } }
            is Carried.Dir -> state.change { moveFolder(c.id, into) }
        }
    }
    fun Modifier.target(folder: String?) = onGloballyPositioned { targets[TARGET + (folder ?: "")] = it.boundsInRoot() }
    val litColour = MaterialTheme.colorScheme.primaryContainer
    val liftedColour = MaterialTheme.colorScheme.surfaceContainerHighest
    fun Modifier.lit(folder: String?) = if (carrying != null && over == TARGET + (folder ?: ""))
        background(litColour, RoundedCornerShape(8.dp)) else this
    @Composable
    fun Handle(what: Carried) {
        var origin by remember { mutableStateOf(Offset.Zero) }
        Icon(
            Icons.Default.DragHandle, "Drag into a folder",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .onGloballyPositioned { origin = it.boundsInRoot().topLeft }
                .pointerInput(what, targets) {
                    detectDragGestures(
                        onDragStart = { at -> carrying = what; carriedBy = Offset.Zero; carriedAt = origin + at },
                        onDragEnd = { drop() },
                        onDragCancel = { carrying = null; carriedBy = Offset.Zero }
                    ) { change, amount ->
                        change.consume()
                        carriedBy += amount
                        carriedAt += amount
                    }
                }
                .padding(12.dp)
        )
    }
    // The whole row picks a setlist or folder up, not only its handle.
    val rowOrigins = remember { HashMap<Carried, Offset>() }
    fun Modifier.carryable(what: Carried) = onGloballyPositioned { rowOrigins[what] = it.boundsInRoot().topLeft }
        .dragAnywhere(
            what to targets,
            onStart = { at -> carrying = what; carriedBy = Offset.Zero; carriedAt = (rowOrigins[what] ?: Offset.Zero) + at },
            onDrag = { amount -> carriedBy += amount; carriedAt += amount },
            onEnd = { drop() },
            onCancel = { carrying = null; carriedBy = Offset.Zero }
        )
    fun Modifier.carried(what: Carried) = if (carrying == what)
        zIndex(1f).graphicsLayer { translationX = carriedBy.x; translationY = carriedBy.y; alpha = 0.85f }
            .background(liftedColour) else this

    Column(Modifier.fillMaxSize()) {
        // Where we are: Setlists > Wind Ensemble > 2025-26
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { folderId = null }, modifier = Modifier.target(null).lit(null)) { Text("Setlists") }
            path.forEach { f ->
                Icon(Icons.Default.ChevronRight, null)
                TextButton(onClick = { folderId = f.id }, modifier = Modifier.target(f.id).lit(f.id)) { Text(f.name) }
            }
            Spacer(Modifier.weight(1f))
            AddButton("New folder") { naming = Naming.NewFolder(folderId) }
            AddButton("New setlist") { naming = Naming.NewSetlist(folderId) }
        }
        HorizontalDivider()
        ListSearch(library.setlists.size + library.folders.size, query, { query = it }, "Find a setlist", Modifier.padding(horizontal = 8.dp))

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
                val me = Carried.Dir(f.id)
                Row(Modifier.carried(me).target(f.id).lit(f.id).carryable(me), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        ListRow(
                            icon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ColourBar(f.color)
                                    Icon(Icons.Default.Folder, null, tint = f.color?.let { androidx.compose.ui.graphics.Color(it) } ?: MaterialTheme.colorScheme.primary)
                                }
                            },
                            title = f.name,
                            detail = library.setlistsUnder(f.id).size.let { n -> if (n == 1) "1 setlist" else "$n setlists" },
                            onClick = { folderId = f.id },
                            menu = listOf(
                                "Rename" to { naming = Naming.RenameFolder(f) },
                                "Colour..." to { colouringFolder = f },
                                "Delete (keeps its setlists)" to { state.change { deleteFolder(f.id) } },
                                "Merge its setlists into one..." to { joiningFolder = f },
                                "Delete with its setlists..." to { clearing = f }
                            )
                        )
                    }
                }
                HorizontalDivider()
            }
            items(setlists, key = { "s" + it.id }) { s ->
                val me = Carried.Set(s.id)
                Row(Modifier.carried(me).carryable(me), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                ListRow(
                    icon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ColourBar(s.color)
                            Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = s.color?.let { androidx.compose.ui.graphics.Color(it) } ?: androidx.compose.material3.LocalContentColor.current)
                        }
                    },
                    title = s.name,
                    detail = listOfNotNull(s.date, "${s.entries.size} songs").joinToString("  ·  "),
                    onClick = { openSetlist = s.id },
                    menu = listOf(
                        "Share with bandmates..." to { sharing = s.id },
                        "Colour..." to { colouring = s },
                        "Rename" to { naming = Naming.RenameSetlist(s) },
                        "Merge into another setlist..." to { joiningInto = s },
                        "Delete" to { state.change { deleteSetlist(s.id) } }
                    )
                )
                }
                }
                HorizontalDivider()
            }
        }
    }

    sharing?.let { id -> ShareSetlistDialog(state, id, onClose = { sharing = null }) }
    // Every setlist in a folder (and its folders) as one, named for the folder, in the folder.
    joiningFolder?.let { f ->
        val lists = library.setlistsUnder(f.id)
        AskName("One setlist, called", f.name, "Merge ${lists.size}", onDone = { name ->
            state.change {
                val into = addSetlist(name.ifBlank { f.name }, f.id)
                mergeSetlists(lists.map { it.id }, into.id)
            }
            joiningFolder = null
        }, onDismiss = { joiningFolder = null })
    }
    joiningInto?.let { s ->
        SetlistChooserDialog(
            state,
            onChosen = { target ->
                if (target.id != s.id) state.change { mergeSetlists(listOf(s.id), target.id) }
                joiningInto = null
            },
            onDismiss = { joiningInto = null }
        )
    }
    clearing?.let { f ->
        val lists = library.setlistsUnder(f.id)
        SheetDialog(
            title = "Delete ${f.name} and its setlists?",
            onDismiss = { clearing = null },
            buttons = {
                TextButton(onClick = { clearing = null }) { Text("Keep them") }
                TextButton(onClick = {
                    state.change {
                        lists.forEach { deleteSetlist(it.id) }
                        // Its folders inside it go too, deepest first.
                        fun under(id: String): List<String> = foldersIn(id).flatMap { under(it.id) + it.id }
                        under(f.id).forEach { deleteFolder(it) }
                        deleteFolder(f.id)
                    }
                    if (folderId == f.id) folderId = f.parentId
                    clearing = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        ) {
            Text(
                "${lists.size} setlist${if (lists.size == 1) "" else "s"} go, on all your devices. The songs in them stay in your library.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
    colouringFolder?.let { f ->
        ColourDialog("Colour for ${f.name}", f.color, onChosen = { state.setFolderColor(f.id, it); colouringFolder = null }, onDismiss = { colouringFolder = null })
    }
    colouring?.let { s ->
        ColourDialog("Colour for ${s.name}", s.color, onChosen = { state.setSetlistColor(s.id, it); colouring = null }, onDismiss = { colouring = null })
    }
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

/** What is being dragged: a setlist or a folder, by id. */
private sealed class Carried {
    abstract val id: String
    data class Set(override val id: String) : Carried()
    data class Dir(override val id: String) : Carried()
    /** A folder is not a place to drop itself. */
    val targetKey: String? get() = if (this is Dir) TARGET + id else null
}

private const val TARGET = "into:"

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
    var colouringEntry by remember { mutableStateOf<com.inksheets.core.SetlistEntry?>(null) }
    // Everything the song list offers for a song, here too.
    var editing by remember { mutableStateOf<com.inksheets.core.Song?>(null) }
    var merging by remember { mutableStateOf<com.inksheets.core.Song?>(null) }
    var recordingsFor by remember { mutableStateOf<com.inksheets.core.Song?>(null) }
    var addingElsewhere by remember { mutableStateOf<com.inksheets.core.Song?>(null) }
    var colouringSong by remember { mutableStateOf<com.inksheets.core.Song?>(null) }
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
        // Grab a song by its handle, or hold it anywhere, and drag it to its new place; it is
        // written when let go.
        val listState = rememberLazyListState()
        // A song removed from the library is not shown, but keeps its place: brought back from the
        // trash, it is back where it was.
        val order = remember(setlist.entries, version) { setlist.entries.filter { songs[it.songId] != null }.toMutableStateList() }
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
                    (if (lifted) Modifier.zIndex(1f).graphicsLayer { translationY = dragBy }
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    else Modifier)
                        // Anywhere on the song, not only its handle: hold it, then drag. A quick
                        // swipe still scrolls the list and a tap still opens the song.
                        .dragAnywhere(
                            entry.id,
                            onStart = { dragging = entry.id; dragBy = 0f },
                            onDrag = { dragTo(it.y) },
                            onEnd = { dropped() },
                            onCancel = { dropped() }
                        )
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
                        }
                    } else {
                        SongRow(
                            // Its colour in this setlist, where it has one; else its own.
                            song = song.copy(color = entry.color ?: song.color),
                            unsure = PartChoice.fit(song, state.profile) == PartChoice.Fit.UNKNOWN,
                            onColour = { colouringEntry = entry },
                            onOpen = { state.playSetlist(setlist.id, setlist.entries.indexOfFirst { it.id == entry.id }.coerceAtLeast(0)) },
                            onLook = { state.partFor(song)?.let { state.peeking = song to it } },
                            onEdit = { editing = song },
                            onAddToSetlist = { addingElsewhere = song },
                            onRecordings = { recordingsFor = song },
                            onMerge = { merging = song },
                            onDelete = { state.removeSong(song) },
                            colourLabel = "Colour in this setlist...",
                            onColourEverywhere = { colouringSong = song },
                            trailing = {
                                Text("${index + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 4.dp))
                                IconButton(onClick = { state.change { removeFromSetlist(setlist.id, entry.id) } }) {
                                    Icon(Icons.Default.Close, "Take out of setlist")
                                }
                            }
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    editing?.let { song -> SongEditorDialog(state, song, onClose = { editing = null }) }
    colouringSong?.let { song ->
        ColourDialog("Colour for ${song.title}", song.color, onChosen = { state.setSongColor(song.id, it); colouringSong = null }, onDismiss = { colouringSong = null })
    }
    merging?.let { song ->
        PickSongDialog(
            state, title = "Put \u201C${song.title}\u201D into which song?", exclude = song.id, near = song.title,
            onChosen = { into -> state.mergeSongs(song.id, into.id); merging = null },
            onDismiss = { merging = null }
        )
    }
    recordingsFor?.let { song -> AudioDialog(state, song, onClose = { recordingsFor = null }) }
    addingElsewhere?.let { song ->
        SetlistChooserDialog(
            state,
            onChosen = { other -> state.change { addToSetlist(other.id, song.id) }; addingElsewhere = null },
            onDismiss = { addingElsewhere = null }
        )
    }
    colouringEntry?.let { e ->
        ColourDialog(
            "Colour in “${setlist.name}”", e.color,
            onChosen = { state.setEntryColor(setlist.id, e.id, it); colouringEntry = null },
            onDismiss = { colouringEntry = null }
        )
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
        var query by remember { mutableStateOf("") }
        Column {
        ListSearch(library.setlists.size, query, { query = it }, "Find a setlist")
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(library.setlists.filter { matches(query, it.name, library.pathTo(it.folderId).joinToString(" ") { f -> f.name }) }, key = { it.id }) { s ->
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
