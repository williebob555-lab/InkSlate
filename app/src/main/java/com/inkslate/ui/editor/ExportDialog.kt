package com.inkslate.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import com.inkslate.ui.OptionChip
import com.inkslate.ui.OptionLabel
import com.inkslate.ui.OptionWrapRow

/** What happens to the file once it has been written. */
enum class ExportDestination(val label: String, val detail: String) {
    SHARE(
        "Send it",
        "Hands it straight to Gmail, Drive, Classroom - whatever you submit through."
    ),
    PICK_FOLDER(
        "Save to...",
        "Opens the system file picker so you can put it wherever you like."
    ),
    BESIDE(
        "Keep it here",
        "Writes it into the same folder as the original and tells you the name."
    )
}

/** Which pages go into the exported file. */
enum class ExportScope(val label: String) {
    ALL("Whole document"),
    CURRENT("This page"),
    RANGE("Pages..."),
    /** Only offered when the caller arrived from a selection in the page manager. */
    SELECTED("The pages I picked")
}

/** Everything the editor needs in order to carry out one export. */
data class ExportRequest(
    /** Null for the whole document. */
    val pages: List<Int>?,
    val flatten: Boolean,
    val destination: ExportDestination,
    /** Without an extension; the editor appends the right one. */
    val baseName: String
)

/**
 * Ask what an export should be, and where it should go.
 *
 * Exporting used to write a copy beside the original and say so in a snackbar, which is a save
 * under a different name: there was no way to hand the finished document to a person or a
 * submission portal without leaving the app and going to find the file. The three destinations
 * here are the three things anyone actually does with a marked-up worksheet - send it, file it
 * somewhere specific, or leave it next to the original - and the page range is here rather than
 * in a menu entry of its own because "just these two pages, sent to my teacher" is one decision.
 */
@Composable
fun ExportDialog(
    documentName: String,
    pageCount: Int,
    currentPage: Int,
    isPdf: Boolean,
    /** Pages already chosen elsewhere - from the page manager's selection - or null. */
    presetPages: List<Int>? = null,
    onDismiss: () -> Unit,
    onExport: (ExportRequest) -> Unit
) {
    var scope by remember {
        mutableStateOf(if (presetPages.isNullOrEmpty()) ExportScope.ALL else ExportScope.SELECTED)
    }
    var destination by remember { mutableStateOf(ExportDestination.SHARE) }
    var flatten by remember { mutableStateOf(true) }
    var fromText by remember { mutableStateOf((currentPage + 1).toString()) }
    var toText by remember { mutableStateOf(pageCount.toString()) }
    var nameText by remember {
        mutableStateOf(documentName.substringBeforeLast('.', documentName))
    }

    val from = fromText.toIntOrNull()
    val to = toText.toIntOrNull()
    val rangeValid = scope != ExportScope.RANGE ||
        (from != null && to != null && from in 1..pageCount && to in from..pageCount)
    val valid = rangeValid && nameText.isNotBlank()

    fun pages(): List<Int>? = when {
        !isPdf -> null
        scope == ExportScope.ALL -> null
        scope == ExportScope.CURRENT -> listOf(currentPage)
        scope == ExportScope.SELECTED -> presetPages
        else -> ((from!! - 1)..(to!! - 1)).toList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export") },
        text = {
            Column(
                Modifier
                    .imePadding()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it.replace(Regex("[\\\\/:*?\"<>|]"), "").take(80) },
                    label = { Text("File name") },
                    singleLine = true,
                    supportingText = {
                        Text(
                            if (isPdf) "Saved as $nameText.pdf"
                            else "Saved as $nameText.${documentName.substringAfterLast('.', "png")}"
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                if (isPdf && pageCount > 1) {
                    OptionLabel("What to include")
                    OptionWrapRow {
                        ExportScope.entries.forEach { option ->
                            if (option == ExportScope.SELECTED && presetPages.isNullOrEmpty()) {
                                return@forEach
                            }
                            OptionChip(
                                if (option == ExportScope.SELECTED) {
                                    option.label + " (" + presetPages!!.size + ")"
                                } else {
                                    option.label
                                },
                                scope == option
                            ) { scope = option }
                        }
                    }
                    if (scope == ExportScope.RANGE) {
                        Row(
                            Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = fromText,
                                onValueChange = { v ->
                                    fromText = v.filter { it.isDigit() }.take(5)
                                },
                                label = { Text("From") },
                                singleLine = true,
                                keyboardOptions =
                                    KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = toText,
                                onValueChange = { v -> toText = v.filter { it.isDigit() }.take(5) },
                                label = { Text("To") },
                                singleLine = true,
                                keyboardOptions =
                                    KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Text(
                            "This document has $pageCount pages.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                OptionLabel("Then")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ExportDestination.entries.forEach { option ->
                        OptionChip(option.label, destination == option) { destination = option }
                    }
                }
                Text(
                    destination.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )

                if (isPdf) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Flatten annotations",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "Safest for submission portals, which sometimes strip annotation " +
                                    "layers. Off, your handwriting stays editable in the copy.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = flatten, onCheckedChange = { flatten = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onExport(
                        ExportRequest(
                            pages = pages(),
                            flatten = flatten,
                            destination = destination,
                            baseName = nameText.trim()
                        )
                    )
                }
            ) { Text(if (destination == ExportDestination.SHARE) "Send" else "Export") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
