package com.inkslate.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A full colour picker.
 *
 * The Android dialog, laid out the same way. Fixed palettes are fine for the common cases but
 * arbitrary as a limit - there is no reason the paper has to be one of six shades. This gives the
 * whole space, while keeping presets and recently used colours one click away for when speed
 * matters more than precision.
 *
 * The HSV arithmetic is written out below rather than taken from a library: `android.graphics
 * .Color` is what the tablet uses and there is no counterpart here, and the conversion is a dozen
 * lines that must agree with it exactly or the same hex would name two different colours.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPickerDialog(
    initial: Int,
    title: String = "Choose a colour",
    presets: List<Int> = emptyList(),
    recents: List<Int> = emptyList(),
    allowAlpha: Boolean = false,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit
) {
    val startHsv = remember(initial) { colorToHsv(initial) }
    var hue by remember { mutableStateOf(startHsv[0]) }
    var sat by remember { mutableStateOf(startHsv[1]) }
    var value by remember { mutableStateOf(startHsv[2]) }
    var alpha by remember { mutableStateOf(((initial ushr 24) and 0xFF) / 255f) }
    var hexText by remember { mutableStateOf(hexOf(initial, allowAlpha)) }

    val current = hsvToColor(hue, sat, value, (alpha * 255).roundToInt().coerceIn(0, 255))

    // keep the hex field in step unless the user is mid-edit
    fun syncHex() { hexText = hexOf(current, allowAlpha) }

    fun adopt(picked: Int) {
        val hsv = colorToHsv(picked)
        hue = hsv[0]; sat = hsv[1]; value = hsv[2]
        alpha = ((picked ushr 24) and 0xFF) / 255f
        syncHex()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {

                // ---- saturation / brightness field ----
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .pointerInput(hue) {
                            detectTapGestures(onTap = { o ->
                                sat = (o.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (o.y / size.height).coerceIn(0f, 1f)
                                syncHex()
                            })
                        }
                        .pointerInput(hue) {
                            detectDragGestures(onDragEnd = { syncHex() }) { change, _ ->
                                sat = (change.position.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                            }
                        }
                ) {
                    val pure = Color(hsvToColor(hue, 1f, 1f, 255))
                    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
                        drawRect(Brush.horizontalGradient(listOf(Color.White, pure)))
                        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                        val cx = sat * size.width
                        val cy = (1f - value) * size.height
                        drawCircle(Color.White, 11f, Offset(cx, cy), style = DrawStroke(3f))
                        drawCircle(Color.Black, 14f, Offset(cx, cy), style = DrawStroke(1.5f))
                    }
                }

                // ---- hue ----
                PickerLabel("Hue")
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .clip(RoundedCornerShape(17.dp))
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = {
                                hue = (it.x / size.width * 360f).coerceIn(0f, 360f); syncHex()
                            })
                        }
                        .pointerInput(Unit) {
                            detectDragGestures(onDragEnd = { syncHex() }) { change, _ ->
                                hue = (change.position.x / size.width * 360f).coerceIn(0f, 360f)
                            }
                        }
                ) {
                    Canvas(Modifier.fillMaxWidth().height(34.dp)) {
                        drawRect(
                            Brush.horizontalGradient(
                                (0..6).map { Color(hsvToColor(it * 60f, 1f, 1f, 255)) }
                            )
                        )
                        val x = hue / 360f * size.width
                        drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 4f)
                        drawLine(Color.Black, Offset(x, 0f), Offset(x, size.height), 1.5f)
                    }
                }

                if (allowAlpha) {
                    PickerLabel("Opacity: " + (alpha * 100).roundToInt() + "%")
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = {
                                    alpha = (it.x / size.width).coerceIn(0f, 1f); syncHex()
                                })
                            }
                            .pointerInput(Unit) {
                                detectDragGestures(onDragEnd = { syncHex() }) { change, _ ->
                                    alpha = (change.position.x / size.width).coerceIn(0f, 1f)
                                }
                            }
                    ) {
                        val opaque = Color(hsvToColor(hue, sat, value, 255))
                        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
                            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, opaque)))
                            val x = alpha * size.width
                            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 4f)
                            drawLine(Color.Black, Offset(x, 0f), Offset(x, size.height), 1.5f)
                        }
                    }
                }

                // ---- preview and hex ----
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(current))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(8.dp)
                            )
                    )
                    OutlinedTextField(
                        value = hexText,
                        onValueChange = { text ->
                            hexText = text.uppercase().filter { it.isDigit() || it in "ABCDEF#" }
                            parseHex(hexText)?.let { parsed ->
                                val hsv = colorToHsv(parsed)
                                hue = hsv[0]; sat = hsv[1]; value = hsv[2]
                                if (allowAlpha) alpha = ((parsed ushr 24) and 0xFF) / 255f
                            }
                        },
                        label = { Text("Hex") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (recents.isNotEmpty()) {
                    PickerLabel("Recent")
                    Swatches(recents) { adopt(it) }
                }
                if (presets.isNotEmpty()) {
                    PickerLabel("Presets")
                    Swatches(presets) { adopt(it) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(current) }) { Text("Use this colour") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Swatches(colors: List<Int>, onPick: (Int) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        colors.distinct().forEach { c ->
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { onPick(c) }
            )
        }
    }
}

@Composable
private fun PickerLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

// ---- colour arithmetic -------------------------------------------------------

/** Hue in degrees, saturation and value 0..1 - the same triple `Color.colorToHSV` produces. */
internal fun colorToHsv(color: Int): FloatArray {
    val r = ((color shr 16) and 0xFF) / 255f
    val g = ((color shr 8) and 0xFF) / 255f
    val b = (color and 0xFF) / 255f
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val d = mx - mn
    val h = when {
        d < 1e-6f -> 0f
        mx == r -> 60f * (((g - b) / d) % 6f)
        mx == g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }
    return floatArrayOf(if (h < 0f) h + 360f else h, if (mx <= 0f) 0f else d / mx, mx)
}

internal fun hsvToColor(hue: Float, sat: Float, value: Float, alpha: Int = 255): Int {
    val h = ((hue % 360f) + 360f) % 360f
    val s = sat.coerceIn(0f, 1f)
    val v = value.coerceIn(0f, 1f)
    val c = v * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = v - c
    val (r, g, b) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    fun ch(f: Float) = ((f + m) * 255f).roundToInt().coerceIn(0, 255)
    return (alpha.coerceIn(0, 255) shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
}

private fun hexOf(color: Int, withAlpha: Boolean): String =
    if (withAlpha) String.format("#%08X", color) else String.format("#%06X", color and 0xFFFFFF)

private fun parseHex(text: String): Int? {
    val cleaned = text.removePrefix("#")
    if (cleaned.length != 6 && cleaned.length != 8) return null
    return runCatching {
        val v = cleaned.toLong(16)
        if (cleaned.length == 6) (0xFF000000L or v).toInt() else v.toInt()
    }.getOrNull()
}
