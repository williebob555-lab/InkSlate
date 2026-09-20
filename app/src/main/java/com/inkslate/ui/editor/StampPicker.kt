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
import androidx.compose.material.icons.filled.Tune
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
import com.inkslate.core.LineEnd
import com.inkslate.core.Palette
import com.inkslate.core.StampShelf
import com.inkslate.core.Expr
import com.inkslate.core.MarkerShape
import com.inkslate.core.PoiLabel
import com.inkslate.core.SiPrefix
import com.inkslate.core.Stamps
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import com.inkslate.core.TextFont
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
    /** Open a stamp's settings without leaving the library. */
    onSettings: (Stamps.Kind) -> Unit,
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
                                    onSettings = { onSettings(k) },
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
    onSettings: () -> Unit,
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
            // Numbers and names are noise at this size; the shape is what tells one stamp
            // from another in a grid of them.
            StampPreview(
                kind,
                options.copy(
                    tickValues = false, xName = "", yName = "",
                    markerLabels = PoiLabel.NONE, labels = false
                ),
                Modifier.fillMaxWidth().aspectRatio(1f)
            )
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
            Icon(
                Icons.Default.Tune,
                "Settings for " + kind.label,
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(if (kind.isShape) 26.dp else 26.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onSettings)
                    .padding(5.dp),
                tint = Color(0xFF6B7280)
            )
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
    // Which colour is being picked from the full picker: where it starts, and where it goes.
    var picking by remember { mutableStateOf<Pair<Int, (Int) -> Unit>?>(null) }
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
                    .heightIn(max = 460.dp)
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

                StampSettingsBody(kind, draft, recentColours, ::edit) { initial, apply -> picking = initial to apply }
            }
        }
    }

    picking?.let { (initial, apply) ->
        ColorPickerDialog(
            initial = if (initial != 0) initial else options.color,
            title = kind.label + " colour",
            presets = Palette.COLORS,
            recents = recentColours,
            onDismiss = { picking = null },
            onPick = { c -> picking = null; apply(c) }
        )
    }
}

