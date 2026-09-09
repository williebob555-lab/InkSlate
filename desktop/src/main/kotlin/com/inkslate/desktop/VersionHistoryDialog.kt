package com.inkslate.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The copies kept before each overwrite, and the way back to one.
 *
 * These are ordinary files in a folder beside the document, which is deliberate: version history
 * that only the app can read is version history you lose when the app does. They can be opened by
 * hand, copied off, or ignored entirely.
 */
@Composable
fun VersionHistoryDialog(
    file: File,
    onDismiss: () -> Unit,
    onRestored: () -> Unit,
    onMessage: (String) -> Unit
) {
    var backups by remember { mutableStateOf(DocumentExport.backupsFor(file)) }
    var confirming by remember { mutableStateOf<File?>(null) }

    val stamp = remember { SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Version history") },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                if (backups.isEmpty()) {
                    Text(
                        "No earlier versions yet. One is kept each time this document is " +
                            "overwritten, in ${DocumentExport.BACKUP_DIR} beside it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "Kept in ${DocumentExport.BACKUP_DIR} beside the document, newest first. " +
                            "Restoring snapshots what is there now first, so it can be undone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    LazyColumn(Modifier.height(320.dp)) {
                        items(backups, key = { it.absolutePath }) { backup ->
                            Card(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clickable { confirming = backup }
                            ) {
                                Row(
                                    Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            stamp.format(Date(backup.lastModified())),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            "${backup.length() / 1024} KB  ·  ${backup.name}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    TextButton(onClick = { confirming = backup }) { Text("Restore") }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )

    confirming?.let { backup ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Restore this version?") },
            text = {
                Text(
                    "${file.name} will be replaced with the copy from " +
                        stamp.format(Date(backup.lastModified())) + ".\n\n" +
                        "What is there now is snapshotted first, so this can itself be undone. " +
                        "The document is reopened afterwards."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = backup
                    confirming = null
                    DocumentExport.restoreBackup(target, file).fold(
                        onSuccess = {
                            backups = DocumentExport.backupsFor(file)
                            onMessage("Restored the version from ${stamp.format(Date(target.lastModified()))}")
                            onRestored()
                        },
                        onFailure = { onMessage(it.message ?: "Could not restore that version") }
                    )
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Cancel") } }
        )
    }
}
