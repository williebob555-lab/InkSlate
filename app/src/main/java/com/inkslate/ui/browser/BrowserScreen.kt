package com.inkslate.ui.browser

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkslate.data.FileEntry
import com.inkslate.data.FileRepo
import com.inkslate.data.SavePrefs
import com.inkslate.data.SortBy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    startDir: File?,
    onOpenFile: (File) -> Unit,
    onOpenSettings: () -> Unit,
    onHome: () -> Unit,
    onDirChanged: (File) -> Unit
) {
    val context = LocalContext.current
    val repo = remember { FileRepo(context) }
    val savePrefs = remember { SavePrefs(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val roots = remember { repo.storageRoots() }
    var dir by remember {
        mutableStateOf(
            startDir?.takeIf { it.isDirectory }
                ?: repo.lastFolder?.let(::File)?.takeIf { it.isDirectory }
                ?: roots.firstOrNull()?.path
                ?: File("/")
        )
    }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var sortBy by remember { mutableStateOf(SortBy.NAME) }
    var ascending by remember { mutableStateOf(true) }
    var sortMenu by remember { mutableStateOf(false) }
    var newFolderOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<File?>(null) }
    var deleteTarget by remember { mutableStateOf<File?>(null) }
    var moveTarget by remember { mutableStateOf<File?>(null) }
    var contextTarget by remember { mutableStateOf<FileEntry?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    var inLibrary by remember(dir, refreshKey) { mutableStateOf(repo.isInLibrary(dir)) }

    val recents = remember(refreshKey, dir) { repo.recents(10) }

    LaunchedEffect(dir, sortBy, ascending, refreshKey) {
        loading = true
        repo.lastFolder = dir.absolutePath
        entries = withContext(Dispatchers.IO) { repo.list(dir, sortBy, ascending) }
        loading = false
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (inLibrary) repo.removeLibraryFolder(dir) else repo.addLibraryFolder(dir)
                    inLibrary = !inLibrary
                    scope.launch {
                        snackbar.showSnackbar(
                            if (inLibrary) "Added to Home" else "Removed from Home"
                        )
                    }
                },
                icon = {
                    Icon(if (inLibrary) Icons.Default.Check else Icons.Default.Add, null)
                },
                text = { Text(if (inLibrary) "On Home" else "Add to Home") }
            )
        },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            dir.name.ifEmpty { "Storage" },
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            dir.absolutePath,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    val parent = dir.parentFile
                    if (parent != null && parent.canRead()) {
                        IconButton(onClick = { dir = parent; onDirChanged(parent) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up one folder")
                        }
                    } else {
                        IconButton(onClick = onHome) { Icon(Icons.Default.Home, "Home") }
                    }
                },
                actions = {
                    IconButton(onClick = { newFolderOpen = true }) {
                        Icon(Icons.Default.CreateNewFolder, "New folder")
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, "Sort")
                        }
                        DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                            SortBy.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = {
                                        Text(if (s == sortBy) "${s.label}  ${if (ascending) "↑" else "↓"}" else s.label)
                                    },
                                    onClick = {
                                        if (s == sortBy) ascending = !ascending else { sortBy = s; ascending = true }
                                        sortMenu = false
                                    }
                                )
                            }
                        }
                    }
                    IconButton(onClick = onHome) { Icon(Icons.Default.Home, "Home") }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {

            RootsRow(roots.map { it.label to it.path }) { dir = it; onDirChanged(it) }

            if (recents.isNotEmpty()) {
                SectionLabel("Recent")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(recents, key = { it.absolutePath }) { f ->
                        RecentChip(f) { repo.noteOpened(it); onOpenFile(it) }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            when {
                loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                entries.isEmpty() -> EmptyFolder()
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 132.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(entries, key = { it.file.absolutePath }) { entry ->
                        EntryTile(
                            entry = entry,
                            repo = repo,
                            pinned = repo.isPinned(entry.file),
                            onClick = {
                                if (entry.isDirectory) { dir = entry.file; onDirChanged(entry.file) }
                                else { repo.noteOpened(entry.file); onOpenFile(entry.file) }
                            },
                            onLongClick = { contextTarget = entry }
                        )
                    }
                }
            }
        }
    }

    // ---- dialogs -------------------------------------------------------------

    contextTarget?.let { entry ->
        EntryActionsSheet(
            entry = entry,
            pinned = repo.isPinned(entry.file),
            onDismiss = { contextTarget = null },
            onRename = { renameTarget = entry.file; contextTarget = null },
            onMove = { moveTarget = entry.file; contextTarget = null },
            onDelete = { deleteTarget = entry.file; contextTarget = null },
            onTogglePin = { repo.togglePin(entry.file); refreshKey++; contextTarget = null }
        )
    }

    moveTarget?.let { target ->
        var options by remember(target) { mutableStateOf<List<File>>(emptyList()) }
        LaunchedEffect(target) {
            options = withContext(Dispatchers.IO) {
                // Somewhere sensible rather than the whole filesystem: the folders on Home, one
                // level below them, and wherever we are standing right now.
                val roots = repo.libraryFolders()
                (roots + roots.flatMap { repo.subfolders(it) } + repo.subfolders(dir) + dir)
                    .distinctBy { it.absolutePath }
                    .filter { it.isDirectory }
                    .filter { it.absolutePath != target.parentFile?.absolutePath }
                    .filter { it.absolutePath != target.absolutePath }
                    .filterNot { it.absolutePath.startsWith(target.absolutePath + File.separator) }
            }
        }
        AlertDialog(
            onDismissRequest = { moveTarget = null },
            title = { Text("Move \"${target.name}\"") },
            text = {
                if (options.isEmpty()) {
                    Text("There is nowhere else to move this to. Add more folders to Home first.")
                } else {
                    Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                        options.forEach { destination ->
                            TextButton(
                                onClick = {
                                    val f = target
                                    moveTarget = null
                                    repo.move(f, destination, savePrefs).fold(
                                        onSuccess = {
                                            refreshKey++
                                            scope.launch {
                                                snackbar.showSnackbar("Moved to ${destination.name}")
                                            }
                                        },
                                        onFailure = {
                                            scope.launch {
                                                snackbar.showSnackbar(
                                                    it.message ?: "Could not move"
                                                )
                                            }
                                        }
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    destination.name.ifEmpty { destination.absolutePath },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { moveTarget = null }) { Text("Cancel") } }
        )
    }

    if (newFolderOpen) {
        TextPromptDialog(
            title = "New folder",
            label = "Folder name",
            initial = "",
            confirmLabel = "Create",
            onDismiss = { newFolderOpen = false },
            onConfirm = { name ->
                newFolderOpen = false
                repo.createFolder(dir, name).fold(
                    onSuccess = { refreshKey++ },
                    onFailure = { scope.launch { snackbar.showSnackbar(it.message ?: "Could not create folder") } }
                )
            }
        )
    }

    renameTarget?.let { target ->
        TextPromptDialog(
            title = "Rename",
            label = "New name",
            initial = target.name,
            confirmLabel = "Rename",
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null
                repo.rename(target, name, savePrefs).fold(
                    onSuccess = { refreshKey++ },
                    onFailure = { scope.launch { snackbar.showSnackbar(it.message ?: "Could not rename") } }
                )
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${target.name}?") },
            text = {
                Text(
                    if (target.isDirectory)
                        "This deletes the folder and everything inside it. This cannot be undone."
                    else "This deletes the file from storage and cannot be undone here. Its " +
                        "handwriting history is kept, so the annotations can still be recovered " +
                        "if this turns out to be the wrong file."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val f = target
                    deleteTarget = null
                    repo.delete(f, savePrefs).fold(
                        onSuccess = { refreshKey++ },
                        onFailure = { scope.launch { snackbar.showSnackbar(it.message ?: "Could not delete") } }
                    )
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }
}

// ---- pieces ------------------------------------------------------------------

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun RootsRow(roots: List<Pair<String, File>>, onPick: (File) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(roots) { (label, path) ->
            Card(
                onClick = { onPick(path) },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Folder, null, Modifier.size(16.dp))
                    Text(label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun RecentChip(file: File, onClick: (File) -> Unit) {
    Card(onClick = { onClick(file) }, modifier = Modifier.height(40.dp)) {
        Row(
            Modifier.padding(horizontal = 10.dp).fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                if (file.extension.equals("pdf", true)) Icons.Default.PictureAsPdf else Icons.Default.Image,
                null, Modifier.size(15.dp)
            )
            Text(
                file.name, style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 150.dp)
            )
        }
    }
}

@Composable
private fun EmptyFolder() {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Folder, null,
                Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Nothing to open here",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                "PDFs and images only",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun EntryTile(
    entry: FileEntry,
    repo: FileRepo,
    pinned: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.78f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (entry.isDirectory) {
                    Icon(
                        Icons.Default.Folder, null,
                        Modifier.size(44.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Thumbnail(entry.file, repo)
                }

                if (pinned) {
                    Icon(
                        Icons.Default.PushPin, "Pinned",
                        Modifier.align(Alignment.TopEnd).padding(6.dp).size(15.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                if (entry.hasInk) {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            "ink",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
            Text(
                entry.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}

/** Loads the page-1 preview off the main thread; shows a placeholder until it lands. */
@Composable
private fun Thumbnail(file: File, repo: FileRepo) {
    var bmp by remember(file.absolutePath, file.lastModified()) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(file.absolutePath) { mutableStateOf(false) }

    LaunchedEffect(file.absolutePath, file.lastModified()) {
        val result = withContext(Dispatchers.IO) { runCatching { repo.thumbnail(file) }.getOrNull() }
        if (result == null) failed = true else bmp = result
    }

    val current = bmp
    when {
        current != null -> androidx.compose.foundation.Image(
            bitmap = current.asImageBitmap(),
            contentDescription = file.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        failed -> Icon(
            if (file.extension.equals("pdf", true)) Icons.Default.PictureAsPdf else Icons.Default.Image,
            null, Modifier.size(36.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        else -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun EntryActionsSheet(
    entry: FileEntry,
    pinned: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                DropdownMenuItem(text = { Text(if (pinned) "Unpin" else "Pin to top") }, onClick = onTogglePin)
                DropdownMenuItem(text = { Text("Rename") }, onClick = onRename)
                DropdownMenuItem(text = { Text("Move to...") }, onClick = onMove)
                DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    onClick = onDelete
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun TextPromptDialog(
    title: String,
    label: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
