package com.inkslate.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.inkslate.core.ClassCodes
import com.inkslate.core.DocumentShelf
import com.inkslate.core.DocumentShelf.Filter
import com.inkslate.core.DocumentShelf.Sort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * The landing screen: your work, arranged the way a home screen is. The tablet and the computer
 * draw this same screen - see library-ui/README.md.
 *
 * Built only from folders the user has added, so it stays about coursework. With one folder added
 * (Classwork, say), Home *is* that folder: its class folders and documents are right there, rather
 * than behind a tile that has to be opened every time.
 *
 * Below the recent documents and the folders, every document in the library, sorted and narrowed
 * the way the music library's songs are - by name, class, folder, how recent - and broken up under
 * headings. Documents (and folders) are moved by dragging them onto a folder.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryHome(
    backend: LibraryBackend,
    platform: LibraryPlatform,
    appName: String,
    refreshKey: Int,
    onOpenFile: (File) -> Unit,
    /** The file browser, to add folders to Home from. */
    onBrowse: () -> Unit,
    onOpenSettings: () -> Unit,
    /** A new document, into the folder being looked at (null: wherever new documents go). */
    onNewDocument: (File?) -> Unit
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val drag = remember { DragToFolder() }

    var roots by remember { mutableStateOf<List<File>>(emptyList()) }
    // Where we are below Home. With one folder added, Home is that folder and the trail starts
    // inside it; with several, the trail starts at one of them.
    var trail by remember { mutableStateOf<List<File>>(emptyList()) }
    val home = roots.singleOrNull()
    val here: File? = trail.lastOrNull() ?: home
    val atTop = trail.isEmpty()

    var folders by remember { mutableStateOf<List<File>>(emptyList()) }
    var recents by remember { mutableStateOf<List<File>>(emptyList()) }
    var starred by remember { mutableStateOf<List<File>>(emptyList()) }
    var docs by remember { mutableStateOf<List<DocumentShelf.Item>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }
    fun refresh() { reload++ }

    // How the documents are listed, remembered between visits.
    var sort by remember { mutableStateOf(runCatching { Sort.valueOf(backend.pref(K_SORT) ?: "") }.getOrDefault(Sort.RECENT)) }
    var filters by remember {
        mutableStateOf(backend.pref(K_FILTERS).orEmpty().split(',').mapNotNull { n -> Filter.entries.firstOrNull { it.name == n } }.toSet())
    }
    var deep by remember { mutableStateOf(backend.pref(K_DEEP) == "on") }
    var query by remember { mutableStateOf("") }
    val searching = query.isNotBlank()

    // Several things picked at once, by path.
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selecting = selected.isNotEmpty()

    // What a menu or dialog is open for.
    var menuFor by remember { mutableStateOf<File?>(null) }
    var renaming by remember { mutableStateOf<File?>(null) }
    var deleting by remember { mutableStateOf<List<File>>(emptyList()) }
    var moving by remember { mutableStateOf<List<File>>(emptyList()) }
    var newFolderIn by remember { mutableStateOf<File?>(null) }
    var organizing by remember { mutableStateOf<Pair<Boolean, File?>?>(null) }

    // ---- loading -------------------------------------------------------------------------

    // Every document under where we are - the whole library at the top - is read once; sorting,
    // filtering and searching are then done on that, so a filter answers at once.
    suspend fun load(): Loaded = withContext(Dispatchers.IO) {
        val r = backend.libraryFolders()
        val single = r.singleOrNull()
        val at = trail.lastOrNull() ?: single
        val inside = trail.isNotEmpty()
        Loaded(
            roots = r,
            folders = if (at != null) backend.subfolders(at) else r,
            recents = if (!inside) backend.recents(16) else emptyList(),
            starred = if (!inside) backend.pinned().filter { it.exists() } else emptyList(),
            docs = if (inside) backend.documentsUnder(at!!, depth = if (deep || query.isNotBlank()) 8 else 1)
            else r.flatMap { backend.documentsUnder(it, depth = 8) }.distinctBy { it.file.absolutePath }
        )
    }

    fun show(l: Loaded) {
        roots = l.roots; folders = l.folders; recents = l.recents; starred = l.starred; docs = l.docs
        // A trail into a folder that is gone (deleted, or removed from Home) goes back up.
        if (trail.isNotEmpty() && !trail.last().isDirectory) trail = trail.takeWhile { it.isDirectory }
    }

    LaunchedEffect(refreshKey, reload, trail, deep, searching) {
        loading = docs.isEmpty() && folders.isEmpty()
        show(load())
        loading = false
    }

    // Documents arrive from other devices while this screen is open, by file sync, which tells
    // this app nothing. Looked at again every few seconds and redrawn only when something changed.
    LaunchedEffect(trail, deep, searching) {
        while (true) {
            delay(HOME_REFRESH_MS)
            if (drag.active) continue
            val fresh = load()
            if (fresh != Loaded(roots, folders, recents, starred, docs)) show(fresh)
        }
    }

    // ---- moving, with a way back -------------------------------------------------------

    /** Move each of [moves] (thing to folder), then offer to put them all back. */
    fun moveAll(moves: List<Pair<File, File>>, automatic: Boolean = false) {
        if (moves.isEmpty()) return
        scope.launch {
            val done = ArrayList<Pair<File, File>>()   // where it landed, where it came from
            val failed = ArrayList<String>()
            withContext(Dispatchers.IO) {
                for ((item, folder) in moves) {
                    val from = item.parentFile ?: continue
                    backend.move(item, folder).fold(
                        onSuccess = { done.add(it to from) },
                        onFailure = { failed.add(it.message ?: "Could not move ${item.name}") }
                    )
                }
            }
            selected = emptySet()
            refresh()
            if (done.isEmpty()) {
                snackbar.showSnackbar(failed.firstOrNull() ?: "Nothing was moved")
                return@launch
            }
            val targets = moves.map { it.second }.distinctBy { it.absolutePath }
            val message = buildString {
                append(if (automatic) "Filed " else "Moved ")
                append(if (done.size == 1) done[0].first.name else "${done.size} items")
                append(" to ")
                append(if (targets.size == 1) targets[0].name else "${targets.size} folders")
                if (failed.isNotEmpty()) append(" (${failed.size} could not be moved)")
            }
            val result = snackbar.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) {
                withContext(Dispatchers.IO) {
                    for ((now, from) in done) backend.move(now, from)
                    // Put back by hand: not filed again by itself.
                    if (automatic) {
                        val skip = backend.pref(K_AUTO_SKIP).orEmpty().lines().filter { it.isNotBlank() }.toMutableSet()
                        val back = done.map { (now, from) -> File(from, now.name).absolutePath }
                        backend.setPref(K_AUTO_SKIP, (skip + back).joinToString("\n"))
                    }
                }
                refresh()
            }
        }
    }

    // Filing by course number, by itself, when it has been switched on. Only moves with one
    // clear folder and no name already taken there; anything put back by hand is left alone.
    LaunchedEffect(refreshKey, roots) {
        while (true) {
            if (backend.pref(AUTO_FILE) == "on" && roots.isNotEmpty() && !drag.active) {
                val moves = withContext(Dispatchers.IO) {
                    val skip = backend.pref(K_AUTO_SKIP).orEmpty().lines().toSet()
                    planFiling(backend, null).suggestions
                        .filter { it.alternatives.isEmpty() && !it.clash && it.document.absolutePath !in skip }
                        .map { it.document to it.target }
                }
                if (moves.isNotEmpty()) moveAll(moves, automatic = true)
            }
            delay(AUTO_FILE_MS)
        }
    }

    fun carry(item: File): List<File> {
        if (item.absolutePath !in selected) return listOf(item)
        return selected.map(::File).filter { it.exists() }
    }
    val dropInto: (List<File>, File) -> Unit = { items, folder ->
        if (folder.isDirectory) {
            moveAll(items.filter { it.parentFile?.absolutePath != folder.absolutePath }.map { it to folder })
        }
    }
    fun toggle(f: File) {
        selected = if (f.absolutePath in selected) selected - f.absolutePath else selected + f.absolutePath
    }
    fun tap(f: File) {
        when {
            selecting -> toggle(f)
            f.isDirectory -> { trail = pathTo(f, roots); query = "" }
            else -> onOpenFile(f)
        }
    }
    fun menu(f: File) { if (selecting) toggle(f) else menuFor = f }

    // ---- back / Escape -------------------------------------------------------------------

    platform.backHandler(drag.active || selecting || query.isNotEmpty() || trail.isNotEmpty()) {
        when {
            drag.active -> drag.cancel()
            selecting -> selected = emptySet()
            query.isNotEmpty() -> query = ""
            else -> trail = trail.dropLast(1)
        }
    }

    // ---- what is listed ----------------------------------------------------------------------

    val counts = remember(docs, roots) { DocumentShelf.counts(docs, roots) }
    val shown = remember(docs, roots, sort, filters, query) {
        DocumentShelf.apply(docs, DocumentShelf.Query(sort = sort, filters = filters, text = query), roots)
    }
    val groups = remember(shown, sort, roots) { DocumentShelf.group(shown, sort, roots) }

    var origin by remember { mutableStateOf(Offset.Zero) }
    val listState = rememberLazyListState()

    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                if (selecting) {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        navigationIcon = {
                            IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Default.Close, "Stop selecting") }
                        },
                        title = { Text("${selected.size} selected") },
                        actions = {
                            IconButton(onClick = {
                                selected = selected + shown.map { it.file.absolutePath }
                            }) { Icon(Icons.Default.SelectAll, "Select every document listed") }
                            IconButton(onClick = { moving = selected.map(::File) }) {
                                Icon(Icons.AutoMirrored.Filled.DriveFileMove, "Move to a folder")
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        val items = selected.map(::File)
                                        // Star them all, unless they all are: then unstar them all.
                                        val all = items.all { backend.isPinned(it) }
                                        items.filter { backend.isPinned(it) == all }.forEach { backend.togglePin(it) }
                                    }
                                    selected = emptySet(); refresh()
                                }
                            }) { Icon(Icons.Default.Star, "Star") }
                            IconButton(onClick = { deleting = selected.map(::File) }) {
                                Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    )
                } else {
                    TopAppBar(
                        navigationIcon = {
                            if (trail.isNotEmpty()) {
                                IconButton(onClick = { trail = trail.dropLast(1) }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up one level")
                                }
                            }
                        },
                        title = {
                            Text(
                                trail.lastOrNull()?.name ?: appName,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        },
                        actions = {
                            platform.linkIndicator()
                            IconButton(onClick = { organizing = true to (if (atTop) null else here) }, enabled = roots.isNotEmpty()) {
                                Icon(Icons.Default.AutoAwesome, "Sort into class folders")
                            }
                            IconButton(onClick = onBrowse) { Icon(Icons.Default.Folder, "All files") }
                            IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings") }
                        }
                    )
                }
            },
            floatingActionButton = {
                if (!selecting && !drag.active) {
                    ExtendedFloatingActionButton(
                        onClick = { onNewDocument(if (atTop && home == null) null else here) },
                        icon = { Icon(Icons.Default.Add, null) },
                        text = { Text(if (!atTop && here != null) "New in ${here.name}" else "New", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {

                // Search is always to hand: everything in the library, or in this folder and below.
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Clear") }
                    },
                    placeholder = { Text(if (!atTop && here != null) "Search in ${here.name}" else "Search your documents") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
                )

                if (trail.isNotEmpty()) {
                    Breadcrumb(trail, home, drag, onHome = { trail = emptyList() }, onJump = { i -> trail = trail.take(i + 1) }, onDrop = dropInto)
                }

                if (loading) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                    return@Column
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 120.dp)) {

                        if (roots.isEmpty()) {
                            item { EmptyLibrary(onBrowse) }
                        }

                        // ---- starred and recent, at the top only, and not while searching ----
                        if (atTop && !searching) {
                            if (starred.isNotEmpty()) {
                                item { SectionHeader("Starred") }
                                item {
                                    ScrollingRow(starred, key = { it.absolutePath }) { f ->
                                        if (f.isDirectory) {
                                            FolderTile(f, backend, drag, starred = true, selected = f.absolutePath in selected,
                                                onOpen = { tap(f) }, onMenu = { menu(f) }, carry = { carry(f) }, onDrop = dropInto)
                                        } else {
                                            DocumentCard(f, backend, drag, starred = true, selected = f.absolutePath in selected,
                                                onOpen = { tap(f) }, onMenu = { menu(f) }, carry = { carry(f) }, onDrop = dropInto)
                                        }
                                    }
                                }
                            }
                            if (recents.isNotEmpty()) {
                                item { SectionHeader("Recent") }
                                item {
                                    ScrollingRow(recents, key = { it.absolutePath }) { f ->
                                        DocumentCard(f, backend, drag, selected = f.absolutePath in selected,
                                            onOpen = { tap(f) }, onMenu = { menu(f) }, carry = { carry(f) }, onDrop = dropInto)
                                    }
                                }
                            }
                        }

                        // ---- folders: a grid that wraps, so none of them is ever off to one side ----
                        if (!searching && (folders.isNotEmpty() || here != null)) {
                            item {
                                Row(
                                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        when {
                                            here == null -> "Your folders"
                                            atTop -> "Folders in ${here.name}"
                                            else -> "Folders"
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (here != null) {
                                        TextButton(onClick = { newFolderIn = here }) {
                                            Icon(Icons.Default.CreateNewFolder, null, Modifier.size(18.dp))
                                            Text(" New folder")
                                        }
                                    }
                                    if (atTop) {
                                        TextButton(onClick = onBrowse) {
                                            Icon(Icons.Default.LibraryAdd, null, Modifier.size(18.dp))
                                            Text(" Add to Home")
                                        }
                                    }
                                }
                            }
                            item {
                                FlowRow(
                                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    folders.forEach { f ->
                                        FolderTile(f, backend, drag, selected = f.absolutePath in selected,
                                            onOpen = { tap(f) }, onMenu = { menu(f) }, carry = { carry(f) }, onDrop = dropInto)
                                    }
                                }
                            }
                        }

                        // ---- the documents: sorted, narrowed, under headings ----
                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 18.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    when {
                                        searching -> "Found"
                                        atTop -> "All documents"
                                        else -> "Documents"
                                    } + "  ·  ${shown.size}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        item {
                            FlowRow(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                // How the list is ordered...
                                Sort.entries.forEach { o ->
                                    FilterChip(selected = sort == o, onClick = { sort = o; backend.setPref(K_SORT, o.name) }, label = { Text(o.label) })
                                }
                                VerticalDivider(Modifier.height(32.dp).padding(horizontal = 4.dp, vertical = 6.dp))
                                // ...then what it is narrowed to: only filters that would change something.
                                Filter.entries.forEach { f ->
                                    val n = counts[f] ?: 0
                                    val on = f in filters
                                    if (on || n in 1 until docs.size) {
                                        FilterChip(
                                            selected = on,
                                            onClick = {
                                                filters = if (on) filters - f else filters + f
                                                backend.setPref(K_FILTERS, filters.joinToString(",") { it.name })
                                            },
                                            label = { Text("${f.label} ($n)") }
                                        )
                                    }
                                }
                                if (!atTop && !searching) {
                                    FilterChip(
                                        selected = deep,
                                        onClick = { deep = !deep; backend.setPref(K_DEEP, if (deep) "on" else null) },
                                        label = { Text("Include subfolders") }
                                    )
                                }
                                if (filters.isNotEmpty()) {
                                    TextButton(onClick = { filters = emptySet(); backend.setPref(K_FILTERS, null) }) { Text("Clear filters") }
                                }
                            }
                        }

                        if (shown.isEmpty()) {
                            item {
                                Text(
                                    when {
                                        searching -> "Nothing matching \"$query\""
                                        docs.isEmpty() && folders.isEmpty() && here != null -> "This folder is empty."
                                        docs.isEmpty() -> "No documents here."
                                        else -> "Nothing matches these filters."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                        groups.forEach { g ->
                            stickyHeader(key = "group:" + g.title) {
                                Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        g.title + "  ·  " + g.items.size,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp)
                                    )
                                }
                            }
                            items(g.items, key = { "doc:" + it.file.absolutePath }) { e ->
                                DocumentRow(
                                    e, backend, drag, roots,
                                    showFolder = atTop || deep || searching || sort != Sort.FOLDER,
                                    selected = e.file.absolutePath in selected,
                                    selecting = selecting,
                                    onOpen = { tap(e.file) }, onMenu = { menu(e.file) },
                                    carry = { carry(e.file) }, onDrop = dropInto
                                )
                            }
                        }
                    }
                    platform.verticalScrollbar(this, listState)
                }
            }
        }

        // ---- while something is carried: somewhere to put it, and it under the finger ----
        if (drag.active) {
            DropDock(
                drag, backend, roots, here,
                modifier = Modifier.align(Alignment.BottomCenter),
                onDrop = dropInto
            )
            val density = LocalDensity.current
            val at = drag.pointer - origin
            Surface(
                shape = RoundedCornerShape(10.dp),
                tonalElevation = 6.dp, shadowElevation = 8.dp,
                color = if (drag.over != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                modifier = Modifier.offset { IntOffset((at.x + with(density) { 12.dp.toPx() }).roundToInt(), (at.y - with(density) { 24.dp.toPx() }).roundToInt()) }
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.DriveFileMove, null, Modifier.size(18.dp))
                    Text(
                        "  " + (drag.carrying.singleOrNull()?.name ?: "${drag.carrying.size} items") +
                            (drag.over?.let { "  →  ${it.name}" } ?: ""),
                        style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 320.dp)
                    )
                }
            }
        }
    }

    // ---- the menu for one thing --------------------------------------------------------------

    menuFor?.let { target ->
        ModalBottomSheet(onDismissRequest = { menuFor = null }, sheetState = rememberModalBottomSheetState()) {
            ItemActions(
                target, backend, roots, here,
                onOpen = { menuFor = null; tap(target) },
                onShowInFolder = { menuFor = null; trail = pathTo(target.parentFile!!, roots); query = "" },
                onSelect = { menuFor = null; selected = setOf(target.absolutePath) },
                onToggleStar = { menuFor = null; scope.launch { withContext(Dispatchers.IO) { backend.togglePin(target) }; refresh() } },
                onMoveTo = { folder -> menuFor = null; moveAll(listOf(target to folder)) },
                onMove = { menuFor = null; moving = listOf(target) },
                onRename = { menuFor = null; renaming = target },
                onDelete = { menuFor = null; deleting = listOf(target) },
                onNewFolder = { menuFor = null; newFolderIn = target },
                onOrganize = { menuFor = null; organizing = true to target },
                onSetDefault = {
                    menuFor = null
                    scope.launch {
                        withContext(Dispatchers.IO) { backend.defaultNewFolder = target }
                        snackbar.showSnackbar("New documents will go in ${target.name}")
                    }
                },
                onRemoveFromHome = {
                    menuFor = null
                    scope.launch {
                        withContext(Dispatchers.IO) { backend.removeLibraryFolder(target) }
                        trail = emptyList(); refresh()
                        snackbar.showSnackbar("${target.name} is no longer on Home. Nothing was deleted.")
                    }
                }
            )
        }
    }

    // ---- dialogs ----------------------------------------------------------------------------

    renaming?.let { target ->
        RenameDialog(target, onDismiss = { renaming = null }) { name ->
            renaming = null
            scope.launch {
                withContext(Dispatchers.IO) { backend.rename(target, name) }.fold(
                    onSuccess = { refresh(); snackbar.showSnackbar("Renamed to ${it.name}") },
                    onFailure = { snackbar.showSnackbar(it.message ?: "Could not rename") }
                )
            }
        }
    }

    if (deleting.isNotEmpty()) {
        val targets = deleting
        val folder = targets.any { it.isDirectory }
        AlertDialog(
            onDismissRequest = { deleting = emptyList() },
            icon = { Icon(Icons.Default.Delete, null) },
            title = {
                Text(
                    when {
                        targets.size > 1 -> "Delete ${targets.size} items?"
                        folder -> "Delete this folder?"
                        else -> "Delete this document?"
                    }
                )
            },
            text = {
                Text(buildString {
                    append(if (targets.size == 1) "\"${targets[0].name}\" will be removed from storage" else "They will be removed from storage")
                    if (folder) append(", along with everything inside")
                    append(". This cannot be undone from the app.")
                })
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = emptyList()
                    scope.launch {
                        val failed = withContext(Dispatchers.IO) { targets.mapNotNull { backend.delete(it).exceptionOrNull()?.message } }
                        selected = emptySet(); refresh()
                        snackbar.showSnackbar(
                            failed.firstOrNull() ?: if (targets.size == 1) "Deleted ${targets[0].name}" else "Deleted ${targets.size} items"
                        )
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = emptyList() }) { Text("Cancel") } }
        )
    }

    if (moving.isNotEmpty()) {
        val items = moving
        FolderPicker(
            backend, items,
            title = if (items.size == 1) "Move \"${items[0].name}\"" else "Move ${items.size} items",
            onDismiss = { moving = emptyList() },
            onPick = { folder -> moving = emptyList(); moveAll(items.map { it to folder }) }
        )
    }

    newFolderIn?.let { parent ->
        NameDialog(
            title = "New folder in ${parent.name}", initial = "", confirm = "Make",
            onDismiss = { newFolderIn = null }
        ) { name ->
            newFolderIn = null
            scope.launch {
                withContext(Dispatchers.IO) { backend.createFolder(parent, name) }.fold(
                    onSuccess = { refresh() },
                    onFailure = { snackbar.showSnackbar(it.message ?: "Could not make the folder") }
                )
            }
        }
    }

    organizing?.let { (_, within) ->
        OrganizeDialog(
            backend, within,
            onDismiss = { organizing = null; refresh() },
            onMove = { moves -> organizing = null; moveAll(moves) }
        )
    }
}

