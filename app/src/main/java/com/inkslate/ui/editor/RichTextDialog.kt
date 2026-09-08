package com.inkslate.ui.editor

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkslate.ink.*
import com.inkslate.ink.Stroke
import com.inkslate.ink.TextAlign
import com.inkslate.ink.TextFont

/** Everything a text object carries, gathered so the dialog can hand it back in one piece. */
data class TextSpec(
    val text: String,
    val size: Float,
    val color: Int,
    val bold: Boolean,
    val italic: Boolean,
    val font: TextFont,
    val align: TextAlign,
    val boxWidth: Float,
    val boxFillColor: Int,
    val boxBorder: Boolean
)

/**
 * Compose or restyle a text object.
 *
 * Shows a live preview in the chosen face and size, because the useful question when placing a
 * label on a worksheet is "does this fit and read", which a settings list alone cannot answer.
 */
@Composable
fun RichTextDialog(
    existing: Stroke?,
    defaultSize: Float,
    defaultColor: Int,
    pageWidth: Float,
    onDismiss: () -> Unit,
    onConfirm: (TextSpec) -> Unit,
    onDelete: (() -> Unit)? = null
) {
    var text by remember { mutableStateOf(existing?.text.orEmpty()) }
    var size by remember { mutableStateOf(existing?.textSize ?: defaultSize) }
    var color by remember { mutableStateOf(existing?.color ?: defaultColor) }
    var bold by remember { mutableStateOf(existing?.bold ?: false) }
    var italic by remember { mutableStateOf(existing?.italic ?: false) }
    var font by remember { mutableStateOf(existing?.font ?: TextFont.SANS) }
    var align by remember { mutableStateOf(existing?.align ?: TextAlign.LEFT) }
    var wrap by remember { mutableStateOf((existing?.boxWidth ?: 0f) > 0f) }
    var boxWidth by remember {
        mutableStateOf(existing?.boxWidth?.takeIf { it > 0f } ?: (pageWidth * 0.5f))
    }
    var boxFill by remember { mutableStateOf(existing?.boxFillColor ?: android.graphics.Color.TRANSPARENT) }
    var border by remember { mutableStateOf(existing?.boxBorder ?: false) }
    var pickingText by remember { mutableStateOf(false) }
    var pickingBox by remember { mutableStateOf(false) }

    val focus = remember { FocusRequester() }
    // Open the keyboard straight away: this dialog exists to type into.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        // decorFitsSystemWindows = false lets imePadding below actually move the content, so the
        // keyboard cannot cover the field you are typing into.
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text(if (existing == null) "Add text" else "Edit text") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 380.dp)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
            ) {

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Text") },
                    minLines = 2,
                    modifier = Modifier.focusRequester(focus),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = font.toComposeFamily(),
                        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal
                    ),
                )

                Label("Font")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(TextFont.entries) { f ->
                        Chip(f.label, font == f) { font = f }
                    }
                }

                Row(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Chip("Bold", bold) { bold = !bold }
                    Chip("Italic", italic) { italic = !italic }
                    Chip("Border", border) { border = !border }
                }

                Label("Alignment")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextAlign.entries.forEach { a ->
                        Chip(a.label, align == a) { align = a }
                    }
                }

                Label("Size: ${size.toInt()} pt")
                Slider(value = size, onValueChange = { size = it }, valueRange = 6f..72f)

                Label("Colour")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { Swatch(Color.Transparent, false, showPlus = true) { pickingText = true } }
                    items(ToolState.PALETTE) { c ->
                        Swatch(Color(c), color == c) { color = c }
                    }
                }

                Label("Box")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(if (wrap) "Wraps to width" else "No wrapping", wrap) { wrap = !wrap }
                }
                if (wrap) {
                    Label("Box width: ${boxWidth.toInt()} pt")
                    Slider(
                        value = boxWidth.coerceIn(40f, pageWidth),
                        onValueChange = { boxWidth = it },
                        valueRange = 40f..pageWidth
                    )
                }

                Label("Box background")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        Swatch(
                            Color.Transparent,
                            boxFill == android.graphics.Color.TRANSPARENT,
                            showNone = true
                        ) { boxFill = android.graphics.Color.TRANSPARENT }
                    }
                    item { Swatch(Color.Transparent, false, showPlus = true) { pickingBox = true } }
                    items(ToolState.PALETTE) { c ->
                        Swatch(Color(c), boxFill == c) { boxFill = c }
                    }
                }

                if (onDelete != null) {
                    TextButton(onClick = onDelete, modifier = Modifier.padding(top = 6.dp)) {
                        Text("Delete this text", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    onConfirm(
                        TextSpec(
                            text = text, size = size, color = color,
                            bold = bold, italic = italic, font = font, align = align,
                            boxWidth = if (wrap) boxWidth else 0f,
                            boxFillColor = boxFill, boxBorder = border
                        )
                    )
                }
            ) { Text(if (existing == null) "Add" else "Update") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (pickingText) {
        com.inkslate.ui.ColorPickerDialog(
            initial = color,
            title = "Text colour",
            presets = ToolState.PALETTE,
            onDismiss = { pickingText = false },
            onPick = { color = it; pickingText = false }
        )
    }
    if (pickingBox) {
        com.inkslate.ui.ColorPickerDialog(
            initial = boxFill,
            title = "Box background",
            presets = ToolState.PALETTE,
            allowAlpha = true,
            onDismiss = { pickingBox = false },
            onPick = { boxFill = it; pickingBox = false }
        )
    }
}

private fun TextFont.toComposeFamily(): FontFamily = when (this) {
    TextFont.SANS -> FontFamily.SansSerif
    TextFont.SERIF -> FontFamily.Serif
    TextFont.MONO -> FontFamily.Monospace
    TextFont.CASUAL -> FontFamily.Cursive
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Swatch(
    color: Color,
    selected: Boolean,
    showNone: Boolean = false,
    showPlus: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                if (selected) 3.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        if (showPlus) {
            Text(
                "+",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (showNone) {
            Text(
                "∅",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
