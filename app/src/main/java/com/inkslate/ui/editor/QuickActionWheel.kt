package com.inkslate.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.LineWeight
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.inkslate.core.BrushType
import com.inkslate.core.Tool
import com.inkslate.core.ToolSnapshot
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** The whole press-drag-lift interaction. Coordinates are in the drawing view's pixels. */
data class QuickWheelGesture(
    val anchor: Offset,
    val pointer: Offset = anchor,
    val submenu: QuickWheelAction? = null,
    val submenuOriginDistance: Float = 0f,
    val initialSize: Float = 0f,
    val previewSize: Float? = null
)

/**
 * Updates the gesture without committing a tool. Colour and size latch after crossing their slot,
 * so the pen can continue into the separate island without the main ring changing underneath it.
 */
fun advanceQuickWheelGesture(
    gesture: QuickWheelGesture,
    pointer: Offset,
    actions: List<QuickWheelAction>,
    current: ToolSnapshot,
    activationRadiusPx: Float,
    sizeStepPx: Float,
    sizeLadder: FloatArray
): QuickWheelGesture {
    val moved = gesture.copy(pointer = pointer)
    val distance = gesture.anchor.distanceTo(pointer)
    val submenu = gesture.submenu ?: quickWheelMainAction(actions, current, gesture.anchor, pointer)
        ?.takeIf { distance >= activationRadiusPx && (it == QuickWheelAction.COLOR || it == QuickWheelAction.SIZE) }
    if (submenu == null) return moved

    val origin = if (gesture.submenu == null) distance else gesture.submenuOriginDistance
    val initial = if (gesture.submenu == null) {
        if (current.tool == Tool.ERASER) current.eraserRadius else current.strokeWidth
    } else gesture.initialSize
    val preview = if (submenu == QuickWheelAction.SIZE) {
        quickWheelSize(sizeLadder, initial, origin, distance, sizeStepPx)
    } else null
    return moved.copy(
        submenu = submenu,
        submenuOriginDistance = origin,
        initialSize = initial,
        previewSize = preview
    )
}

/** Direction wins, not distance: travelling beyond the drawn ring keeps the same obvious choice. */
fun quickWheelMainAction(
    actions: List<QuickWheelAction>,
    current: ToolSnapshot,
    anchor: Offset,
    pointer: Offset
): QuickWheelAction? {
    if (actions.isEmpty()) return null
    val dx = pointer.x - anchor.x
    val dy = pointer.y - anchor.y
    if (hypot(dx.toDouble(), dy.toDouble()) < 8.0) return currentAction(current)?.takeIf { it in actions }
    val angle = normalizedDegrees(Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat())
    val index = (((angle + 90f + 180f / actions.size) / (360f / actions.size)).toInt()).floorMod(actions.size)
    return actions[index]
}

fun quickWheelColorIndex(
    actions: List<QuickWheelAction>,
    anchor: Offset,
    pointer: Offset,
    colorCount: Int
): Int {
    if (colorCount <= 1) return 0
    val colorIndex = actions.indexOf(QuickWheelAction.COLOR).coerceAtLeast(0)
    val base = slotAngle(colorIndex, actions.size)
    val pointerAngle = normalizedDegrees(
        Math.toDegrees(atan2((pointer.y - anchor.y).toDouble(), (pointer.x - anchor.x).toDouble())).toFloat()
    )
    val delta = angleDifference(pointerAngle, base).coerceIn(-COLOR_ARC_DEGREES, COLOR_ARC_DEGREES)
    return (((delta + COLOR_ARC_DEGREES) / (COLOR_ARC_DEGREES * 2f)) * (colorCount - 1))
        .roundToInt().coerceIn(0, colorCount - 1)
}

fun quickWheelSize(
    ladder: FloatArray,
    initial: Float,
    originDistance: Float,
    distance: Float,
    stepPx: Float
): Float {
    if (ladder.isEmpty()) return initial
    val start = ToolState.stopIndex(ladder, initial)
    val steps = ((distance - originDistance) / stepPx.coerceAtLeast(1f)).roundToInt()
    return ladder[(start + steps).coerceIn(0, ladder.lastIndex)]
}

