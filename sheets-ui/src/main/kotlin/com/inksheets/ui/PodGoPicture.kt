package com.inksheets.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.inksheets.core.ControlRef
import com.inksheets.core.Controllers
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/**
 * A Line 6 POD Go drawn as it lies on the floor, as a game controller's mapping screen has it:
 * each footswitch, the pedal, its toe switch and the volume knob lights when the unit says
 * something for it. Which message is which is the player's to say: tap a spot, press that control,
 * and what came is put there - then given any of the remote's actions.
 */
internal object PodGoPicture {
    enum class Kind { SWITCH, PEDAL, KNOB }

    /** A spot: [box] in fractions of the picture's width and height. */
    class Spot(val id: String, val label: String, val name: String, val kind: Kind, val box: Rect)

    /** Width over height. */
    const val ASPECT = 1.7f

    private fun fs(n: Int, col: Int, row: Int) =
        Spot("fs$n", "FS$n", "Footswitch $n (${if (row == 0) "top" else "bottom"} row)", Kind.SWITCH,
            Rect(0.04f + col * 0.175f, 0.57f + row * 0.20f, 0.20f + col * 0.175f, 0.77f + row * 0.20f))

    /** As on the unit (Line 6's cheat sheet): FS1-3 over FS4-6, MODE over TAP, the pedal to the right, VOLUME at the top. */
    val spots = listOf(
        fs(1, 0, 0), fs(2, 1, 0), fs(3, 2, 0), fs(4, 0, 1), fs(5, 1, 1), fs(6, 2, 1),
        Spot("mode", "MODE", "MODE", Kind.SWITCH, Rect(0.565f, 0.57f, 0.695f, 0.77f)),
        Spot("tap", "TAP", "TAP / Tuner", Kind.SWITCH, Rect(0.565f, 0.77f, 0.695f, 0.97f)),
        Spot("toe", "TOE", "The pedal's toe switch", Kind.SWITCH, Rect(0.73f, 0.04f, 0.96f, 0.21f)),
        Spot("exp", "EXP", "Expression pedal", Kind.PEDAL, Rect(0.73f, 0.23f, 0.96f, 0.96f)),
        Spot("vol", "VOL", "Volume knob", Kind.KNOB, Rect(0.55f, 0.03f, 0.69f, 0.31f))
    )

    // The unit is black whatever the app's theme.
    val BODY = Color(0xFF2A2C30)
    val PLATE = Color(0xFF383B40)
    val INK = Color(0xFFE4E6EA)
    val LIT = Color(0xFF5CC8FF)
    val SET = Color(0xFFB4B8BE)
    val UNSET = Color(0xFF6A6E74)
}

