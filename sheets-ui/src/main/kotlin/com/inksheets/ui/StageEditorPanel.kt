package com.inksheets.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.inksheets.core.omr.MicrophonePattern
import com.inksheets.core.omr.StageMicrophone
import com.inksheets.core.omr.StageScene
import kotlin.math.cos
import kotlin.math.sin

private sealed interface StageSelection {
    data class Performer(val index: Int) : StageSelection
    data class Microphone(val index: Int) : StageSelection
}

/** Optional top-down scene editor. It only edits [StageScene] data: opening it does not read a
 * score, initialize audio, or allocate a renderer. The owner decides when and where to persist.
 */
@Composable
internal fun StageEditorPanel(
    scene: StageScene,
    onSceneChange: (StageScene) -> Unit,
    onClose: () -> Unit
) {
    var selected by remember { mutableStateOf<StageSelection?>(null) }
    val validSelection = when (val item = selected) {
        is StageSelection.Performer -> item.takeIf { it.index in scene.performers.indices }
        is StageSelection.Microphone -> item.takeIf { it.index in scene.microphones.indices }
        null -> null
    }
    if (validSelection != selected) selected = validSelection

    SheetDialog("Stage & microphones", onDismiss = onClose, wide = true, buttons = {
        TextButton(onClick = { selected = null }, enabled = selected != null) { Text("Clear selection") }
        TextButton(onClick = onClose) { Text("Done") }
    }) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 680.dp
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StageMap(scene, selected, { selected = it }, onSceneChange,
                        Modifier.fillMaxWidth().height(320.dp))
                    StageInspector(scene, selected, onSceneChange, Modifier.fillMaxWidth().heightIn(max = 300.dp))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                    StageMap(scene, selected, { selected = it }, onSceneChange,
                        Modifier.weight(1f).heightIn(min = 420.dp, max = 580.dp))
                    StageInspector(scene, selected, onSceneChange, Modifier.width(280.dp).heightIn(max = 580.dp))
                }
            }
        }
    }
}

