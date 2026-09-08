package com.inkslate.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.inkslate.ui.OptionChip
import com.inkslate.ui.OptionLabel
import com.inkslate.ui.OptionWrapRow

/** What a picked file turned out to be, once it had been looked at. */
data class ImportCandidate(
    val path: String,
    val displayName: String,
    val isImage: Boolean,
    val pageCount: Int,
    val width: Float,
    val height: Float
)

/**
 * Choose what to take from a file that is being imported.
 *
 * A page range rather than "all of it": picking the answer sheet out of a fifty-page pack should
 * not mean adding fifty pages and deleting forty-nine of them by hand afterwards. Images skip the
 * range entirely - a picture is one page - and are asked the one question that matters for them
 * instead, which is whether to keep their own shape or fit the document's.
 */
@Composable
fun ImportPagesDialog(
    candidates: List<ImportCandidate>,
    documentWidth: Float,
    documentHeight: Float,
    insertAfter: Int?,
    onDismiss: () -> Unit,
    onImport: (pages: List<Triple<ImportCandidate, Int, Boolean>>) -> Unit
) {
    val multiPage = candidates.filter { !it.isImage && it.pageCount > 1 }
    val single = candidates.firstOrNull()

    var fromText by remember { mutableStateOf("1") }
    var toText by remember {
        mutableStateOf((multiPage.firstOrNull()?.pageCount ?: 1).toString())
    }
    // Images only: keep the picture's own proportions, or letterbox it onto a document page.
    var fitToPage by remember { mutableStateOf(true) }

    val limit = multiPage.minOfOrNull { it.pageCount } ?: 1
    val from = fromText.toIntOrNull()
    val to = toText.toIntOrNull()
    val rangeValid = multiPage.isEmpty() ||
        (from != null && to != null && from in 1..limit && to in from..limit)

    val anyImages = candidates.any { it.isImage }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (candidates.size == 1) "Import from ${single?.displayName}"
                else "Import ${candidates.size} files"
            )
        },
        text = {
            Column(Modifier.imePadding()) {
                Text(
                    if (insertAfter == null) "Added at the end of the document."
                    else "Added after page ${insertAfter + 1}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (multiPage.isNotEmpty()) {
                    Row(
                        Modifier.padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = fromText,
                            onValueChange = { v -> fromText = v.filter { it.isDigit() }.take(5) },
                            label = { Text("From page") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = toText,
                            onValueChange = { v -> toText = v.filter { it.isDigit() }.take(5) },
                            label = { Text("To page") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        if (multiPage.size == 1) {
                            "${multiPage[0].displayName} has ${multiPage[0].pageCount} pages."
                        } else {
                            "The range applies to each file; the shortest has $limit pages."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                if (anyImages) {
                    OptionLabel("Pictures")
                    OptionWrapRow {
                        OptionChip(
                            "Fit a ${documentWidth.toInt()}×${documentHeight.toInt()} page",
                            fitToPage
                        ) { fitToPage = true }
                        OptionChip("Keep the picture's own shape", !fitToPage) {
                            fitToPage = false
                        }
                    }
                }

                if (candidates.size > 1) {
                    Text(
                        candidates.joinToString(", ") { it.displayName },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = rangeValid && candidates.isNotEmpty(),
                onClick = {
                    val picked = ArrayList<Triple<ImportCandidate, Int, Boolean>>()
                    for (c in candidates) {
                        if (c.isImage) {
                            picked.add(Triple(c, 0, fitToPage))
                        } else {
                            val lo = ((from ?: 1) - 1).coerceIn(0, c.pageCount - 1)
                            val hi = ((to ?: c.pageCount) - 1).coerceIn(lo, c.pageCount - 1)
                            for (i in lo..hi) picked.add(Triple(c, i, false))
                        }
                    }
                    onImport(picked)
                }
            ) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
