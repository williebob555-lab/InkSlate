package com.inkslate.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.inkslate.core.Palette
import com.inkslate.core.Stroke
import com.inkslate.core.TextAlign
import com.inkslate.core.TextFont

/**
 * Write or edit a text box.
 *
 * The Android `RichTextDialog`, with the same controls in the same order: the words first, then
 * the font, size, weight, alignment, colour, and the box's own background and border. Deleting is
 * offered here as well as from the selection bar, because a text box you have just emptied is
 * almost always one you meant to remove.
 */
@Composable
fun TextDialog(
    initial: Stroke,
    swatches: List<Int>,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onConfirm: (Stroke) -> Unit
) {
    var text by remember(initial.id) { mutableStateOf(initial.text.orEmpty()) }
    var size by remember(initial.id) { mutableStateOf(initial.textSize) }
    var font by remember(initial.id) { mutableStateOf(initial.font) }
    var align by remember(initial.id) { mutableStateOf(initial.align) }
    var bold by remember(initial.id) { mutableStateOf(initial.bold) }
    var italic by remember(initial.id) { mutableStateOf(initial.italic) }
    var color by remember(initial.id) { mutableStateOf(initial.color) }
    var boxFill by remember(initial.id) { mutableStateOf(initial.boxFillColor) }
    var border by remember(initial.id) { mutableStateOf(initial.boxBorder) }
    var wrapWidth by remember(initial.id) { mutableStateOf(initial.boxWidth) }
    var picking by remember { mutableStateOf<TextColourTarget?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.text.isNullOrEmpty()) "Text" else "Edit text") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Text") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp)
                )

                OptionLabel("Font")
                OptionWrapRow {
                    TextFont.entries.forEach { f ->
                        OptionChip(f.label, font == f) { font = f }
                    }
                }

                OptionLabel("Style")
                OptionWrapRow {
                    OptionChip("Bold", bold) { bold = !bold }
                    OptionChip("Italic", italic) { italic = !italic }
                    OptionChip("Border", border) { border = !border }
                }

                OptionLabel("Alignment")
                OptionWrapRow {
                    TextAlign.entries.forEach { a ->
                        OptionChip(a.label, align == a) { align = a }
                    }
                }

                OptionLabel("Size: ${size.toInt()} pt")
                Slider(
                    value = size,
                    onValueChange = { size = it },
                    valueRange = 6f..72f
                )

                OptionLabel(
                    if (wrapWidth > 0f) "Wraps at ${wrapWidth.toInt()} pt"
                    else "Wrapping: off, one line per paragraph"
                )
                Slider(
                    value = wrapWidth,
                    onValueChange = { wrapWidth = if (it < 24f) 0f else it },
                    valueRange = 0f..600f
                )

                OptionLabel("Colour")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { CustomSwatch { picking = TextColourTarget.TEXT } }
                    items(swatches) { c ->
                        Swatch(Color(c), color == c) { color = c }
                    }
                }

                OptionLabel("Background")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        // Transparent is the default and has to be reachable again once a
                        // background has been chosen.
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(
                                    if (boxFill == 0) 3.dp else 1.dp,
                                    if (boxFill == 0) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                    CircleShape
                                )
                                .clickable { boxFill = 0 },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("/", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    item { CustomSwatch { picking = TextColourTarget.BACKGROUND } }
                    items(swatches) { c ->
                        Swatch(Color(c), boxFill == c) { boxFill = c }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    onConfirm(
                        initial.copy(
                            text = text,
                            textSize = size,
                            font = font,
                            align = align,
                            bold = bold,
                            italic = italic,
                            color = color,
                            boxFillColor = boxFill,
                            boxBorder = border,
                            boxWidth = wrapWidth,
                            updatedUtc = System.currentTimeMillis()
                        )
                    )
                }
            ) { Text("Done") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )

    picking?.let { target ->
        val isText = target == TextColourTarget.TEXT
        ColorPickerDialog(
            initial = if (isText) color else (boxFill.takeIf { it != 0 } ?: Palette.BLACK),
            title = if (isText) "Text colour" else "Background",
            presets = Palette.COLORS,
            recents = swatches,
            allowAlpha = !isText,
            onDismiss = { picking = null },
            onPick = {
                if (isText) color = it else boxFill = it
                picking = null
            }
        )
    }
}

private enum class TextColourTarget { TEXT, BACKGROUND }

/** A swatch that opens the full picker rather than selecting a fixed colour. */
@Composable
private fun CustomSwatch(onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "+",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
