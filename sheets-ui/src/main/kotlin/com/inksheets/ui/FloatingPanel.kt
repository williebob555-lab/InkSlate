package com.inksheets.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
internal fun FloatingPanel(
    title: String,
    onClose: () -> Unit,
    width: Dp = 300.dp,
    /** Buttons along the foot, wrapping onto a second line rather than squeezing. */
    footer: (@Composable () -> Unit)? = null,
    /** Its content scrolls within it when the screen is too short for it ([false]: it scrolls itself). */
    scroll: Boolean = true,
    content: @Composable () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val room = with(density) { Offset(maxWidth.toPx(), maxHeight.toPx()) }
        // Never wider or taller than the screen it is on: a phone gets the width it has.
        val panelWidth = minOf(width, maxWidth - 16.dp).coerceAtLeast(200.dp)
        val panelHeight = (maxHeight - 16.dp).coerceAtLeast(160.dp)
        val wide = with(density) { panelWidth.toPx() }
        // Open over the music: the strips at the sides make room for it, or step aside (see Overlays).
        Opened(title, panelWidth)
        val leftEdge = with(density) { (Overlays.left + 8.dp).toPx() }
        val rightEdge = room.x - with(density) { (Overlays.right + 8.dp).toPx() }
        var at by remember { mutableStateOf(PanelSpots.at[title] ?: Offset(rightEdge - wide, with(density) { (if (maxHeight < 500.dp) 8.dp else 72.dp).toPx() })) }
        // Kept on screen, however the window has changed since - and clear of the strips, where there is room beside them.
        val (lo, hi) = if (rightEdge - leftEdge >= wide) leftEdge to rightEdge - wide else 0f to (room.x - wide).coerceAtLeast(0f)
        val shown = Offset(at.x.coerceIn(lo, hi), at.y.coerceIn(0f, (room.y - with(density) { 64.dp.toPx() }).coerceAtLeast(0f)))
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            // No taller than the screen below where it stands: on a short screen it scrolls, never runs off the foot.
            modifier = Modifier.offset { IntOffset(shown.x.roundToInt(), shown.y.roundToInt()) }.width(panelWidth)
                .heightIn(max = minOf(panelHeight, with(density) { (room.y - shown.y).toDp() } - 8.dp).coerceAtLeast(120.dp))
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth()
                        .pointerInput(title) {
                            // A slow drag moves the window; a quick swipe down closes it, like
                            // every other panel - and it opens again where it was.
                            var from = Offset.Zero
                            var startedAt = 0L
                            detectDragGestures(
                                onDragStart = { at = shown; from = shown; startedAt = System.currentTimeMillis() },
                                onDragEnd = {
                                    val moved = at - from
                                    val quick = System.currentTimeMillis() - startedAt < 350
                                    if (quick && moved.y > 90.dp.toPx() && kotlin.math.abs(moved.x) < moved.y) {
                                        at = from; PanelSpots.at[title] = from; onClose()
                                    }
                                }
                            ) { change, amount ->
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
                Box(Modifier.weight(1f, fill = false).then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(start = 12.dp, end = 12.dp, bottom = if (footer == null) 12.dp else 0.dp)) { content() }
                if (footer != null) {
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End
                    ) { footer() }
                }
            }
        }
    }
}
