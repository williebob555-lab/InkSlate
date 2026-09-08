package com.inkslate.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A full colour picker.
 *
 * Fixed palettes are fine for the common cases but arbitrary as a limit - there is no reason the
 * paper has to be one of six shades. This gives the whole space, while keeping presets and
 * recently used colours one tap away for the cases where speed matters more than precision.
 */
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
    val startHsv = remember(initial) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) }
    }
    var hue by remember { mutableStateOf(startHsv[0]) }
    var sat by remember { mutableStateOf(startHsv[1]) }
    var value by remember { mutableStateOf(startHsv[2]) }
    var alpha by remember { mutableStateOf(android.graphics.Color.alpha(initial) / 255f) }
    var hexText by remember { mutableStateOf(hexOf(initial, allowAlpha)) }

    val current = android.graphics.Color.HSVToColor(
        (alpha * 255).roundToInt().coerceIn(0, 255),
        floatArrayOf(hue, sat, value)
    )

    // keep the hex field in step unless the user is mid-edit
    fun syncHex() { hexText = hexOf(current, allowAlpha) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).imePadding().verticalScroll(rememberScrollState())) {

                // ---- saturation / brightness field ----
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .pointerInput(hue) {
                            fun update(o: Offset) {
                                sat = (o.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (o.y / size.height).coerceIn(0f, 1f)
                            }
                            detectTapGestures(onTap = { update(it); syncHex() })
                        }
                        .pointerInput(hue) {
                            detectDragGestures(
                                onDragEnd = { syncHex() }
                            ) { change, _ ->
                                sat = (change.position.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                            }
                        }
                ) {
                    val pure = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
                    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
                        drawRect(
                            Brush.horizontalGradient(listOf(Color.White, pure))
                        )
                        drawRect(
                            Brush.verticalGradient(listOf(Color.Transparent, Color.Black))
                        )
                        // crosshair
                        val cx = sat * size.width
                        val cy = (1f - value) * size.height
                        drawCircle(Color.White, radius = 11f, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
                        drawCircle(Color.Black, radius = 14f, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
                    }
                }

                // ---- hue ----
                Label("Hue")
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
                                (0..6).map {
                                    Color(
                                        android.graphics.Color.HSVToColor(
                                            floatArrayOf(it * 60f, 1f, 1f)
                                        )
                                    )
                                }
                            )
                        )
                        val x = hue / 360f * size.width
                        drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 4f)
                        drawLine(Color.Black, Offset(x, 0f), Offset(x, size.height), 1.5f)
                    }
                }

                if (allowAlpha) {
                    Label("Opacity: " + (alpha * 100).roundToInt() + "%")
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
                        val opaque = Color(
                            android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
                        )
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
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    )
                    OutlinedTextField(
                        value = hexText,
                        onValueChange = { text ->
                            hexText = text.uppercase().filter { it.isDigit() || it in "ABCDEF#" }
                            parseHex(hexText)?.let { parsed ->
                                val hsv = FloatArray(3)
                                android.graphics.Color.colorToHSV(parsed, hsv)
                                hue = hsv[0]; sat = hsv[1]; value = hsv[2]
                                if (allowAlpha) {
                                    alpha = android.graphics.Color.alpha(parsed) / 255f
                                }
                            }
                        },
                        label = { Text("Hex") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (recents.isNotEmpty()) {
                    Label("Recent")
                    Swatches(recents) { picked ->
                        val hsv = FloatArray(3)
                        android.graphics.Color.colorToHSV(picked, hsv)
                        hue = hsv[0]; sat = hsv[1]; value = hsv[2]
                        alpha = android.graphics.Color.alpha(picked) / 255f
                        syncHex()
                    }
                }

                if (presets.isNotEmpty()) {
                    Label("Presets")
                    Swatches(presets) { picked ->
                        val hsv = FloatArray(3)
                        android.graphics.Color.colorToHSV(picked, hsv)
                        hue = hsv[0]; sat = hsv[1]; value = hsv[2]
                        alpha = android.graphics.Color.alpha(picked) / 255f
                        syncHex()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(current) }) { Text("Use this colour") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

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
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
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
