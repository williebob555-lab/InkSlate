package com.inksheets.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inksheets.core.SetlistBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Choosing a file by walking folders, showing only the kinds wanted. [within] keeps it inside one
 * folder (the music folder, for recordings that must sync with their song).
 */
@Composable
internal fun FilePickerDialog(
    title: String,
    start: File,
    extensions: Set<String>,
    onChosen: (File) -> Unit,
    onDismiss: () -> Unit,
    within: File? = null,
    note: String? = null,
    /** Another way to choose, beside Cancel - "Import from elsewhere", say. */
    extra: (@Composable () -> Unit)? = null
) {
    var at by remember { mutableStateOf(start.takeIf { it.isDirectory } ?: File(System.getProperty("user.home"))) }
    val entries = remember(at) {
        at.listFiles { f -> !f.name.startsWith(".") && (f.isDirectory || f.extension.lowercase() in extensions) }
            .orEmpty().sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }
    val canGoUp = at.parentFile != null && (within == null || at.canonicalPath != within.canonicalPath)
    SheetDialog(title = title, onDismiss = onDismiss, wide = true, buttons = {
        extra?.invoke()
        TextButton(onClick = onDismiss) { Text("Cancel") }
    }) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { at.parentFile?.let { at = it } }, enabled = canGoUp) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up")
                }
                Text(at.absolutePath, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(entries, key = { it.path }) { f ->
                    Row(
                        Modifier.fillMaxWidth().clickable { if (f.isDirectory) at = f else onChosen(f) }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(if (f.isDirectory) Icons.Default.Folder else Icons.Default.Description, null)
                        Spacer(Modifier.width(12.dp))
                        Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Export a setlist as one file and hand it to the share sheet (or show it in the file manager). */
@Composable
internal fun ShareSetlistDialog(state: SheetsState, setlistId: String, onClose: () -> Unit) {
    var result by remember { mutableStateOf<Result<File>?>(null) }
    LaunchedEffect(setlistId) {
        result = withContext(Dispatchers.IO) {
            runCatching {
                val root = state.root!!
                val lib = state.library!!
                val name = lib.setlist(setlistId)!!.name.map { if (it in "\\/:*?\"<>|") ' ' else it }.joinToString("").trim()
                val out = File(root, "Shared setlists/$name.${SetlistBundle.EXTENSION}")
                SetlistBundle.export(lib, root, setlistId, out)
                out
            }
        }
        result?.getOrNull()?.let { state.platform.share(it) }
    }
    SheetDialog(title = "Share setlist", onDismiss = onClose) {
        when (val r = result) {
            null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text("Packing the parts...")
            }
            else -> r.fold(
                onSuccess = { file ->
                    Text(
                        "Saved as ${file.name} in the music folder's \"Shared setlists\". Send it to " +
                            "your bandmates; in InkSheets they choose Open a shared setlist, and their " +
                            "instrument filter shows them their own parts.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                onFailure = { Text("It could not be packed: ${it.message}", color = MaterialTheme.colorScheme.error) }
            )
        }
    }
}

/** Open a `.inksheets` file someone sent. */
@Composable
internal fun OpenSharedDialog(state: SheetsState, onClose: () -> Unit) {
    var chosen by remember { mutableStateOf<File?>(null) }
    var result by remember { mutableStateOf<Result<SetlistBundle.Imported>?>(null) }
    val file = chosen
    if (file == null) {
        FilePickerDialog(
            title = "Open a shared setlist",
            start = state.platform.startFolder,
            extensions = setOf(SetlistBundle.EXTENSION),
            onChosen = { chosen = it },
            onDismiss = onClose,
            note = "A .${SetlistBundle.EXTENSION} file sent to you - look in Downloads."
        )
        return
    }
    LaunchedEffect(file) {
        result = withContext(Dispatchers.IO) {
            runCatching { SetlistBundle.import(state.library!!, state.root!!, file) }
        }
        state.change { }
    }
    SheetDialog(title = "Shared setlist", onDismiss = onClose) {
        when (val r = result) {
            null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text("Bringing it in...")
            }
            else -> r.fold(
                onSuccess = { imported ->
                    Text(
                        "\"${imported.setlist.name}\" is in Setlists, under \"${SetlistBundle.SHARED_FOLDER}\". " +
                            "${imported.songsAdded} new songs" +
                            (if (imported.songsMatched > 0) "; ${imported.songsMatched} you already had gained any new parts." else "."),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                onFailure = { Text("That file could not be opened: ${it.message}", color = MaterialTheme.colorScheme.error) }
            )
        }
    }
}
