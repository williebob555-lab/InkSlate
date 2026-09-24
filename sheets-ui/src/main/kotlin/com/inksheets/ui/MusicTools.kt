package com.inksheets.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inksheets.core.Instruments
import com.inksheets.core.Metronome
import com.inksheets.core.Tuner
import kotlin.math.abs
import kotlin.math.roundToInt

/** One metronome for the whole app, so it keeps going while pages turn and dialogs close. */
internal object SharedMetronome {
    var engine: Metronome? = null
    var running by mutableStateOf(false)
    var beat by mutableStateOf(-1)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun MetronomeDialog(state: SheetsState, onClose: () -> Unit) {
    val out = state.platform.audioOut
    val engine = remember {
        SharedMetronome.engine ?: Metronome(out?.sampleRate ?: 48_000).also { SharedMetronome.engine = it }
    }
    var settings by remember { mutableStateOf(engine.settings) }
    val taps = remember { mutableStateListOf<Long>() }

    fun use(s: Metronome.Settings) {
        settings = s
        engine.settings = s
    }

    fun start() {
        if (out == null) return
        engine.reset()
        engine.onBeat = { SharedMetronome.beat = it }
        out.start { engine.fill(it) }
        SharedMetronome.running = true
    }

    fun stop() {
        out?.stop()
        SharedMetronome.running = false
        SharedMetronome.beat = -1
    }

    SheetDialog(title = "Metronome", onDismiss = onClose) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            // The beat, big enough to catch from the corner of an eye over a loud band.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(settings.beatsPerBar) { b ->
                    val lit = SharedMetronome.running && SharedMetronome.beat == b
                    val colour by animateColorAsState(
                        when {
                            lit && b == 0 -> MaterialTheme.colorScheme.primary
                            lit -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                    Box(Modifier.size(22.dp).background(colour, CircleShape))
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("${settings.bpm.roundToInt()}", fontSize = 56.sp, fontWeight = FontWeight.Light)
            Text("beats per minute", style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { use(settings.copy(bpm = (settings.bpm - 5).coerceAtLeast(20.0))) }) { Text("−5") }
                TextButton(onClick = { use(settings.copy(bpm = (settings.bpm - 1).coerceAtLeast(20.0))) }) { Text("−1") }
                TextButton(onClick = { use(settings.copy(bpm = (settings.bpm + 1).coerceAtMost(300.0))) }) { Text("+1") }
                TextButton(onClick = { use(settings.copy(bpm = (settings.bpm + 5).coerceAtMost(300.0))) }) { Text("+5") }
            }
            Slider(
                value = settings.bpm.toFloat(),
                onValueChange = { use(settings.copy(bpm = it.roundToInt().toDouble())) },
                valueRange = 20f..300f
            )
            Text("Beats in a bar", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(2, 3, 4, 5, 6, 7).forEach { n ->
                    FilterChip(selected = settings.beatsPerBar == n, onClick = { use(settings.copy(beatsPerBar = n)) }, label = { Text("$n") })
                }
            }
            Text("Clicks per beat", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1 to "♩", 2 to "♫", 3 to "3", 4 to "♬").forEach { (n, label) ->
                    FilterChip(selected = settings.subdivision == n, onClick = { use(settings.copy(subdivision = n)) }, label = { Text(label) })
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    val now = System.currentTimeMillis()
                    taps += now
                    while (taps.size > 12) taps.removeAt(0)
                    Metronome.tapTempo(taps)?.let { use(settings.copy(bpm = it.roundToInt().toDouble().coerceIn(20.0, 300.0))) }
                }) { Text("Tap") }
                if (SharedMetronome.running) {
                    Button(onClick = { stop() }) { Text("Stop") }
                } else {
                    Button(onClick = { start() }, enabled = out != null) { Text("Start") }
                }
            }
            if (out == null) Text("No sound output on this device.", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
internal fun TunerDialog(state: SheetsState, onClose: () -> Unit) {
    val mic = state.platform.microphone
    // Name notes as the chosen instrument reads them: a treble-clef baritone sees C, not B-flat.
    val instrument = state.profile?.instruments?.firstOrNull()?.let { Instruments.byId[it] }
    var transposed by remember { mutableStateOf(true) }
    var hz by remember { mutableStateOf<Double?>(null) }
    var listening by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var a4 by remember { mutableStateOf(440.0) }

    DisposableEffect(mic) {
        if (mic != null) {
            val window = Tuner.windowFor(mic.sampleRate)
            var smoothed: Double? = null
            listening = mic.start(window) { block ->
                val reading = Tuner.detect(block, mic.sampleRate)
                if (reading == null || reading.clarity < 0.75) return@start
                // A little smoothing, so the needle settles rather than shivers.
                val s = smoothed?.let { prev ->
                    if (abs(1200 * kotlin.math.log2(reading.hz / prev)) > 60) reading.hz else prev * 0.6 + reading.hz * 0.4
                } ?: reading.hz
                smoothed = s
                hz = s
            }
            failed = !listening
        }
        onDispose { mic?.stop() }
    }
    val note = hz?.let { Tuner.note(it, if (transposed) instrument?.transpose ?: 0 else 0, a4) }

    SheetDialog(title = "Tuner", onDismiss = onClose) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            val n = note
            Text(
                n?.let { "${it.name}${it.octave}" } ?: "–",
                fontSize = 72.sp, fontWeight = FontWeight.Light, textAlign = TextAlign.Center
            )
            if (n != null && transposed && instrument != null && instrument.transpose != 0) {
                Text("concert ${n.concertName}", style = MaterialTheme.typography.labelMedium)
            }
            val cents = n?.cents ?: 0.0
            val inTune = n != null && abs(cents) < 5
            Needle(cents = if (n == null) null else cents, good = inTune)
            Text(
                when {
                    n == null -> if (failed) "The microphone could not be opened." else "Play a note"
                    inTune -> "In tune"
                    cents < 0 -> "${abs(cents).roundToInt()} cents flat"
                    else -> "${cents.roundToInt()} cents sharp"
                },
                color = if (inTune) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            if (instrument != null && instrument.transpose != 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = transposed, onClick = { transposed = true }, label = { Text("As ${instrument.name} reads") })
                    Spacer(Modifier.size(8.dp))
                    FilterChip(selected = !transposed, onClick = { transposed = false }, label = { Text("Concert pitch") })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("A = ${a4.roundToInt()} Hz")
                TextButton(onClick = { a4 -= 1 }) { Text("−") }
                TextButton(onClick = { a4 += 1 }) { Text("+") }
            }
            if (mic == null) Text("No microphone on this device.", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun Needle(cents: Double?, good: Boolean) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val mark = if (good) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    val centre = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 12.dp)) {
        val mid = size.width / 2
        drawLine(track, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 6f)
        drawLine(centre, Offset(mid, 0f), Offset(mid, size.height), strokeWidth = 2f)
        if (cents != null) {
            val x = mid + (cents.coerceIn(-50.0, 50.0) / 50.0 * mid).toFloat()
            drawLine(mark, Offset(x, 2f), Offset(x, size.height - 2f), strokeWidth = 10f)
        }
    }
}
