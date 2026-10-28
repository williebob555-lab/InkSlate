package com.inkslate.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.inkslate.core.PageCrop
import kotlin.math.abs

/** Edits only a viewport; the thumbnail and every annotation keep their original coordinates. */
@Composable
internal fun PageCropDialog(
    thumbnail: ImageBitmap?, initial: PageCrop, count: Int,
    onApply: (PageCrop?) -> Unit, onDismiss: () -> Unit
) {
    var crop by remember { mutableStateOf(initial.takeIf { it.valid } ?: PageCrop()) }
    var edge by remember { mutableStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crop "+if (count == 1) "page" else "$count pages") },
        text = {
            Column {
                Text("Drag an edge to hide margins. Ink and the original page stay intact. The same crop applies to every picked page.")
                Box(Modifier.fillMaxWidth().height(280.dp)) {
                    val aspect = thumbnail?.let { it.width.toFloat() / it.height } ?: 0.75f
                    Box(Modifier.fillMaxHeight().aspectRatio(aspect).align(androidx.compose.ui.Alignment.Center)) {
                        if (thumbnail != null) Image(thumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { point ->
                                    val x = point.x / size.width; val y = point.y / size.height
                                    edge = listOf(abs(x - crop.left), abs(y - crop.top), abs(x - crop.right), abs(y - crop.bottom)).withIndex().minBy { it.value }.index
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val x = (change.position.x / size.width).coerceIn(0f, 1f)
                                    val y = (change.position.y / size.height).coerceIn(0f, 1f)
                                    crop = when (edge) {
                                        0 -> crop.copy(left = x.coerceAtMost(crop.right - 0.06f))
                                        1 -> crop.copy(top = y.coerceAtMost(crop.bottom - 0.06f))
                                        2 -> crop.copy(right = x.coerceAtLeast(crop.left + 0.06f))
                                        else -> crop.copy(bottom = y.coerceAtLeast(crop.top + 0.06f))
                                    }
                                }
                            )
                        }) {
                            val l = crop.left * size.width; val t = crop.top * size.height
                            val r = crop.right * size.width; val b = crop.bottom * size.height
                            val shade = Color.Black.copy(alpha = 0.45f)
                            drawRect(shade, size = Size(size.width, t))
                            drawRect(shade, Offset(0f, b), Size(size.width, size.height - b))
                            drawRect(shade, Offset(0f, t), Size(l, b - t))
                            drawRect(shade, Offset(r, t), Size(size.width - r, b - t))
                            drawRect(Color(0xFF1976D2), Offset(l, t), Size(r-l, b-t), style = Stroke(3.dp.toPx()))
                        }
                    }
                }
                Text("Visible: "+((crop.right-crop.left)*100).toInt()+"% width, "+((crop.bottom-crop.top)*100).toInt()+"% height")
                TextButton(onClick = { crop = PageCrop() }) { Text("Reset to full page") }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(crop) }) { Text("Apply crop") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
