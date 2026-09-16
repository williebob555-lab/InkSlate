package com.inkslate.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.inkslate.core.DashStyle
import com.inkslate.core.FillStyle
import com.inkslate.core.Palette
import com.inkslate.core.StampShelf
import com.inkslate.core.Stamps
import com.inkslate.ink.StrokeRasteriser
import com.inkslate.ui.ColorPickerDialog
import com.inkslate.ui.OptionChip
import com.inkslate.ui.OptionWrapRow
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Every shape and stamp, for finding the ones that are not already in the tray.
 *
 * Choosing one puts it in hand and closes; the next tap on the page places it. There is no
 * settings step on the way: a stamp comes out the way it was last set, and is changed from the
 * tray's settings button or by selecting one on the page. The pin puts a stamp in the tray for good.
 */
@Composable
fun StampLibraryDialog(
    shelf: StampShelf,
    onDismiss: () -> Unit,
    onTogglePin: (Stamps.Kind) -> Unit,
    onPick: (Stamps.Kind) -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth(0.96f).heightIn(max = 620.dp),
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 4.dp
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Shapes & stamps",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
                }
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 14.dp)
                ) {
                    Stamps.Group.entries.forEach { group ->
                        val kinds = Stamps.Kind.entries.filter { it.group == group }
                        if (kinds.isEmpty()) return@forEach
                        Text(
                            group.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp)
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            kinds.forEach { k ->
                                LibraryTile(
                                    kind = k,
                                    options = shelf.optionsFor(k),
                                    pinned = shelf.isPinned(k),
                                    onPin = { onTogglePin(k) },
                                    onClick = { onPick(k) }
                                )
                            }
                        }
                    }
                }
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
            .width(104.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(PAPER)
        ) {
            StampPreview(kind, options, Modifier.fillMaxWidth().aspectRatio(1f))
            // The group shapes have nothing to pin: all four are always in the tray.
            if (!kind.isShape) {
                Icon(
                    if (pinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                    if (pinned) "Unpin from the tray" else "Pin to the tray",
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(30.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onPin)
                        .padding(6.dp),
                    tint = if (pinned) MaterialTheme.colorScheme.primary else Color(0xFF9AA0A6)
                )
            }
        }
        Text(
            kind.label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(top = 3.dp)
        )
    }
}

// ---- previews ------------------------------------------------------------------

/** The page colour previews sit on, because that is what a stamp will sit on. */
internal val PAPER = Color(0xFFFDFDFB)

/** Virtual units a preview is built in before it is scaled to fit. */
private const val VIRTUAL = 240f

/**
 * A stamp drawn by the renderer that will place it, fitted inside [modifier]'s box.
 *
 * Scaled by the same amount across and down, and centred. The old preview stretched a square
 * drawing to whatever box it was given, which is how a number line came out as a smear and a
 * circle as an egg.
 */
@Composable
fun StampPreview(kind: Stamps.Kind, options: Stamps.StampOptions, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val aspect = Stamps.aspectFor(kind, options)
        // The drawing's own box, in virtual units, as wide as it wants to be.
        val margin = 0.16f
        val availW = size.width * (1f - margin * 2f)
        val availH = size.height * (1f - margin * 2f)
        if (availW <= 0f || availH <= 0f) return@Canvas
        val fitW = min(availW, availH * aspect)
        val scale = fitW / VIRTUAL
        val boxW = VIRTUAL
        val boxH = VIRTUAL / aspect
        // Weight relative to how big the stamp is placed, so a thin stamp does not preview heavy.
        val placedWidth = if (options.size > 0f) options.size else 220f
        val shown = options.copy(weight = options.weight * (VIRTUAL / placedWidth).coerceIn(0.5f, 2.5f))
        var n = 0
        val strokes = Stamps.build(
            kind, com.inkslate.core.Box(0f, 0f, boxW, boxH), page = 0, options = shown
        ) { "preview-" + (n++) }
        drawIntoCanvas { c ->
            val native = c.nativeCanvas
            val save = native.save()
            native.translate((size.width - boxW * scale) / 2f, (size.height - boxH * scale) / 2f)
            native.scale(scale, scale)
            val previous = StrokeRasteriser.colorFilter
            StrokeRasteriser.colorFilter = null
            strokes.forEach { StrokeRasteriser.draw(native, it) }
            StrokeRasteriser.colorFilter = previous
            native.restoreToCount(save)
        }
    }
}

