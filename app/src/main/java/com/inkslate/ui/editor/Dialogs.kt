package com.inkslate.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import com.inkslate.data.FileOverride
import com.inkslate.data.InkFormat
import com.inkslate.data.SaveMode
import com.inkslate.data.SaveSettings

@Composable
fun TextEntryDialog(
    initial: String,
    title: String = "Text",
    singleLine: Boolean = false,
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
                singleLine = singleLine,
                minLines = if (singleLine) 1 else 3,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) { Text(if (initial.isEmpty()) "Add" else "Update") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Per-file save rules.
 *
 * Every row can sit at "Use default", which is what makes overrides partial: pinning one file to
 * always-overwrite should not freeze its autosave behaviour against later changes to the global
 * setting. Rows show the inherited value inline so it is obvious what "default" currently means.
 */
@Composable
fun FileRulesDialog(
    fileName: String,
    global: SaveSettings,
    current: FileOverride,
    onDismiss: () -> Unit,
    onApply: (FileOverride) -> Unit
) {
    var draft by remember { mutableStateOf(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Rules for this file", style = MaterialTheme.typography.titleMedium)
                Text(
                    fileName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Anything left on \"Use default\" follows Settings and keeps following it if " +
                        "you change your mind later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                OverrideRow(
                    title = "When I save",
                    inheritedLabel = global.mode.label,
                    currentLabel = draft.mode?.label,
                    options = SaveMode.entries.map { it.label to it }
                ) { draft = draft.copy(mode = it) }

                OverrideRow(
                    title = "Ink in exported PDFs",
                    inheritedLabel = global.inkFormat.label,
                    currentLabel = draft.inkFormat?.label,
                    options = InkFormat.entries.map { it.label to it }
                ) { draft = draft.copy(inkFormat = it) }

                OverrideRow(
                    title = "Autosave",
                    inheritedLabel = if (global.autosave) "On" else "Off",
                    currentLabel = draft.autosave?.let { if (it) "On" else "Off" },
                    options = listOf("On" to true, "Off" to false)
                ) { draft = draft.copy(autosave = it) }

                OverrideRow(
                    title = "Keep backups when overwriting",
                    inheritedLabel = if (global.backupOnOverwrite) "On" else "Off",
                    currentLabel = draft.backupOnOverwrite?.let { if (it) "On" else "Off" },
                    options = listOf("On" to true, "Off" to false)
                ) { draft = draft.copy(backupOnOverwrite = it) }

                OverrideRow(
                    title = "Confirm before overwriting",
                    inheritedLabel = if (global.confirmOverwrite) "On" else "Off",
                    currentLabel = draft.confirmOverwrite?.let { if (it) "On" else "Off" },
                    options = listOf("On" to true, "Off" to false)
                ) { draft = draft.copy(confirmOverwrite = it) }

                if (!draft.isEmpty) {
                    TextButton(onClick = { draft = FileOverride() }) {
                        Text("Clear all overrides for this file")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(draft) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * One overridable setting. Passing null through [onPick] returns the row to inheritance, which is
 * why the option list always carries an explicit "Use default" entry.
 */
@Composable
private fun <T> OverrideRow(
    title: String,
    inheritedLabel: String,
    currentLabel: String?,
    options: List<Pair<String, T>>,
    onPick: (T?) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val overridden = currentLabel != null

    Box {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (overridden) "Overridden" else "Default: $inheritedLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (overridden) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { open = true }) {
                Text(currentLabel ?: "Use default")
            }
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Use default ($inheritedLabel)") },
                onClick = { onPick(null); open = false }
            )
            options.forEach { (label, value) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onPick(value); open = false }
                )
            }
        }
    }
}
