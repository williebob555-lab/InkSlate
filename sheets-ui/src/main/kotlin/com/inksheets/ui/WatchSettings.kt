package com.inksheets.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inksheets.core.watch.FlickTrainer

/**
 * Settings for turning pages with the watch (experimental): on or off, how the watch is, the
 * calibrations kept (one per instrument) and the one in use, and the last flick and what it did.
 */
@Composable
internal fun WatchSettings(state: SheetsState) {
    val watch = state.watch
    var naming by remember { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf<String?>(null) }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = watch.available) { watch.turn(!watch.on) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Turn pages with a flick of the watch (experimental)")
            Text(
                "A Wear OS watch (a Galaxy Watch) paired with this phone: flick the wrist one way for the next page, the other way to go back. " +
                    "The page turns here - or on the tablet, while this phone is its remote. Calibrate first, playing your instrument: " +
                    "it learns your flicks and your playing, so the playing is never taken for a flick.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = watch.on, enabled = watch.available, onCheckedChange = { watch.turn(it) })
    }
    if (!watch.available) {
        Text("Not available on this device - it needs Android with a watch paired.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    if (!watch.on) return
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 8.dp)) {
        // Asked again now and then while this is in sight, so what it says is current.
        LaunchedEffect(Unit) { while (true) { watch.look(); kotlinx.coroutines.delay(5_000) } }
        val hello = watch.hello?.takeIf { System.currentTimeMillis() - watch.helloAt < 30_000 }
        val status = when {
            watch.lookedAt == 0L -> "Looking for the watch..."
            watch.watches.isEmpty() -> "No watch connected to this phone."
            hello == null -> "${watch.watches.first()}: no answer from InkSheets on it. Install the InkSheets watch app (from the same release page as this app), then open it once."
            hello.calibrating -> "${watch.watches.first()}: calibrating."
            !hello.listening -> "${watch.watches.first()}: not listening. Tap Listen on the watch, or here."
            hello.model == null -> "${watch.watches.first()}: listening, but not calibrated yet."
            else -> "${watch.watches.first()}: listening, calibrated for ${hello.model}."
        }
        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        if (hello != null && !hello.calibrating) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { watch.listen(!hello.listening) }) { Text(if (hello.listening) "Stop the watch listening" else "Listen") }
            }
        }
        watch.lastFlick?.let { f ->
            val ago = ((System.currentTimeMillis() - f.at) / 1000).coerceAtLeast(0)
            Text(
                "Last flick: ${if (f.next) "next page" else "back"} - ${f.result} (${if (ago < 60) "${ago}s" else "${ago / 60} min"} ago)",
                style = MaterialTheme.typography.labelSmall
            )
        }

        Text("Calibrations", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        if (watch.calibrations.isEmpty()) {
            Text("None yet. Each takes about three minutes of playing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(
                "The one in use follows the part showing: a trombone part, the trombone's calibration. Or pick one here.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            watch.followed?.let { Text("Part showing is for $it - using ${watch.active ?: "none"}.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
        }
        watch.calibrations.forEach { name ->
            Row(Modifier.fillMaxWidth().clickable { watch.use(name) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = watch.active == name, onClick = { watch.use(name) })
                Column(Modifier.weight(1f)) {
                    Text(name)
                    val ids = watch.instrumentsOf(name)
                    Text(
                        if (ids.isEmpty()) "For no instrument - chosen by hand only" else "For " + ids.joinToString(", ") { com.inksheets.core.Instruments.byId[it]?.name ?: it },
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { watch.delete(name) }) { Icon(Icons.Filled.Delete, contentDescription = "Delete $name") }
            }
            Row(Modifier.padding(start = 40.dp)) {
                TextButton(onClick = { choosing = name }) { Text("Instruments...") }
                TextButton(onClick = { naming = null; watch.calibrate(name) }) { Text("Calibrate more") }
            }
        }
        watch.report?.takeIf { watch.active != null }?.let { ReportText(it) }
        OutlinedButton(onClick = { naming = "" }, modifier = Modifier.padding(top = 4.dp)) { Text("New calibration...") }
    }
    choosing?.let { name ->
        var ids by remember(name) { mutableStateOf(watch.instrumentsOf(name)) }
        SheetDialog(
            title = "$name is for",
            onDismiss = { choosing = null },
            buttons = {
                TextButton(onClick = { choosing = null }) { Text("Cancel") }
                TextButton(onClick = { watch.setInstruments(name, ids); choosing = null }) { Text("Keep") }
            }
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Parts for these use this calibration.", style = MaterialTheme.typography.bodyMedium)
                InstrumentChips(state, ids) { ids = it }
            }
        }
    }
    naming?.let { start ->
        var name by remember { mutableStateOf(start) }
        var ids by remember { mutableStateOf(emptyList<String>()) }
        SheetDialog(
            title = "Calibrate for which instrument?",
            onDismiss = { naming = null },
            buttons = {
                TextButton(onClick = { naming = null }) { Text("Cancel") }
                TextButton(onClick = {
                    naming = null
                    watch.setInstruments(name.trim(), ids)
                    watch.calibrate(name)
                }, enabled = name.isNotBlank()) { Text("Next") }
            }
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Each instrument moves the arm its own way, so each gets its own calibration - used whenever a part for it is showing.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.padding(top = 8.dp))
                InstrumentChips(state, ids) { chosen ->
                    // The name follows the first instrument chosen, until it is typed over.
                    val auto = ids.firstOrNull()?.let { com.inksheets.core.Instruments.byId[it]?.name }
                    if (name.isBlank() || name == auto) name = chosen.firstOrNull()?.let { com.inksheets.core.Instruments.byId[it]?.name }.orEmpty()
                    ids = chosen
                }
            }
        }
    }
    watch.calibration?.let { CalibrationDialog(state, it) }
}

