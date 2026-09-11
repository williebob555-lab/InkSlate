package com.inkslate.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.inkslate.core.Stroke
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Trim a picture down to the part that matters.
 *
 * The crop is stored on the object rather than applied to the file. The picture is shared between
 * devices and referenced by id, so trimming the bytes would change what every other copy of it
 * shows - and would throw the discarded edges away for good. Stored, the crop can be widened
 * again later or undone entirely.
 */
@Composable
fun CropDialog(
    stroke: Stroke,
    picture: ImageBitmap?,
    onDismiss: () -> Unit,
    onApply: (Stroke) -> Unit
) {
    // Held as fractions of the whole picture, which is exactly how the model stores it.
    var left by remember(stroke.id) { mutableStateOf(stroke.cropLeft) }
    var top by remember(stroke.id) { mutableStateOf(stroke.cropTop) }
    var right by remember(stroke.id) { mutableStateOf(stroke.cropRight) }
    var bottom by remember(stroke.id) { mutableStateOf(stroke.cropBottom) }

    /** Which corner a drag has hold of, decided when it starts and kept for its duration. */
    var grabbed by remember { mutableStateOf(-1) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crop") },
        text = {
            Column {
                Text(
                    "Drag the corners. The picture itself is kept whole, so this can be widened " +
                        "again later or undone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp)
                )

                val aspect = if (picture == null || picture.height == 0) 1.4f
                else picture.width.toFloat() / picture.height

                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspect)
                        .pointerInput(stroke.id) {
                            detectDragGestures(
                                onDragStart = { at ->
                                    val fx = at.x / size.width
                                    val fy = at.y / size.height
                                    // The nearest corner, so a drag never has to start exactly on
                                    // a handle that is only a few pixels across.
                                    val corners = listOf(
                                        left to top, right to top, right to bottom, left to bottom
                                    )
                                    grabbed = corners.indices.minByOrNull { i ->
                                        val (cx, cy) = corners[i]
                                        abs(cx - fx) + abs(cy - fy)
                                    } ?: -1
                                },
                                onDragEnd = { grabbed = -1 }
                            ) { change, _ ->
                                val fx = (change.position.x / size.width).coerceIn(0f, 1f)
                                val fy = (change.position.y / size.height).coerceIn(0f, 1f)
                                // A crop cannot be inverted or vanish: each corner is kept a
                                // little clear of the one opposite it.
                                val gap = 0.05f
                                when (grabbed) {
                                    0 -> { left = min(fx, right - gap); top = min(fy, bottom - gap) }
                                    1 -> { right = max(fx, left + gap); top = min(fy, bottom - gap) }
                                    2 -> { right = max(fx, left + gap); bottom = max(fy, top + gap) }
                                    3 -> { left = min(fx, right - gap); bottom = max(fy, top + gap) }
                                }
                            }
                        }
                ) {
                    Canvas(Modifier.fillMaxWidth().aspectRatio(aspect)) {
                        picture?.let {
                            drawImage(
                                it,
                                dstOffset = IntOffset.Zero,
                                dstSize = IntSize(size.width.toInt(), size.height.toInt())
                            )
                        }
                        val l = left * size.width
                        val t = top * size.height
                        val r = right * size.width
                        val b = bottom * size.height

                        // Everything outside the crop dimmed, so what will survive is obvious.
                        val shade = Color(0x99000000)
                        drawRect(shade, Offset.Zero, Size(size.width, t))
                        drawRect(shade, Offset(0f, b), Size(size.width, size.height - b))
                        drawRect(shade, Offset(0f, t), Size(l, b - t))
                        drawRect(shade, Offset(r, t), Size(size.width - r, b - t))

                        val accent = Color(0xFF3B82F6)
                        drawRect(
                            accent,
                            topLeft = Offset(l, t),
                            size = Size(r - l, b - t),
                            style = DrawStroke(width = 2f)
                        )
                        for (corner in listOf(l to t, r to t, r to b, l to b)) {
                            drawCircle(Color.White, 7f, Offset(corner.first, corner.second))
                            drawCircle(
                                accent, 7f, Offset(corner.first, corner.second),
                                style = DrawStroke(2f)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onApply(
                    stroke.copy(
                        cropLeft = left,
                        cropTop = top,
                        cropRight = right,
                        cropBottom = bottom,
                        updatedUtc = System.currentTimeMillis()
                    )
                )
            }) { Text("Crop") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    left = 0f; top = 0f; right = 1f; bottom = 1f
                }) { Text("Whole picture") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
