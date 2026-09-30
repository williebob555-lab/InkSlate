package com.inksheets.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkslate.core.Perform
import java.io.File

/**
 * The music tools' own strip, down the side away from the action strip, in a lane of its own the
 * page is fitted beside - so the tools, like the strip, are never over the music. Opened from the
 * action strip's Music button (with "Read the music" on in Settings), closed with its X.
 */
@Composable
fun BoxScope.MusicStrip(state: SheetsState) {
    val open = ScoreTools.open
    LaunchedEffect(open) {
        state.platform.setMusicLane(open)
        Perform.recentre?.invoke()
    }
    if (!open) return
    val file = state.currentPath?.let { File(it) }
    // Read again whenever what was read changes (a part just read).
    val score = remember(state.currentPath, Transcriber.shown, Transcriber.busy) { ScoreTools.scoreHere(state) }
    var goingTo by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var sounds by remember { mutableStateOf(false) }
    var speeding by remember { mutableStateOf(false) }
    var printingFor by remember { mutableStateOf(false) }
    val named = true
    BoxWithConstraints(Modifier.matchParentSize()) {
        val btn = ((maxHeight - 150.dp) / 14 - 12.dp).coerceIn(26.dp, 40.dp)
        Surface(
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 3.dp,
            shadowElevation = 2.dp,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            modifier = Modifier.align(if (state.stripOnLeft) Alignment.CenterEnd else Alignment.CenterStart).padding(6.dp).width(60.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(vertical = 6.dp)
            ) {
                if (score == null) {
                    // Nothing to work from yet: reading the part is the one thing to do.
                    val busy = Transcriber.busy
                    StripButton(Icons.Default.MusicNote, "Read", "Read this part's music", btn, named, lit = busy != null) {
                        if (file != null && busy == null) Transcriber.read(state, file) { Perform.marksChanged() }
                    }
                    Text(busy ?: "Reads the notes off this part, once", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp,
                        lineHeight = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 4.dp))
                } else {
                    StripButton(Icons.Default.Visibility, "Clean", "Show the clean reading over the print", btn, named, lit = ScoreTools.underlay) {
                        ScoreTools.showUnderlay(!ScoreTools.underlay)
                    }
                    StripButton(Icons.Default.AutoFixHigh, "Clean up", "Clean-up pen: draw over bars to show them clean", btn, named, lit = ScoreTools.tool == ScoreTools.Tool.CLEAN) {
                        ScoreTools.choose(ScoreTools.Tool.CLEAN)
                    }
                    if (ScoreTools.canUndo(state)) {
                        StripButton(Icons.Default.Undo, "Undo", "Undo the last clean-up", btn, named) { ScoreTools.undoClean(state) }
                    }
                    HorizontalDivider(Modifier.width(28.dp).padding(vertical = 2.dp))
                    StripButton(Icons.Default.SelectAll, "Select", "Select bars: press and drag across them", btn, named, lit = ScoreTools.tool == ScoreTools.Tool.SELECT) {
                        ScoreTools.choose(ScoreTools.Tool.SELECT)
                    }
                    ScoreTools.selection?.let { sel ->
                        Text(if (sel.first == sel.last) "Bar ${sel.first}" else "Bars ${sel.first}-${sel.last}",
                            style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.clickable { ScoreTools.clearSelection() })
                    }
                    val playing = ScoreTools.playing
                    StripButton(if (playing != null) Icons.Default.Stop else Icons.Default.PlayArrow, if (playing != null) "Stop" else "Play",
                        "Play the bars selected, or from this page", btn, named, lit = playing != null) {
                        if (playing != null) ScoreTools.stop(state) else if (ScoreTools.band) ScoreTools.playBand(state) else ScoreTools.play(state)
                    }
                    // The band without you: the other parts played, yours left for you.
                    StripButton(Icons.Default.Groups, "Band", "Play the other parts, not yours", btn, named, lit = ScoreTools.band) { ScoreTools.band = !ScoreTools.band }
                    ScoreTools.bandReading?.let { Text("Reading $it", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, textAlign = TextAlign.Center) }
                    StripButton(Icons.Default.Repeat, "Loop", "Play round and round", btn, named, lit = ScoreTools.loop) { ScoreTools.loop = !ScoreTools.loop }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        StripButton(Icons.Default.FastForward, "Speed up", "Each time round a little faster, up to the tempo", btn, named, lit = ScoreTools.ramp && ScoreTools.loop) {
                            speeding = true
                        }
                        SpeedUpMenu(speeding) { speeding = false }
                    }
                    // Where it is, and how fast: always said, never guessed.
                    Text(
                        when {
                            playing != null -> "Bar ${playing.first}\n♩ ${playing.second}" + if (playing.third > 0) "\nround ${playing.third + 1}" else ""
                            else -> "♩ ${SharedMetronome.bpm.toInt()}"
                        },
                        style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, lineHeight = 11.sp, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { state.metronomeOpen = true }.padding(2.dp)
                    )
                    // Practise with the notes checked by ear: Listen following the music as read.
                    if (state.listenTurns) {
                        val checking = Listener.active && Listener.practising
                        StripButton(Icons.Default.Hearing, "Check", "Play along: the page turns with you and bars that sound off are marked", btn, named, lit = checking) {
                            if (checking) Listener.stop(state) else { Listener.offBars = emptyList(); ScoreTools.marksMoved(); Listener.start(state, practice = true) }
                        }
                        if (Listener.offBars.isNotEmpty()) {
                            Text("Sounded off: " + Listener.offBars.take(6).joinToString(), style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, lineHeight = 10.sp,
                                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 3.dp).clickable { ScoreTools.goTo(state, Listener.offBars.first()) })
                        }
                    }
                    HorizontalDivider(Modifier.width(28.dp).padding(vertical = 2.dp))
                    StripButton(Icons.Default.Tag, "Bar", "Go to a bar by its number", btn, named) { goingTo = true }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        StripButton(Icons.Default.LibraryMusic, "Sound", "Played as which instrument", btn, named, lit = ScoreTools.soundAs != null) { sounds = true }
                        SoundMenu(state, sounds) { sounds = false }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        StripButton(Icons.Default.MoreVert, "More", "Save as MIDI, the notes read", btn, named) { more = true }
                        DropdownMenu(more, onDismissRequest = { more = false }) {
                            DropdownMenuItem(text = { Text(if (ScoreTools.cues) "Hide cue notes" else "Cue notes before each entry") }, onClick = {
                                more = false
                                ScoreTools.showCues(state, !ScoreTools.cues)
                            })
                            DropdownMenuItem(text = { Text("Save as a MIDI file") }, onClick = {
                                more = false
                                if (file != null) ScoreTools.said = exportMidi(state, file, score)
                            })
                            DropdownMenuItem(text = { Text("Print it afresh (a clean PDF)") }, onClick = {
                                more = false
                                if (file != null) ScoreTools.said = cleanPrint(state, file, score)
                            })
                            DropdownMenuItem(text = { Text("Print it for another instrument...") }, onClick = {
                                more = false
                                printingFor = true
                            })
                            DropdownMenuItem(text = { Text("The notes read, and bars that may be wrong...") }, onClick = {
                                more = false
                                state.readMusicOpen = true
                            })
                            DropdownMenuItem(text = { Text("Read this part again") }, onClick = {
                                more = false
                                if (file != null) Transcriber.read(state, file, again = true) { Perform.marksChanged() }
                            })
                        }
                    }
                }
                ScoreTools.said?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, lineHeight = 10.sp, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 4.dp).clickable { ScoreTools.said = null })
                }
                StripButton(Icons.Default.Close, "Close", "Put the music tools away", btn, named) { ScoreTools.close(state) }
            }
        }
    }
    if (goingTo) GoToBar(state) { goingTo = false }
    if (printingFor && file != null && score != null) PrintFor(state, file, score) { printingFor = false }
}

