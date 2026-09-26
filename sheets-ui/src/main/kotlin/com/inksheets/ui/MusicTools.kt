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
    /** The tempo, shown on the strip beside the metronome button. */
    var bpm by mutableStateOf(com.inksheets.core.Metronome.Settings().bpm)
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
        SharedMetronome.bpm = s.bpm
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

    FloatingPanel(title = "Metronome", onClose = onClose) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            // The beat, big enough to catch from the corner of an eye over a loud band.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(settings.beatsPerBar) { b ->
                    val lit = SharedMetronome.running && SharedMetronome.beat == b
                    val colour by animateColorAsState(
                        when {
                            lit && b == 0 -> MaterialTheme.colorScheme.primary
                            lit -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                    Box(Modifier.size(16.dp).background(colour, CircleShape))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { use(settings.copy(bpm = (settings.bpm - 1).coerceAtLeast(20.0))) }) { Text("−") }
                Text("${settings.bpm.roundToInt()}", fontSize = 36.sp, fontWeight = FontWeight.Light)
                TextButton(onClick = { use(settings.copy(bpm = (settings.bpm + 1).coerceAtMost(300.0))) }) { Text("+") }
            }
            Slider(
                value = settings.bpm.toFloat(),
                onValueChange = { use(settings.copy(bpm = it.roundToInt().toDouble())) },
                valueRange = 20f..300f
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Beats", style = MaterialTheme.typography.labelMedium)
                listOf(2, 3, 4, 6).forEach { n ->
                    FilterChip(selected = settings.beatsPerBar == n, onClick = { use(settings.copy(beatsPerBar = n)) }, label = { Text("$n") })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(1 to "♩", 2 to "♫", 3 to "3", 4 to "♬").forEach { (n, label) ->
                    FilterChip(selected = settings.subdivision == n, onClick = { use(settings.copy(subdivision = n)) }, label = { Text(label) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
    val instrument = state.profile?.instruments?.firstOrNull()?.let { Instruments.byId[com.inksheets.core.PartChoice.seat(it).first] }
    var transposed by remember { mutableStateOf(true) }
    var hz by remember { mutableStateOf<Double?>(null) }
    var listening by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var a4 by remember { mutableStateOf(440.0) }

    // The first time on the tablet, opening the microphone asks for permission and fails; it is
    // tried again every moment until the answer is yes, so allowing it starts the tuner.
    var attempt by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(failed, attempt) {
        if (failed) {
            kotlinx.coroutines.delay(1_200)
            attempt++
        }
    }
    DisposableEffect(mic, attempt) {
        if (mic != null) {
            val size = Tuner.windowFor(mic.sampleRate)
            val window = FloatArray(size)
            var sinceLast = 0
            var smoothed: Double? = null
            listening = mic.start { chunk ->
                // Slide the window along; look again every quarter of a window.
                val n = chunk.size.coerceAtMost(size)
                System.arraycopy(window, n, window, 0, size - n)
                System.arraycopy(chunk, chunk.size - n, window, size - n, n)
                sinceLast += chunk.size
                if (sinceLast < size / 4) return@start
                sinceLast = 0
                val reading = Tuner.detect(window.copyOf(), mic.sampleRate)
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

    FloatingPanel(title = "Tuner", onClose = onClose) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            val n = note
            Text(
                n?.let { "${it.name}${it.octave}" } ?: "–",
                fontSize = 48.sp, fontWeight = FontWeight.Light, textAlign = TextAlign.Center
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