@Composable
private fun StampSettingsBody(
    kind: Stamps.Kind,
    options: Stamps.StampOptions,
    recentColours: List<Int>,
    edit: ((Stamps.StampOptions) -> Stamps.StampOptions) -> Unit,
    onCustomColour: (Int, (Int) -> Unit) -> Unit
) {
    Heading("Colour")
    ColourRow(options.color, false, recentColours, { c -> edit { it.copy(color = c) } }) {
        onCustomColour(options.color) { c -> edit { it.copy(color = c) } }
    }

    Heading("Line weight: " + trimNumber(options.weight))
    Slider(
        value = options.weight.coerceIn(0.5f, 8f),
        onValueChange = { v -> edit { it.copy(weight = (v * 4f).roundToInt() / 4f) } },
        valueRange = 0.5f..8f
    )
    ScaleSlider("Opacity", options.opacity, 0.1f..1f) { v -> edit { it.copy(opacity = v) } }

    if (Stamps.Knob.VARIANT in kind.knobs && kind.variants.isNotEmpty()) {
        Heading("Style")
        OptionWrapRow {
            kind.variants.forEachIndexed { i, name ->
                OptionChip(name, options.variant == i) { edit { it.copy(variant = i) } }
            }
        }
    }

    if (Stamps.Knob.ENDS in kind.knobs) {
        // Each end on its own: an arrow, a double arrow and a dimension line are one line.
        Heading("Start")
        OptionWrapRow {
            LineEnd.entries.forEach { e -> OptionChip(e.label, options.startEnd == e) { edit { it.copy(startEnd = e) } } }
        }
        Heading("End")
        OptionWrapRow {
            LineEnd.entries.forEach { e -> OptionChip(e.label, options.finishEnd == e) { edit { it.copy(finishEnd = e) } } }
        }
        if (options.startEnd != LineEnd.NONE || options.finishEnd != LineEnd.NONE) {
            ScaleSlider("End size", options.endScale, 0.3f..4f) { v -> edit { it.copy(endScale = v) } }
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


    if (Stamps.Knob.EQUATION in kind.knobs) {
        Section("The equation")
        OutlinedTextField(
            value = options.expression,
            onValueChange = { v -> edit { it.copy(expression = v.take(120)) } },
            label = { Text("y =") },
            placeholder = { Text("sin(x)") },
            singleLine = true,
            isError = !Expr.parse(options.expression, options.degrees).ok,
            supportingText = {
                val parsed = Expr.parse(options.expression, options.degrees)
                Text(
                    parsed.error
                        ?: "x is the variable. 2x, x^2, sqrt(x), sin/cos/tan, ln, log, pi, e.",
                    style = MaterialTheme.typography.labelSmall
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
        OptionWrapRow {
            OptionChip("Radians", !options.degrees) { edit { it.copy(degrees = false) } }
            OptionChip("Degrees", options.degrees) { edit { it.copy(degrees = true) } }
        }
    }

    if (Stamps.Knob.WAVE in kind.knobs) {
        Section("The wave")
        Heading("A\u00b7sin(\u03c9t + \u03c6) + C, or cosine")
        OptionWrapRow {
            OptionChip("Sine", !options.cosine) { edit { it.copy(cosine = false) } }
            OptionChip("Cosine", options.cosine) { edit { it.copy(cosine = true) } }
        }
        NumberField("Amplitude A", options.amplitude) { v -> edit { it.copy(amplitude = v) } }
        Heading("Frequency, given as")
        OptionWrapRow {
            Stamps.WaveIn.entries.forEach { w ->
                OptionChip(w.label, options.waveIn == w) { edit { it.copy(waveIn = w) } }
            }
        }
        val twoPi = (2.0 * Math.PI).toFloat()
        when (options.waveIn) {
            Stamps.WaveIn.OMEGA -> NumberField("\u03c9 (rad/s)", options.omega) { v ->
                edit { it.copy(omega = v) }
            }
            Stamps.WaveIn.FREQUENCY -> NumberField("f (Hz)", Stamps.frequencyOf(options)) { v ->
                edit { it.copy(omega = v * twoPi) }
            }
            Stamps.WaveIn.PERIOD -> NumberField("T (s)", Stamps.periodOf(options)) { v ->
                if (v != 0f) edit { it.copy(omega = twoPi / v) }
            }
        }
        NumberField(
            if (options.phaseInDegrees) "Phase \u03c6 (\u00b0)" else "Phase \u03c6 (rad)",
            options.phase
        ) { v -> edit { it.copy(phase = v) } }
        OptionWrapRow {
            OptionChip("Radians", !options.phaseInDegrees) { edit { it.copy(phaseInDegrees = false) } }
            OptionChip("Degrees", options.phaseInDegrees) { edit { it.copy(phaseInDegrees = true) } }
        }
        NumberField("DC offset C", options.offset) { v -> edit { it.copy(offset = v) } }
        NumberField("Decay \u03b1 in e^(-\u03b1t)", options.damping) { v -> edit { it.copy(damping = v) } }
        if (options.damping != 0f) {
            SwitchRow("Draw the envelope", options.showEnvelope) { v -> edit { it.copy(showEnvelope = v) } }
        }
    }

    if (Stamps.Knob.THROW in kind.knobs) {
        Section("The throw")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Starts at x", options.startX, Modifier.weight(1f)) { v -> edit { it.copy(startX = v) } }
            NumberField("Height", options.startHeight, Modifier.weight(1f)) { v -> edit { it.copy(startHeight = v) } }
        }
        Heading("Velocity, given as")
        OptionWrapRow {
            Stamps.VelocityIn.entries.forEach { m ->
                OptionChip(m.label, options.velocityIn == m) { edit { it.copy(velocityIn = m) } }
            }
        }
        if (options.velocityIn == Stamps.VelocityIn.SPEED_ANGLE) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Speed (m/s)", options.speed, Modifier.weight(1f)) { v -> edit { it.copy(speed = v) } }
                NumberField("Angle (\u00b0)", options.launchAngle, Modifier.weight(1f)) { v -> edit { it.copy(launchAngle = v) } }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("vx (m/s)", options.velocityX, Modifier.weight(1f)) { v -> edit { it.copy(velocityX = v) } }
                NumberField("vy (m/s)", options.velocityY, Modifier.weight(1f)) { v -> edit { it.copy(velocityY = v) } }
            }
        }
        Heading("Acceleration")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Gravity down", options.gravity, Modifier.weight(1f)) { v -> edit { it.copy(gravity = v) } }
            NumberField("Along x", options.accelX, Modifier.weight(1f)) { v -> edit { it.copy(accelX = v) } }
        }
        NumberField("Stop after (s), 0 until it lands", options.stopAfter) { v -> edit { it.copy(stopAfter = v) } }
        Stepper("Velocity arrows", options.velocityArrows, 0..12) { v -> edit { it.copy(velocityArrows = v) } }
        if (options.velocityArrows > 0) {
            SwitchRow("Split each into components", options.componentArrows) { v ->
                edit { it.copy(componentArrows = v) }
            }
        }
    }

    if (Stamps.Knob.POI in kind.knobs) {
        Section("Points of interest")
        OptionWrapRow {
            val thrown = kind == Stamps.Kind.PROJECTILE
            OptionChip(if (thrown) "Highest point" else "Maximums", options.markMax) {
                edit { it.copy(markMax = !it.markMax) }
            }
            if (!thrown) OptionChip("Minimums", options.markMin) { edit { it.copy(markMin = !it.markMin) } }
            OptionChip(if (thrown) "Where it lands" else "Zeros", options.markZeros) {
                edit { it.copy(markZeros = !it.markZeros) }
            }
            OptionChip(if (thrown) "Where it starts" else "Crosses y", options.markIntercept) {
                edit { it.copy(markIntercept = !it.markIntercept) }
            }
        }
        Heading("Marked with")
        OptionWrapRow {
            MarkerShape.entries.forEach { m ->
                OptionChip(m.label, options.markerShape == m) { edit { it.copy(markerShape = m) } }
            }
        }
        ScaleSlider("Marker size", options.markerScale, 0.3f..3f) { v -> edit { it.copy(markerScale = v) } }
        Heading("Marker colour")
        ColourRow(options.markerColor, true, recentColours, { c -> edit { it.copy(markerColor = c) } }) {
            onCustomColour(options.markerColor) { c -> edit { it.copy(markerColor = c) } }
        }
        Heading("Each one says")
        OptionWrapRow {
            PoiLabel.entries.forEach { l ->
                OptionChip(l.label, options.markerLabels == l) { edit { it.copy(markerLabels = l) } }
            }
        }
    }

    if (Stamps.Knob.X_AXIS in kind.knobs || Stamps.Knob.Y_AXIS in kind.knobs) {
        Section("Units")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = options.xUnit,
                onValueChange = { v -> edit { it.copy(xUnit = v.take(8)) } },
                label = { Text("Across") },
                placeholder = { Text("s, m, Hz") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = options.yUnit,
                onValueChange = { v -> edit { it.copy(yUnit = v.take(8)) } },
                label = { Text("Up") },
                placeholder = { Text("V, m") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        Heading("Numbers written in")
        OptionWrapRow {
            SiPrefix.entries.forEach { pfx ->
                OptionChip("x: " + pfx.label, options.xPrefix == pfx) { edit { it.copy(xPrefix = pfx) } }
            }
        }
        OptionWrapRow {
            SiPrefix.entries.forEach { pfx ->
                OptionChip("y: " + pfx.label, options.yPrefix == pfx) { edit { it.copy(yPrefix = pfx) } }
            }
        }
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
        Heading("Colour")
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
        Heading("Colour")
        ColourRow(options.gridColor, true, recentColours, { c -> edit { it.copy(gridColor = c) } }) {
            onCustomColour(options.gridColor) { c -> edit { it.copy(gridColor = c) } }
        }
        ScaleSlider("Weight", options.gridWeight, 0.25f..4f) { v -> edit { it.copy(gridWeight = v) } }
        Heading("Line")
        OptionWrapRow {
            DashStyle.entries.forEach { d -> OptionChip(d.label, options.gridDash == d) { edit { it.copy(gridDash = d) } } }
        }
    }

    if (Stamps.Feature.FILL in parts || (Stamps.Knob.FILL in kind.knobs && options.fill != FillStyle.NONE)) {
        Section("Fill")
        Heading("Colour")
        ColourRow(options.fillColor, true, recentColours, { c -> edit { it.copy(fillColor = c) } }) {
            onCustomColour(options.fillColor) { c -> edit { it.copy(fillColor = c) } }
        }
    }

    if (Stamps.Feature.TEXT in parts) {
        Section("Text")
        Heading("Colour")
        ColourRow(options.textColor, true, recentColours, { c -> edit { it.copy(textColor = c) } }) {
            onCustomColour(options.textColor) { c -> edit { it.copy(textColor = c) } }
        }
        ScaleSlider("Size", options.textScale, 0.4f..3f) { v -> edit { it.copy(textScale = v) } }
        Heading("Font")
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

    if (Stamps.Feature.CURVE in parts) {
        Section("The curve")
        Heading("Colour")
        ColourRow(options.curveColor, true, recentColours, { c -> edit { it.copy(curveColor = c) } }) {
            onCustomColour(options.curveColor) { c -> edit { it.copy(curveColor = c) } }
        }
        ScaleSlider("Weight", options.curveWeight, 0.3f..5f) { v -> edit { it.copy(curveWeight = v) } }
        Heading("Line")
        OptionWrapRow {
            DashStyle.entries.forEach { d ->
                OptionChip(d.label, options.curveDash == d) { edit { it.copy(curveDash = d) } }
            }
        }
        Heading("Smoothness: " + options.smoothness + " points")
        Slider(
            value = options.smoothness.toFloat(),
            onValueChange = { v -> edit { it.copy(smoothness = v.roundToInt()) } },
            valueRange = 40f..800f
        )
        SwitchRow("Draw the axes", options.showAxes) { v -> edit { it.copy(showAxes = v) } }
        SwitchRow("Fit the axes to it", options.fitAxes) { v -> edit { it.copy(fitAxes = v) } }
    }

    if (Stamps.Feature.VECTORS in parts) {
        Section("Velocity arrows")
        Heading("Colour")
        ColourRow(options.vectorColor, true, recentColours, { c -> edit { it.copy(vectorColor = c) } }) {
            onCustomColour(options.vectorColor) { c -> edit { it.copy(vectorColor = c) } }
        }
        ScaleSlider("Length", options.vectorScale, 0.2f..3f) { v -> edit { it.copy(vectorScale = v) } }
    }

    // What the graph works out, in the units it is drawn in. A graph that can say where its
    // maximum is saves reading it off the picture, which is where the marks get lost.
    val readout = remember(kind, options) { Stamps.inspect(kind, Stamps.sanitise(kind, options)) }
    if (readout.isNotEmpty()) {
        Section("What it works out")
        readout.forEach { (name, value) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                Text(
                    name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(value, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
/**
 * A number, typed.
 *
 * Kept as text while it is being typed so a half-written "-" or "0." is not thrown away between
 * keystrokes; the value only reaches the stamp once it parses as a number.
 */
@Composable
private fun NumberField(
    label: String,
    value: Float,
    modifier: Modifier = Modifier,
    onChange: (Float) -> Unit
) {
    var text by remember(label) { mutableStateOf(trimNumber(value)) }
    if (text.toFloatOrNull() != null && text.toFloatOrNull() != value) text = trimNumber(value)
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v.filter { it.isDigit() || it == '.' || it == '-' }.take(12)
            text.toFloatOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier.padding(top = 6.dp)
    )
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
    Heading(label + ": " + (value * 100f).roundToInt() + "%")
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
                    .size(30.dp)
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
                .size(30.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onCustom),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.Add, "Another colour", Modifier.size(15.dp)) }
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
