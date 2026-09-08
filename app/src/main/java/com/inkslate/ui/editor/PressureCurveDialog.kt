package com.inkslate.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Tunes how hard you have to press.
 *
 * A single exponent covers what people actually need: below 1 makes light pressure produce more
 * width, which suits a light hand; above 1 requires a firmer press. Showing the curve alongside a
 * live stroke preview matters more than the number, because nobody thinks about their handwriting
 * in terms of gamma.
 */
@Composable
fun PressureCurveDialog(
    initialGamma: Float,
    initialMin: Float,
    initialDynamics: Float,
    onDismiss: () -> Unit,
    onApply: (gamma: Float, minWidth: Float, dynamics: Float) -> Unit,
    onReset: () -> Unit
) {
    var gamma by remember { mutableStateOf(initialGamma) }
    var minWidth by remember { mutableStateOf(initialMin) }
    var dynamics by remember { mutableStateOf(initialDynamics) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pressure response") },
        text = {
            Column {
                Text(
                    "The curve maps how hard you press, left to right, onto how wide the line " +
                        "gets. Drag the sliders and watch the shape.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                val ink = MaterialTheme.colorScheme.primary
                val surface = MaterialTheme.colorScheme.surfaceVariant

                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.6f)
                        .padding(vertical = 12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(surface)
                ) {
                    // grid
                    for (i in 1..3) {
                        val x = size.width * i / 4f
                        val y = size.height * i / 4f
                        drawLine(grid, Offset(x, 0f), Offset(x, size.height), 1f)
                        drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f)
                    }
                    // the response curve
                    val path = Path()
                    val steps = 48
                    // the top of the curve is where the expressiveness setting lands, so the
                    // preview grows and shrinks with it rather than always filling the box
                    val top = (1f + (1f - minWidth) * (dynamics - 1f) * 0.5f).coerceIn(0.15f, 1f)
                    for (i in 0..steps) {
                        val t = i / steps.toFloat()
                        val shaped = Math.pow(t.toDouble(), gamma.toDouble()).toFloat()
                        val w = (minWidth + (top - minWidth) * shaped).coerceIn(0f, 1f)
                        val x = t * size.width
                        val y = size.height * (1f - w)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, ink, style = DrawStroke(width = 3f))

                    // a stroke drawn with that response, as a tapered ribbon
                    val baseY = size.height * 0.86f
                    for (i in 0 until steps) {
                        val t = i / steps.toFloat()
                        val shaped = Math.pow(t.toDouble(), gamma.toDouble()).toFloat()
                        val w = (minWidth + (top - minWidth) * shaped)
                            .coerceIn(0f, 1f) * size.height * 0.10f
                        val x = t * size.width
                        drawLine(
                            Color.Black.copy(alpha = 0.75f),
                            Offset(x, baseY - w / 2f),
                            Offset(x, baseY + w / 2f),
                            size.width / steps + 1f
                        )
                    }
                }

                // The ends of these ranges are deliberately past the point of good taste. A
                // slider whose extremes are both usable has no extremes, and the whole reason to
                // expose the curve is to let someone push it until it does what they want.
                Label("Sensitivity: " + describeGamma(gamma))
                Slider(value = gamma, onValueChange = { gamma = it }, valueRange = 0.12f..6f)

                Label("Lightest stroke: " + (minWidth * 100).toInt() + "% of full width")
                Slider(value = minWidth, onValueChange = { minWidth = it }, valueRange = 0f..1f)

                Label("Expressiveness: " + describeDynamics(dynamics))
                Slider(
                    value = dynamics,
                    onValueChange = { dynamics = it },
                    valueRange = 0f..3f
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        gamma = 0.7f; minWidth = 0.3f; dynamics = 1f
                    }) { Text("Light hand") }
                    TextButton(onClick = {
                        gamma = 1f; minWidth = 0.35f; dynamics = 1f
                    }) { Text("Neutral") }
                    TextButton(onClick = {
                        gamma = 0.55f; minWidth = 0f; dynamics = 2.4f
                    }) { Text("Wild") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(gamma, minWidth, dynamics) }) { Text("Apply") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

private fun describeGamma(g: Float): String = when {
    g < 0.3f -> "Feather touch"
    g < 0.6f -> "Very light touch"
    g < 0.9f -> "Light touch"
    g < 1.2f -> "Neutral"
    g < 1.8f -> "Firm"
    g < 3f -> "Very firm"
    else -> "Lean on it"
}

private fun describeDynamics(d: Float): String = when {
    d < 0.15f -> "None - one flat width"
    d < 0.7f -> "Subtle"
    d < 1.3f -> "As the brush was designed"
    d < 2f -> "Strong"
    d < 2.6f -> "Dramatic"
    else -> "Absurd"
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
}