/** The picture, each spot lit as its control is heard; [selected] outlined. */
@Composable
private fun PodGoDrawing(hub: ControllerHub, selected: String?, widest: androidx.compose.ui.unit.Dp = 420.dp, onTap: (PodGoPicture.Spot) -> Unit) {
    // Lit for a moment: looked at again once that moment is over.
    var tick by remember { mutableStateOf(0) }
    val heardAt = hub.lastAt
    LaunchedEffect(heardAt) { if (heardAt > 0) { delay(450); tick++ } }
    val now = remember(tick, heardAt) { System.currentTimeMillis() }
    fun lit(s: PodGoPicture.Spot): Boolean {
        if (hub.placing == s.id) return now - heardAt < 400
        val c = hub.spots[s.id]?.control ?: return false
        return hub.lastHeard[c]?.let { now - it.first < 400 } == true
    }
    fun travel(s: PodGoPicture.Spot): Float? = hub.spots[s.id]?.control?.let { hub.lastHeard[it] }?.let { it.second / 127f }

    BoxWithConstraints(Modifier.widthIn(max = widest).fillMaxWidth().aspectRatio(PodGoPicture.ASPECT).clip(RoundedCornerShape(10.dp))) {
        val w = maxWidth; val h = maxHeight
        Canvas(Modifier.size(w, h)) { drawUnit() }
        val density = androidx.compose.ui.platform.LocalDensity.current
        for (s in PodGoPicture.spots) {
            val on = lit(s)
            // Drawn to the picture's size: a phone on its side shows it small.
            val tall = h * s.box.height
            val led = minOf(18.dp, tall * 0.42f)
            val words = with(density) { minOf(11.dp, tall * 0.26f).toSp() }
            val labelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = words, lineHeight = words * 1.15f)
            val placed = hub.spots[s.id] != null
            val ring = if (on) PodGoPicture.LIT else if (placed) PodGoPicture.SET else PodGoPicture.UNSET
            Box(
                Modifier.offset(w * s.box.left, h * s.box.top).size(w * s.box.width, h * s.box.height)
                    .clip(RoundedCornerShape(8.dp))
                    .then(if (s.id == selected) Modifier.border(2.dp, PodGoPicture.LIT, RoundedCornerShape(8.dp)).background(Color(0x22FFFFFF)) else Modifier)
                    .clickable { onTap(s) },
                contentAlignment = Alignment.Center
            ) {
                when (s.kind) {
                    PodGoPicture.Kind.SWITCH -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Canvas(Modifier.size(led)) {
                            drawCircle(Color(0xFF8E9297), radius = size.minDimension * 0.30f)
                            if (on) drawCircle(ring.copy(alpha = 0.45f), radius = size.minDimension * 0.5f)
                            drawCircle(ring, radius = size.minDimension * 0.44f, style = Stroke(size.minDimension * 0.12f))
                        }
                        Text(s.label, style = labelStyle, maxLines = 1, softWrap = false, color = if (placed || on) PodGoPicture.INK else PodGoPicture.UNSET)
                    }
                    PodGoPicture.Kind.PEDAL -> {
                        val t = travel(s)
                        Canvas(Modifier.matchParentSize().padding(6.dp)) {
                            // How far down the toe is: filled from the heel.
                            if (t != null) drawRoundRect(PodGoPicture.LIT.copy(alpha = if (on) 0.55f else 0.3f),
                                topLeft = Offset(0f, size.height * (1 - t)), size = Size(size.width, size.height * t), cornerRadius = CornerRadius(6f))
                            drawRoundRect(ring, cornerRadius = CornerRadius(6f), style = Stroke(2f))
                        }
                        // Its name over how far down it is, sized to the pedal.
                        val narrow = with(density) { minOf(11.dp, w * s.box.width * 0.16f).toSp() }
                        Text(s.label + (t?.let { "\n${Math.round(it * 100)}%" } ?: ""), maxLines = 2, softWrap = false,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = narrow, lineHeight = narrow * 1.15f,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                            color = if (placed || on) PodGoPicture.INK else PodGoPicture.UNSET)
                    }
                    PodGoPicture.Kind.KNOB -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val t = travel(s)
                        Canvas(Modifier.size(minOf(22.dp, tall * 0.55f))) {
                            val r = size.minDimension / 2
                            drawCircle(Color(0xFF1C1D20), radius = r)
                            drawCircle(ring, radius = r * 0.92f, style = Stroke(r * 0.16f))
                            // The pointer, from 7 o'clock round to 5.
                            val a = Math.toRadians(135.0 + 270.0 * (t ?: 0.5f))
                            drawLine(PodGoPicture.INK, center, center + Offset((cos(a) * r * 0.75f).toFloat(), (sin(a) * r * 0.75f).toFloat()), strokeWidth = r * 0.18f)
                        }
                        Text(s.label, style = labelStyle, maxLines = 1, softWrap = false, color = if (placed || on) PodGoPicture.INK else PodGoPicture.UNSET)
                    }
                }
            }
        }
    }
}

