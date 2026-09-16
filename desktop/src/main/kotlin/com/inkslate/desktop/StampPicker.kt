package com.inkslate.desktop

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.inkslate.core.Box as InkBox
import com.inkslate.core.DashStyle
import com.inkslate.core.FillStyle
import com.inkslate.core.LineEnd
import com.inkslate.core.Palette
import com.inkslate.core.StampShelf
import com.inkslate.core.Stamps
import com.inkslate.core.TextFont
import com.inkslate.core.Stroke
import kotlin.math.min
import kotlin.math.roundToInt

/** The page colour previews sit on, because that is what a stamp will sit on. */
internal val PAPER = Color(0xFFFDFDFB)

/** Virtual units a preview is built in before it is scaled to fit. */
private const val VIRTUAL = 240f

/**
 * Every shape and stamp, for finding the ones that are not already in the tray.
 *
 * Choosing one puts it in hand and closes; the next click on the page places it. There is no
 * settings step on the way: it comes out the way it was last set, and is changed from the tray's
 * settings button or by selecting one on the page. The pin keeps a stamp in the tray.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StampLibrary(
    shelf: StampShelf,
    onDismiss: () -> Unit,
    onTogglePin: (Stamps.Kind) -> Unit,
    onPick: (Stamps.Kind) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
            Text(
                "Shapes & stamps",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, bottom = 8.dp)
            )
            HorizontalDivider()
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Stamps.Group.entries.forEach { group ->
                    val kinds = Stamps.Kind.entries.filter { it.group == group }
                    if (kinds.isEmpty()) return@forEach
                    Text(
                        group.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 6.dp)
                    )
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        kinds.forEach { k ->
                            LibraryTile(k, shelf.optionsFor(k), shelf.isPinned(k), { onTogglePin(k) }) {
                                onPick(k)
                            }
                        }
                    }
                }
                Box(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun LibraryTile(
    kind: Stamps.Kind,
    options: Stamps.StampOptions,
    pinned: Boolean,
    onPin: () -> Unit,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .size(112.dp, 132.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(100.dp).clip(RoundedCornerShape(6.dp)).background(PAPER)) {
            StampPreview(kind, options, Modifier.size(100.dp))
            if (!kind.isShape) {
                Icon(
                    if (pinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                    if (pinned) "Unpin from the tray" else "Pin to the tray",
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(26.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onPin)
                        .padding(5.dp),
                    tint = if (pinned) MaterialTheme.colorScheme.primary else Color(0xFF9AA0A6)
                )
            }
        }
        Text(
            kind.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/**
 * A stamp drawn by the renderer that will place it, fitted inside [modifier]'s box - scaled the
 * same amount across and down, centred, with its numbers and labels.
 */
@Composable
fun StampPreview(kind: Stamps.Kind, options: Stamps.StampOptions, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val aspect = Stamps.aspectFor(kind, options)
    val boxH = VIRTUAL / aspect
    val strokes = remember(kind, options) {
        val placedWidth = if (options.size > 0f) options.size else 220f
        val shown = options.copy(weight = options.weight * (VIRTUAL / placedWidth).coerceIn(0.5f, 2.5f))
        var n = 0
        Stamps.build(kind, InkBox(0f, 0f, VIRTUAL, boxH), page = 0, options = shown) { "preview-${n++}" }
    }
    Canvas(modifier) {
        val margin = 0.16f
        val availW = size.width * (1f - margin * 2f)
        val availH = size.height * (1f - margin * 2f)
        if (availW <= 0f || availH <= 0f) return@Canvas
        val s = min(availW, availH * aspect) / VIRTUAL
        translate((size.width - VIRTUAL * s) / 2f, (size.height - boxH * s) / 2f) {
            scale(s, s, pivot = Offset.Zero) {
                strokes.forEach {
                    if (it.kind == Stroke.Kind.TEXT) drawTextStroke(it, measurer) else drawStroke(it, cached = false)
                }
            }
        }
    }
}