/** The speed-up settings: from what share of the tempo, and how much faster each time round. */
@Composable
private fun SpeedUpMenu(open: Boolean, onDismiss: () -> Unit) {
    DropdownMenu(open, onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).width(240.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Speed up each time round", Modifier.weight(1f))
                androidx.compose.material3.Switch(checked = ScoreTools.ramp, onCheckedChange = { ScoreTools.ramp = it; if (it) ScoreTools.loop = true })
            }
            Text("Start at ${ScoreTools.rampFrom}% of the tempo", style = MaterialTheme.typography.bodySmall)
            Slider(value = ScoreTools.rampFrom.toFloat(), onValueChange = { ScoreTools.rampFrom = it.toInt() }, valueRange = 40f..95f, steps = 10)
            Text("${ScoreTools.rampStep} beats a minute faster each time", style = MaterialTheme.typography.bodySmall)
            Slider(value = ScoreTools.rampStep.toFloat(), onValueChange = { ScoreTools.rampStep = it.toInt() }, valueRange = 1f..10f, steps = 8)
            Text("Up to the metronome's tempo, then round at that.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Which instrument the bars are played as: the part's own, or another's. */
@Composable
private fun SoundMenu(state: SheetsState, open: Boolean, onDismiss: () -> Unit) {
    DropdownMenu(open, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("The part's own instrument") }, onClick = { ScoreTools.soundAs = null; onDismiss() })
        HorizontalDivider()
        for ((id, name) in listOf("piano" to "Piano", "flute" to "Flute", "clarinet" to "Clarinet", "alto-sax" to "Alto sax", "tenor-sax" to "Tenor sax",
            "trumpet" to "Trumpet", "horn" to "Horn", "trombone" to "Trombone", "tuba" to "Tuba", "violin" to "Violin", "cello" to "Cello", "electric-bass" to "Bass guitar")) {
            DropdownMenuItem(text = { Text(name + if (ScoreTools.soundAs == id) "  ✓" else "") }, onClick = { ScoreTools.soundAs = id; onDismiss() })
        }
    }
}

