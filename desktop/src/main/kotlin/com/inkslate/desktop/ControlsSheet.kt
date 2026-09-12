package com.inkslate.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import com.inkslate.core.InputAction
import com.inkslate.core.InputBindings
import com.inkslate.core.InputButton
import com.inkslate.core.InputDevice

/**
 * What everything on the desk does, inside the document rather than behind the settings.
 *
 * This was a list of rows in the settings screen, which is a screen you cannot reach while you are
 * working - so a binding could only be checked or changed by leaving the thing you were doing.
 * It also asked somebody to recognise "Mouse - Forward" as a button on their own hand.
 *
 * So the devices are drawn. Choosing a row lights up the part of the pen or the mouse it belongs
 * to, which answers "which button is that?" without anybody having to know the names.
 */
@Composable
fun ControlsSheet(onDismiss: () -> Unit) {
    var bindings by remember { mutableStateOf(InputBindingStore.bindings) }
    var chosen by remember { mutableStateOf(InputDevice.PEN to InputButton.NONE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Controls") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Every way of touching the page, and what it does. Anything not listed - a " +
                        "pen with a third button, a tablet that calls itself something else - " +
                        "draws with the pen belonging to whatever is holding it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(Modifier.padding(top = 12.dp)) {
                    Column(
                        Modifier.width(150.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        PenDiagram(chosen, Modifier.width(96.dp).height(150.dp))
                        MouseDiagram(chosen, Modifier.width(96.dp).height(130.dp))
                    }

                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        InputBindings.rows().forEach { row ->
                            BindingRow(
                                device = row.first,
                                button = row.second,
                                bindings = bindings,
                                selected = chosen == row,
                                onSelect = { chosen = row },
                                onChange = {
                                    bindings = bindings.with(row.first, row.second, it)
                                    InputBindingStore.bindings = bindings
                                }
                            )
                        }
                    }
                }

                TextButton(onClick = {
                    bindings = InputBindings()
                    InputBindingStore.bindings = bindings
                }) { Text("Put them all back") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun BindingRow(
    device: InputDevice,
    button: InputButton,
    bindings: InputBindings,
    selected: Boolean,
    onSelect: () -> Unit,
    onChange: (InputAction) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val action = bindings.actionFor(device, button)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else Color.Transparent
            )
            .clickable { onSelect() }
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("${device.label} - ${button.label}", style = MaterialTheme.typography.bodyMedium)
            if (bindings.isChanged(device, button)) {
                Text(
                    "changed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        Box {
            TextButton(onClick = { onSelect(); open = true }) { Text(action.label) }
            DropdownMenu(open, onDismissRequest = { open = false }) {
                InputAction.entries.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(choice.label) },
                        onClick = {
                            open = false
                            onChange(choice)
                        }
                    )
                }
            }
        }
    }
}

// ---- the drawings ------------------------------------------------------------

/**
 * A stylus, with the part under discussion lit.
 *
 * Drawn rather than drawn from a picture: it has to follow the theme, and a shape this simple is
 * fewer lines of code than loading an image would be.
 */
@Composable
private fun PenDiagram(chosen: Pair<InputDevice, InputButton>, modifier: Modifier) {
    val line = MaterialTheme.colorScheme.onSurfaceVariant
    val lit = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier) {
        val penOn = chosen.first == InputDevice.PEN
        val width = size.width * 0.34f
        val left = (size.width - width) / 2f
        val tipHeight = size.height * 0.17f

        // The barrel.
        drawRoundRect(
            color = idle,
            topLeft = Offset(left, 0f),
            size = Size(width, size.height - tipHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(width / 2.4f)
        )
        drawRoundRect(
            color = line,
            topLeft = Offset(left, 0f),
            size = Size(width, size.height - tipHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(width / 2.4f),
            style = DrawStroke(width = 1.5f)
        )

        // The nib, which is the tip that draws.
        val nib = Path().apply {
            moveTo(left, size.height - tipHeight)
            lineTo(left + width, size.height - tipHeight)
            lineTo(left + width / 2f, size.height)
            close()
        }
        drawPath(
            nib,
            if (penOn && chosen.second == InputButton.NONE) lit else idle
        )
        drawPath(nib, line, style = DrawStroke(width = 1.5f))

        // Two buttons on the barrel, the upper one nearer the hand.
        fun button(atY: Float, which: InputButton) {
            drawRoundRect(
                color = if (penOn && chosen.second == which) lit else line.copy(alpha = 0.45f),
                topLeft = Offset(left + width * 0.12f, atY),
                size = Size(width * 0.76f, size.height * 0.07f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f)
            )
        }
        button(size.height * 0.30f, InputButton.PEN_ONE)
        button(size.height * 0.43f, InputButton.PEN_TWO)
    }
}

/** A mouse, with its five buttons, the one under discussion lit. */
@Composable
private fun MouseDiagram(chosen: Pair<InputDevice, InputButton>, modifier: Modifier) {
    val line = MaterialTheme.colorScheme.onSurfaceVariant
    val lit = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier) {
        val on = chosen.first == InputDevice.MOUSE
        fun tint(which: InputButton) = if (on && chosen.second == which) lit else idle

        val body = androidx.compose.ui.geometry.CornerRadius(size.width * 0.45f)
        drawRoundRect(color = idle, size = size, cornerRadius = body)

        // The two halves of the top, split by the wheel.
        val topHeight = size.height * 0.42f
        drawRect(
            tint(InputButton.PRIMARY),
            topLeft = Offset(0f, 0f),
            size = Size(size.width / 2f, topHeight)
        )
        drawRect(
            tint(InputButton.SECONDARY),
            topLeft = Offset(size.width / 2f, 0f),
            size = Size(size.width / 2f, topHeight)
        )
        val wheelWidth = size.width * 0.16f
        drawRoundRect(
            color = tint(InputButton.MIDDLE),
            topLeft = Offset((size.width - wheelWidth) / 2f, topHeight * 0.18f),
            size = Size(wheelWidth, topHeight * 0.7f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(wheelWidth / 2f)
        )

        // The thumb buttons, on the near side.
        drawRoundRect(
            color = tint(InputButton.BACK),
            topLeft = Offset(-1f, size.height * 0.46f),
            size = Size(size.width * 0.12f, size.height * 0.1f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f)
        )
        drawRoundRect(
            color = tint(InputButton.FORWARD),
            topLeft = Offset(-1f, size.height * 0.60f),
            size = Size(size.width * 0.12f, size.height * 0.1f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f)
        )

        drawRoundRect(color = line, size = size, cornerRadius = body, style = DrawStroke(1.5f))
        drawLine(
            line,
            Offset(size.width / 2f, 0f),
            Offset(size.width / 2f, topHeight),
            strokeWidth = 1.5f
        )
        drawLine(line, Offset(0f, topHeight), Offset(size.width, topHeight), strokeWidth = 1.5f)
    }
}
