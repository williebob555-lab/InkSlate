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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowOutward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.PanoramaFishEye
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
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
import com.inkslate.ink.*
import com.inkslate.core.Tool
import com.inkslate.ink.Stamps
import com.inkslate.ink.StrokeRasteriser
import kotlin.math.roundToInt

/**
 * Pick a ready-made drawing, and say what it should be.
 *
 * Two steps rather than one grid of finished pictures. Browsing is a grid, because a set of labels
 * says very little about what "Brace" looks like and every tile draws the real stamp through the
 * real renderer. Choosing one opens its own controls - how many slices, which quadrant, what the
 * axis runs from - because the stamps that are worth having are the ones that fit the question in
 * front of you, and a fraction circle fixed at eighths fits one question in eight.
 */
@Composable
fun StampPickerDialog(
    inkColor: Int,
    recents: List<Stamps.Kind>,
    optionsFor: (Stamps.Kind) -> Stamps.StampOptions,
    onDismiss: () -> Unit,
    onPick: (Stamps.Kind, Stamps.StampOptions) -> Unit,
    onPickShape: (Tool) -> Unit
) {
    var chosen by remember { mutableStateOf<Stamps.Kind?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            Modifier.fillMaxWidth(0.96f).heightIn(max = 620.dp),
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 4.dp
        ) {
            val kind = chosen
            if (kind == null) {
                StampBrowser(recents, optionsFor, onDismiss, onPickShape) { chosen = it }
            } else {
                StampConfigurer(
                    kind = kind,
                    inkColor = inkColor,
                    initial = optionsFor(kind),
                    onBack = { chosen = null },
                    onDismiss = onDismiss,
                    onPlace = { opts -> onPick(kind, opts) }
                )
            }
        }
    }
}

// ---- browsing ----------------------------------------------------------------

@Composable
private fun StampBrowser(
    recents: List<Stamps.Kind>,
    optionsFor: (Stamps.Kind) -> Stamps.StampOptions,
    onDismiss: () -> Unit,
    onPickShape: (Tool) -> Unit,
    onChoose: (Stamps.Kind) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Stamps", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Placed as ordinary strokes, so you can move, resize, recolour or partly " +
                        "erase them like anything you drew.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
        }

        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                .padding(bottom = 14.dp)
        ) {
            // Line, arrow, box and oval used to sit in the dock beside the pen, which put four
            // permanent buttons in the way of the tools actually used every minute. They are the
            // same kind of thing as a stamp - a shape you drag out onto the page - so they live
            // here now. They stay drawing tools rather than becoming stamps, because a dragged
            // shape follows the ruler and the snapping and a stamp does not.
            SectionLabel("Shapes")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                ShapeTile(Icons.Default.Remove, "Line") { onPickShape(Tool.LINE); onDismiss() }
                ShapeTile(Icons.Default.ArrowOutward, "Arrow") { onPickShape(Tool.ARROW); onDismiss() }
                ShapeTile(Icons.Default.CropSquare, "Box") { onPickShape(Tool.RECT); onDismiss() }
                ShapeTile(Icons.Default.PanoramaFishEye, "Oval") { onPickShape(Tool.ELLIPSE); onDismiss() }
            }

            if (recents.isNotEmpty()) {
                SectionLabel("Recent")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Drawn with the settings they were last used with, because that is what
                    // "recent" means here: the number line you have been using, not a generic one.
                    recents.forEach { k -> StampTile(k, optionsFor(k)) { onChoose(k) } }
                }
            }
            Stamps.Group.entries.forEach { group ->
                val kinds = Stamps.Kind.entries.filter { it.group == group }
                if (kinds.isEmpty()) return@forEach
                SectionLabel(group.label)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    kinds.forEach { k -> StampTile(k, k.defaults) { onChoose(k) } }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp)
    )
}

// ---- configuring -------------------------------------------------------------

