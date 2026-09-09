package com.inkslate.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Interests
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.inkslate.core.BrushType
import com.inkslate.core.DashStyle
import com.inkslate.core.EraserMode
import com.inkslate.core.FillStyle
import com.inkslate.core.InputMode
import com.inkslate.core.Palette
import com.inkslate.core.Tool
import kotlin.math.roundToInt

/** Everything the toolbar can ask the editor to do. */
class ToolBarActions(
    val onChanged: () -> Unit = {},
    val onUndo: () -> Unit = {},
    val onRedo: () -> Unit = {},
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val onRestyleSelection: (Int?, Float?) -> Unit = { _, _ -> },
    val onDeleteSelection: () -> Unit = {},
    val onDuplicateSelection: () -> Unit = {},
    val onCopySelection: () -> Unit = {},
    val onCutSelection: () -> Unit = {},
    val onClearSelection: () -> Unit = {},
    val onPaste: () -> Unit = {},
    val canPaste: Boolean = false,
    val onInsertTable: () -> Unit = {},
    val onPickCustomColour: () -> Unit = {},
    val onInsertSymbol: () -> Unit = {},
    val onInsertStamp: () -> Unit = {},
    val onToggleRuler: () -> Unit = {},
    val onMessage: (String) -> Unit = {}
)