fun visibleActions(configured: List<QuickWheelAction>, current: ToolSnapshot): List<QuickWheelAction> {
    val ordered = configured.distinct().take(QuickWheelAction.MAX_ACTIONS).toMutableList()
    val active = currentAction(current)
    if (active != null && active !in ordered) {
        if (ordered.size == QuickWheelAction.MAX_ACTIONS) ordered[0] = active else ordered.add(0, active)
    }
    return ordered
}

fun currentAction(current: ToolSnapshot): QuickWheelAction? = when {
    current.tool == Tool.ERASER -> QuickWheelAction.ERASER
    current.tool == Tool.SELECT -> QuickWheelAction.SELECT_ONCE
    current.tool == Tool.DRAW && current.brush == BrushType.HIGHLIGHTER -> QuickWheelAction.HIGHLIGHTER
    current.tool == Tool.DRAW -> QuickWheelAction.PEN
    else -> null
}

fun quickWheelPalette(state: ToolState, current: ToolSnapshot): List<Int> {
    val alternatives = (
        state.customColors + listOf(
            0xFF111827.toInt(), 0xFF2563EB.toInt(), 0xFF7C3AED.toInt(),
            0xFFDC2626.toInt(), 0xFFF59E0B.toInt(), 0xFF16A34A.toInt(),
            0xFF0891B2.toInt(), 0xFFFDE047.toInt(), 0xFFFFFFFF.toInt()
        )
    ).distinct().filter { it != current.color }.take(MAX_COLOR_CHOICES - 1).toMutableList()
    // Straight outward from Colour means "keep this colour". The alternatives curve to either
    // side, so merely entering the island can never unexpectedly repaint the pen.
    alternatives.add((alternatives.size / 2), current.color)
    return alternatives
}

/**
 * A compact, transparent radial guide. It never owns pointer input: the drawing view that received
 * pen-down keeps the uninterrupted stream, while this overlay only reflects the current choice.
 */
@Composable
fun QuickActionWheel(state: ToolState, gesture: QuickWheelGesture) {
    val density = LocalDensity.current
    val current = state.active.snapshot()
    val actions = visibleActions(state.quickWheelActions, current)
    val candidate = when (gesture.submenu) {
        null -> quickWheelMainAction(actions, current, gesture.anchor, gesture.pointer)
        else -> gesture.submenu
    }
    val palette = quickWheelPalette(state, current)
    val colorChoice = quickWheelColorIndex(actions, gesture.anchor, gesture.pointer, palette.size)

    Box(Modifier.fillMaxSize()) {
        val itemPx = with(density) { ACTION_SIZE.toPx() }
        val radiusPx = with(density) { MAIN_RADIUS.toPx() }
        val active = currentAction(current)

        actions.forEachIndexed { index, action ->
            val angle = slotAngle(index, actions.size)
            val center = gesture.anchor.radial(angle, radiusPx)
            val selected = action == candidate
            if (action == active) ActiveToolWedge(gesture.anchor, angle, actionAccent(action, current))
            ActionIsland(
                action = action,
                current = current,
                selected = selected,
                active = action == active,
                modifier = Modifier.offset {
                    IntOffset((center.x - itemPx / 2f).roundToInt(), (center.y - itemPx / 2f).roundToInt())
                }
            )
        }

        when (gesture.submenu) {
            QuickWheelAction.COLOR -> ColorIsland(
                anchor = gesture.anchor,
                actions = actions,
                colors = palette,
                selected = colorChoice
            )
            QuickWheelAction.SIZE -> SizeIsland(
                anchor = gesture.anchor,
                actions = actions,
                value = gesture.previewSize ?: gesture.initialSize,
                eraser = current.tool == Tool.ERASER
            )
            else -> Unit
        }

        candidate?.let {
            Text(
                it.label,
                color = actionAccent(it, current),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.offset {
                    IntOffset(
                        (gesture.anchor.x - with(density) { 34.dp.toPx() }).roundToInt(),
                        (gesture.anchor.y + with(density) { 15.dp.toPx() }).roundToInt()
                    )
                }.size(68.dp, 18.dp)
            )
        }
    }
}

private val WedgeShape = GenericShape { size, _ ->
    moveTo(0f, size.height * .30f)
    quadraticBezierTo(0f, size.height * .5f, 0f, size.height * .70f)
    lineTo(size.width - size.height * .48f, size.height)
    quadraticBezierTo(size.width, size.height, size.width, size.height * .5f)
    quadraticBezierTo(size.width, 0f, size.width - size.height * .48f, 0f)
    close()
}