// ---- settings ------------------------------------------------------------------

/**
 * A stamp's settings, docked over the bottom of the page rather than in a dialog.
 *
 * Not modal on purpose: when it is changing a stamp already on the page, the page *is* the
 * preview, and a dialog would sit on top of the thing being adjusted. [preview] draws one anyway
 * for the stamp in hand, which is not on the page yet.
 *
 * Only what this stamp responds to is shown. A graph gets its two axes, ticks, numbers and names;
 * a tick mark gets a colour and a weight.
 */
@Composable
fun StampSettingsPanel(
    kind: Stamps.Kind,
    options: Stamps.StampOptions,
    /** What is being changed: the next one placed, or one already on the page. */
    subtitle: String,
    /** Changes when a different stamp is being edited, so what was typed for one is not kept. */
    editKey: Any,
    preview: Boolean,
    recentColours: List<Int>,
    onChange: (Stamps.StampOptions) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    var picking by remember { mutableStateOf(false) }
    // What is typed, kept apart from what is drawn. A range is briefly backwards while it is being
    // retyped - from 10 before the "to" has been raised - and sanitising that on every keystroke
    // would throw the half-finished numbers away and put the old ones back under the cursor.
    var draft by remember(kind, editKey) { mutableStateOf(options) }
    fun edit(block: (Stamps.StampOptions) -> Stamps.StampOptions) {
        draft = block(draft)
        onChange(Stamps.sanitise(kind, draft))
    }

    Surface(
        modifier.fillMaxWidth().imePadding(),
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 6.dp),
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
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 14.dp)
            ) {
                if (preview) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(PAPER)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                            .align(Alignment.CenterHorizontally)
                    ) {
                        StampPreview(kind, options, Modifier.fillMaxWidth().height(140.dp))
                    }
                }

                StampSettingsBody(kind, draft, recentColours, ::edit) { picking = true }
            }
        }
    }

    if (picking) {
        ColorPickerDialog(
            initial = options.color,
            title = kind.label + " colour",
            presets = Palette.COLORS,
            recents = recentColours,
            onDismiss = { picking = false },
            onPick = { c -> picking = false; edit { it.copy(color = c) } }
        )
    }
}