@Composable
private fun StampConfigurer(
    kind: Stamps.Kind,
    inkColor: Int,
    initial: Stamps.StampOptions,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    onPlace: (Stamps.StampOptions) -> Unit
) {
    var opts by remember(kind) { mutableStateOf(Stamps.sanitise(kind, initial)) }
    // Kept as text rather than as numbers: an axis that will not let you delete the minus sign
    // on the way to typing a different one is not editable.
    var fromText by remember(kind) { mutableStateOf(trimNumber(opts.rangeFrom)) }
    var toText by remember(kind) { mutableStateOf(trimNumber(opts.rangeTo)) }

    fun edit(block: (Stamps.StampOptions) -> Stamps.StampOptions) {
        opts = Stamps.sanitise(kind, block(opts))
    }

    Column(Modifier.fillMaxWidth().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to all stamps")
            }
            Column(Modifier.weight(1f)) {
                Text(kind.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    kind.group.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
        }

        // The preview sits on white, because that is what it will sit on: an ink colour that
        // disappears against the dialog's own surface would be a preview that lies.
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(150.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFFDFDFB))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            StampPreview(kind, opts, inkColor, Modifier.fillMaxWidth().height(130.dp))
        }

        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
        ) {
            if (Stamps.Knob.VARIANT in kind.knobs && kind.variants.isNotEmpty()) {
                OptionHeading("Style")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    kind.variants.forEachIndexed { i, name ->
                        Chip(name, opts.variant == i) { edit { it.copy(variant = i) } }
                    }
                }
            }

            if (Stamps.Knob.DIVISIONS in kind.knobs) {
                Stepper(
                    label = kind.divisionsLabel,
                    value = opts.divisions,
                    range = kind.divisionsRange
                ) { v -> edit { it.copy(divisions = v) } }
            }

            if (Stamps.Knob.FILLED in kind.knobs) {
                Stepper(
                    label = "Shaded",
                    value = opts.filled,
                    range = 0..opts.divisions
                ) { v -> edit { it.copy(filled = v) } }
            }

            if (Stamps.Knob.RANGE in kind.knobs) {
                OptionHeading("Runs from")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = fromText,
                        onValueChange = { v ->
                            fromText = v.filter { it.isDigit() || it == '-' || it == '.' }.take(9)
                            v.toFloatOrNull()?.let { f -> edit { o -> o.copy(rangeFrom = f) } }
                        },
                        label = { Text("From") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = toText,
                        onValueChange = { v ->
                            toText = v.filter { it.isDigit() || it == '-' || it == '.' }.take(9)
                            v.toFloatOrNull()?.let { f -> edit { o -> o.copy(rangeTo = f) } }
                        },
                        label = { Text("To") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (Stamps.Knob.LABELS in kind.knobs) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Labels", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Numbers and letters drawn as text objects, so you can edit or " +
                                "delete them afterwards.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = opts.labels,
                        onCheckedChange = { v -> edit { it.copy(labels = v) } }
                    )
                }
            }

            if (kind.knobs.isEmpty()) {
                Text(
                    "This one has nothing to set. Its colour and line weight come from the pen " +
                        "you have in hand.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Then tap the page, or drag out the space it should fill.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Spacer(Modifier.width(6.dp))
            Button(onClick = { onPlace(opts) }) { Text("Use it") }
        }
    }
}

@Composable
private fun OptionHeading(text: String) {
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
        // The slider is for getting near, the arrows are for landing exactly. Both, because
        // "twenty parts" and "one more than this" are different intentions.
        Slider(
            value = value.coerceIn(safeRange.first, safeRange.last).toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(range.first, range.last)) },
            valueRange = safeRange.first.toFloat()..safeRange.last.toFloat(),
            steps = (safeRange.last - safeRange.first - 1).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth().height(26.dp)
        )
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg =
        if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant
    val fg =
        if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp)
    )
}

// ---- previews ----------------------------------------------------------------

private const val TILE = 120f

/**
 * Draw a stamp at whatever size it is given, through the renderer that will actually place it.
 *
 * Nothing here approximates the result: the preview and the placed object come out of the same
 * builder, so they cannot drift apart.
 */
@Composable
private fun StampPreview(
    kind: Stamps.Kind,
    opts: Stamps.StampOptions,
    argb: Int,
    modifier: Modifier = Modifier
) {
    val strokes = remember(kind, opts, argb) {
        var n = 0
        Stamps.build(
            kind, boxFor(kind, opts), page = 0, color = argb, width = 1.4f, options = opts
        ) { "preview-" + (n++) }
    }
    Canvas(modifier) {
        drawIntoCanvas { c ->
            val native = c.nativeCanvas
            val save = native.save()
            native.scale(size.width / TILE, size.height / TILE)
            val previous = StrokeRasteriser.colorFilter
            StrokeRasteriser.colorFilter = null
            strokes.forEach { StrokeRasteriser.draw(native, it) }
            StrokeRasteriser.colorFilter = previous
            native.restoreToCount(save)
        }
    }
}

/** The stamp's own proportions, centred in the square the preview draws into. */
private fun boxFor(kind: Stamps.Kind, opts: Stamps.StampOptions): android.graphics.RectF {
    val aspect = Stamps.aspectFor(kind, opts)
    var w = TILE - 22f
    var h = w / aspect
    if (h > TILE - 22f) { h = TILE - 22f; w = h * aspect }
    val left = (TILE - w) / 2f
    val top = (TILE - h) / 2f
    return android.graphics.RectF(left, top, left + w, top + h)
}

@Composable
private fun StampTile(kind: Stamps.Kind, opts: Stamps.StampOptions, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    val argb = android.graphics.Color.argb(
        255,
        (ink.red * 255).toInt(),
        (ink.green * 255).toInt(),
        (ink.blue * 255).toInt()
    )
    Column(
        modifier = Modifier
            .width(104.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        StampPreview(kind, opts, argb, Modifier.fillMaxWidth().aspectRatio(1f))
        Text(
            kind.label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}

/**
 * A shape tool in the stamp grid. Deliberately an icon rather than a rendered preview: a stamp
 * tile shows what you will get because a stamp has a fixed drawing, whereas a box is whatever
 * box you drag.
 */
@Composable
private fun ShapeTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(104.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon, null,
                Modifier.size(38.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}

/** "5", "2.5" - never "5.0", which reads as a precision the value does not have. */
private fun trimNumber(v: Float): String {
    val rounded = (v * 100f).roundToInt() / 100f
    return if (kotlin.math.abs(rounded - rounded.roundToInt()) < 0.005f) {
        rounded.roundToInt().toString()
    } else {
        ("%.2f".format(rounded)).trimEnd('0').trimEnd('.')
    }
}