@Composable
private fun BoxScope.ActiveToolWedge(anchor: Offset, angle: Float, accent: Color) {
    val density = LocalDensity.current
    val lengthPx = with(density) { 69.dp.toPx() }
    val heightPx = with(density) { 38.dp.toPx() }
    val center = anchor.radial(angle, with(density) { 25.dp.toPx() })
    Surface(
        shape = WedgeShape,
        color = accent.copy(alpha = .92f),
        shadowElevation = 5.dp,
        modifier = Modifier
            .offset {
                IntOffset((center.x - lengthPx / 2f).roundToInt(), (center.y - heightPx / 2f).roundToInt())
            }
            .size(69.dp, 38.dp)
            .graphicsLayer { rotationZ = angle }
    ) {}
}

@Composable
private fun ActionIsland(
    action: QuickWheelAction,
    current: ToolSnapshot,
    selected: Boolean,
    active: Boolean,
    modifier: Modifier
) {
    val accent = actionAccent(action, current)
    Surface(
        shape = CircleShape,
        color = when {
            selected -> accent
            active -> accent.copy(alpha = .72f)
            else -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .88f)
        },
        contentColor = if (selected || active) Color.White else accent,
        border = BorderStroke(
            if (selected) 3.dp else 1.dp,
            if (selected) Color.White else accent.copy(alpha = .68f)
        ),
        shadowElevation = if (selected) 9.dp else 3.dp,
        modifier = modifier.size(if (selected) 46.dp else ACTION_SIZE)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(actionIcon(action), action.label, Modifier.size(if (selected) 24.dp else 20.dp))
        }
    }
}

@Composable
private fun BoxScope.ColorIsland(
    anchor: Offset,
    actions: List<QuickWheelAction>,
    colors: List<Int>,
    selected: Int
) {
    if (colors.isEmpty()) return
    val density = LocalDensity.current
    val radiusPx = with(density) { COLOR_RADIUS.toPx() }
    val dotPx = with(density) { COLOR_DOT.toPx() }
    val index = actions.indexOf(QuickWheelAction.COLOR).coerceAtLeast(0)
    val base = slotAngle(index, actions.size)

    Canvas(Modifier.fillMaxSize()) {
        drawArc(
            color = Color(0xFF111827).copy(alpha = .86f),
            startAngle = base - COLOR_ARC_DEGREES,
            sweepAngle = COLOR_ARC_DEGREES * 2f,
            useCenter = false,
            topLeft = Offset(anchor.x - radiusPx, anchor.y - radiusPx),
            size = Size(radiusPx * 2f, radiusPx * 2f),
            style = Stroke(with(density) { 38.dp.toPx() }, cap = StrokeCap.Round)
        )
    }
    colors.forEachIndexed { colorIndex, value ->
        val fraction = if (colors.size == 1) .5f else colorIndex / (colors.size - 1f)
        val angle = base - COLOR_ARC_DEGREES + COLOR_ARC_DEGREES * 2f * fraction
        val center = anchor.radial(angle, radiusPx)
        val isSelected = colorIndex == selected
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(if (isSelected) 3.dp else 1.dp, Color.White.copy(alpha = .95f)),
            shadowElevation = if (isSelected) 7.dp else 2.dp,
            modifier = Modifier
                .offset {
                    IntOffset((center.x - dotPx / 2f).roundToInt(), (center.y - dotPx / 2f).roundToInt())
                }
                .size(if (isSelected) 31.dp else COLOR_DOT)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(if (isSelected) 22.dp else 19.dp)
                        .clip(CircleShape)
                        .background(Color(value))
                )
            }
        }
    }
}

