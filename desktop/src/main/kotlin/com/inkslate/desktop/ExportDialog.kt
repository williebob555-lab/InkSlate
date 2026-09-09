package com.inkslate.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp

/** Which pages an export covers. */
enum class ExportScope(val label: String) {
    ALL("The whole document"),
    CURRENT("This page only"),
    RANGE("A range of pages")
}

/** What was asked for, once the dialog is done with. */
data class ExportRequest(
    val name: String,
    /** Null means every page. */
    val pages: List<Int>?,
    val flatten: Boolean
)

/**
 * Export a copy: the whole document, this page, or a run of pages.
 *
 * Separate from Save, and deliberately so. Saving is what happens to the document you are working
 * on, under its own rules; this is "make me a file to hand in", which is a different question with
 * a different answer - usually flattened, usually a subset, and never over the original.
 */
@Composable
fun ExportDialog(
    documentName: String,
    pageCount: Int,
    currentPage: Int,
    isPdf: Boolean,
    onDismiss: () -> Unit,
    onExport: (ExportRequest) -> Unit
) {
    var scope by remember { mutableStateOf(ExportScope.ALL) }
    var flatten by remember { mutableStateOf(true) }
    var fromText by remember { mutableStateOf((currentPage + 1).toString()) }
    var toText by remember { mutableStateOf(pageCount.toString()) }
    var nameText by remember {
        mutableStateOf(documentName.substringBeforeLast('.', documentName) + "_export")
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
        else -> ((from!! - 1)..(to!! - 1)).toList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    label = { Text("File name") },
                    singleLine = true,
                    supportingText = { Text("Written next to the original, as a PDF.") },
                    modifier = Modifier.fillMaxWidth()
                )

                if (isPdf && pageCount > 1) {
                    OptionLabel("Pages")
                    OptionWrapRow {
                        ExportScope.entries.forEach { option ->
                            OptionChip(option.label, scope == option) { scope = option }
                        }
                    }

                    if (scope == ExportScope.RANGE) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = fromText,
                                onValueChange = { v -> fromText = v.filter { it.isDigit() }.take(5) },
                                label = { Text("From") },
                                singleLine = true,
                                isError = !rangeValid,
                                modifier = Modifier.weight(1f)
                            )
                            Text("  to  ", style = MaterialTheme.typography.bodyMedium)
                            OutlinedTextField(
                                value = toText,
                                onValueChange = { v -> toText = v.filter { it.isDigit() }.take(5) },
                                label = { Text("To") },
                                singleLine = true,
                                isError = !rangeValid,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Text(
                            "This document has $pageCount page${if (pageCount == 1) "" else "s"}.",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (rangeValid) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error
                        )
                    }
                }

                OptionLabel("Handwriting")
                OptionWrapRow {
                    OptionChip("Flattened into the page", flatten) { flatten = true }
                    OptionChip("Editable annotations", !flatten) { flatten = false }
                }
                Text(
                    if (flatten) {
                        "Permanent, and the safest option for a submission portal that strips " +
                            "annotations."
                    } else {
                        "Still selectable in Acrobat, and the copy can be annotated again."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onExport(ExportRequest(nameText.trim(), pages(), flatten)) }
            ) { Text("Export") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