/** Instruments to tick, the player's own (their instrument choice) first; [onChange] hears the ids ticked. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun InstrumentChips(state: SheetsState, chosen: List<String>, onChange: (List<String>) -> Unit) {
    val mine = state.profile?.instruments.orEmpty()
    val all = com.inksheets.core.Instruments.all.sortedBy { i -> mine.indexOf(i.id).let { if (it < 0) Int.MAX_VALUE else it } }
    Column(Modifier.padding(top = 8.dp)) {
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
            all.forEach { i ->
                androidx.compose.material3.FilterChip(
                    selected = i.id in chosen,
                    onClick = { onChange(if (i.id in chosen) chosen - i.id else chosen + i.id) },
                    label = { Text(i.name) }
                )
            }
        }
    }
}

/** How a calibration stands, in plain words. */
@Composable
private fun ReportText(r: FlickTrainer.Report) {
    val lines = buildList {
        if (r.model == null) add(r.problem ?: "It could not learn your flicks.")
        else {
            add("Learned from ${r.nextCues + r.backCues} cues and ${r.playingSeconds}s of playing.")
            add(
                "Replayed through what it learned: ${r.right} of ${r.nextCues + r.backCues} cues turned the right way" +
                    (if (r.wrong > 0) ", ${r.wrong} the wrong way" else "") +
                    (if (r.missed > 0) ", ${r.missed} missed" else "") +
                    ", and ${if (r.falseTurns == 0) "no" else r.falseTurns.toString()} turn${if (r.falseTurns == 1) "" else "s"} while just playing."
            )
            if (r.nextPlaying > 0f) add("Your slowest flick was ${"%.1f".format(r.margin)}× as fast as your fastest playing" +
                (if (r.margin >= 1.5f) " - clear." else if (r.margin >= 1.15f) " - close; flick sharply." else " - too close; flick harder."))
            if (r.model?.back == null) add("Back: not learned - only the next page will turn.")
            r.problem?.let { add(it) }
        }
    }
    Column(Modifier.padding(top = 4.dp)) {
        lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/**
 * Calibrating: play normally, and flick when cued - the watch buzzes once for the next page, twice
 * for back, and this screen says it in big letters too.
 */
@Composable
private fun CalibrationDialog(state: SheetsState, c: WatchFlicks.Calibration) {
    DisposableEffect(Unit) {
        state.platform.keepAwake(true)
        onDispose { state.platform.keepAwake(false) }
    }
    val running = c.phase !in setOf("done", "failed")
    SheetDialog(
        title = "Calibrating for ${c.name}",
        onDismiss = { state.watch.closeCalibration() },
        buttons = {
            if (running) TextButton(onClick = { state.watch.closeCalibration() }) { Text("Stop") }
            else {
                TextButton(onClick = { state.watch.calibrate(c.name) }) { Text("Calibrate again") }
                TextButton(onClick = { state.watch.closeCalibration() }) { Text("Done") }
            }
        }
    ) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            when (c.phase) {
                "starting" -> {
                    Text("Starting... Open InkSheets on the watch if it is not open.", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Then pick up your instrument. Play normally for about three minutes - real music, the way you play it on stage. " +
                            "When the watch buzzes once (and this says NEXT), flick for the next page. Two buzzes (BACK): flick the other way. " +
                            "Then keep playing.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                "playing", "next", "back" -> {
                    val (word, color) = when (c.phase) {
                        "next" -> "Flick: NEXT ▶" to Color(0xFF1B7A3A)
                        "back" -> "Flick: ◀ BACK" to Color(0xFFB35A00)
                        else -> "Play normally" to MaterialTheme.colorScheme.surfaceVariant
                    }
                    Box(
                        Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(12.dp)).background(color),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            word, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                            color = if (c.phase == "playing") MaterialTheme.colorScheme.onSurfaceVariant else Color.White
                        )
                    }
                    Text(
                        if (c.phase == "playing") "Next cue in ${c.secondsLeft}s" else "Then keep playing",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)
                    )
                    LinearProgressIndicator(progress = { c.progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Text("${c.heard} readings from the watch", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                "learning" -> Text("Learning your flicks...", style = MaterialTheme.typography.bodyLarge)
                "done" -> c.result?.let { r ->
                    Text(when {
                        r.model != null && r.problem == null -> "In use now - the watch has it."
                        r.model != null -> "Not put to use yet: it could take playing for a flick. Calibrate again to add more."
                        else -> "Not learned."
                    }, style = MaterialTheme.typography.titleMedium)
                    ReportText(r)
                    Text(
                        "\"Calibrate again\" adds another recording to this one: the more playing it has heard, the surer it is.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                "failed" -> Text(c.problem ?: "Stopped.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
