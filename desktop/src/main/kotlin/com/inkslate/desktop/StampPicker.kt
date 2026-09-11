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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowOutward
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.PanoramaFishEye
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.unit.dp
import com.inkslate.core.Box as InkBox
import com.inkslate.core.Stamps
import com.inkslate.core.Tool
import kotlin.math.roundToInt

/** The size a preview tile is drawn at, in the stamp's own coordinates. */
private const val TILE = 96f

/**
 * The stamps, and the two or three controls each one actually responds to.
 *
 * The Android picker: grouped rather than one undifferentiated grid, previewed as the drawing it
 * will make rather than as a name, and with only the knobs that stamp declares - a number line
 * gets a range and an interval count, a tick gets nothing. Showing every control for every stamp
 * would be a wall of settings that mostly do nothing.
 *
 * The previews are drawn with [drawStroke], the same renderer the page uses, so a stamp cannot
 * promise a shape the document will not deliver.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StampPicker(
    colour: Int,
    onDismiss: () -> Unit,
    onPickShape: (Tool) -> Unit,
    onPick: (Stamps.Kind, Stamps.StampOptions) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var chosen by remember { mutableStateOf<Stamps.Kind?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp)) {
            val kind = chosen
            if (kind == null) {
                Text(
                    "Stamps",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 20.dp, bottom = 4.dp)
                )
                Text(
                    "Pick one, then drag it out on the page.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, bottom = 8.dp)
                )
                HorizontalDivider()
                Column(Modifier.verticalScroll(rememberScrollState())) {

                    // Line, arrow, box and oval sit here rather than in the toolbar. They are the
                    // same kind of thing as a stamp - something you drag out onto the page - and
                    // four permanent slots in front of the tools used constantly was the wrong
                    // trade. They stay tools rather than becoming stamps because a shape follows
                    // the ruler and the snapping and a stamp does not.
                    Text(
                        "Shapes",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 18.dp, top = 12.dp, bottom = 6.dp)
                    )
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ShapeTile(Icons.Default.Remove, "Line") {
                            onPickShape(Tool.LINE); onDismiss()
                        }
                        ShapeTile(Icons.Default.ArrowOutward, "Arrow") {
                            onPickShape(Tool.ARROW); onDismiss()
                        }
                        ShapeTile(Icons.Default.CropSquare, "Box") {
                            onPickShape(Tool.RECT); onDismiss()
                        }
                        ShapeTile(Icons.Default.PanoramaFishEye, "Oval") {
                            onPickShape(Tool.ELLIPSE); onDismiss()
                        }
                    }

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
                                StampTile(k, k.defaults, colour) { chosen = k }
                            }
                        }
                    }
                    Box(Modifier.height(20.dp))
                }
            } else {
                StampOptionsPanel(
                    kind = kind,
                    colour = colour,
                    onBack = { chosen = null },
                    onPlace = { options -> onPick(kind, options) }
                )
            }
        }
    }
}

/**
 * A shape tool in the grid.
 *
 * Deliberately an icon rather than a rendered preview: a stamp tile shows the drawing it makes,
 * and a line drawn as a preview of "line" says nothing the word does not.
 */