/** A bar number, and straight there. */
@Composable
private fun GoToBar(state: SheetsState, onDone: () -> Unit) {
    var text by remember { mutableStateOf("") }
    fun go() { text.trim().toIntOrNull()?.let { if (ScoreTools.goTo(state, it)) onDone() } }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Go to bar") },
        text = {
            Column {
                OutlinedTextField(text, { text = it.filter(Char::isDigit).take(4) }, singleLine = true, label = { Text("Bar number") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { go() }))
                ScoreTools.said?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { go() }) { Text("Go") } },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } }
    )
}

/** The part engraved afresh from its reading, as a PDF beside the app's shared files, handed to the system to open or send. */
private fun cleanPrint(state: SheetsState, file: File, score: com.inksheets.core.omr.Score, forName: String? = null): String {
    val song = state.current
    val own = state.partShown()?.let { com.inksheets.core.Instruments.partName(it) } ?: file.nameWithoutExtension
    val part = forName?.let { "$it (from $own)" } ?: own
    val out = File(state.platform.cacheFolder, "${file.nameWithoutExtension} (${forName ?: "clean"}).pdf")
    return runCatching {
        val bytes = com.inksheets.core.omr.CleanPrint.pdf(score, song?.title ?: file.nameWithoutExtension, part)
        out.parentFile?.mkdirs()
        out.writeBytes(bytes)
        state.platform.share(out)
        "Printed ${score.measures.size} bars afresh: ${out.name}"
    }.getOrElse { "Couldn't print it: ${it.message}" }
}

/** The part re-written for another instrument - sounding the same - and printed afresh. */
@Composable
private fun PrintFor(state: SheetsState, file: File, score: com.inksheets.core.omr.Score, onDone: () -> Unit) {
    val own = state.partShown()?.instrument?.let { com.inksheets.core.PartChoice.seat(it).first }?.let { com.inksheets.core.Instruments.byId[it] }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Print it for...") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                Text("Re-written so it sounds the same, in the other instrument's key.", style = MaterialTheme.typography.bodySmall)
                for (ins in com.inksheets.core.Instruments.byId.values.sortedBy { it.name }) {
                    Text(ins.name, Modifier.fillMaxWidthClickable {
                        val moved = com.inksheets.core.omr.Transpose.forInstrument(score, own?.transpose ?: 0, ins.transpose)
                        ScoreTools.said = cleanPrint(state, file, moved, ins.name)
                        onDone()
                    }.padding(vertical = 8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Cancel") } }
    )
}

private fun Modifier.fillMaxWidthClickable(onClick: () -> Unit): Modifier = this.fillMaxWidth().clickable(onClick = onClick)