@Composable
private fun StageMap(
    scene: StageScene,
    selected: StageSelection?,
    onSelect: (StageSelection?) -> Unit,
    onSceneChange: (StageScene) -> Unit,
    modifier: Modifier
) {
    val latestScene by rememberUpdatedState(scene)
    val latestChange by rememberUpdatedState(onSceneChange)
    val performerColor = MaterialTheme.colorScheme.primary
    val microphoneColor = MaterialTheme.colorScheme.tertiary
    val selectedColor = MaterialTheme.colorScheme.error
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    val grid = MaterialTheme.colorScheme.outlineVariant
    val podium = MaterialTheme.colorScheme.secondary

    Canvas(modifier
        .background(surface, RoundedCornerShape(12.dp))
        .semantics { contentDescription = "Top-down stage. Drag performers and microphones to position them." }
        .pointerInput(scene.room.width, scene.room.depth, scene.performers.size, scene.microphones.size) {
            val hitRadius = 30.dp.toPx()
            var dragging: StageSelection? = null
            var workingScene: StageScene? = null
            fun point(x: Double, y: Double): Offset = Offset(
                ((x + latestScene.room.width / 2.0) / latestScene.room.width * size.width).toFloat(),
                (y / latestScene.room.depth * size.height).toFloat()
            )
            detectDragGestures(
                onDragStart = { at ->
                    val choices = buildList {
                        latestScene.performers.forEachIndexed { i, p -> add(StageSelection.Performer(i) to point(p.x, p.y)) }
                        latestScene.microphones.forEachIndexed { i, m -> add(StageSelection.Microphone(i) to point(m.x, m.y)) }
                    }
                    dragging = choices.minByOrNull { (_, where) -> (where - at).getDistance() }
                        ?.takeIf { (_, where) -> (where - at).getDistance() <= hitRadius }?.first
                    workingScene = latestScene
                    onSelect(dragging)
                },
                onDragEnd = { dragging = null; workingScene = null },
                onDragCancel = { dragging = null; workingScene = null }
            ) { change, amount ->
                change.consume()
                val now = workingScene ?: latestScene
                val dx = amount.x / size.width * now.room.width
                val dy = amount.y / size.height * now.room.depth
                when (val item = dragging) {
                    is StageSelection.Performer -> now.performers.getOrNull(item.index)?.let { p ->
                        val updated = now.copy(performers = now.performers.replaced(item.index, p.copy(
                            x = (p.x + dx).coerceIn(-now.room.width / 2.0, now.room.width / 2.0),
                            y = (p.y + dy).coerceIn(0.0, now.room.depth)
                        )))
                        workingScene = updated
                        latestChange(updated)
                    }
                    is StageSelection.Microphone -> now.microphones.getOrNull(item.index)?.let { m ->
                        val updated = now.copy(microphones = now.microphones.replaced(item.index, m.copy(
                            x = (m.x + dx).coerceIn(-now.room.width / 2.0, now.room.width / 2.0),
                            y = (m.y + dy).coerceIn(0.0, now.room.depth)
                        )))
                        workingScene = updated
                        latestChange(updated)
                    }
                    null -> Unit
                }
            }
        }
        .padding(12.dp)) {
        fun point(x: Double, y: Double) = Offset(
            ((x + scene.room.width / 2.0) / scene.room.width * size.width).toFloat(),
            (y / scene.room.depth * size.height).toFloat()
        )
        for (i in 1 until 4) {
            val x = size.width * i / 4f
            val y = size.height * i / 4f
            drawLine(grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        drawRoundRect(grid, style = Stroke(width = 2f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f))
        val podiumAt = point(scene.podium.x, scene.podium.y.coerceIn(0.0, scene.room.depth))
        drawCircle(podium, radius = 9.dp.toPx(), center = podiumAt, style = Stroke(width = 3f))

        scene.performers.forEachIndexed { index, performer ->
            val at = point(performer.x, performer.y)
            val color = if (selected == StageSelection.Performer(index)) selectedColor else performerColor
            val radius = (9.5 + performer.playerCount.coerceAtMost(8) * .65).dp.toPx()
            drawCircle(color.copy(alpha = if (performer.muted) .35f else 1f), radius, at)
            val direction = Offset(sin(performer.facing).toFloat(), cos(performer.facing).toFloat())
            drawLine(Color.White.copy(alpha = .9f), at, at + direction * radius, strokeWidth = 2f)
            if (performer.solo) drawCircle(selectedColor, radius + 4.dp.toPx(), at, style = Stroke(2.dp.toPx()))
        }
        scene.microphones.forEachIndexed { index, microphone ->
            val at = point(microphone.x, microphone.y)
            val color = if (selected == StageSelection.Microphone(index)) selectedColor else microphoneColor
            val radius = 9.dp.toPx()
            drawCircle(color.copy(alpha = if (microphone.muted) .35f else 1f), radius, at, style = Stroke(3.dp.toPx()))
            val direction = Offset(sin(microphone.facing).toFloat(), cos(microphone.facing).toFloat())
            drawLine(color, at, at + direction * 18.dp.toPx(), strokeWidth = 3f)
            if (microphone.solo) drawCircle(selectedColor, radius + 4.dp.toPx(), at, style = Stroke(2.dp.toPx()))
        }
    }
}

@Composable
private fun StageInspector(scene: StageScene, selected: StageSelection?, onSceneChange: (StageScene) -> Unit,
                           modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (selected) {
            is StageSelection.Performer -> scene.performers.getOrNull(selected.index)?.let { performer ->
                Text(performer.partId, style = MaterialTheme.typography.titleMedium)
                Text("Performer / section", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                PositionText(performer.x, performer.y)
                LabeledSlider("Gain", performer.gain.toFloat(), 0f..2f) { value ->
                    onSceneChange(scene.copy(performers = scene.performers.replaced(selected.index, performer.copy(gain = value.toDouble()))))
                }
                ToggleRow("Mute", performer.muted) { value ->
                    onSceneChange(scene.copy(performers = scene.performers.replaced(selected.index, performer.copy(muted = value))))
                }
                ToggleRow("Solo", performer.solo) { value ->
                    onSceneChange(scene.copy(performers = scene.performers.replaced(selected.index, performer.copy(solo = value))))
                }
            }
            is StageSelection.Microphone -> scene.microphones.getOrNull(selected.index)?.let { microphone ->
                Text(microphone.id, style = MaterialTheme.typography.titleMedium)
                Text("Microphone · ${microphone.outputBus}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                PositionText(microphone.x, microphone.y)
                LabeledSlider("Gain", microphone.gain.toFloat(), 0f..2f) { value ->
                    updateMicrophone(scene, selected.index, microphone.copy(gain = value.toDouble()), onSceneChange)
                }
                LabeledSlider("Pan", microphone.pan.toFloat(), -1f..1f) { value ->
                    updateMicrophone(scene, selected.index, microphone.copy(pan = value.toDouble()), onSceneChange)
                }
                Text("Pattern", style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MicrophonePattern.entries.forEach { pattern ->
                        FilterChip(selected = microphone.pattern == pattern, onClick = {
                            updateMicrophone(scene, selected.index, microphone.copy(pattern = pattern), onSceneChange)
                        }, label = { Text(pattern.displayName()) })
                    }
                }
                ToggleRow("Mute", microphone.muted) { value ->
                    updateMicrophone(scene, selected.index, microphone.copy(muted = value), onSceneChange)
                }
                ToggleRow("Solo", microphone.solo) { value ->
                    updateMicrophone(scene, selected.index, microphone.copy(solo = value), onSceneChange)
                }
            }
            null -> {
                Text("Stage layout", style = MaterialTheme.typography.titleMedium)
                Text("Drag a performer or microphone, then select it to adjust gain, routing position, mute, solo, and microphone pickup.",
                    style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("${scene.performers.size} performer sections")
                Text("${scene.microphones.size} microphones")
                Text("${scene.room.width.toInt()} × ${scene.room.depth.toInt()} m room",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.size(4.dp))
    }
}

@Composable
private fun PositionText(x: Double, y: Double) {
    Text("Position ${"%.1f".format(x)} m across · ${"%.1f".format(y)} m back",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text("%.2f".format(value), style = MaterialTheme.typography.labelMedium)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Switch(checked, onCheckedChange)
    }
}

private fun updateMicrophone(scene: StageScene, index: Int, microphone: StageMicrophone,
                             onSceneChange: (StageScene) -> Unit) {
    onSceneChange(scene.copy(microphones = scene.microphones.replaced(index, microphone)))
}

private fun MicrophonePattern.displayName(): String = when (this) {
    MicrophonePattern.OMNI -> "Omnidirectional"
    MicrophonePattern.CARDIOID -> "Cardioid"
    MicrophonePattern.FIGURE_EIGHT -> "Figure eight"
}

private fun <T> List<T>.replaced(index: Int, value: T): List<T> = toMutableList().also { it[index] = value }