@Composable
private fun ShapeTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .size(112.dp, 128.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, label, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.onSurface)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun StampTile(
    kind: Stamps.Kind,
    options: Stamps.StampOptions,
    colour: Int,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .size(112.dp, 128.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        StampPreview(
            kind, options, MaterialTheme.colorScheme.onSurface,
            Modifier.size(96.dp).background(Color.White.copy(alpha = 0.06f))
        )
        Text(
            kind.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** The stamp as it will actually be drawn, fitted into a square tile. */
@Composable
private fun StampPreview(
    kind: Stamps.Kind,
    options: Stamps.StampOptions,
    ink: Color,
    modifier: Modifier = Modifier
) {
    val argb = ink.toArgb()
    val strokes = remember(kind, options, argb) {
        var n = 0
        Stamps.build(
            kind, boxFor(kind, options), page = 0, color = argb, width = 1.2f, options = options
        ) { "preview-${n++}" }
    }
    Canvas(modifier) {
        val s = size.minDimension / TILE
        translate((size.width - TILE * s) / 2f, (size.height - TILE * s) / 2f) {
            scale(s, s, pivot = Offset.Zero) {
                strokes.forEach { if (it.kind != com.inkslate.core.Stroke.Kind.TEXT) drawStroke(it) }
            }
        }
    }
}

private fun Color.toArgb(): Int {
    fun ch(f: Float) = (f * 255f).roundToInt().coerceIn(0, 255)
    return (ch(alpha) shl 24) or (ch(red) shl 16) or (ch(green) shl 8) or ch(blue)
}

/** The tile-sized box a preview is built into, keeping the stamp's own proportions. */
private fun boxFor(kind: Stamps.Kind, options: Stamps.StampOptions): InkBox {
    val aspect = Stamps.aspectFor(kind, options)
    var w = TILE - 22f
    var h = w / aspect
    if (h > TILE - 22f) { h = TILE - 22f; w = h * aspect }
    val left = (TILE - w) / 2f
    val top = (TILE - h) / 2f
    return InkBox(left, top, left + w, top + h)
}

@Composable
private fun StampOptionsPanel(
    kind: Stamps.Kind,
    colour: Int,
    onBack: () -> Unit,
    onPlace: (Stamps.StampOptions) -> Unit
) {
    var options by remember(kind) { mutableStateOf(kind.defaults) }
    // Kept as text so a half-typed "-" or "1." is not thrown away between keystrokes.
    var fromText by remember(kind) { mutableStateOf(trimNumber(kind.defaults.rangeFrom)) }
    var toText by remember(kind) { mutableStateOf(trimNumber(kind.defaults.rangeTo)) }

    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "All stamps")
        }
        Text(
            kind.label,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { onPlace(options) }) { Text("Use it") }
    }
    HorizontalDivider()

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)
    ) {
        Box(
            Modifier
                .padding(top = 12.dp)
                .size(150.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .align(Alignment.CenterHorizontally)
        ) {
            StampPreview(
                kind, options, MaterialTheme.colorScheme.onSurface, Modifier.size(150.dp)
            )
        }

        if (Stamps.Knob.DIVISIONS in kind.knobs) {
            OptionLabel("${kind.divisionsLabel}: ${options.divisions}")
            Slider(
                value = options.divisions.toFloat(),
                onValueChange = { options = options.copy(divisions = it.roundToInt()) },
                valueRange = kind.divisionsRange.first.toFloat()..kind.divisionsRange.last.toFloat()
            )
        }

        if (Stamps.Knob.FILLED in kind.knobs) {
            OptionLabel("Shaded: ${options.filled} of ${options.divisions}")
            Slider(
                value = options.filled.toFloat(),
                onValueChange = { options = options.copy(filled = it.roundToInt()) },
                valueRange = 0f..options.divisions.toFloat()
            )
        }

        if (Stamps.Knob.RANGE in kind.knobs) {
            // Typed rather than dragged, as on the tablet: a number line from 0 to 1000, or from
            // -0.5 to 0.5, is an ordinary thing to want and no slider range covers both.
            OptionLabel("Runs from")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = fromText,
                    onValueChange = { v ->
                        fromText = v.filter { it.isDigit() || it == '-' || it == '.' }.take(9)
                        v.toFloatOrNull()?.let { f -> options = options.copy(rangeFrom = f) }
                    },
                    label = { Text("From") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = toText,
                    onValueChange = { v ->
                        toText = v.filter { it.isDigit() || it == '-' || it == '.' }.take(9)
                        v.toFloatOrNull()?.let { f -> options = options.copy(rangeTo = f) }
                    },
                    label = { Text("To") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (Stamps.Knob.VARIANT in kind.knobs && kind.variants.isNotEmpty()) {
            OptionLabel("Style")
            OptionWrapRow {
                kind.variants.forEachIndexed { i, name ->
                    OptionChip(name, options.variant == i) { options = options.copy(variant = i) }
                }
            }
        }

        if (Stamps.Knob.LABELS in kind.knobs) {
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Labels",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = options.labels,
                    onCheckedChange = { options = options.copy(labels = it) }
                )
            }
        }

        Text(
            "Drag it out on the page to set its size. It becomes ordinary strokes, so it can be " +
                "moved, resized, recoloured and partly erased like anything you drew.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 14.dp)
        )
    }
}

/** "5" rather than "5.0", so a whole number does not read as a measurement. */
private fun trimNumber(value: Float): String =
    if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()