/** The body: the screen, its knobs and buttons, the footswitch plate and the pedal's tread. */
private fun DrawScope.drawUnit() {
    val w = size.width; val h = size.height
    fun r(l: Float, t: Float, rr: Float, b: Float) = Rect(l * w, t * h, rr * w, b * h)
    drawRoundRect(PodGoPicture.BODY, cornerRadius = CornerRadius(w * 0.025f))
    val screen = r(0.04f, 0.07f, 0.34f, 0.40f)
    drawRoundRect(Color(0xFF101317), screen.topLeft, screen.size, CornerRadius(4f))
    drawRoundRect(Color(0xFF1E3A4C), Offset(screen.left + 6f, screen.top + 6f), Size(screen.width - 12f, screen.height * 0.22f), CornerRadius(3f))
    for (i in 0 until 5) drawCircle(Color(0xFF1C1D20), radius = w * 0.022f, center = Offset(w * (0.07f + i * 0.06f), h * 0.48f))
    drawCircle(Color(0xFF1C1D20), radius = w * 0.026f, center = Offset(w * 0.415f, h * 0.15f))
    drawCircle(Color(0xFF1C1D20), radius = w * 0.022f, center = Offset(w * 0.415f, h * 0.36f))
    drawRoundRect(Color(0xFF4A4D52), Offset(w * 0.465f, h * 0.11f), Size(w * 0.05f, h * 0.08f), CornerRadius(3f))
    val plate = r(0.03f, 0.56f, 0.70f, 0.98f)
    drawRoundRect(PodGoPicture.PLATE, plate.topLeft, plate.size, CornerRadius(6f))
    val pedal = r(0.72f, 0.03f, 0.97f, 0.97f)
    drawRoundRect(Color(0xFF1C1D20), pedal.topLeft, pedal.size, CornerRadius(8f))
    var y = pedal.top + h * 0.26f
    while (y < pedal.bottom - 6f) { drawLine(Color(0xFF34363A), Offset(pedal.left + 8f, y), Offset(pedal.right - 8f, y), 2f); y += h * 0.05f }
}

/**
 * Settings for a POD Go on its picture: tap a spot, press or move that control, and give it what
 * to do. A game controller's mapping screen, for a foot.
 */
@Composable
internal fun PodGoDialog(state: SheetsState, onClose: () -> Unit) {
    val hub = state.controllers
    var selected by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    val spot = PodGoPicture.spots.firstOrNull { it.id == selected }
    val listening = spot != null && hub.placing == spot.id
    var chosen by remember(hub.placing) { mutableStateOf<ControlRef?>(null) }
    val heard = chosen ?: hub.placingHeard.firstOrNull()

    fun tap(s: PodGoPicture.Spot) {
        selected = s.id; picking = false
        if (hub.spots[s.id] == null) hub.place(s.id) else hub.cancelPlacing()
    }
    fun back() {
        when {
            picking -> picking = false
            listening -> { hub.cancelPlacing(); if (hub.spots[spot!!.id] == null) selected = null }
            else -> selected = null
        }
    }

    if (picking && spot != null) {
        ControllerActionDialog(state, sweeps = spot.kind != PodGoPicture.Kind.SWITCH,
            onPick = { action -> hub.bindSpot(spot.id, action); picking = false }, onDismiss = { picking = false })
        return
    }
    SheetDialog(
        title = when {
            spot == null -> "POD Go"
            else -> spot.name
        },
        onDismiss = { hub.cancelPlacing(); onClose() },
        wide = true,
        buttons = {
            when {
                spot == null -> TextButton(onClick = { hub.cancelPlacing(); onClose() }) { Text("Close") }
                listening -> {
                    TextButton(onClick = { back() }) { Text("Back") }
                    TextButton(onClick = { heard?.let { hub.keepPlaced(spot.id, it) } }, enabled = heard != null) { Text("Keep") }
                }
                else -> {
                    TextButton(onClick = { hub.clearSpot(spot.id); selected = null }) { Text("Take it off") }
                    TextButton(onClick = { hub.place(spot.id) }) { Text("Learn again") }
                    TextButton(onClick = { back() }) { Text("Back") }
                }
            }
        }
    ) {
        val details: @Composable () -> Unit = {
            when {
                spot == null -> Overview(state, onTap = { tap(it) })
                listening -> Listening(hub, spot, heard, onChoose = { chosen = it })
                else -> SpotDetails(state, spot, onAdd = { picking = true })
            }
        }
        // Each screen of it from its top: the picture in sight to tap.
        val scroll = remember(selected, listening) { androidx.compose.foundation.ScrollState(0) }
        BoxWithConstraints {
            // One control open on a short screen (a phone on its side): the picture smaller, so what
            // is said about the control is in sight under it.
            val widest = if (spot != null) minOf(420.dp, maxHeight * 0.48f * PodGoPicture.ASPECT) else 420.dp
            Column(Modifier.verticalScroll(scroll), horizontalAlignment = Alignment.CenterHorizontally) {
                PodGoDrawing(hub, selected, widest, onTap = { tap(it) })
                Spacer(Modifier.size(10.dp))
                Column(Modifier.fillMaxWidth()) { details() }
            }
        }
    }
}