/** How often Home looks for documents that changed while it was open. */
private const val HOME_REFRESH_MS = 5_000L
/** How often, at most, filing by itself looks for something to file. */
private const val AUTO_FILE_MS = 30_000L

private const val K_SORT = "home.sort"
private const val K_FILTERS = "home.filters"
private const val K_DEEP = "home.includeSubfolders"
/** Documents put back by hand after being filed by themselves: not filed again. */
private const val K_AUTO_SKIP = "home.autoFile.skip"

private data class Loaded(
    val roots: List<File>,
    val folders: List<File>,
    val recents: List<File>,
    val starred: List<File>,
    val docs: List<DocumentShelf.Item>
)

/** The trail from Home down to [folder]: with one folder on Home, that folder is not a step. */
private fun pathTo(folder: File, roots: List<File>): List<File> {
    val root = roots.firstOrNull { folder.absolutePath == it.absolutePath || isInside(folder, it) } ?: return listOf(folder)
    val steps = generateSequence(folder) { f -> if (f.absolutePath == root.absolutePath) null else f.parentFile }.toList().reversed()
    return if (roots.size == 1) steps.drop(1) else steps
}

// ---- pieces ----------------------------------------------------------------------------------

@Composable
private fun Breadcrumb(
    trail: List<File>,
    home: File?,
    drag: DragToFolder,
    onHome: () -> Unit,
    onJump: (Int) -> Unit,
    onDrop: (List<File>, File) -> Unit
) {
    // Each step is also somewhere to drop: dragging a document onto a step moves it up there.
    val over = drag.over
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val homeMod = if (home != null) Modifier.dropTarget(drag, "crumb:home", home) else Modifier
        IconButton(onClick = onHome, modifier = homeMod.size(34.dp).highlight(home != null && over?.absolutePath == home.absolutePath)) {
            Icon(Icons.Default.Home, "Home", Modifier.size(18.dp))
        }
        trail.forEachIndexed { i, f ->
            Text("  ›  ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(
                onClick = { onJump(i) },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                modifier = Modifier.dropTarget(drag, "crumb:" + f.absolutePath, f).highlight(over?.absolutePath == f.absolutePath)
            ) {
                Text(f.name, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                    fontWeight = if (i == trail.lastIndex) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun Modifier.highlight(on: Boolean): Modifier =
    if (on) this.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else this

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
    )
}

/**
 * A row that runs off the side, with arrows at its ends while there is more that way - a mouse
 * wheel turns a list up and down, not sideways, so on a computer these were the only way along.
 */
@Composable
private fun <T> ScrollingRow(list: List<T>, key: (T) -> Any, content: @Composable (T) -> Unit) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxWidth()) {
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(list, key = key) { content(it) }
        }
        if (state.canScrollBackward) {
            RowArrow(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Back along", Modifier.align(Alignment.CenterStart)) {
                scope.launch { state.animateScrollToItem((state.firstVisibleItemIndex - 3).coerceAtLeast(0)) }
            }
        }
        if (state.canScrollForward) {
            RowArrow(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Further along", Modifier.align(Alignment.CenterEnd)) {
                scope.launch { state.animateScrollToItem((state.firstVisibleItemIndex + 3).coerceAtMost(list.lastIndex.coerceAtLeast(0))) }
            }
        }
    }
}

