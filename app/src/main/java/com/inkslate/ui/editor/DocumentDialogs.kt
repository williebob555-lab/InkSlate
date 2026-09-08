package com.inkslate.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Browse and restore the snapshots taken before each overwrite.
 *
 * These have been written since the first build but were never reachable, which made them a
 * safety net nobody could actually pull.
 */
/** One snapshot of a document's handwriting, with how much is in it. */
data class InkVersion(val file: File, val strokes: Int)

@Composable
fun VersionHistoryDialog(
    backups: List<File>,
    inkVersions: List<InkVersion> = emptyList(),
    onDismiss: () -> Unit,
    onRestore: (File) -> Unit,
    onRestoreInk: (File) -> Unit = {}
) {
    var confirming by remember { mutableStateOf<File?>(null) }
    var confirmingInk by remember { mutableStateOf<InkVersion?>(null) }
    val stamp = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Version history") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {

                // Handwriting first. It is the part with no copy anywhere else, so it is the
                // part someone opening this dialog in a panic is looking for.
                Text(
                    "Handwriting",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
                if (inkVersions.isEmpty()) {
                    Text(
                        "No earlier handwriting saved yet. From now on a copy is kept every " +
                            "time your annotations are written.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "Restoring adds back anything missing; it never removes what you have.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    inkVersions.forEach { v ->
                        Card(
                            onClick = { confirmingInk = v },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    stamp.format(Date(v.file.lastModified())),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    if (v.strokes == 1) "1 mark" else "${v.strokes} marks",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    "Exported file",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
                if (backups.isEmpty()) {
                    Text(
                        "No previous versions yet. One is saved automatically each time you " +
                            "overwrite the original.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "Restoring also snapshots the current file first, so this is reversible.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    backups.forEach { f ->
                        Card(
                            onClick = { confirming = f },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    stamp.format(Date(f.lastModified())),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    (f.length() / 1024).toString() + " KB",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )

    confirmingInk?.let { v ->
        AlertDialog(
            onDismissRequest = { confirmingInk = null },
            title = { Text("Restore this handwriting?") },
            text = {
                Text(
                    "Adds back the marks from " + stamp.format(Date(v.file.lastModified())) +
                        " that are no longer in the document. Nothing currently on the page " +
                        "is removed, so this is safe to try."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val f = v.file; confirmingInk = null; onRestoreInk(f)
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingInk = null }) { Text("Cancel") }
            }
        )
    }

    confirming?.let { f ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Restore this version?") },
            text = {
                Text(
                    "The file on disk will be replaced with the version from " +
                        stamp.format(Date(f.lastModified())) +
                        ". The current one is snapshotted first, so you can come back."
                )
            },
            confirmButton = {
                TextButton(onClick = { val target = f; confirming = null; onRestore(target) }) {
                    Text("Restore")
                }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } }
        )
    }
}