@Composable
private fun StampSettingsBody(
    kind: Stamps.Kind,
    options: Stamps.StampOptions,
    recentColours: List<Int>,
    edit: ((Stamps.StampOptions) -> Stamps.StampOptions) -> Unit,
    onCustomColour: () -> Unit
) {
    Heading("Colour")
    val swatches = remember(recentColours) { (Palette.COLORS.take(10) + recentColours).distinct().take(14) }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        swatches.forEach { c ->
            val chosen = c == options.color
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(
                        if (chosen) 3.dp else 1.dp,
                        if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        CircleShape
                    )
                    .clickable { edit { it.copy(color = c) } }
            )
        }
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onCustomColour),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.Add, "Another colour", Modifier.size(16.dp)) }
    }

    Heading("Line weight  ·  " + trimNumber(options.weight))
    Slider(
        value = options.weight.coerceIn(0.5f, 6f),
        onValueChange = { v -> edit { it.copy(weight = (v * 4f).roundToInt() / 4f) } },
        valueRange = 0.5f..6f
    )

    if (Stamps.Knob.VARIANT in kind.knobs && kind.variants.isNotEmpty()) {
        Heading("Style")
        OptionWrapRow {
            kind.variants.forEachIndexed { i, name ->
                OptionChip(name, options.variant == i) { edit { it.copy(variant = i) } }
            }
        }
    }

    if (Stamps.Knob.DASH in kind.knobs) {
        Heading("Line")
        OptionWrapRow {
            DashStyle.entries.forEach { d ->
                OptionChip(d.label, options.dash == d) { edit { it.copy(dash = d) } }
            }
        }
    }

    if (Stamps.Knob.FILL in kind.knobs) {
        Heading("Fill")
        OptionWrapRow {
            FillStyle.entries.forEach { f ->
                OptionChip(f.label, options.fill == f) { edit { it.copy(fill = f) } }
            }
        }
    }

    if (Stamps.Knob.DIVISIONS in kind.knobs) {
        Stepper(kind.divisionsLabel, options.divisions, kind.divisionsRange) { v ->
            edit { it.copy(divisions = v) }
        }
    }

    if (Stamps.Knob.FILLED in kind.knobs) {
        Stepper("Shaded", options.filled, 0..options.divisions) { v -> edit { it.copy(filled = v) } }
    }

    val graph = kind == Stamps.Kind.AXES || kind == Stamps.Kind.COORD_GRID
    if (graph) {
        Heading("Start from")
        OptionWrapRow {
            val four = options.rangeFrom < 0f && options.yFrom < 0f
            val first = options.rangeFrom == 0f && options.yFrom == 0f
            OptionChip("Four quadrants", four) {
                edit { it.copy(rangeFrom = -5f, rangeTo = 5f, step = 1f, yFrom = -5f, yTo = 5f, yStep = 1f) }
            }
            OptionChip("First quadrant", first) {
                edit { it.copy(rangeFrom = 0f, rangeTo = 10f, step = 1f, yFrom = 0f, yTo = 10f, yStep = 1f) }
            }
        }
    }

    if (Stamps.Knob.X_AXIS in kind.knobs) {
        RangeRow(
            heading = if (graph) "Horizontal axis" else "Values",
            key = kind.name + ":x",
            from = options.rangeFrom, to = options.rangeTo, step = options.step,
            onFrom = { v -> edit { it.copy(rangeFrom = v) } },
            onTo = { v -> edit { it.copy(rangeTo = v) } },
            onStep = { v -> edit { it.copy(step = v) } }
        )
    }
    if (Stamps.Knob.Y_AXIS in kind.knobs) {
        RangeRow(
            heading = "Vertical axis",
            key = kind.name + ":y",
            from = options.yFrom, to = options.yTo, step = options.yStep,
            onFrom = { v -> edit { it.copy(yFrom = v) } },
            onTo = { v -> edit { it.copy(yTo = v) } },
            onStep = { v -> edit { it.copy(yStep = v) } }
        )
    }

    if (Stamps.Knob.TICKS in kind.knobs) {
        SwitchRow("Tick marks", options.ticks) { v -> edit { it.copy(ticks = v) } }
    }
    if (Stamps.Knob.TICK_VALUES in kind.knobs) {
        SwitchRow("Numbers at the ticks", options.tickValues) { v -> edit { it.copy(tickValues = v) } }
    }

    if (Stamps.Knob.AXIS_NAMES in kind.knobs) {
        Heading("Axis names")
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
}

/**
 * From, to and step, typed.
 *
 * Kept as text while it is being typed, keyed to the stamp, so a half-typed "-" or "0." is not
 * thrown away between keystrokes; the number only reaches the stamp once it parses.
 */
@Composable
private fun RangeRow(
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
    // A preset chip changes the numbers from outside; follow it. Typing cannot trip this, because
    // the draft holds exactly what was typed.
    if (fromText.toFloatOrNull() != null && fromText.toFloatOrNull() != from) fromText = trimNumber(from)
    if (toText.toFloatOrNull() != null && toText.toFloatOrNull() != to) toText = trimNumber(to)
    if (stepText.toFloatOrNull() != null && stepText.toFloatOrNull() != step) stepText = trimNumber(step)

    fun clean(v: String) = v.filter { it.isDigit() || it == '-' || it == '.' }.take(9)
    Heading(heading)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = fromText,
            onValueChange = { v -> fromText = clean(v); fromText.toFloatOrNull()?.let(onFrom) },
            label = { Text("From") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = toText,
            onValueChange = { v -> toText = clean(v); toText.toFloatOrNull()?.let(onTo) },
            label = { Text("To") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    val safeRange = if (range.last <= range.first) range.first..(range.first + 1) else range
    Column(Modifier.padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = { onChange((value - 1).coerceAtLeast(range.first)) },
                modifier = Modifier.size(30.dp)
            ) { Icon(Icons.Default.Remove, "Fewer", Modifier.size(16.dp)) }
            Text(
                value.toString(),
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(34.dp)
            )
            IconButton(
                onClick = { onChange((value + 1).coerceAtMost(range.last)) },
                modifier = Modifier.size(30.dp)
            ) { Icon(Icons.Default.Add, "More", Modifier.size(16.dp)) }
        }
        Slider(
            value = value.coerceIn(safeRange.first, safeRange.last).toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(range.first, range.last)) },
            valueRange = safeRange.first.toFloat()..safeRange.last.toFloat(),
            steps = max(0, safeRange.last - safeRange.first - 1)
        )
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