@Composable
private fun RowArrow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(50), tonalElevation = 3.dp, shadowElevation = 3.dp,
        modifier = modifier.padding(horizontal = 4.dp).size(36.dp)
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, label) }
    }
}

@Composable
private fun EmptyLibrary(onBrowse: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("Add your coursework folders", style = MaterialTheme.typography.titleMedium)
            Text(
                "Home shows the folders you add, so it stays about your assignments rather than " +
                    "everything on the device. Add one folder - Classwork, say - and its class folders " +
                    "and documents are right here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp)
            )
            OutlinedButton(onClick = onBrowse) {
                Icon(Icons.Default.Folder, null, Modifier.size(17.dp))
                Text("  Browse files")
            }
        }
    }
}

/**
 * A folder as a square showing a little of what is inside: four page corners tell the problem
 * sets from the lecture notes without reading a word. Also somewhere to drop documents.
 */
@Composable
private fun FolderTile(
    folder: File,
    backend: LibraryBackend,
    drag: DragToFolder,
    starred: Boolean = false,
    selected: Boolean,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    carry: () -> List<File>,
    onDrop: (List<File>, File) -> Unit
) {
    var preview by remember(folder.absolutePath) { mutableStateOf<List<File>>(emptyList()) }
    var count by remember(folder.absolutePath) { mutableStateOf(0) }
    var isDefault by remember(folder.absolutePath) { mutableStateOf(false) }
    LaunchedEffect(folder.absolutePath) {
        withContext(Dispatchers.IO) {
            Triple(backend.folderPreview(folder, 4), backend.itemCount(folder), backend.isDefaultNewFolder(folder))
        }.let { (p, c, d) -> preview = p; count = c; isDefault = d }
    }
    val hovered = drag.over?.absolutePath == folder.absolutePath
    val carried = drag.carrying.any { it.absolutePath == folder.absolutePath }

    Card(
        modifier = Modifier
            .width(150.dp)
            .dropTarget(drag, (if (starred) "star:" else "tile:") + folder.absolutePath, folder)
            .highlight(hovered || selected)
            .openMenuOrDrag(drag, folder.name, onOpen, onMenu, carry, onDrop),
        colors = CardDefaults.cardColors(
            containerColor = when {
                hovered -> MaterialTheme.colorScheme.primaryContainer
                carried -> MaterialTheme.colorScheme.surfaceContainerHighest
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(1.25f).padding(8.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (row in 0 until 2) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                            for (col in 0 until 2) {
                                Box(
                                    Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(5.dp))
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    preview.getOrNull(row * 2 + col)?.let { Thumb(it, backend) }
                                }
                            }
                        }
                    }
                }
                if (hovered) {
                    Icon(Icons.Default.FolderOpen, null, Modifier.align(Alignment.Center).size(40.dp), tint = MaterialTheme.colorScheme.primary)
                }
                if (starred) {
                    Icon(Icons.Default.Star, null, Modifier.align(Alignment.TopEnd).size(15.dp), tint = MaterialTheme.colorScheme.primary)
                }
                if (selected) {
                    Icon(Icons.Default.CheckCircle, null, Modifier.align(Alignment.TopStart).size(18.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Column(Modifier.padding(start = 10.dp, end = 8.dp, bottom = 8.dp)) {
                Text(folder.name.ifEmpty { folder.absolutePath }, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (isDefault) "new documents go here" else if (count == 1) "1 item" else "$count items",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isDefault) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun DocumentCard(
    file: File,
    backend: LibraryBackend,
    drag: DragToFolder,
    starred: Boolean = false,
    selected: Boolean,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    carry: () -> List<File>,
    onDrop: (List<File>, File) -> Unit
) {
    Card(
        modifier = Modifier.width(128.dp).highlight(selected).openMenuOrDrag(drag, file.name, onOpen, onMenu, carry, onDrop)
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().aspectRatio(0.78f).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Thumb(file, backend)
                if (starred) {
                    Icon(Icons.Default.Star, null, Modifier.align(Alignment.TopEnd).padding(5.dp).size(14.dp), tint = MaterialTheme.colorScheme.primary)
                }
                if (selected) {
                    Icon(Icons.Default.CheckCircle, null, Modifier.align(Alignment.TopStart).padding(5.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                file.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun DocumentRow(
    entry: DocumentShelf.Item,
    backend: LibraryBackend,
    drag: DragToFolder,
    roots: List<File>,
    showFolder: Boolean,
    selected: Boolean,
    selecting: Boolean,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    carry: () -> List<File>,
    onDrop: (List<File>, File) -> Unit
) {
    val carried = drag.carrying.any { it.absolutePath == entry.file.absolutePath }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 3.dp)
            .openMenuOrDrag(drag, entry.name, onOpen, onMenu, carry, onDrop),
        colors = CardDefaults.cardColors(
            containerColor = when {
                selected -> MaterialTheme.colorScheme.secondaryContainer
                carried -> MaterialTheme.colorScheme.surfaceContainerHighest
                else -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (selecting) {
                Icon(
                    if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    if (selected) "Selected" else "Not selected",
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box(
                Modifier.size(44.dp, 56.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) { Thumb(entry.file, backend) }
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        if (showFolder) { append(DocumentShelf.folderLabel(entry.file, roots)); append("  ·  ") }
                        append(relativeTime(entry.modified))
                        if (entry.annotated) append("  ·  written on")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * The strip of folders along the bottom while something is being carried.
 *
 * The folder grid may be scrolled away by the time a document far down the list is picked up,
 * and a folder inside another is not on the screen at all. So: up a level, the folders here, and
 * - rest on one for a moment - the folders inside it, as deep as it goes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DropDock(
    drag: DragToFolder,
    backend: LibraryBackend,
    roots: List<File>,
    here: File?,
    modifier: Modifier,
    onDrop: (List<File>, File) -> Unit
) {
    // Where the strip is showing: here, or a folder it has been opened into.
    var at by remember { mutableStateOf(here) }
    var folders by remember { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(at, roots) {
        folders = withContext(Dispatchers.IO) { at?.let { backend.subfolders(it) } ?: roots }
    }
    val parent = at?.parentFile?.takeIf { p -> roots.any { p.absolutePath == it.absolutePath || isInside(p, it) } }
    // Resting on a folder that has folders of its own opens it in the strip; resting on the one
    // above goes back up. Dropping on either still moves there.
    val over = drag.over
    LaunchedEffect(over) {
        if (over == null || over.absolutePath == at?.absolutePath) return@LaunchedEffect
        val isParent = over.absolutePath == parent?.absolutePath
        if (!isParent && folders.none { it.absolutePath == over.absolutePath }) return@LaunchedEffect
        delay(SPRING_MS)
        if (drag.over?.absolutePath == over.absolutePath &&
            (isParent || withContext(Dispatchers.IO) { backend.subfolders(over).isNotEmpty() })
        ) at = over
    }
    Surface(
        modifier = modifier.fillMaxWidth().padding(10.dp),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 8.dp, shadowElevation = 10.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                at.let { a -> if (a == null) "Drop on a folder" else "Drop on a folder in ${a.name}  ·  rest on one to open it" },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (parent != null) {
                    DockChip(parent, "Up to ${parent.name}", Icons.Default.ArrowUpward, drag, highlighted = drag.over?.absolutePath == parent.absolutePath)
                }
                at?.let { a ->
                    if (drag.canDrop(a)) DockChip(a, "Into ${a.name}", Icons.Default.FolderOpen, drag, highlighted = drag.over?.absolutePath == a.absolutePath, keySuffix = ":self")
                }
                folders.forEach { f ->
                    DockChip(f, f.name, Icons.Default.Folder, drag, highlighted = drag.over?.absolutePath == f.absolutePath)
                }
            }
        }
    }
}

@Composable
private fun DockChip(
    folder: File,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    drag: DragToFolder,
    highlighted: Boolean,
    keySuffix: String = ""
) {
    val usable = drag.canDrop(folder)
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.dropTarget(drag, DOCK + folder.absolutePath + keySuffix, folder)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, Modifier.size(18.dp))
            Text(
                " $label", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (usable) androidx.compose.ui.graphics.Color.Unspecified else MaterialTheme.colorScheme.outline,
                modifier = Modifier.widthIn(max = 200.dp)
            )
        }
    }
}

@Composable
private fun ItemActions(
    target: File,
    backend: LibraryBackend,
    roots: List<File>,
    here: File?,
    onOpen: () -> Unit,
    onShowInFolder: () -> Unit,
    onSelect: () -> Unit,
    onToggleStar: () -> Unit,
    onMoveTo: (File) -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onNewFolder: () -> Unit,
    onOrganize: () -> Unit,
    onSetDefault: () -> Unit,
    onRemoveFromHome: () -> Unit
) {
    var starred by remember(target.absolutePath) { mutableStateOf(false) }
    // Where its course number says it belongs - one tap rather than a trip through the picker.
    var classFolder by remember(target.absolutePath) { mutableStateOf<File?>(null) }
    LaunchedEffect(target.absolutePath) {
        withContext(Dispatchers.IO) {
            starred = backend.isPinned(target)
            if (target.isFile) {
                classFolder = ClassCodes.suggest(listOf(target), backend.allFolders(roots))
                    .firstOrNull { it.alternatives.isEmpty() && !it.clash }?.target
            }
        }
    }
    val isRoot = roots.any { it.absolutePath == target.absolutePath }
    val elsewhere = target.parentFile?.absolutePath != here?.absolutePath && !isRoot
    Column(Modifier.padding(bottom = 20.dp)) {
        Text(
            target.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 10.dp)
        )
        HorizontalDivider()
        ActionRow(if (target.isDirectory) Icons.Default.FolderOpen else Icons.Default.PictureAsPdf, "Open", onOpen)
        classFolder?.let { f -> ActionRow(Icons.Default.AutoAwesome, "Move to ${f.name}", { onMoveTo(f) }) }
        if (elsewhere && target.parentFile != null) ActionRow(Icons.Default.Folder, "Show in folder", onShowInFolder)
        ActionRow(Icons.Default.CheckCircle, "Select", onSelect)
        ActionRow(if (starred) Icons.Default.Star else Icons.Default.StarBorder, if (starred) "Remove star" else "Star", onToggleStar)
        if (!isRoot) ActionRow(Icons.AutoMirrored.Filled.DriveFileMove, "Move to...", onMove)
        if (target.isDirectory) {
            ActionRow(Icons.Default.CreateNewFolder, "New folder inside", onNewFolder)
            ActionRow(Icons.Default.AutoAwesome, "Sort into class folders", onOrganize)
            ActionRow(Icons.AutoMirrored.Filled.PlaylistAdd, "Put new documents here", onSetDefault)
        }
        ActionRow(Icons.Default.DriveFileRenameOutline, "Rename", onRename)
        if (isRoot) ActionRow(Icons.Default.RemoveCircleOutline, "Remove from Home", onRemoveFromHome)
        ActionRow(Icons.Default.Delete, "Delete", onDelete, danger = true)
    }
}

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, danger: Boolean = false) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 22.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Icon(icon, null, Modifier.size(21.dp), tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.bodyLarge, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }
    }
}

/**
 * Rename, with the name chosen and the extension left out of the selection - and put back if it
 * gets typed away, since a worksheet without ".pdf" opens nowhere.
 */
@Composable
private fun RenameDialog(target: File, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    val ext = if (target.isFile) target.extension else ""
    var value by remember(target) {
        mutableStateOf(TextFieldValue(target.name, TextRange(0, if (ext.isNotEmpty()) target.name.length - ext.length - 1 else target.name.length)))
    }
    fun finalName(): String {
        val typed = value.text.trim()
        return if (ext.isNotEmpty() && !typed.endsWith(".$ext", ignoreCase = true)) "$typed.$ext" else typed
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
        title = { Text("Rename") },
        text = {
            Column {
                OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, label = { Text("Name") })
                if (target.isFile) {
                    Text(
                        "Your handwriting and pictures are stored inside the document, so they follow the new name.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = value.text.isNotBlank() && finalName() != target.name, onClick = { onRename(finalName()) }) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.CreateNewFolder, null) },
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onDone(name.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
internal fun Thumb(file: File, backend: LibraryBackend) {
    var bmp by remember(file.absolutePath, file.lastModified()) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(file.absolutePath) { mutableStateOf(false) }
    LaunchedEffect(file.absolutePath, file.lastModified()) {
        val result = withContext(Dispatchers.IO) { runCatching { backend.thumbnail(file) }.getOrNull() }
        if (result == null) failed = true else bmp = result
    }
    val current = bmp
    when {
        current != null -> Image(bitmap = current, contentDescription = file.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        failed -> Icon(
            if (file.extension.equals("pdf", true)) Icons.Default.PictureAsPdf else Icons.Default.Image,
            null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        else -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
    }
}

/** How long resting on a folder in the strip takes to open it. */
private const val SPRING_MS = 700L
