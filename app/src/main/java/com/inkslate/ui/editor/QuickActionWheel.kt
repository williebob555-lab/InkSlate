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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.LineWeight
import androidx.compose.material.icons.filled.Layers
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
import com.inkslate.core.AnnotationLayers
import com.inkslate.core.DesignConfig
import com.inkslate.data.LocalDesignConfig
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
    val previewSize: Float? = null,
    val previewLayer: Int? = null
)

/**
 * Updates the gesture without committing a tool. Submenus are radial lanes, not latches: circling
 * the ring always returns to highlighting tools as soon as the pen leaves a submenu's region.
 */
fun advanceQuickWheelGesture(
    gesture: QuickWheelGesture,
    pointer: Offset,
    actions: List<QuickWheelAction>,
    current: ToolSnapshot,
    activationRadiusPx: Float,
    sizeTrackStartPx: Float,
    sizeTrackLengthPx: Float,
    sizeLadder: FloatArray
): QuickWheelGesture {
    val config = DesignConfig.current.wheel
    val moved = gesture.copy(pointer = pointer)
    val distance = gesture.anchor.distanceTo(pointer)
    val main = quickWheelMainAction(actions, current, gesture.anchor, pointer)
    val pointerAngle = pointerAngle(gesture.anchor, pointer)
    val colorAngle = actionAngle(actions, QuickWheelAction.COLOR)
    val sizeAngle = actionAngle(actions, QuickWheelAction.SIZE)
    val pixelsPerDp = activationRadiusPx / config.activationRadiusDp
    val colorRadiusPx = config.colorRadiusDp * pixelsPerDp
    val colorBandHalfWidthPx = config.colorDotDp * 1.3f * pixelsPerDp
    val inColorBand = distance >= colorRadiusPx - colorBandHalfWidthPx &&
        distance <= colorRadiusPx + colorBandHalfWidthPx
    val inLayerLane = QuickWheelAction.LAYER_DRAW in actions &&
        kotlin.math.abs(angleDifference(pointerAngle, config.layerEntryAngleDegrees)) <= config.layerLaneHalfAngleDegrees
    val submenu = when {
        distance < activationRadiusPx -> null
        inLayerLane -> QuickWheelAction.LAYER_DRAW
        gesture.submenu == QuickWheelAction.COLOR && inColorBand && colorAngle != null &&
            kotlin.math.abs(angleDifference(pointerAngle, colorAngle)) <= config.colorArcDegrees + 8f ->
            QuickWheelAction.COLOR
        main == QuickWheelAction.COLOR && inColorBand && colorAngle != null &&
            kotlin.math.abs(angleDifference(pointerAngle, colorAngle)) <= config.submenuEntryDegrees ->
            QuickWheelAction.COLOR
        main == QuickWheelAction.SIZE && sizeAngle != null &&
            kotlin.math.abs(angleDifference(pointerAngle, sizeAngle)) <= config.submenuEntryDegrees ->
            QuickWheelAction.SIZE
        else -> null
    }
    if (submenu == null) return moved.copy(submenu = null, previewSize = null, previewLayer = null)

    val initial = if (gesture.submenu != submenu) {
        if (current.tool == Tool.ERASER) current.eraserRadius else current.strokeWidth
    } else gesture.initialSize
    val preview = if (submenu == QuickWheelAction.SIZE) {
        quickWheelSize(sizeLadder, distance, sizeTrackStartPx, sizeTrackLengthPx)
    } else null
    return moved.copy(
        submenu = submenu,
        submenuOriginDistance = if (submenu == QuickWheelAction.LAYER_DRAW)
            config.layerTrackStartDp * pixelsPerDp else sizeTrackStartPx,
        initialSize = initial,
        previewSize = preview,
        previewLayer = if (submenu == QuickWheelAction.LAYER_DRAW) quickWheelLayer(
            distance, config.layerTrackStartDp * pixelsPerDp, config.layerTrackLengthDp * pixelsPerDp
        ) else null
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
    return actions.minByOrNull { action ->
        kotlin.math.abs(angleDifference(angle, quickWheelActionAngle(actions, action)))
    }
}

/** The layer shortcut keeps its lower-right home even when optional actions are hidden. */
fun quickWheelActionAngle(actions: List<QuickWheelAction>, action: QuickWheelAction): Float {
    val index = actions.indexOf(action).coerceAtLeast(0)
    val layerIndex = actions.indexOf(QuickWheelAction.LAYER_DRAW)
    val step = 360f / actions.size.coerceAtLeast(1)
    return if (layerIndex >= 0) DesignConfig.current.wheel.layerEntryAngleDegrees + (index - layerIndex) * step
        else -90f + index * step
}

fun quickWheelColorIndex(
    actions: List<QuickWheelAction>,
    anchor: Offset,
    pointer: Offset,
    colorCount: Int,
    arcDegrees: Float = DesignConfig.current.wheel.colorArcDegrees
): Int {
    if (colorCount <= 1) return 0
    val base = quickWheelActionAngle(actions, QuickWheelAction.COLOR)
    val pointerAngle = normalizedDegrees(
        Math.toDegrees(atan2((pointer.y - anchor.y).toDouble(), (pointer.x - anchor.x).toDouble())).toFloat()
    )
    val delta = angleDifference(pointerAngle, base).coerceIn(-arcDegrees, arcDegrees)
    return (((delta + arcDegrees) / (arcDegrees * 2f)) * (colorCount - 1))
        .roundToInt().coerceIn(0, colorCount - 1)
}

fun quickWheelSize(
    ladder: FloatArray,
    distance: Float,
    trackStartPx: Float,
    trackLengthPx: Float
): Float {
    if (ladder.isEmpty()) return 1f
    val fraction = ((distance - trackStartPx) / trackLengthPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
    return ladder[(fraction * ladder.lastIndex).roundToInt().coerceIn(0, ladder.lastIndex)]
}

/** Ordered radial stops: layer 1 nearest the ring, then layer 2, then layer 3 farthest out. */
fun quickWheelLayer(distance: Float, trackStartPx: Float, trackLengthPx: Float): Int {
    val fraction = ((distance - trackStartPx) / trackLengthPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
    return listOf(AnnotationLayers.ONE, AnnotationLayers.TWO, AnnotationLayers.THREE)[(fraction * 2f).roundToInt()]
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

/** Fixed order gives the curved palette muscle memory regardless of the currently selected ink. */
fun quickWheelPalette(): List<Int> = DesignConfig.current.wheel.palette.map { DesignConfig.argb(it) }

/**
 * A compact, transparent radial guide. It never owns pointer input: the drawing view that received
 * pen-down keeps the uninterrupted stream, while this overlay only reflects the current choice.
 */
@Composable
fun QuickActionWheel(state: ToolState, gesture: QuickWheelGesture) {
    val config = LocalDesignConfig.current.wheel
    val density = LocalDensity.current
    val current = state.active.snapshot()
    val actions = visibleActions(state.quickWheelActions, current)
    val candidate = when (gesture.submenu) {
        null -> quickWheelMainAction(actions, current, gesture.anchor, gesture.pointer)
        else -> gesture.submenu
    }
    val palette = config.palette.map { DesignConfig.argb(it) }
    val colorChoice = quickWheelColorIndex(actions, gesture.anchor, gesture.pointer, palette.size, config.colorArcDegrees)
    val layerChoice = gesture.previewLayer ?: state.recordLayerMask

    Box(Modifier.fillMaxSize()) {
        val radiusPx = with(density) { config.mainRadiusDp.dp.toPx() }
        val active = currentAction(current)

        actions.forEach { action ->
            val angle = quickWheelActionAngle(actions, action)
            val center = gesture.anchor.radial(angle, radiusPx)
            val selected = action == candidate
            val itemPx = with(density) { (config.actionSizeDp + if (selected) 6f else 0f).dp.toPx() }
            if (action == active) ActiveToolConnection(
                gesture.anchor, center, actionAccent(action, current)
            )
            ActionIsland(
                action = action,
                current = current,
                selected = selected,
                active = action == active,
                layer = layerChoice,
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
            QuickWheelAction.LAYER_DRAW -> LayerDrawIsland(
                anchor = gesture.anchor,
                selected = gesture.previewLayer ?: AnnotationLayers.ONE
            )
            else -> Unit
        }

        candidate?.let {
            Text(
                if (it == QuickWheelAction.LAYER_DRAW) "Layer ${layerNumber(gesture.previewLayer ?: AnnotationLayers.ONE)}" else it.label,
                color = actionAccent(it, current, layerChoice),
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

@Composable
private fun BoxScope.ActiveToolConnection(anchor: Offset, slot: Offset, accent: Color) {
    val density = LocalDensity.current
    val config = LocalDesignConfig.current.wheel
    Canvas(Modifier.fillMaxSize()) {
        val width = with(density) { (config.actionSizeDp - 1f).dp.toPx() }
        drawLine(accent.copy(alpha = .94f), anchor, slot, width, cap = StrokeCap.Round)
        drawCircle(accent.copy(alpha = .94f), width / 2f, anchor)
        drawCircle(accent.copy(alpha = .94f), width / 2f, slot)
    }
}

@Composable
private fun ActionIsland(
    action: QuickWheelAction,
    current: ToolSnapshot,
    selected: Boolean,
    active: Boolean,
    layer: Int,
    modifier: Modifier
) {
    val accent = actionAccent(action, current, layer)
    val config = LocalDesignConfig.current.wheel
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
        modifier = modifier.size((config.actionSizeDp + if (selected) 6f else 0f).dp)
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
    val config = LocalDesignConfig.current.wheel
    val density = LocalDensity.current
    val radiusPx = with(density) { config.colorRadiusDp.dp.toPx() }
    val base = quickWheelActionAngle(actions, QuickWheelAction.COLOR)

    Canvas(Modifier.fillMaxSize()) {
        drawArc(
            color = Color(0xFF111827).copy(alpha = .86f),
            startAngle = base - config.colorArcDegrees,
            sweepAngle = config.colorArcDegrees * 2f,
            useCenter = false,
            topLeft = Offset(anchor.x - radiusPx, anchor.y - radiusPx),
            size = Size(radiusPx * 2f, radiusPx * 2f),
            style = Stroke(with(density) { (config.colorDotDp + 11f).dp.toPx() }, cap = StrokeCap.Round)
        )
    }
    colors.forEachIndexed { colorIndex, value ->
        val fraction = if (colors.size == 1) .5f else colorIndex / (colors.size - 1f)
        val angle = base - config.colorArcDegrees + config.colorArcDegrees * 2f * fraction
        val center = anchor.radial(angle, radiusPx)
        val isSelected = colorIndex == selected
        val dotSize = (config.colorDotDp + if (isSelected) 4f else 0f).dp
        val dotPx = with(density) { dotSize.toPx() }
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(if (isSelected) 3.dp else 1.dp, Color.White.copy(alpha = .95f)),
            shadowElevation = if (isSelected) 7.dp else 2.dp,
            modifier = Modifier
                .offset {
                    IntOffset((center.x - dotPx / 2f).roundToInt(), (center.y - dotPx / 2f).roundToInt())
                }
                .size(dotSize)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size((config.colorDotDp - if (isSelected) 5f else 8f).dp)
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
    val config = LocalDesignConfig.current.wheel
    val angle = quickWheelActionAngle(actions, QuickWheelAction.SIZE)
    val trackCenter = anchor.radial(angle, with(density) { (config.sizeTrackStartDp + config.sizeTrackLengthDp / 2f).dp.toPx() })
    val widthPx = with(density) { config.sizeTrackLengthDp.dp.toPx() }
    val heightPx = with(density) { 38.dp.toPx() }
    Surface(
        shape = RoundedCornerShape(50),
        color = Color(0xFF052E2B).copy(alpha = .94f),
        contentColor = Color(0xFF5EEAD4),
        border = BorderStroke(1.dp, Color(0xFF2DD4BF)),
        shadowElevation = 6.dp,
        modifier = Modifier
            .offset {
                IntOffset((trackCenter.x - widthPx / 2f).roundToInt(), (trackCenter.y - heightPx / 2f).roundToInt())
            }
            .size(config.sizeTrackLengthDp.dp, 38.dp)
            .graphicsLayer { rotationZ = angle }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size((config.sizeTrackLengthDp - 16f).dp, 30.dp)) {
                val ladder = if (eraser) ToolState.ERASER_SIZES else ToolState.WIDTHS
                val chosen = ToolState.stopIndex(ladder, value)
                val left = 5f
                val right = size.width - 5f
                drawLine(Color(0xFF5EEAD4).copy(alpha = .55f), Offset(left, center.y), Offset(right, center.y), 2f)
                val largest = ladder.maxOrNull()?.coerceAtLeast(.01f) ?: 1f
                ladder.forEachIndexed { mark, stop ->
                    val x = if (ladder.size <= 1) size.width / 2f
                    else left + (right - left) * mark / ladder.lastIndex
                    val relative = kotlin.math.sqrt((stop / largest).coerceIn(0f, 1f))
                    val radius = 1.6f + relative * 4.2f
                    drawCircle(
                        color = if (mark == chosen) Color.White else Color(0xFF5EEAD4).copy(alpha = .68f),
                        radius = if (mark == chosen) radius + 2.2f else radius,
                        center = Offset(x, center.y)
                    )
                    if (mark == chosen) drawCircle(Color(0xFF0D9488), radius.coerceAtLeast(1f), Offset(x, center.y))
                }
            }
        }
    }
}

@Composable
private fun BoxScope.LayerDrawIsland(anchor: Offset, selected: Int) {
    val config = LocalDesignConfig.current.wheel
    val density = LocalDensity.current
    val layers = listOf(AnnotationLayers.ONE, AnnotationLayers.TWO, AnnotationLayers.THREE)
    val startPx = with(density) { config.layerTrackStartDp.dp.toPx() }
    val lengthPx = with(density) { config.layerTrackLengthDp.dp.toPx() }
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .92f)
    Canvas(Modifier.fillMaxSize()) {
        drawLine(
            trackColor,
            anchor.radial(config.layerEntryAngleDegrees, startPx),
            anchor.radial(config.layerEntryAngleDegrees, startPx + lengthPx),
            with(density) { 38.dp.toPx() },
            cap = StrokeCap.Round
        )
    }
    layers.forEachIndexed { index, layer ->
        val center = anchor.radial(config.layerEntryAngleDegrees, startPx + lengthPx * index / 2f)
        val chosen = layer == selected
        val dotSize = if (chosen) 38.dp else 32.dp
        val dotPx = with(density) { dotSize.toPx() }
        val accent = Color(AnnotationLayers.colour(layer))
        Surface(
            shape = CircleShape,
            color = if (chosen) accent else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (chosen) Color.White else accent,
            border = BorderStroke(if (chosen) 3.dp else 1.dp, if (chosen) Color.White else accent),
            shadowElevation = if (chosen) 8.dp else 3.dp,
            modifier = Modifier.offset {
                IntOffset((center.x - dotPx / 2f).roundToInt(), (center.y - dotPx / 2f).roundToInt())
            }.size(dotSize)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text((index + 1).toString(), style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

private fun layerNumber(layer: Int): Int = when (layer) {
    AnnotationLayers.TWO -> 2
    AnnotationLayers.THREE -> 3
    else -> 1
}

private fun actionIcon(action: QuickWheelAction): ImageVector = when (action) {
    QuickWheelAction.PEN -> Icons.Default.Edit
    QuickWheelAction.HIGHLIGHTER -> Icons.Default.Highlight
    QuickWheelAction.ERASER -> Icons.Default.CleaningServices
    QuickWheelAction.LAYER_DRAW -> Icons.Default.Layers
    QuickWheelAction.SELECT_ONCE -> Icons.Default.SelectAll
    QuickWheelAction.UNDO -> Icons.AutoMirrored.Filled.Undo
    QuickWheelAction.REDO -> Icons.AutoMirrored.Filled.Redo
    QuickWheelAction.COLOR -> Icons.Default.Palette
    QuickWheelAction.SIZE -> Icons.Default.LineWeight
}

private fun actionAccent(action: QuickWheelAction, current: ToolSnapshot, layer: Int = AnnotationLayers.ONE): Color = when (action) {
    QuickWheelAction.PEN -> Color(current.color).takeIf { current.brush != BrushType.HIGHLIGHTER } ?: Color(0xFF2563EB)
    QuickWheelAction.HIGHLIGHTER -> if (current.brush == BrushType.HIGHLIGHTER) Color(current.color) else Color(0xFFF59E0B)
    QuickWheelAction.ERASER -> Color(0xFFEF4444)
    QuickWheelAction.LAYER_DRAW -> Color(AnnotationLayers.colour(layer))
    QuickWheelAction.SELECT_ONCE -> Color(0xFF8B5CF6)
    QuickWheelAction.UNDO -> Color(0xFF0891B2)
    QuickWheelAction.REDO -> Color(0xFF4F46E5)
    QuickWheelAction.COLOR -> Color(current.color)
    QuickWheelAction.SIZE -> Color(0xFF0D9488)
}

private fun actionAngle(actions: List<QuickWheelAction>, action: QuickWheelAction): Float? =
    action.takeIf { it in actions }?.let { quickWheelActionAngle(actions, it) }

private fun pointerAngle(anchor: Offset, pointer: Offset): Float = normalizedDegrees(
    Math.toDegrees(atan2((pointer.y - anchor.y).toDouble(), (pointer.x - anchor.x).toDouble())).toFloat()
)

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

private fun Offset.radial(angleDegrees: Float, radius: Float): Offset {
    val radians = Math.toRadians(angleDegrees.toDouble())
    return Offset(x + cos(radians).toFloat() * radius, y + sin(radians).toFloat() * radius)
}

private fun Offset.distanceTo(other: Offset): Float =
    hypot((other.x - x).toDouble(), (other.y - y).toDouble()).toFloat()