/**
 * A stamp's settings, docked over the bottom of the page rather than in a sheet.
 *
 * Not modal: when it is changing a stamp already on the page the page is the preview, and a sheet
 * would cover the thing being adjusted. Only what this stamp responds to is shown.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StampSettingsPanel(
    kind: Stamps.Kind,
    options: Stamps.StampOptions,
    subtitle: String,
    /** Changes when a different stamp is being edited, so what was typed for one is not kept. */
    editKey: Any,
    preview: Boolean,
    recentColours: List<Int>,
    onChange: (Stamps.StampOptions) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Which colour is being picked from the full picker: where it starts, and where it goes.
    var picking by remember { mutableStateOf<Pair<Int, (Int) -> Unit>?>(null) }
    // What is typed, kept apart from what is drawn: a range is briefly backwards while it is being
    // retyped, and sanitising that on every keystroke would put the old numbers back.
    var draft by remember(kind, editKey) { mutableStateOf(options) }
    fun edit(block: (Stamps.StampOptions) -> Stamps.StampOptions) {
        draft = block(draft)
        onChange(Stamps.sanitise(kind, draft))
    }

    Surface(
        modifier.widthIn(max = 560.dp).fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(kind.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onDone) { Text("Done") }
            }
            Column(
                Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 14.dp)
            ) {
                if (preview) {
                    val a = Stamps.aspectFor(kind, Stamps.sanitise(kind, draft)).coerceIn(1.3f, 3.2f)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(PAPER)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                    ) {
                        StampPreview(kind, Stamps.sanitise(kind, draft), Modifier.fillMaxWidth().height(150.dp))
                    }
                }
                SettingsBody(kind, draft, recentColours, ::edit) { initial, apply -> picking = initial to apply }
            }
        }
    }

    picking?.let { (initial, apply) ->
        ColorPickerDialog(
            initial = if (initial != 0) initial else draft.color,
            title = kind.label + " colour",
            presets = Palette.COLORS,
            recents = recentColours,
            onDismiss = { picking = null },
            onPick = { c -> picking = null; apply(c) }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsBody(
    kind: Stamps.Kind,
    options: Stamps.StampOptions,
    recentColours: List<Int>,
    edit: ((Stamps.StampOptions) -> Stamps.StampOptions) -> Unit,
    onCustomColour: (Int, (Int) -> Unit) -> Unit
) {
    OptionLabel("Colour")
    ColourRow(options.color, false, recentColours, { c -> edit { it.copy(color = c) } }) {
        onCustomColour(options.color) { c -> edit { it.copy(color = c) } }
    }

    OptionLabel("Line weight: " + trimNumber(options.weight))
    Slider(
        value = options.weight.coerceIn(0.5f, 8f),
        onValueChange = { v -> edit { it.copy(weight = (v * 4f).roundToInt() / 4f) } },
        valueRange = 0.5f..8f
    )
    ScaleSlider("Opacity", options.opacity, 0.1f..1f) { v -> edit { it.copy(opacity = v) } }

    if (Stamps.Knob.VARIANT in kind.knobs && kind.variants.isNotEmpty()) {
        OptionLabel("Style")
        OptionWrapRow {
            kind.variants.forEachIndexed { i, name ->
                OptionChip(name, options.variant == i) { edit { it.copy(variant = i) } }
            }
        }
    }
    if (Stamps.Knob.ENDS in kind.knobs) {
        // Each end on its own: an arrow, a double arrow and a dimension line are one line.
        OptionLabel("Start")
        OptionWrapRow {
            LineEnd.entries.forEach { e -> OptionChip(e.label, options.startEnd == e) { edit { it.copy(startEnd = e) } } }
        }
        OptionLabel("End")
        OptionWrapRow {
            LineEnd.entries.forEach { e -> OptionChip(e.label, options.finishEnd == e) { edit { it.copy(finishEnd = e) } } }
        }
        if (options.startEnd != LineEnd.NONE || options.finishEnd != LineEnd.NONE) {
            ScaleSlider("End size", options.endScale, 0.3f..4f) { v -> edit { it.copy(endScale = v) } }
        }
    }
    if (Stamps.Knob.DASH in kind.knobs) {
        OptionLabel("Line")
        OptionWrapRow {
            DashStyle.entries.forEach { d -> OptionChip(d.label, options.dash == d) { edit { it.copy(dash = d) } } }
        }
    }
    if (Stamps.Knob.FILL in kind.knobs) {
        OptionLabel("Fill")
        OptionWrapRow {
            FillStyle.entries.forEach { f -> OptionChip(f.label, options.fill == f) { edit { it.copy(fill = f) } } }
        }
    }
    if (Stamps.Knob.DIVISIONS in kind.knobs) {
        OptionLabel("${kind.divisionsLabel}: ${options.divisions}")
        Slider(
            value = options.divisions.toFloat().coerceIn(kind.divisionsRange.first.toFloat(), kind.divisionsRange.last.toFloat()),
            onValueChange = { v -> edit { it.copy(divisions = v.roundToInt()) } },
            valueRange = kind.divisionsRange.first.toFloat()..kind.divisionsRange.last.toFloat()
        )
    }
    if (Stamps.Knob.FILLED in kind.knobs) {
        OptionLabel("Shaded: ${options.filled} of ${options.divisions}")
        Slider(
            value = options.filled.toFloat(),
            onValueChange = { v -> edit { it.copy(filled = v.roundToInt()) } },
            valueRange = 0f..options.divisions.toFloat().coerceAtLeast(1f)
        )
    }

    val graph = kind == Stamps.Kind.AXES || kind == Stamps.Kind.COORD_GRID
    if (graph) {
        OptionLabel("Start from")
        OptionWrapRow {
            OptionChip("Four quadrants", options.rangeFrom < 0f && options.yFrom < 0f) {
                edit { it.copy(rangeFrom = -5f, rangeTo = 5f, step = 1f, yFrom = -5f, yTo = 5f, yStep = 1f) }
            }
            OptionChip("First quadrant", options.rangeFrom == 0f && options.yFrom == 0f) {
                edit { it.copy(rangeFrom = 0f, rangeTo = 10f, step = 1f, yFrom = 0f, yTo = 10f, yStep = 1f) }
            }
        }
    }
    if (Stamps.Knob.X_AXIS in kind.knobs) {
        RangeFields(
            if (graph) "Horizontal axis" else "Values", kind.name + ":x",
            options.rangeFrom, options.rangeTo, options.step,
            { v -> edit { it.copy(rangeFrom = v) } },
            { v -> edit { it.copy(rangeTo = v) } },
            { v -> edit { it.copy(step = v) } }
        )
    }
    if (Stamps.Knob.Y_AXIS in kind.knobs) {
        RangeFields(
            "Vertical axis", kind.name + ":y",
            options.yFrom, options.yTo, options.yStep,
            { v -> edit { it.copy(yFrom = v) } },
            { v -> edit { it.copy(yTo = v) } },
            { v -> edit { it.copy(yStep = v) } }
        )
    }
    if (Stamps.Knob.TICKS in kind.knobs) {
        SwitchRow("Tick marks", options.ticks) { v -> edit { it.copy(ticks = v) } }
    }
    if (Stamps.Knob.TICK_VALUES in kind.knobs) {
        SwitchRow("Numbers at the ticks", options.tickValues) { v -> edit { it.copy(tickValues = v) } }
    }
    if (Stamps.Knob.AXIS_NAMES in kind.knobs) {
        OptionLabel("Axis names")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = options.xName,
                onValueChange = { v -> edit { it.copy(xName = v.take(24)) } },
                label = { Text(if (graph) "Across" else "Along the bottom") },
                placeholder = { Text("None") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = options.yName,
                onValueChange = { v -> edit { it.copy(yName = v.take(24)) } },
                label = { Text(if (graph) "Up" else "Up the side") },
                placeholder = { Text("None") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
    }
    if (Stamps.Knob.LABELS in kind.knobs) {
        SwitchRow("Labels", options.labels) { v -> edit { it.copy(labels = v) } }
    }

    val parts = remember(kind, options) { Stamps.features(kind, Stamps.sanitise(kind, options)) }
    val hasTicks = Stamps.Knob.TICKS in kind.knobs

    if (Stamps.Feature.ARROWS in parts && Stamps.Knob.ENDS !in kind.knobs) {
        Section("Arrowheads")
        OptionWrapRow {
            LineEnd.entries.forEach { e -> OptionChip(e.label, options.arrowEnds == e) { edit { it.copy(arrowEnds = e) } } }
        }
        if (options.arrowEnds != LineEnd.NONE) {
            ScaleSlider("Size", options.endScale, 0.3f..4f) { v -> edit { it.copy(endScale = v) } }
        }
    }

    if (Stamps.Feature.DETAIL in parts || hasTicks) {
        Section(if (hasTicks) "Ticks and dividers" else "Dividers and detail lines")
        OptionLabel("Colour")
        ColourRow(options.detailColor, true, recentColours, { c -> edit { it.copy(detailColor = c) } }) {
            onCustomColour(options.detailColor) { c -> edit { it.copy(detailColor = c) } }
        }
        ScaleSlider("Weight", options.detailWeight, 0.25f..4f) { v -> edit { it.copy(detailWeight = v) } }
        if (hasTicks) {
            ScaleSlider("Tick length", options.tickLength, 0f..4f) { v -> edit { it.copy(tickLength = v) } }
        }
    }

    if (Stamps.Feature.FINE in parts) {
        Section("Grid and fine lines")
        OptionLabel("Colour")
        ColourRow(options.gridColor, true, recentColours, { c -> edit { it.copy(gridColor = c) } }) {
            onCustomColour(options.gridColor) { c -> edit { it.copy(gridColor = c) } }
        }
        ScaleSlider("Weight", options.gridWeight, 0.25f..4f) { v -> edit { it.copy(gridWeight = v) } }
        OptionLabel("Line")
        OptionWrapRow {
            DashStyle.entries.forEach { d -> OptionChip(d.label, options.gridDash == d) { edit { it.copy(gridDash = d) } } }
        }
    }

    if (Stamps.Feature.FILL in parts || (Stamps.Knob.FILL in kind.knobs && options.fill != FillStyle.NONE)) {
        Section("Fill")
        OptionLabel("Colour")
        ColourRow(options.fillColor, true, recentColours, { c -> edit { it.copy(fillColor = c) } }) {
            onCustomColour(options.fillColor) { c -> edit { it.copy(fillColor = c) } }
        }
    }

    if (Stamps.Feature.TEXT in parts) {
        Section("Text")
        OptionLabel("Colour")
        ColourRow(options.textColor, true, recentColours, { c -> edit { it.copy(textColor = c) } }) {
            onCustomColour(options.textColor) { c -> edit { it.copy(textColor = c) } }
        }
        ScaleSlider("Size", options.textScale, 0.4f..3f) { v -> edit { it.copy(textScale = v) } }
        OptionLabel("Font")
        OptionWrapRow {
            TextFont.entries.forEach { f -> OptionChip(f.label, options.textFont == f) { edit { it.copy(textFont = f) } } }
            OptionChip("Bold", options.textBold) { edit { it.copy(textBold = !it.textBold) } }
        }
    }

    if (Stamps.Feature.VALUES in parts) {
        Section("Numbers")
        ScaleSlider("Size, against the text", options.valueScale, 0.4f..3f) { v -> edit { it.copy(valueScale = v) } }
        OutlinedTextField(
            value = options.valueSuffix,
            onValueChange = { v -> edit { it.copy(valueSuffix = v.take(8)) } },
            label = { Text("After each number") },
            placeholder = { Text("e.g. cm, °, %") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        )
    }
}

/** A heading that starts a group of settings for one part of the stamp. */
@Composable
private fun Section(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 18.dp, bottom = 2.dp)
    )
}

/**
 * A slider for a size or weight against the stamp's own, shown as a percentage.
 *
 * Relative rather than absolute because the stamp can be any size: "twice as heavy" means the
 * same thing on a thumbnail and on a whole-page graph, where "3 points" does not.
 */
@Composable
private fun ScaleSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    OptionLabel(label + ": " + (value * 100f).roundToInt() + "%")
    Slider(
        value = value.coerceIn(range.start, range.endInclusive),
        onValueChange = { v -> onChange((v * 20f).roundToInt() / 20f) },
        valueRange = range
    )
}

/**
 * Colour swatches for one part. [allowSame] adds a "same as the stamp" choice, which is the value
 * 0 - so a part nobody has coloured follows the stamp's colour when that changes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColourRow(
    selected: Int,
    allowSame: Boolean,
    recentColours: List<Int>,
    onPick: (Int) -> Unit,
    onCustom: () -> Unit
) {
    val swatches = remember(recentColours) { (Palette.COLORS.take(10) + recentColours).distinct().take(14) }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (allowSame) OptionChip("Same as stamp", selected == 0) { onPick(0) }
        swatches.forEach { c ->
            val chosen = c == selected
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(
                        if (chosen) 3.dp else 1.dp,
                        if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        CircleShape
                    )
                    .clickable { onPick(c) }
            )
        }
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onCustom),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.Add, "Another colour", Modifier.size(15.dp)) }
    }
}

/** From, to and each tick, typed; kept as text so a half-typed "-" or "0." survives. */
@Composable
private fun RangeFields(
    heading: String,
    key: String,
    from: Float,
    to: Float,
    step: Float,
    onFrom: (Float) -> Unit,
    onTo: (Float) -> Unit,
    onStep: (Float) -> Unit
) {
    var fromText by remember(key) { mutableStateOf(trimNumber(from)) }
    var toText by remember(key) { mutableStateOf(trimNumber(to)) }
    var stepText by remember(key) { mutableStateOf(trimNumber(step)) }
    // A preset chip changes the numbers from outside; follow it.
    if (fromText.toFloatOrNull() != null && fromText.toFloatOrNull() != from) fromText = trimNumber(from)
    if (toText.toFloatOrNull() != null && toText.toFloatOrNull() != to) toText = trimNumber(to)
    if (stepText.toFloatOrNull() != null && stepText.toFloatOrNull() != step) stepText = trimNumber(step)

    fun clean(v: String) = v.filter { it.isDigit() || it == '-' || it == '.' }.take(9)
    OptionLabel(heading)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = fromText,
            onValueChange = { v -> fromText = clean(v); fromText.toFloatOrNull()?.let(onFrom) },
            label = { Text("From") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = toText,
            onValueChange = { v -> toText = clean(v); toText.toFloatOrNull()?.let(onTo) },
            label = { Text("To") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = stepText,
            onValueChange = { v ->
                stepText = clean(v).replace("-", "")
                stepText.toFloatOrNull()?.takeIf { it > 0f }?.let(onStep)
            },
            label = { Text("Each tick") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** "5", "2.5" - never "5.0", which reads as a precision the value does not have. */
internal fun trimNumber(v: Float): String {
    val rounded = (v * 1000f).roundToInt() / 1000f
    return if (kotlin.math.abs(rounded - rounded.roundToInt()) < 0.0005f) {
        rounded.roundToInt().toString()
    } else {
        ("%.3f".format(rounded)).trimEnd('0').trimEnd('.')
    }
}
