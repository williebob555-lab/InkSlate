package com.inksheets.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inksheets.core.ControlBinding
import com.inksheets.core.Controllers
import com.inksheets.core.RemoteButton

/**
 * Settings for controllers: what is plugged in, what it last sent, and what each of its
 * switches, pedals and faders does - given by choosing an action, then pressing or moving it.
 */
@Composable
internal fun ControllerSettings(state: SheetsState) {
    val hub = state.controllers
    var adding by remember { mutableStateOf(false) }
    var podGo by remember { mutableStateOf(false) }
    Text(
        "Controllers",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp)
    )
    Column(Modifier.padding(horizontal = 16.dp)) {
        if (!hub.available) {
            Text("Not available on this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Row(Modifier.fillMaxWidth().clickable { hub.turn(!hub.on) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Pedals, switches and faders")
                Text(
                    "A foot controller sending MIDI, or a POD Go by USB: give its switches any of the remote's buttons, " +
                        "and its pedals the tempo or a recording's volume or speed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = hub.on, onCheckedChange = { hub.turn(it) })
        }
        if (!hub.on) return@Column
        Text(
            if (hub.devices.isEmpty()) "Nothing plugged in." else "Plugged in: " + hub.devices.joinToString(", "),
            style = MaterialTheme.typography.labelMedium,
            color = if (hub.devices.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
        )
        hub.recent.firstOrNull()?.let {
            Text("Last: " + it.describe(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for (b in hub.bindings.toList()) BindingRow(state, b, onRemove = { hub.remove(b) })
        TextButton(onClick = { podGo = true }) { Text("POD Go: set up on its picture...") }
        TextButton(onClick = { adding = true }) { Text("Add a control...") }
    }
    if (adding) AddControlDialog(state, onClose = { adding = false })
    if (podGo) PodGoDialog(state, onClose = { podGo = false })
}

@Composable
private fun BindingRow(state: SheetsState, b: ControlBinding, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(actionName(state, b.action), maxLines = 1, overflow = TextOverflow.Ellipsis)
            // A switch: tap to change when it counts - when pressed, at every message, or when let go.
            Text(
                ControllerHub.name(b.control) + when {
                    b.continuous -> " · moved across its range"
                    b.everyMessage -> " · every message"
                    b.whenOff -> " · when let go"
                    else -> " · when pressed"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (b.continuous) Modifier else Modifier.clickable {
                    when {
                        b.everyMessage -> state.controllers.setWhen(b, every = false, off = true)
                        b.whenOff -> state.controllers.setWhen(b, every = false, off = false)
                        else -> state.controllers.setWhen(b, every = true, off = false)
                    }
                }
            )
        }
        IconButton(onClick = onRemove) { Icon(Icons.Default.Close, contentDescription = "Remove") }
    }
}

internal fun actionName(state: SheetsState, b: RemoteButton): String =
    b.label ?: SWEEP_OFFERS.firstOrNull { it.button == b }?.name ?: defaultName(b, null, RemoteControl.listing(state))

@Composable
private fun AddControlDialog(state: SheetsState, onClose: () -> Unit) {
    val hub = state.controllers
    val learning = hub.learning
    if (learning != null) {
        val learned = hub.learned
        SheetDialog(
            title = "Press or move it now",
            onDismiss = { hub.cancelLearning(); onClose() },
            buttons = {
                TextButton(onClick = { hub.cancelLearning() }) { Text("Back") }
                TextButton(onClick = { hub.keepLearned(); onClose() }, enabled = learned != null) { Text("Keep") }
            }
        ) {
            Column {
                Text(actionName(state, learning), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (Controllers.isSweep(learning)) "Move the pedal or fader that should set it, end to end."
                    else "Press the switch that should do it, once, and let go.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    learned?.let { "Heard: " + ControllerHub.name(it.control) + if (it.everyMessage) " (one message a press)" else "" }
                        ?: if (hub.devices.isEmpty()) "Nothing plugged in yet." else "Listening to " + hub.devices.joinToString(", ") + "...",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
        return
    }
    ControllerActionDialog(state, sweeps = null, onPick = { hub.learn(it) }, onDismiss = onClose)
}
