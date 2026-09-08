package com.inkslate.ui.home

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.inkslate.data.FileEntry
import com.inkslate.data.FileRepo
import com.inkslate.data.SavePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The landing screen: your work, arranged the way a home screen is.
 *
 * Built only from folders the user has added, so it stays about coursework rather than every PDF
 * that has ever landed in Downloads. Folders open in place with a breadcrumb rather than throwing
 * you into a separate file manager, because the thing people want after opening a folder is
 * usually to go back up one and try the next one.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onOpenFile: (File) -> Unit,
    onOpenFolder: (File) -> Unit,
    onBrowse: () -> Unit,
    onOpenSettings: () -> Unit,
    onNewDocument: () -> Unit,
    refreshKey: Int
) {
    val context = LocalContext.current
    val repo = remember { FileRepo(context) }
    val savePrefs = remember { SavePrefs(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // Where we are. Empty means the top level: starred, recent, and the added folders.
    var trail by remember { mutableStateOf<List<File>>(emptyList()) }
    val here = trail.lastOrNull()

    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }

    var folders by remember { mutableStateOf<List<File>>(emptyList()) }
    var recents by remember { mutableStateOf<List<File>>(emptyList()) }
    var starred by remember { mutableStateOf<List<File>>(emptyList()) }
    var documents by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var results by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }

    // Long-press target, and the dialogs it can lead to.
    var sheetFor by remember { mutableStateOf<File?>(null) }
    var renaming by remember { mutableStateOf<File?>(null) }
    var deleting by remember { mutableStateOf<File?>(null) }
    var moving by remember { mutableStateOf<File?>(null) }

    fun refresh() { reload++ }

    LaunchedEffect(refreshKey, reload, here?.absolutePath) {
        loading = true
        val at = here
        val loaded = withContext(Dispatchers.IO) {
            if (at == null) {
                Loaded(
                    folders = repo.libraryFolders(),
                    recents = repo.recents(12),
                    starred = repo.pinned().filter { it.exists() },
                    documents = repo.libraryFiles(limit = 40)
                )
            } else {
                Loaded(
                    folders = repo.subfolders(at),
                    recents = emptyList(),
                    starred = emptyList(),
                    documents = repo.documentsIn(at)
                )
            }
        }
        folders = loaded.folders
        recents = loaded.recents
        starred = loaded.starred
        documents = loaded.documents
        loading = false
    }

    LaunchedEffect(query) {
        if (query.isBlank()) { results = emptyList(); return@LaunchedEffect }
        results = withContext(Dispatchers.IO) { repo.search(query) }
    }

    // Up one level before leaving the app, which is what the breadcrumb implies.
    BackHandler(enabled = trail.isNotEmpty() || searchOpen) {
        when {
            searchOpen -> { searchOpen = false; query = "" }
            else -> trail = trail.dropLast(1)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
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
                        here?.name ?: "InkSlate",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                },
                actions = {
                    IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" }) {
                        Icon(Icons.Default.Search, "Search")
                    }
                    IconButton(onClick = onBrowse) { Icon(Icons.Default.Folder, "All files") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings") }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewDocument,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("New") }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {

            AnimatedVisibility(visible = searchOpen) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Clear, "Clear")
                            }
                        }
                    },
                    placeholder = { Text("Search your documents") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
                )
            }

            if (trail.isNotEmpty()) {
                Breadcrumb(
                    trail = trail,
                    onHome = { trail = emptyList() },
                    onJump = { i -> trail = trail.take(i + 1) }
                )
            }

            if (loading && query.isBlank()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }

            // ---- search takes over the whole surface while it has a query ----
            if (query.isNotBlank()) {
                if (results.isEmpty()) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text(
                            "Nothing matching \"$query\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                        item {
                            Text(
                                if (results.size == 1) "1 document" else "${results.size} documents",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 18.dp, top = 6.dp, bottom = 4.dp)
                            )
                        }
                        items(results, key = { it.file.absolutePath }) { e ->
                            DocumentRow(e, repo, onOpen = onOpenFile, onLongPress = { sheetFor = e.file })
                        }
                    }
                }
                return@Column
            }

            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {

                if (here == null && folders.isEmpty()) {
                    item { EmptyLibrary(onBrowse) }
                }

                if (starred.isNotEmpty()) {
                    item { SectionHeader("Starred") }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(starred, key = { it.absolutePath }) { f ->
                                if (f.isDirectory) {
                                    FolderTile(
                                        f, repo, starred = true,
                                        onOpen = { trail = listOf(f) },
                                        onLongPress = { sheetFor = f }
                                    )
                                } else {
                                    DocumentCard(
                                        f, repo, starred = true,
                                        onOpen = { onOpenFile(f) },
                                        onLongPress = { sheetFor = f }
                                    )
                                }
                            }
                        }
                    }
                }

                if (recents.isNotEmpty()) {
                    item { SectionHeader("Recent") }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(recents, key = { it.absolutePath }) { f ->
                                DocumentCard(
                                    f, repo,
                                    onOpen = { onOpenFile(f) },
                                    onLongPress = { sheetFor = f }
                                )
                            }
                        }
                    }
                }

                if (folders.isNotEmpty()) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (here == null) "Your folders" else "Folders",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            if (here == null) {
                                TextButton(onClick = onBrowse) { Text("Add") }
                            }
                        }
                    }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(folders, key = { it.absolutePath }) { f ->
                                FolderTile(
                                    f, repo,
                                    onOpen = { trail = trail + f },
                                    onLongPress = { sheetFor = f }
                                )
                            }
                        }
                    }
                }

                if (documents.isNotEmpty()) {
                    item { SectionHeader(if (here == null) "All documents" else "Documents") }
                    items(documents, key = { it.file.absolutePath }) { e ->
                        DocumentRow(e, repo, onOpen = onOpenFile, onLongPress = { sheetFor = e.file })
                    }
                } else if (here != null && folders.isEmpty()) {
                    item {
                        Text(
                            "This folder is empty.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }

    // ---- long press ----------------------------------------------------------

    sheetFor?.let { target ->
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(onDismissRequest = { sheetFor = null }, sheetState = sheetState) {
            ItemActions(
                target = target,
                repo = repo,
                onOpen = {
                    sheetFor = null
                    if (target.isDirectory) trail = trail + target else onOpenFile(target)
                },
                onToggleStar = {
                    repo.togglePin(target); sheetFor = null; refresh()
                },
                onRename = { sheetFor = null; renaming = target },
                onMove = { sheetFor = null; moving = target },
                onDelete = { sheetFor = null; deleting = target },
                onSetDefault = {
                    repo.defaultNewFolder = target
                    sheetFor = null
                    scope.launch { snackbar.showSnackbar("New documents will go in ${target.name}") }
                },
                onDismiss = { sheetFor = null }
            )
        }
    }

    renaming?.let { target ->
        var name by remember(target) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            icon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
            title = { Text("Rename") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text("Name") }
                    )
                    Text(
                        "Your handwriting is stored inside the document, so it follows the new " +
                            "name automatically.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && name != target.name,
                    onClick = {
                        val f = target
                        val to = name
                        renaming = null
                        scope.launch {
                            withContext(Dispatchers.IO) { repo.rename(f, to, savePrefs) }.fold(
                                onSuccess = { refresh(); snackbar.showSnackbar("Renamed to ${it.name}") },
                                onFailure = { snackbar.showSnackbar(it.message ?: "Could not rename") }
                            )
                        }
                    }
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } }
        )
    }

    deleting?.let { target ->
        val isFolder = target.isDirectory
        AlertDialog(
            onDismissRequest = { deleting = null },
            icon = { Icon(Icons.Default.Delete, null) },
            title = { Text(if (isFolder) "Delete this folder?" else "Delete this document?") },
            text = {
                Text(
                    buildString {
                        append("\"${target.name}\" will be removed from storage")
                        if (isFolder) append(", along with everything inside it")
                        append(". This cannot be undone from the app.")
                        if (!isFolder) {
                            append("\n\nYour handwriting history is kept, so if this turns out ")
                            append("to be the wrong file the annotations can still be recovered.")
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val f = target
                    deleting = null
                    scope.launch {
                        withContext(Dispatchers.IO) { repo.delete(f, savePrefs) }.fold(
                            onSuccess = { refresh(); snackbar.showSnackbar("Deleted ${f.name}") },
                            onFailure = { snackbar.showSnackbar(it.message ?: "Could not delete") }
                        )
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }

    moving?.let { target ->
        MoveDialog(
            target = target,
            repo = repo,
            onDismiss = { moving = null },
            onMove = { destination ->
                val f = target
                moving = null
                scope.launch {
                    withContext(Dispatchers.IO) { repo.move(f, destination, savePrefs) }.fold(
                        onSuccess = {
                            refresh()
                            snackbar.showSnackbar("Moved to ${destination.name}")
                        },
                        onFailure = { snackbar.showSnackbar(it.message ?: "Could not move") }
                    )
                }
            }
        )
    }
}

private data class Loaded(
    val folders: List<File>,
    val recents: List<File>,
    val starred: List<File>,
    val documents: List<FileEntry>
)

// ---- pieces ------------------------------------------------------------------

@Composable
private fun Breadcrumb(trail: List<File>, onHome: () -> Unit, onJump: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onHome, modifier = Modifier.size(30.dp)) {
            Icon(Icons.Default.Home, "Home", Modifier.size(17.dp))
        }
        trail.forEachIndexed { i, f ->
            Text(
                "  ›  ",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                onClick = { onJump(i) },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
            ) {
                Text(
                    f.name,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    fontWeight = if (i == trail.lastIndex) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 8.dp)
    )
}

@Composable
private fun EmptyLibrary(onBrowse: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("Add your coursework folders", style = MaterialTheme.typography.titleMedium)
            Text(
                "This page shows the folders you add, so it stays about your assignments rather " +
                    "than everything on the tablet. Browse to a folder and add it here.",
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
 * A folder as a large square showing a little of what is inside.
 *
 * A row of identical folder icons tells you nothing; four page corners tell you which one is the
 * problem sets and which one is the lecture notes without reading a word.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderTile(
    folder: File,
    repo: FileRepo,
    starred: Boolean = false,
    onOpen: () -> Unit,
    onLongPress: () -> Unit
) {
    var preview by remember(folder.absolutePath) { mutableStateOf<List<File>>(emptyList()) }
    var count by remember(folder.absolutePath) { mutableStateOf(0) }
    val isDefault = remember(folder.absolutePath) { repo.isDefaultNewFolder(folder) }

    LaunchedEffect(folder.absolutePath) {
        // One trip to disk, not two: this runs for every folder tile on the screen.
        val loaded = withContext(Dispatchers.IO) {
            repo.folderPreview(folder, 4) to
                (folder.listFiles()?.count { !it.name.startsWith(".") } ?: 0)
        }
        preview = loaded.first
        count = loaded.second
    }

    Card(
        modifier = Modifier
            .width(148.dp)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .padding(9.dp)
            ) {
                // A two-by-two of page corners, the way a phone shows an app folder.
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (row in 0 until 2) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            for (col in 0 until 2) {
                                val index = row * 2 + col
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(
                                            MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    preview.getOrNull(index)?.let { Thumb(it, repo) }
                                }
                            }
                        }
                    }
                }
                if (starred) {
                    Icon(
                        Icons.Default.Star, null,
                        Modifier.align(Alignment.TopEnd).size(15.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Row(
                Modifier.padding(start = 10.dp, end = 8.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        folder.name.ifEmpty { folder.absolutePath },
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (isDefault) "new documents go here"
                        else if (count == 1) "1 item" else "$count items",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isDefault) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocumentCard(
    file: File,
    repo: FileRepo,
    starred: Boolean = false,
    onOpen: () -> Unit,
    onLongPress: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(132.dp)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.78f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Thumb(file, repo)
                if (starred) {
                    Icon(
                        Icons.Default.Star, null,
                        Modifier.align(Alignment.TopEnd).padding(5.dp).size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Text(
                file.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocumentRow(
    entry: FileEntry,
    repo: FileRepo,
    onOpen: (File) -> Unit,
    onLongPress: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 3.dp)
            .combinedClickable(onClick = { onOpen(entry.file) }, onLongClick = onLongPress),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier
                    .size(44.dp, 56.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) { Thumb(entry.file, repo) }

            Column(Modifier.weight(1f)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        append(entry.file.parentFile?.name.orEmpty())
                        append("  ·  ")
                        append(relativeTime(entry.modified))
                        if (entry.hasInk) append("  ·  annotated")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ItemActions(
    target: File,
    repo: FileRepo,
    onOpen: () -> Unit,
    onToggleStar: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onSetDefault: () -> Unit,
    onDismiss: () -> Unit
) {
    val isStarred = remember(target.absolutePath) { repo.isPinned(target) }
    Column(Modifier.padding(bottom = 20.dp)) {
        Text(
            target.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 10.dp)
        )
        HorizontalDivider()

        ActionRow(if (target.isDirectory) Icons.Default.Folder else Icons.Default.PictureAsPdf,
            "Open", onOpen)
        ActionRow(
            if (isStarred) Icons.Default.Star else Icons.Default.StarBorder,
            if (isStarred) "Remove star" else "Star",
            onToggleStar
        )
        if (target.isDirectory) {
            ActionRow(Icons.Default.PlaylistAdd, "Put new documents here", onSetDefault)
        }
        ActionRow(Icons.Default.DriveFileRenameOutline, "Rename", onRename)
        ActionRow(Icons.Default.DriveFileMove, "Move", onMove)
        ActionRow(Icons.Default.Delete, "Delete", onDelete, danger = true)
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    danger: Boolean = false
) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Icon(
                icon, null, Modifier.size(21.dp),
                tint = if (danger) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (danger) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * Pick somewhere to move to.
 *
 * Deliberately limited to the library and one level below it. Offering the whole filesystem for a
 * move is how documents end up somewhere nobody can find again.
 */
@Composable
private fun MoveDialog(
    target: File,
    repo: FileRepo,
    onDismiss: () -> Unit,
    onMove: (File) -> Unit
) {
    var options by remember { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(Unit) {
        options = withContext(Dispatchers.IO) {
            val roots = repo.libraryFolders()
            (roots + roots.flatMap { repo.subfolders(it) })
                .distinctBy { it.absolutePath }
                .filter { it.absolutePath != target.parentFile?.absolutePath }
                .filter { it.absolutePath != target.absolutePath }
                .filterNot { it.absolutePath.startsWith(target.absolutePath + File.separator) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.DriveFileMove, null) },
        title = { Text("Move \"${target.name}\"") },
        text = {
            if (options.isEmpty()) {
                Text("There is nowhere else to move this to yet. Add more folders first.")
            } else {
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    options.forEach { dir ->
                        Card(
                            onClick = { onMove(dir) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        ) {
                            Row(
                                Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(Icons.Default.Folder, null, Modifier.size(19.dp))
                                Column {
                                    Text(dir.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        dir.parentFile?.name.orEmpty(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun Thumb(file: File, repo: FileRepo) {
    var bmp by remember(file.absolutePath, file.lastModified()) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(file.absolutePath) { mutableStateOf(false) }

    LaunchedEffect(file.absolutePath, file.lastModified()) {
        val result = withContext(Dispatchers.IO) { runCatching { repo.thumbnail(file) }.getOrNull() }
        if (result == null) failed = true else bmp = result
    }

    val current = bmp
    when {
        current != null -> Image(
            bitmap = current.asImageBitmap(),
            contentDescription = file.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        failed -> Icon(
            if (file.extension.equals("pdf", true)) Icons.Default.PictureAsPdf else Icons.Default.Image,
            null, Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        else -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
    }
}

/** Short relative time, the way a document list usually reads. */
private fun relativeTime(millis: Long): String {
    if (millis <= 0) return ""
    val diff = System.currentTimeMillis() - millis
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    val days = TimeUnit.MILLISECONDS.toDays(diff)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        days < 365 -> "${days / 7}w ago"
        else -> "${days / 365}y ago"
    }
}