/**
 * The drawing tools.
 *
 * The Android toolbar, section for section: selection actions when there is a selection, then
 * colour and presets, then size, then the tools themselves, then everything else behind "More".
 * Two things the tablet needs are absent because the platform does not have them - the pen/finger
 * profile toggle, since a mouse is one input, and the barrel-button profiles.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolBar(
    state: ToolState,
    selectionCount: Int,
    actions: ToolBarActions
) {
    // A snapshot keyed on the revision counter. Reading the counter as a bare statement is not
    // enough to register a Compose state read, which is what made brush changes on the tablet
    // appear only after some other event forced a recomposition.
    val cfg = remember(state.revision) { state.snapshot() }
    var expanded by remember { mutableStateOf(false) }

    fun change(block: () -> Unit) {
        block()
        actions.onChanged()
    }

    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 330.dp).verticalScroll(rememberScrollState())
        ) {

            // ---- selection actions, shown only while something is selected ----
            AnimatedVisibility(visible = selectionCount > 0) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "$selectionCount selected",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { actions.onRestyleSelection(cfg.color, null) }) {
                        Icon(Icons.Default.BorderColor, "Apply current colour")
                    }
                    IconButton(onClick = actions.onCopySelection) {
                        Icon(Icons.Default.ContentCopy, "Copy")
                    }
                    IconButton(onClick = actions.onCutSelection) {
                        Icon(Icons.Default.ContentCut, "Cut")
                    }
                    IconButton(onClick = actions.onDuplicateSelection) {
                        Icon(Icons.Default.Layers, "Duplicate")
                    }
                    IconButton(onClick = actions.onDeleteSelection) {
                        Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                    IconButton(onClick = actions.onClearSelection) {
                        Icon(Icons.Default.Close, "Done selecting")
                    }
                }
            }

            // ---- colour and presets, always visible ----
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                InputModeToggle(state.activeMode) { held -> change { state.advanceMode(held) } }
                Spacer(Modifier.width(6.dp))
                // Deduplicated: a colour picked from the palette is also remembered as a recent
                // one, so without this the same swatch appears twice and both draw themselves
                // as selected.
                val swatches = remember(state.customColors) { state.swatches() }
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    itemsIndexed(state.presets) { i, p ->
                        PresetDot(
                            color = Color(p.color),
                            width = p.width,
                            highlighter = p.brush.isHighlighter,
                            selected = state.activePreset == i,
                            onClick = { change { state.selectPreset(i) } },
                            // Right-click overwrites, which is the tablet's long press.
                            onMenu = { change { state.savePresetFromCurrent(i) } }
                        )
                    }
                    item {
                        IconButton(
                            onClick = { change { state.addPreset() } },
                            modifier = Modifier.size(32.dp)
                        ) { Icon(Icons.Default.Add, "Save this pen as a preset", Modifier.size(17.dp)) }
                    }
                    item {
                        // The fixed palette covers the common cases; this exists because there is
                        // no good reason to be limited to them.
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                .clickable { actions.onPickCustomColour() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "+",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    items(swatches) { c ->
                        ColorDot(Color(c), cfg.color == c) {
                            change {
                                state.edit { it.color = c }
                                state.rememberColor(c)
                                state.activePreset = -1
                            }
                        }
                    }
                }
            }

            // ---- size, always visible ----
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isEraser = cfg.tool == Tool.ERASER
                // A ladder of fixed stops, not a continuous range: the slider addresses an index,
                // so every position is a width you can land on again.
                val ladder = if (isEraser) Palette.ERASER_SIZES else Palette.WIDTHS
                val current = if (isEraser) cfg.eraserRadius else cfg.strokeWidth
                val index = Palette.stopIndex(ladder, current)

                fun setStop(i: Int) = change {
                    val v = ladder[i.coerceIn(0, ladder.lastIndex)]
                    state.edit { if (isEraser) it.eraserRadius = v else it.strokeWidth = v }
                    state.activePreset = -1
                }

                Text(
                    if (isEraser) "Eraser" else "Size",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(46.dp)
                )
                IconButton(onClick = { setStop(index - 1) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Remove, "Thinner", Modifier.size(15.dp))
                }
                Slider(
                    value = index.toFloat(),
                    onValueChange = { setStop(it.roundToInt()) },
                    valueRange = 0f..ladder.lastIndex.toFloat(),
                    steps = ladder.size - 2,
                    modifier = Modifier.weight(1f).height(30.dp)
                )
                IconButton(onClick = { setStop(index + 1) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Add, "Thicker", Modifier.size(15.dp))
                }
                Text(
                    // two decimals below 1pt, where a hundredth is a visible difference
                    if (current < 1f) "%.2f".format(current) else "%.1f".format(current),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(38.dp),
                    textAlign = TextAlign.End
                )
                IconButton(
                    onClick = {
                        change {
                            state.edit { it.dynamicWidth = !it.dynamicWidth }
                            if (state.active.dynamicWidth) {
                                actions.onMessage(
                                    "Width follows the zoom: strokes come out the same thickness " +
                                        "on screen however far in you are."
                                )
                            }
                        }
                    },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        Icons.Default.ZoomIn,
                        if (cfg.dynamicWidth) "Width follows the zoom (on)"
                        else "Width follows the zoom (off)",
                        Modifier.size(17.dp),
                        tint = if (cfg.dynamicWidth) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    )
                }
            }

            // ---- eraser mode, only while the eraser is the tool in hand ----
            if (cfg.tool == Tool.ERASER) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Takes",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(46.dp)
                    )
                    EraserMode.entries.forEach { mode ->
                        Chip(mode.label, cfg.eraserMode == mode) {
                            change { state.edit { it.eraserMode = mode } }
                        }
                    }
                }
            }

            // ---- tools ----
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.Start,
                verticalArrangement = Arrangement.Top
            ) {
                ToolButton(
                    Icons.Default.Gesture, "Draw",
                    cfg.tool == Tool.DRAW && !cfg.brush.isHighlighter
                ) {
                    change {
                        state.edit {
                            it.tool = Tool.DRAW
                            if (it.brush.isHighlighter) it.toggleHighlighter()
                        }
                    }
                }
                ToolButton(
                    Icons.Default.BorderColor, "Marker",
                    cfg.brush.isHighlighter && cfg.tool == Tool.DRAW
                ) {
                    // toggles, so leaving the highlighter is one click and restores your pen
                    change { state.edit { it.toggleHighlighter() } }
                }
                ToolButton(Icons.Default.Remove, "Erase", cfg.tool == Tool.ERASER) {
                    change { state.edit { it.tool = Tool.ERASER } }
                }
                ToolButton(Icons.Default.SelectAll, "Select", cfg.tool == Tool.SELECT) {
                    change { state.edit { it.tool = Tool.SELECT } }
                }
                ToolButton(Icons.Default.OpenWith, "Pan", cfg.tool == Tool.PAN) {
                    change { state.edit { it.tool = Tool.PAN } }
                }
                ToolDivider()
                ToolButton(Icons.Default.TextFields, "Text", cfg.tool == Tool.TEXT) {
                    change { state.edit { it.tool = Tool.TEXT } }
                }
                ToolButton(Icons.Default.GridOn, "Table", cfg.tool == Tool.TABLE) {
                    change { state.edit { it.tool = Tool.TABLE } }
                }
                ToolButton(Icons.Default.Interests, "Shapes", cfg.tool.isShape) {
                    actions.onInsertStamp()
                }
                ToolButton(Icons.Default.Functions, "Symbol", false) { actions.onInsertSymbol() }
                ToolButton(Icons.Default.Straighten, "Ruler", state.rulerVisible) {
                    actions.onToggleRuler()
                }
                if (actions.canPaste) {
                    ToolButton(Icons.Default.ContentPaste, "Paste", false) { actions.onPaste() }
                }
                ToolDivider()
                ToolButton(
                    Icons.AutoMirrored.Filled.Undo, "Undo", false, enabled = actions.canUndo
                ) { actions.onUndo() }
                ToolButton(
                    Icons.AutoMirrored.Filled.Redo, "Redo", false, enabled = actions.canRedo
                ) { actions.onRedo() }
                ToolButton(
                    if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                    if (expanded) "Less" else "More", false
                ) { expanded = !expanded }
            }

            // ---- everything else ----
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp)) {

                    Label("Brush")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(BrushType.entries) { b ->
                            BrushOption(b, cfg.color, cfg.brush == b) {
                                change {
                                    state.edit { it.selectBrush(b) }
                                    state.activePreset = -1
                                }
                            }
                        }
                    }

                    SliderRow("Smoothing", cfg.smoothing, 0f..1f) { v ->
                        change { state.edit { it.smoothing = v } }
                    }
                    SliderRow("Opacity", cfg.opacity, 0.05f..1f) { v ->
                        change { state.edit { it.opacity = v }; state.activePreset = -1 }
                    }
                    SliderRow("Expression", state.active.dynamics, 0f..3f) { v ->
                        change { state.edit { it.dynamics = v } }
                    }

                    Label("Line")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DashStyle.entries.forEach { d ->
                            Chip(d.label, cfg.dash == d) { change { state.edit { it.dash = d } } }
                        }
                    }

                    Label("Shape fill")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FillStyle.entries.forEach { f ->
                            Chip(f.label, cfg.fillStyle == f) {
                                change { state.edit { it.fillStyle = f } }
                            }
                        }
                    }
                    if (cfg.fillStyle != FillStyle.NONE) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 6.dp)
                        ) {
                            items(state.swatches()) { c ->
                                ColorDot(Color(c), state.active.fillColor == c) {
                                    change { state.edit { it.fillColor = c } }
                                }
                            }
                        }
                    }

                    SliderRow("Text size", cfg.textSize, 6f..72f) { v ->
                        change { state.edit { it.textSize = v } }
                    }

                    Label("Table: ${state.tableRows} x ${state.tableCols}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Rows",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = state.tableRows.toFloat(),
                            onValueChange = { state.tableRows = it.roundToInt().coerceIn(1, 20) },
                            valueRange = 1f..20f,
                            modifier = Modifier.width(120.dp).height(28.dp)
                        )
                        Text(
                            "Columns",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = state.tableCols.toFloat(),
                            onValueChange = { state.tableCols = it.roundToInt().coerceIn(1, 20) },
                            valueRange = 1f..20f,
                            modifier = Modifier.width(120.dp).height(28.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Which pen is in hand, and the only way to reach a barrel profile.
 *
 * A plain click steps between the pen and the finger. Clicking it *with a stylus barrel button
 * held* selects that button's own profile - which is the only way in, because a barrel profile is
 * not a third thing in a loop: it is the pen with a button held, and having it turn up while
 * switching between pen and finger is both surprising and hard to get back out of.
 *
 * The button state is read from the raw pointer event rather than from a click, because the whole
 * gesture is a click *plus* a button and ordinary click handling has no notion of a stylus barrel.
 */
