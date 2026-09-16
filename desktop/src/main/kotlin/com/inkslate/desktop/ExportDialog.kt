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
import java.io.File

/** Which pages an export covers. */
enum class ExportScope(val label: String) {
    ALL("Whole document"),
    CURRENT("This page"),
    RANGE("Pages..."),
    /** Only offered when the export was started from a selection in the pages sheet. */
    SELECTED("The pages I picked")
}

/** Where the finished file goes. */
enum class ExportDestination(val label: String, val detail: String) {
    SAVE_AS("Save to...", "Asks where to put it, starting from wherever the last export went."),
    BESIDE("Keep it here", "Writes it into the same folder as the original.")
}

/** What a picture is exported as. A PDF only has one answer, so this is asked of pictures alone. */
enum class ExportFormat(val label: String, val extension: String) {
    PICTURE("Picture", "png"),
    PDF("PDF", "pdf")
}

/** What was asked for, once the dialog is done with. */
data class ExportRequest(
    /** Without an extension; [format] decides it. */
    val baseName: String,
    /** Null means every page. */
    val pages: List<Int>?,
    val flatten: Boolean,
    val destination: ExportDestination,
    val format: ExportFormat
)

/**
 * Export a copy: the whole document, this page, a run of pages, or the pages picked in the sheet.
 *
 * Separate from Save, and deliberately so. Saving is what happens to the document you are working
 * on, under its own rules; this is "make me a file to hand in", which is a different question with
 * a different answer - usually flattened, usually a subset, never over the original, and usually
 * somewhere other than beside it, which is why the Windows save dialog is the default.
 */
@Composable
fun ExportDialog(
    documentName: String,
    pageCount: Int,
    currentPage: Int,
    isPdf: Boolean,
    /** Pages chosen in the pages sheet, or null when the export was started from the menu. */
    presetPages: List<Int>? = null,
    onDismiss: () -> Unit,
    onExport: (ExportRequest) -> Unit
) {
    var scope by remember {
        mutableStateOf(if (presetPages.isNullOrEmpty()) ExportScope.ALL else ExportScope.SELECTED)
    }
    var destination by remember { mutableStateOf(ExportDestination.SAVE_AS) }
    var format by remember { mutableStateOf(if (isPdf) ExportFormat.PDF else ExportFormat.PICTURE) }
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
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it.replace(Regex("[\\\\/:*?\"<>|]"), "").take(80) },
                    label = { Text("File name") },
                    singleLine = true,
                    supportingText = { Text("Saved as $nameText.${format.extension}") },
                    modifier = Modifier.fillMaxWidth()
                )

                if (!isPdf) {
                    OptionLabel("As")
                    OptionWrapRow {
                        ExportFormat.entries.forEach { option ->
                            OptionChip(option.label, format == option) { format = option }
                        }
                    }
                }

                if (isPdf && pageCount > 1) {
                    OptionLabel("What to include")
                    OptionWrapRow {
                        ExportScope.entries.forEach { option ->
                            if (option == ExportScope.SELECTED && presetPages.isNullOrEmpty()) {
                                return@forEach
                            }
                            val label = if (option == ExportScope.SELECTED) {
                                option.label + " (" + presetPages!!.size + ")"
                            } else {
                                option.label
                            }
                            OptionChip(label, scope == option) { scope = option }
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

                OptionLabel("Then")
                OptionWrapRow {
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

                // A picture is flat whatever is chosen, so the question is only asked of a PDF.
                if (isPdf) {
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
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onExport(
                        ExportRequest(
                            baseName = nameText.trim(),
                            pages = pages(),
                            flatten = flatten || !isPdf,
                            destination = destination,
                            format = format
                        )
                    )
                }
            ) { Text(if (destination == ExportDestination.SAVE_AS) "Export..." else "Export") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Where an export goes, asked through the Windows save dialog.
 *
 * Starts in the folder the last export went to, because handing in a week of worksheets is the
 * same folder five times, and the original's folder is rarely where a submission copy belongs.
 * Null when the dialog was cancelled.
 */
fun askExportTarget(original: File, fileName: String): File? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Export to", java.awt.FileDialog.SAVE)
    val remembered = DesktopPrefs.get(LAST_EXPORT_DIR)?.let(::File)?.takeIf { it.isDirectory }
    dialog.directory = (remembered ?: original.parentFile)?.absolutePath
    dialog.file = fileName
    dialog.isVisible = true
    val name = dialog.file ?: return null
    val dir = dialog.directory ?: return null
    DesktopPrefs.put(LAST_EXPORT_DIR, dir)
    val extension = fileName.substringAfterLast('.', "")
    // The dialog lets the extension be typed away; the file is still what it is.
    val fixed = if (extension.isNotEmpty() && !name.endsWith(".$extension", ignoreCase = true)) {
        "$name.$extension"
    } else {
        name
    }
    return File(dir, fixed)
}

private const val LAST_EXPORT_DIR = "export.lastDir"
