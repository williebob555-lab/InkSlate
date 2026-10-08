package com.inkslate.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkslate.core.ClassCodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pick where something goes - any folder, at any depth, in the folders on Home.
 *
 * The old one offered the added folders and one level below them, and left out whichever folder
 * the thing was already in: so a document in Classwork could never be moved into
 * Classwork/PHYS 161/Homework, because the way there was the one folder not on the list. This one
 * is walked like a file manager - tap a folder to go into it, "Move here" to put it there - and
 * opens where the thing already is, since the place it is going is usually close by.
 *
 * At the top: where the course number in the name says it belongs, and the last few places things
 * were moved to, since homework goes to the same handful of folders all term.
 */
@Composable
fun FolderPicker(
    backend: LibraryBackend,
    items: List<File>,
    title: String,
    confirm: String = "Move here",
    onDismiss: () -> Unit,
    onPick: (File) -> Unit
) {
    val scope = rememberCoroutineScope()
    var roots by remember { mutableStateOf<List<File>>(emptyList()) }
    // Where the picker is looking. Null is the top: the folders on Home.
    var at by remember { mutableStateOf<File?>(null) }
    var folders by remember { mutableStateOf<List<File>>(emptyList()) }
    var suggested by remember { mutableStateOf<List<File>>(emptyList()) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        val (r, start, offered) = withContext(Dispatchers.IO) {
            val r = backend.libraryFolders()
            val parent = items.mapNotNull { it.parentFile }.distinctBy { it.absolutePath }.singleOrNull()
            val start = parent?.takeIf { p -> r.any { p.absolutePath == it.absolutePath || isInside(p, it) } }
                ?: r.singleOrNull()
            val all = backend.allFolders(r)
            val byCode = ClassCodes.suggest(items.filter { it.isFile }, all).map { it.target }
            val recent = backend.pref(RECENT_TARGETS).orEmpty().lines().filter { it.isNotBlank() }.map(::File)
                .filter { it.isDirectory }
            val offered = (byCode + recent).distinctBy { it.absolutePath }
                .filter { f -> items.all { it.parentFile?.absolutePath != f.absolutePath } && fits(items, f) }
                .take(4)
            Triple(r, start, offered)
        }
        roots = r; at = start; suggested = offered
    }
    LaunchedEffect(at, roots, reload) {
        val here = at
        folders = withContext(Dispatchers.IO) {
            if (here == null) roots else backend.subfolders(here)
        }
    }

    // A single added folder is Home itself, so it is not a step of its own on the path.
    val home = roots.singleOrNull()
    val path = remember(at, roots) {
        val here = at ?: return@remember emptyList()
        generateSequence(here) { f -> if (roots.any { it.absolutePath == f.absolutePath }) null else f.parentFile }
            .toList().reversed()
            .filter { it.absolutePath != home?.absolutePath }
    }
    val here = at
    val alreadyHere = here != null && items.all { it.parentFile?.absolutePath == here.absolutePath }
    val canMoveHere = here != null && !alreadyHere && fits(items, here)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null) },
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.widthIn(min = 280.dp)) {
                if (suggested.isNotEmpty()) {
                    Text(
                        "Suggested",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    suggested.forEach { f ->
                        PickerRow(
                            f, subtitle = labelFor(f, roots),
                            icon = { Icon(Icons.Default.AutoAwesome, null, Modifier.size(19.dp)) },
                            onClick = { rememberTarget(backend, f); onPick(f) }
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                }
                // Where it is looking: Home, then each folder on the way, each a way back.
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { at = home }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Home, "Home", Modifier.size(18.dp))
                    }
                    path.forEachIndexed { i, f ->
                        Text(" › ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { at = f }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Text(
                                f.name, maxLines = 1,
                                fontWeight = if (i == path.lastIndex) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    if (folders.isEmpty()) {
                        Text(
                            if (here == null) "Add a folder to Home first." else "No folders in here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                    folders.forEach { f ->
                        val blocked = items.any { it.absolutePath == f.absolutePath }
                        PickerRow(
                            f,
                            subtitle = if (blocked) "This is what is being moved" else null,
                            enabled = !blocked,
                            trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Open ${f.name}") },
                            onClick = { at = f }
                        )
                    }
                }
                if (creating && here != null) {
                    var name by remember { mutableStateOf("") }
                    fun make() {
                        scope.launch {
                            withContext(Dispatchers.IO) { backend.createFolder(here, name) }.fold(
                                onSuccess = { made -> creating = false; at = made; reload++ },
                                onFailure = { error = it.message ?: "Could not make it" }
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = name, onValueChange = { name = it; error = null },
                            singleLine = true, label = { Text("New folder in ${here.name}") },
                            isError = error != null,
                            supportingText = error?.let { { Text(it) } },
                            keyboardOptions = DoneKey, keyboardActions = doneAction(name.isNotBlank()) { make() },
                            modifier = Modifier.weight(1f).onEnter(name.isNotBlank()) { make() }
                        )
                        TextButton(enabled = name.isNotBlank(), onClick = { make() }) { Text("Make") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canMoveHere, onClick = { here?.let { rememberTarget(backend, it); onPick(it) } }) {
                Text(
                    when {
                        here == null -> confirm
                        alreadyHere -> "Already here"
                        else -> "$confirm: ${here.name}"
                    },
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        },
        dismissButton = {
            Row {
                if (here != null && !creating) {
                    TextButton(onClick = { creating = true }) {
                        Icon(Icons.Default.CreateNewFolder, null, Modifier.size(18.dp))
                        Text(" New folder")
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun PickerRow(
    folder: File,
    subtitle: String? = null,
    enabled: Boolean = true,
    icon: @Composable () -> Unit = { Icon(Icons.Default.Folder, null, Modifier.size(19.dp)) },
    trailing: @Composable () -> Unit = {},
    onClick: () -> Unit
) {
    Surface(onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            icon()
            Column(Modifier.weight(1f)) {
                Text(folder.name.ifEmpty { folder.absolutePath }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            trailing()
        }
    }
}

/** Remembered so the next move offers it again. */
internal fun rememberTarget(backend: LibraryBackend, folder: File) {
    val old = backend.pref(RECENT_TARGETS).orEmpty().lines().filter { it.isNotBlank() && it != folder.absolutePath }
    backend.setPref(RECENT_TARGETS, (listOf(folder.absolutePath) + old).take(6).joinToString("\n"))
}

/** Somewhere [items] may go: not into one of themselves. */
private fun fits(items: List<File>, folder: File): Boolean = items.none {
    it.absolutePath == folder.absolutePath || isInside(folder, it)
}

internal fun isInside(child: File, parent: File) =
    child.absolutePath.startsWith(parent.absolutePath + File.separator)

/** "Classwork / PHYS 161" - where a folder is, said from Home. */
internal fun labelFor(folder: File, roots: List<File>): String {
    val root = roots.firstOrNull { folder.absolutePath == it.absolutePath || isInside(folder, it) } ?: return folder.parent.orEmpty()
    val parent = folder.parentFile ?: return ""
    if (folder.absolutePath == root.absolutePath) return "On Home"
    if (parent.absolutePath == root.absolutePath) return root.name
    return root.name + " / " + parent.absolutePath.removePrefix(root.absolutePath + File.separator).replace(File.separator, " / ")
}

internal const val RECENT_TARGETS = "home.recentTargets"