@Composable
private fun InputModeToggle(mode: InputMode, onCycle: (Int) -> Unit) {
    var heldButton by remember { mutableStateOf(0) }

    val icon = when (mode) {
        InputMode.PEN -> Icons.Default.Gesture
        InputMode.TOUCH -> Icons.Default.TouchApp
        else -> Icons.Default.Adjust
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press &&
                            event.changes.any { it.type == PointerType.Stylus }
                        ) {
                            heldButton = when {
                                event.buttons.isTertiaryPressed -> 2
                                event.buttons.isSecondaryPressed -> 1
                                else -> 0
                            }
                        }
                        // Never consumed: the ordinary click below still has to happen.
                    }
                }
            }
            .clickable {
                val held = heldButton
                heldButton = 0
                onCycle(held)
            }
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(
            mode.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

// ---- pieces ------------------------------------------------------------------

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(78.dp)
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f).height(30.dp)
        )
        Text(
            "%.2f".format(value),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(40.dp),
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
    )
}

@Composable
private fun ToolDivider() {
    Box(
        Modifier
            .padding(horizontal = 6.dp, vertical = 12.dp)
            .size(1.dp, 26.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    )
}

@Composable
private fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        selected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        Modifier
            .padding(2.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, Modifier.size(19.dp), tint = tint)
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

/** A saved pen, drawn as the mark it makes rather than as a word. */
@Composable
private fun PresetDot(
    color: Color,
    width: Float,
    highlighter: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onMenu: () -> Unit
) {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                CircleShape
            )
            .clickable(onClick = onClick)
            .secondaryClick(onMenu),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(22.dp)) {
            val r = (width.coerceIn(0.5f, 14f) / 14f) * (size.minDimension / 2.2f) + 2f
            drawCircle(color, radius = r, alpha = if (highlighter) 0.55f else 1f)
        }
    }
}

