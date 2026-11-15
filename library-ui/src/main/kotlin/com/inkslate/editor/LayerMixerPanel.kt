package com.inkslate.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkslate.core.AnnotationLayers
import com.inkslate.core.DesignConfig

data class LayerMixerHeights(val visibility: Float, val actions: Float, val fader: Float)

fun layerMixerHeights(availableHeightDp: Float, visibilityHeightDp: Float, actionHeightDp: Float): LayerMixerHeights {
    val available = availableHeightDp.coerceAtLeast(0f)
    val scale = (available / (visibilityHeightDp + 2 * actionHeightDp)).coerceIn(0f, 1f)
    return LayerMixerHeights(visibilityHeightDp * scale, actionHeightDp * scale, available)
}

/** Buttons take width, never fader travel. All three channels share one height and baseline. */
@Composable
fun LayerMixerPanel(
    visibleMask: Int, recordingMask: Int, opacities: List<Float>, filterMask: Int,
    config: DesignConfig.Mixer,
    onVisibility: (Int) -> Unit, onRecord: (Int) -> Unit,
    onOpacity: (Int, Float) -> Unit, onOpacityFinished: () -> Unit,
    onFilter: (Int) -> Unit, modifier: Modifier = Modifier
) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .45f))) {
        Row(Modifier.fillMaxSize().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(1, 2, 4).forEachIndexed { index, layer ->
                if (index > 0) VerticalDivider(Modifier.fillMaxHeight().padding(
                    horizontal = (config.channelGapDp / 2f).dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = .45f))
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    val heights = layerMixerHeights(maxHeight.value, config.visibilityHeightDp * fontScale, config.actionHeightDp * fontScale)
                    val color = Color(AnnotationLayers.colour(layer))
                    val visible = visibleMask and layer != 0
                    val armed = recordingMask and layer != 0
                    val filtered = filterMask and layer != 0
                    val label = (index + 1).toString()
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.width(config.controlWidthDp.dp * fontScale).testTag("layer-$label-controls"),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.fillMaxWidth().height(heights.visibility.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(color.copy(alpha = if (visible) .18f else .03f))
                                .border(if (visible) 2.dp else 1.dp, color.copy(alpha = if (visible) 1f else .35f), RoundedCornerShape(7.dp))
                                .clickable { onVisibility(layer) }
                                .semantics { contentDescription = "Layer $label ${if (visible) "visible" else "muted"}. Tap to ${if (visible) "mute" else "show"}." },
                                contentAlignment = Alignment.Center) {
                                Text(label, color = color.copy(alpha = if (visible) 1f else .55f),
                                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            }
                            MixerAction(Icons.Default.FiberManualRecord, "Write", if (armed) "Writing on layer $label" else "Write only on layer $label",
                                if (armed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                heights.actions) { onRecord(layer) }
                            MixerAction(Icons.Default.FilterAlt, "Filter", "${if (filtered) "Remove" else "Place"} layer $label filter",
                                if (filtered) color else MaterialTheme.colorScheme.onSurfaceVariant,
                                heights.actions) { onFilter(layer) }
                        }
                        LayerFader(opacities.getOrElse(index) { 1f }, color, label,
                            { onOpacity(layer, it) }, onOpacityFinished,
                            Modifier.weight(1f).fillMaxHeight().testTag("layer-$label-fader"))
                    }
                }
            }
        }
    }
}

@Composable
private fun MixerAction(icon: ImageVector, label: String, description: String, color: Color,
                        height: Float, action: () -> Unit) {
    Column(Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(6.dp))
        .clickable(onClick = action).semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(13.dp), tint = color)
        Text(label, fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1, color = color)
    }
}

@Composable
private fun LayerFader(value: Float, color: Color, label: String, onValue: (Float) -> Unit,
                       onFinished: () -> Unit, modifier: Modifier) {
    val choose by rememberUpdatedState(onValue)
    val finish by rememberUpdatedState(onFinished)
    val track = MaterialTheme.colorScheme.outline.copy(alpha = .5f)
    val surface = MaterialTheme.colorScheme.surface
    Canvas(modifier.semantics {
        contentDescription = "Layer $label opacity"
        stateDescription = "${(value * 100).toInt()} percent"
        progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
        setProgress { choose(it.coerceIn(0f, 1f)); finish(); true }
    }.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            fun set(y: Float) {
                val inset = minOf(10.dp.toPx(), size.height / 4f)
                choose((1f - (y - inset) / (size.height - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f))
            }
            down.consume(); set(down.position.y)
            try {
                do {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    set(change.position.y); change.consume()
                } while (change.pressed)
            } finally { finish() }
        }
    }) {
        val radius = minOf(10.dp.toPx(), size.width / 2f, size.height / 4f)
        val top = radius
        val bottom = size.height - radius
        val x = size.width / 2
        val y = bottom - (bottom - top) * value.coerceIn(0f, 1f)
        drawLine(track, Offset(x, top), Offset(x, bottom), 2.dp.toPx(), StrokeCap.Round)
        drawLine(color.copy(alpha = .65f), Offset(x, bottom), Offset(x, y), 2.dp.toPx(), StrokeCap.Round)
        drawCircle(surface, radius, Offset(x, y))
        drawCircle(color, radius * .65f, Offset(x, y))
    }
}