/** Every spot: what is on it and what it does. */
@Composable
private fun Overview(state: SheetsState, onTap: (PodGoPicture.Spot) -> Unit) {
    val hub = state.controllers
    Text(
        if (hub.devices.isEmpty()) "Nothing plugged in. Plug the POD Go in by USB, then tap a switch, the pedal or the knob here and press or move it."
        else "Tap a switch, the pedal or the knob, then press or move it on the POD Go. Each lights here when the POD Go says something for it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    for (s in PodGoPicture.spots) {
        val does = hub.bindingsOf(s.id)
        Column(Modifier.fillMaxWidth().clickable { onTap(s) }.padding(vertical = 6.dp)) {
            Text(s.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                when {
                    hub.spots[s.id] == null -> "Not set: tap to set"
                    does.isEmpty() -> "Set, does nothing yet"
                    else -> does.joinToString(", ") { b ->
                        actionName(state, b.action) + when {
                            b.continuous || b.everyMessage || hub.spots[s.id]?.momentary == true -> ""
                            b.whenOff -> " (when it goes dark)"
                            else -> " (when it lights)"
                        }
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (does.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** Listening for the control to put on [spot]: what came, the likeliest first and chosen. */
@Composable
private fun Listening(hub: ControllerHub, spot: PodGoPicture.Spot, heard: ControlRef?, onChoose: (ControlRef) -> Unit) {
    Text(
        when (spot.kind) {
            PodGoPicture.Kind.SWITCH -> "Press ${spot.label} on the POD Go, once."
            PodGoPicture.Kind.PEDAL -> "Move the pedal heel to toe."
            PodGoPicture.Kind.KNOB -> "Turn the volume knob."
        },
        style = MaterialTheme.typography.bodyMedium
    )
    if (hub.placingHeard.isEmpty()) {
        Text(
            if (hub.devices.isEmpty()) "Nothing plugged in yet." else "Listening to " + hub.devices.joinToString(", ") + "...",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp)
        )
        return
    }
    Text(
        if (hub.placingHeard.size == 1) "Heard:" else "Heard these. The first is most likely it:",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
    for (c in hub.placingHeard.toList()) {
        Row(Modifier.fillMaxWidth().clickable { onChoose(c) }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = c == heard, onClick = { onChoose(c) })
            Text(ControllerHub.name(c) + "  ×" + hub.placingCount(c), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

/** What is on [spot], and what it does. */
@Composable
private fun SpotDetails(state: SheetsState, spot: PodGoPicture.Spot, onAdd: () -> Unit) {
    val hub = state.controllers
    val placed = hub.spots[spot.id] ?: return
    Text("Is " + ControllerHub.name(placed.control), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("Does", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 10.dp))
    val does = hub.bindingsOf(spot.id)
    if (does.isEmpty()) Text("Nothing yet.", style = MaterialTheme.typography.bodyMedium)
    for (b in does) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(actionName(state, b.action), modifier = Modifier.weight(1f))
            IconButton(onClick = { hub.remove(b) }) { Icon(Icons.Default.Close, contentDescription = "Remove") }
        }
        // A switch that lights at one press and goes dark at the next (the toe switch): each press,
        // or one way only - pressed twice, done once.
        if (!b.continuous && !placed.momentary && placed.control.kind == com.inksheets.core.ControlEvent.CC) {
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((label, every, off) in listOf(Triple("Every press", true, false), Triple("When it lights", false, false), Triple("When it goes dark", false, true))) {
                    androidx.compose.material3.FilterChip(
                        selected = b.everyMessage == every && (every || b.whenOff == off),
                        onClick = { hub.setWhen(b, every, off) },
                        label = { Text(label) }
                    )
                }
            }
        }
    }
    TextButton(onClick = onAdd) { Text(if (does.isEmpty()) "Give it an action..." else "Give it another action...") }
}