@Composable
private fun ColorDot(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(if (selected) 30.dp else 26.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                if (selected) 3.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                CircleShape
            )
            .clickable(onClick = onClick)
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

/**
 * A brush, shown as the stroke it makes.
 *
 * Names alone do not distinguish a gel pen from a ballpoint. A sample of the actual taper does,
 * and it is drawn with the same outline builder the page uses, so it cannot promise a stroke the
 * canvas will not deliver.
 */
@Composable
private fun BrushOption(brush: BrushType, color: Int, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(Modifier.size(64.dp, 22.dp)) {
            val sample = com.inkslate.core.Stroke(
                id = "sample",
                kind = com.inkslate.core.Stroke.Kind.FREEHAND,
                color = color,
                baseWidth = brush.defaultWidth.coerceAtMost(6f),
                brush = brush,
                opacity = brush.defaultAlpha,
                points = (0..24).map { i ->
                    val t = i / 24f
                    com.inkslate.core.InkPoint(
                        4f + t * (size.width - 8f),
                        size.height / 2f +
                            (kotlin.math.sin(t * Math.PI * 1.4).toFloat() * size.height * 0.22f),
                        brush.widthFor(
                            brush.defaultWidth.coerceAtMost(6f),
                            // a light-to-heavy-to-light press, which is what shows a taper
                            kotlin.math.sin(t * Math.PI).toFloat().coerceIn(0.08f, 1f)
                        )
                    )
                }
            )
            drawStroke(sample)
        }
        Text(
            brush.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
