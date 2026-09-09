package com.inkslate.desktop

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.inkslate.desktop.BlankDocumentFactory.PageSize

/**
 * Builds a new blank document: notebook page, graph paper, or a large whiteboard.
 *
 * The Android dialog, minus the choice between a fixed page and a canvas that grows. The infinite
 * canvas is not in this build's editor yet, and offering paper that claims to grow and then does
 * not is worse than not offering it - the option comes back when the editor can honour it.
 */
@Composable
fun NewDocumentDialog(
    onDismiss: () -> Unit,
    onCreate: (BlankDocumentFactory.Spec) -> Unit
) {
    var name by remember { mutableStateOf("Untitled") }
    var size by remember { mutableStateOf(PageSize.LETTER) }
    var pages by remember { mutableStateOf(1) }
    var paper by remember { mutableStateOf(PaperStyle()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New document") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    PaperPreview(
                        paper,
                        Modifier
                            .padding(start = 12.dp)
                            .height(58.dp)
                            .aspectRatio(size.width / size.height)
                            .clip(RoundedCornerShape(4.dp))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(4.dp)
                            )
                    )
                }

                BlankPaperOptions(style = paper, onChange = { paper = it })

                OptionLabel("Size")
                OptionWrapRow {
                    PageSize.entries.forEach { p ->
                        OptionChip(p.label, size == p) { size = p }
                    }
                }

                OptionLabel("Pages: $pages")
                Slider(
                    value = pages.toFloat(),
                    onValueChange = { pages = it.toInt().coerceAtLeast(1) },
                    valueRange = 1f..50f
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onCreate(
                        BlankDocumentFactory.Spec(
                            name = name,
                            pageSize = size,
                            background = paper.background,
                            pageCount = pages,
                            paperColor = paper.paperColor,
                            lineColor = paper.lineColor,
                            spacing = paper.spacing
                        )
                    )
                }
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
