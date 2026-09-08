package com.inkslate.ui.editor

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
 * What to insert, when adding blank pages to a document that already exists.
 *
 * The paper controls are the same composable the New-document screen uses, so a page added
 * halfway through a set of notes can be exactly the paper you would have started them on. What
 * differs is only what has to: the size defaults to matching the document rather than to Letter,
 * because a page that does not match the ones around it is almost never what was meant.
 */
@Composable
fun InsertPagesDialog(
    /** The size of the document's own pages, offered as the default. */
    documentWidth: Float,
    documentHeight: Float,
    /** The style last used in this sheet, so adding a second matching page is one tap. */
    initial: PaperStyle,
    insertAfter: Int?,
    onDismiss: () -> Unit,
    onInsert: (count: Int, width: Float, height: Float, paper: PaperStyle) -> Unit
) {
    var paper by remember { mutableStateOf(initial) }
    var count by remember { mutableStateOf(1) }
    // null means "match the document", which is the default and usually the whole answer
    var size by remember { mutableStateOf<PageSize?>(null) }
    var picking by remember { mutableStateOf<PaperTarget?>(null) }

    val width = size?.width ?: documentWidth
    val height = size?.height ?: documentHeight

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 1) "Insert a page" else "Insert $count pages") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PaperPreview(
                        paper,
                        Modifier
                            .height(72.dp)
                            .aspectRatio(width / height)
                            .clip(RoundedCornerShape(4.dp))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(4.dp)
                            )
                    )
                    Text(
                        if (insertAfter == null) "Added at the end of the document"
                        else "Added after page ${insertAfter + 1}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }

                BlankPaperOptions(
                    style = paper,
                    onChange = { paper = it },
                    onPickColour = { target, _ -> picking = target }
                )

                OptionLabel("Size")
                OptionWrapRow {
                    OptionChip(
                        "Match document (${documentWidth.toInt()}×${documentHeight.toInt()})",
                        size == null
                    ) { size = null }
                    PageSize.entries.forEach { p ->
                        OptionChip(p.label, size == p) { size = p }
                    }
                }

                OptionLabel("How many: $count")
                Slider(
                    value = count.toFloat(),
                    onValueChange = { count = it.toInt().coerceAtLeast(1) },
                    valueRange = 1f..50f
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onInsert(count, width, height, paper) }) { Text("Insert") }
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