@Composable
private fun BoxScope.SizeIsland(
    anchor: Offset,
    actions: List<QuickWheelAction>,
    value: Float,
    eraser: Boolean
) {
    val density = LocalDensity.current
    val index = actions.indexOf(QuickWheelAction.SIZE).coerceAtLeast(0)
    val angle = slotAngle(index, actions.size)
    val center = anchor.radial(angle, with(density) { SIZE_RADIUS.toPx() })
    val widthPx = with(density) { 118.dp.toPx() }
    val heightPx = with(density) { 42.dp.toPx() }
    Surface(
        shape = RoundedCornerShape(50),
        color = Color(0xFF052E2B).copy(alpha = .94f),
        contentColor = Color(0xFF5EEAD4),
        border = BorderStroke(1.dp, Color(0xFF2DD4BF)),
        shadowElevation = 6.dp,
        modifier = Modifier
            .offset {
                IntOffset((center.x - widthPx / 2f).roundToInt(), (center.y - heightPx / 2f).roundToInt())
            }
            .size(118.dp, 42.dp)
            .graphicsLayer { rotationZ = angle }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(92.dp, 30.dp)) {
                val radii = floatArrayOf(2f, 3.4f, 5.2f, 7.4f, 10f)
                val chosen = sizeMarkIndex(value, eraser)
                radii.forEachIndexed { mark, radiusDp ->
                    drawCircle(
                        color = if (mark == chosen) Color.White else Color(0xFF5EEAD4).copy(alpha = .55f),
                        radius = with(density) { radiusDp.dp.toPx() },
                        center = Offset(size.width * (mark + 1) / 6f, size.height / 2f)
                    )
                }
            }
        }
    }
}

private fun actionIcon(action: QuickWheelAction): ImageVector = when (action) {
    QuickWheelAction.PEN -> Icons.Default.Edit
    QuickWheelAction.HIGHLIGHTER -> Icons.Default.Highlight
    QuickWheelAction.ERASER -> Icons.Default.CleaningServices
    QuickWheelAction.SELECT_ONCE -> Icons.Default.SelectAll
    QuickWheelAction.UNDO -> Icons.AutoMirrored.Filled.Undo
    QuickWheelAction.REDO -> Icons.AutoMirrored.Filled.Redo
    QuickWheelAction.COLOR -> Icons.Default.Palette
    QuickWheelAction.SIZE -> Icons.Default.LineWeight
}

private fun actionAccent(action: QuickWheelAction, current: ToolSnapshot): Color = when (action) {
    QuickWheelAction.PEN -> Color(current.color).takeIf { current.brush != BrushType.HIGHLIGHTER } ?: Color(0xFF2563EB)
    QuickWheelAction.HIGHLIGHTER -> if (current.brush == BrushType.HIGHLIGHTER) Color(current.color) else Color(0xFFF59E0B)
    QuickWheelAction.ERASER -> Color(0xFFEF4444)
    QuickWheelAction.SELECT_ONCE -> Color(0xFF8B5CF6)
    QuickWheelAction.UNDO -> Color(0xFF0891B2)
    QuickWheelAction.REDO -> Color(0xFF4F46E5)
    QuickWheelAction.COLOR -> Color(current.color)
    QuickWheelAction.SIZE -> Color(0xFF0D9488)
}

private fun sizeMarkIndex(value: Float, eraser: Boolean): Int {
    val ladder = if (eraser) ToolState.ERASER_SIZES else ToolState.WIDTHS
    if (ladder.size <= 1) return 0
    val index = ToolState.stopIndex(ladder, value)
    return (index * 4f / ladder.lastIndex).roundToInt().coerceIn(0, 4)
}

private fun slotAngle(index: Int, count: Int): Float = -90f + index * (360f / count.coerceAtLeast(1))

private fun normalizedDegrees(value: Float): Float {
    var result = value % 360f
    if (result < 0f) result += 360f
    return result
}

private fun angleDifference(a: Float, b: Float): Float {
    var delta = normalizedDegrees(a) - normalizedDegrees(b)
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    return delta
}

private fun Int.floorMod(divisor: Int): Int = ((this % divisor) + divisor) % divisor

private fun Offset.radial(angleDegrees: Float, radius: Float): Offset {
    val radians = Math.toRadians(angleDegrees.toDouble())
    return Offset(x + cos(radians).toFloat() * radius, y + sin(radians).toFloat() * radius)
}

private fun Offset.distanceTo(other: Offset): Float =
    hypot((other.x - x).toDouble(), (other.y - y).toDouble()).toFloat()

private const val COLOR_ARC_DEGREES = 58f
private const val MAX_COLOR_CHOICES = 9
private val ACTION_SIZE = 40.dp
private val MAIN_RADIUS = 54.dp
private val COLOR_RADIUS = 108.dp
private val COLOR_DOT = 27.dp
private val SIZE_RADIUS = 111.dp
