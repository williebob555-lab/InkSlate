package com.inkslate.desktop

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState

/**
 * The title bar of an app that has no frame of its own, shown in an ordinary window: dragged to
 * move the window, double-clicked to maximise it, and the usual buttons - with one more, to go
 * back to covering the screen.
 */
@Composable
fun FrameWindowScope.WindowTitleBar(state: WindowState, onClose: () -> Unit) {
    Surface(tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
            WindowDraggableArea(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.fillMaxHeight().padding(start = 12.dp), contentAlignment = Alignment.CenterStart) {
                    Text(AppFlavor.name, style = MaterialTheme.typography.labelLarge)
                }
            }
            val small = Modifier.size(width = 44.dp, height = 32.dp)
            IconButton(onClick = { AppFlavor.chooseWindowed(false) }, modifier = small) { Icon(Icons.Default.Fullscreen, "Cover the whole screen") }
            IconButton(onClick = { state.isMinimized = true }, modifier = small) { Icon(Icons.Default.Minimize, "Minimise") }
            IconButton(onClick = {
                state.placement = if (state.placement == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized
            }, modifier = small) { Icon(Icons.Default.CropSquare, if (state.placement == WindowPlacement.Maximized) "Restore" else "Maximise") }
            IconButton(onClick = onClose, modifier = small) { Icon(Icons.Default.Close, "Close") }
        }
    }
}

/** Edges to size a frameless window by: the right, the bottom, and the corner between them. */
@Composable
fun BoxScope.ResizeEdges(state: WindowState) {
    if (state.placement != WindowPlacement.Floating) return
    fun Modifier.sizing(dx: Boolean, dy: Boolean) = pointerInput(dx, dy) {
        detectDragGestures { change, amount ->
            change.consume()
            val w = (state.size.width.value + if (dx) amount.x / density else 0f).coerceAtLeast(480f)
            val h = (state.size.height.value + if (dy) amount.y / density else 0f).coerceAtLeast(360f)
            state.size = DpSize(w.dp, h.dp)
        }
    }
    val edge = 6.dp
    Box(Modifier.align(Alignment.CenterEnd).width(edge).fillMaxHeight().pointerHoverIcon(PointerIcon(java.awt.Cursor(java.awt.Cursor.E_RESIZE_CURSOR))).sizing(dx = true, dy = false))
    Box(Modifier.align(Alignment.BottomCenter).height(edge).fillMaxWidth().pointerHoverIcon(PointerIcon(java.awt.Cursor(java.awt.Cursor.S_RESIZE_CURSOR))).sizing(dx = false, dy = true))
    Box(Modifier.align(Alignment.BottomEnd).size(14.dp).pointerHoverIcon(PointerIcon(java.awt.Cursor(java.awt.Cursor.SE_RESIZE_CURSOR))).sizing(dx = true, dy = true))
}
