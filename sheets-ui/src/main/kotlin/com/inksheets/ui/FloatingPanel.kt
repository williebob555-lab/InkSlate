package com.inksheets.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Where each floating panel was left, so it opens there again. */
private object PanelSpots {
    val at = HashMap<String, Offset>()
}

/**
 * A small window over the music: moved by its title bar, closed by its cross, and nothing else
 * of the screen taken - the page underneath still turns and still takes the pen.
 */
@Composable
internal fun FloatingPanel(title: String, onClose: () -> Unit, width: Dp = 300.dp, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val room = with(density) { Offset(maxWidth.toPx(), maxHeight.toPx()) }
        val wide = with(density) { width.toPx() }
        var at by remember { mutableStateOf(PanelSpots.at[title] ?: Offset(room.x - wide - with(density) { 88.dp.toPx() }, with(density) { 72.dp.toPx() })) }
        // Kept on screen, however the window has changed since.
        val shown = Offset(at.x.coerceIn(0f, (room.x - wide).coerceAtLeast(0f)), at.y.coerceIn(0f, (room.y - with(density) { 64.dp.toPx() }).coerceAtLeast(0f)))
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.offset { IntOffset(shown.x.roundToInt(), shown.y.roundToInt()) }.width(width)
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth()
                        .pointerInput(title) {
                            detectDragGestures(onDragStart = { at = shown }) { change, amount ->
                                change.consume()
                                at += amount
                                PanelSpots.at[title] = at
                            }
                        }
                        .padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.DragIndicator, "Move", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(start = 6.dp))
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
                }
                Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) { content() }
            }
        }
    }
}
