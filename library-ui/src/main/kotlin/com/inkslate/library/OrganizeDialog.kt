package com.inkslate.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkslate.core.ClassCodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What sorting into class folders found: where documents go, and courses with no folder yet. */
internal class FilingPlan(
    val suggestions: List<ClassCodes.Suggestion>,
    /** A course number seen in documents that no folder is named for, with a name to give one. */
    val missing: List<Pair<String, Int>>,
    val roots: List<File>
)

/** Look over [scope] (or the whole library) for documents to file. Off the main thread. */
internal fun planFiling(backend: LibraryBackend, scope: File?): FilingPlan {
    val roots = backend.libraryFolders()
    val folders = backend.allFolders(roots)
    val docs = (if (scope != null) listOf(scope) else roots)
        .flatMap { backend.documentsUnder(it, depth = 6) }
        .map { it.file }
        .distinctBy { it.absolutePath }
    val suggestions = ClassCodes.suggest(docs, folders)

    // Courses in names with no folder of their own: offered as folders to make. Only from
    // documents not already filed in some folder, and only numbers seen twice or more, so one
    // "Page 104" does not turn into a folder suggestion.
    val known = ClassCodes.classFolders(folders).flatMap { c -> c.codes.map { it.number } }.toSet()
    val loose = docs.filter { d -> roots.any { it.absolutePath == d.parentFile?.absolutePath } }
    val counts = HashMap<String, MutableList<ClassCodes.Code>>()
    for (d in loose) for (c in ClassCodes.codesIn(d.name)) if (c.number !in known) counts.getOrPut(c.number) { mutableListOf() }.add(c)
    val missing = counts.filter { it.value.size >= 2 }.map { (number, seen) ->
        // The letters most often written before it, when they are the same at least twice.
        val subject = seen.mapNotNull { it.subject }.groupingBy { it }.eachCount()
            .filter { it.value >= 2 }.maxByOrNull { it.value }?.key
        listOfNotNull(subject, number).joinToString(" ") to seen.size
    }.sortedBy { it.first }
    return FilingPlan(suggestions, missing, roots)
}

/**
 * "Sort into class folders": every document with a course number in its name - 222, PHYS161 -
 * offered a move into the folder named for that course, to look over and accept in one go.
 * Courses that have documents but no folder yet can have one made here, and the whole thing can
 * be left to happen by itself as new documents arrive.
 */
@Composable
fun OrganizeDialog(
    backend: LibraryBackend,
    /** Only documents in here, or the whole library. */
    scope: File?,
    onDismiss: () -> Unit,
    /** The moves chosen, done by the caller so they can be undone from one place. */
    onMove: (List<Pair<File, File>>) -> Unit
) {
    val co = rememberCoroutineScope()
    var plan by remember { mutableStateOf<FilingPlan?>(null) }
    var reload by remember { mutableStateOf(0) }
    val chosen = remember { mutableStateMapOf<String, File?>() }
    var auto by remember { mutableStateOf(backend.pref(AUTO_FILE) == "on") }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) {
        val found = withContext(Dispatchers.IO) { planFiling(backend, scope) }
        // Ticked unless there is a choice to be made or a name already taken.
        for (s in found.suggestions) {
            if (s.document.absolutePath !in chosen) {
                chosen[s.document.absolutePath] = if (s.clash || s.alternatives.isNotEmpty()) null else s.target
            }
        }
        plan = found
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.AutoAwesome, null) },
        title = { Text("Sort into class folders") },
        text = {
            Column(Modifier.widthIn(min = 300.dp)) {
                Text(
                    "Documents with a course number in their name, like 222 or PHYS161, go into the " +
                        "folder named for that course.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val p = plan
                if (p == null) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() }
                    return@Column
                }
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    if (p.suggestions.isEmpty()) {
                        Text(
                            "Everything with a course number is already in its folder.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 14.dp)
                        )
                    }
                    p.suggestions.forEach { s -> SuggestionRow(s, chosen[s.document.absolutePath]) { chosen[s.document.absolutePath] = it } }

                    if (p.missing.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text("Courses with no folder yet", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        val roots = p.roots
                        p.missing.forEach { (suggestedName, count) ->
                            var name by remember(suggestedName) { mutableStateOf(suggestedName) }
                            val canMake = name.isNotBlank() && roots.isNotEmpty()
                            fun make() {
                                val parent = scope ?: roots.first()
                                co.launch {
                                    withContext(Dispatchers.IO) { backend.createFolder(parent, name) }
                                        .fold(onSuccess = { reload++ }, onFailure = { error = it.message })
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = name, onValueChange = { name = it }, singleLine = true,
                                    supportingText = { Text(if (count == 1) "1 document" else "$count documents") },
                                    keyboardOptions = DoneKey, keyboardActions = doneAction(canMake) { make() },
                                    modifier = Modifier.weight(1f).onEnter(canMake) { make() }
                                )
                                TextButton(enabled = canMake, onClick = { make() }) {
                                    Icon(Icons.Default.CreateNewFolder, null, Modifier.size(18.dp))
                                    Text(" Make")
                                }
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Do this by itself", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "New documents with one clear course go straight into its folder. You can undo each one.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = auto, onCheckedChange = {
                        auto = it
                        backend.setPref(AUTO_FILE, if (it) "on" else null)
                    })
                }
            }
        },
        confirmButton = {
            val moves = plan?.suggestions.orEmpty().mapNotNull { s -> chosen[s.document.absolutePath]?.let { s.document to it } }
            TextButton(enabled = moves.isNotEmpty(), onClick = { onMove(moves) }) {
                Text(if (moves.size == 1) "Move 1 document" else "Move ${moves.size} documents")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun SuggestionRow(s: ClassCodes.Suggestion, target: File?, onChoose: (File?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = target != null,
            enabled = !s.clash,
            onCheckedChange = { onChoose(if (it) s.target else null) }
        )
        Column(Modifier.weight(1f)) {
            Text(s.document.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box {
                TextButton(onClick = { if (s.alternatives.isNotEmpty()) open = true }, enabled = !s.clash) {
                    Icon(Icons.Default.Folder, null, Modifier.size(15.dp))
                    Text(
                        " " + (target ?: s.target).name + (if (s.alternatives.isNotEmpty()) "  ▾" else ""),
                        style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                DropdownMenu(open, onDismissRequest = { open = false }) {
                    (listOf(s.target) + s.alternatives).forEach { f ->
                        DropdownMenuItem(text = { Text(f.name) }, onClick = { open = false; onChoose(f) })
                    }
                }
            }
            if (s.clash) {
                Text(
                    "${s.target.name} already has a file with this name",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error
                )
            } else if (s.alternatives.isNotEmpty() && target == null) {
                Text(
                    "More than one folder has ${s.code} - pick one",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(s.code, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp).padding(start = 6.dp))
    }
}

/** Whether new documents are filed by themselves. */
internal const val AUTO_FILE = "home.autoFile"
