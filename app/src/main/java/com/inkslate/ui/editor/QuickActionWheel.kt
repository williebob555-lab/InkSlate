package com.inkslate.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.LineWeight
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkslate.core.BrushType
import com.inkslate.core.Tool
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A deliberately small, transient wheel at the stylus tip.
 *
 * It is 164dp across including its touch targets. Colour reuses the same five slots rather than
 * opening another panel, and size is changed by pulling its slot away from or toward the centre.
 */
@Composable
fun QuickActionWheel(
    state: ToolState,
    anchor: Offset,
    onSelectOnce: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onChanged: () -> Unit,
    onDismiss: () -> Unit
) {
    val density = LocalDensity.current
    var colors by remember(anchor) { mutableStateOf(false) }
    val current = remember(state.revision, state.activeMode) { state.active.snapshot() }
    var shownWidth by remember(anchor) {
        mutableFloatStateOf(if (current.tool == Tool.ERASER) current.eraserRadius else current.strokeWidth)
    }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .clickable(onClick = onDismiss)
    ) {
        val wheel = 164.dp
        val wheelPx = with(density) { wheel.toPx() }
        val left = (anchor.x - wheelPx / 2f).coerceIn(0f, (constraints.maxWidth - wheelPx).coerceAtLeast(0f))
        val top = (anchor.y - wheelPx / 2f).coerceIn(0f, (constraints.maxHeight - wheelPx).coerceAtLeast(0f))

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .98f),
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
            modifier = Modifier
                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .size(wheel)
                .clickable(onClick = {})
        ) {
            Box {
                val palette = if (current.brush == BrushType.HIGHLIGHTER) {
                    listOf(0xFFFDE047, 0xFFA3E635, 0xFFF9A8D4, 0xFF67E8F9, 0xFFC4B5FD)
                } else {
                    listOf(0xFF111827, 0xFF2563EB, 0xFFDC2626, 0xFF16A34A, 0xFF9333EA)
                }
                if (colors) {
                    palette.forEachIndexed { index, value ->
                        WheelSlot(index, palette.size) {
                            ColorDot(Color(value)) {
                                state.edit { c ->
                                    if (c.tool == Tool.ERASER) c.tool = Tool.DRAW
                                    c.color = value.toInt()
                                }
                                state.rememberColor(value.toInt())
                                onChanged(); onDismiss()
                            }
                        }
                    }
                } else {
                    state.quickWheelActions.forEachIndexed { index, action ->
                        WheelSlot(index, state.quickWheelActions.size) {
                            when (action) {
                                QuickWheelAction.PEN -> WheelIcon(Icons.Default.Gesture, action.label) {
                                    state.edit { c ->
                                        if (c.brush == BrushType.HIGHLIGHTER) c.toggleHighlighter()
                                        c.tool = Tool.DRAW
                                    }
                                    onChanged(); onDismiss()
                                }
                                QuickWheelAction.HIGHLIGHTER -> WheelIcon(Icons.Default.BorderColor, action.label) {
                                    state.edit { c ->
                                        if (c.brush != BrushType.HIGHLIGHTER) c.toggleHighlighter()
                                        c.tool = Tool.DRAW
                                    }
                                    onChanged(); onDismiss()
                                }
                                QuickWheelAction.ERASER -> WheelIcon(Icons.Default.Remove, action.label) {
                                    state.edit { it.tool = Tool.ERASER }
                                    onChanged(); onDismiss()
                                }
                                QuickWheelAction.SELECT_ONCE -> WheelIcon(Icons.Default.SelectAll, action.label) {
                                    onSelectOnce(); onDismiss()
                                }
                                QuickWheelAction.UNDO -> WheelIcon(Icons.AutoMirrored.Filled.Undo, action.label) {
                                    onUndo(); onDismiss()
                                }
                                QuickWheelAction.REDO -> WheelIcon(Icons.AutoMirrored.Filled.Redo, action.label) {
                                    onRedo(); onDismiss()
                                }
                                QuickWheelAction.COLOR -> WheelIcon(Icons.Default.Palette, action.label) {
                                    colors = true
                                }
                                QuickWheelAction.SIZE -> SizeSlot(
                                    value = shownWidth,
                                    onValue = { value ->
                                        shownWidth = value
                                        if (state.active.tool == Tool.ERASER) state.active.eraserRadius = value
                                        else state.active.strokeWidth = value
                                        onChanged()
                                    },
                                    onFinished = {
                                        state.edit { c ->
                                            if (c.tool == Tool.ERASER) c.eraserRadius = shownWidth
                                            else c.strokeWidth = shownWidth
                                        }
                                        onChanged(); onDismiss()
                                    }
                                )
                            }
                        }
                    }
                }

                IconButton(
                    onClick = { if (colors) colors = false else onDismiss() },
                    modifier = Modifier.align(Alignment.Center).size(44.dp)
                ) {
                    Icon(if (colors) Icons.Default.ArrowBack else Icons.Default.Close, if (colors) "Back" else "Close")
                }
            }
        }
    }
}

@Composable
private fun BoxScope.WheelSlot(index: Int, count: Int, content: @Composable () -> Unit) {
    val angle = Math.toRadians(-90.0 + index * (360.0 / count.coerceAtLeast(1)))
    val radius = 55.dp
    val item = 44.dp
    val x = 82.dp + radius * cos(angle).toFloat() - item / 2
    val y = 82.dp + radius * sin(angle).toFloat() - item / 2
    Box(Modifier.offset(x, y).size(item), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun WheelIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Icon(icon, description, Modifier.size(21.dp))
    }
}

@Composable
private fun ColorDot(color: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(color))
    }
}

@Composable
private fun SizeSlot(value: Float, onValue: (Float) -> Unit, onFinished: () -> Unit) {
    var accumulated by remember { mutableFloatStateOf(0f) }
    var starting by remember { mutableFloatStateOf(value) }
    val latestValue by rememberUpdatedState(value)
    Box(
        Modifier
            .size(44.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { accumulated = 0f; starting = latestValue },
                    onDragEnd = onFinished,
                    onDragCancel = onFinished
                ) { change, amount ->
                    change.consume()
                    // This slot sits to the upper left. Moving farther left/up is outward.
                    accumulated += -(amount.x + amount.y) / 2f
                    onValue((starting + accumulated / 10f).coerceIn(.25f, 48f))
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.LineWeight, "Size: ${"%.1f".format(value)}", Modifier.size(20.dp))
        Text(
            if (value < 10f) "%.1f".format(value) else value.roundToInt().toString(),
            fontSize = 8.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
