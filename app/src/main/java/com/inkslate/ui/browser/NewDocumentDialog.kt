package com.inkslate.ui.browser

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.inkslate.pdf.BlankDocumentFactory
import com.inkslate.pdf.BlankDocumentFactory.PageSize
import com.inkslate.ui.BlankPaperOptions
import com.inkslate.ui.ColorPickerDialog
import com.inkslate.ui.OptionChip
import com.inkslate.ui.OptionLabel
import com.inkslate.ui.OptionWrapRow
import com.inkslate.ui.PaperPreview
import com.inkslate.ui.PaperStyle
import com.inkslate.ui.PaperTarget

/**
 * Builds a new blank document: notebook page, graph paper, or a large whiteboard canvas.
 *
 * The paper controls are shared verbatim with inserting a page into an open document - see
 * [BlankPaperOptions]. Everything unique to *creating a file* stays here: a name, and how many
 * pages to start with.
 */
@Composable
fun NewDocumentDialog(
    onDismiss: () -> Unit,
    onCreate: (BlankDocumentFactory.Spec) -> Unit
) {
    var name by remember { mutableStateOf("Untitled") }
    var size by remember { mutableStateOf(PageSize.LETTER) }
    var pages by remember { mutableStateOf(1) }
    var canvas by remember { mutableStateOf(false) }
    var paper by remember { mutableStateOf(PaperStyle()) }
    var picking by remember { mutableStateOf<PaperTarget?>(null) }

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

                BlankPaperOptions(
                    style = paper,
                    onChange = { paper = it },
                    onPickColour = { target, _ -> picking = target }
                )

                OptionLabel(if (canvas) "Starting size" else "Size")
                OptionWrapRow {
                    PageSize.entries.forEach { p ->
                        OptionChip(p.label, size == p) { size = p }
                    }
                }

                OptionLabel("Shape")
                OptionWrapRow {
                    OptionChip("Fixed pages", !canvas) { canvas = false }
                    OptionChip("Canvas, grows as you write", canvas) { canvas = true }
                }
                if (canvas) {
                    Text(
                        "One page that gets bigger whenever you write near an edge. It is still " +
                            "an ordinary PDF, so it opens and syncs like everything else.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    OptionLabel("Pages: $pages")
                    Slider(
                        value = pages.toFloat(),
                        onValueChange = { pages = it.toInt().coerceAtLeast(1) },
                        valueRange = 1f..50f
                    )
                }
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
                            pageCount = if (canvas) 1 else pages,
                            paperColor = paper.paperColor,
                            lineColor = paper.lineColor,
                            spacing = paper.spacing,
                            autoGrow = canvas
                        )
                    )
                }
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    picking?.let { target ->
        val isPaper = target == PaperTarget.PAPER
        ColorPickerDialog(
            initial = if (isPaper) paper.paperColor else paper.lineColor,
            title = if (isPaper) "Paper colour" else "Ruling colour",
            presets = if (isPaper) BlankDocumentFactory.PAPER_COLORS
            else BlankDocumentFactory.LINE_COLORS,
            onDismiss = { picking = null },
            onPick = { picked ->
                paper = if (isPaper) paper.copy(paperColor = picked)
                else paper.copy(lineColor = picked)
                picking = null
            }
        )
    }
}
