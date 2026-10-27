package com.inksheets.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkslate.core.Perform
import java.io.File

private data class ScoreAction(
    val title: String,
    val detail: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val action: () -> Unit,
    val popup: (@Composable () -> Unit)? = null
)

/** A complete tool directory, rather than a second permanent strip stealing score space.
 * Closing this directory preserves the selected page tool and playback state.
 */
@Composable
internal fun ScoreToolsPanel(state: SheetsState, onClose: () -> Unit) {
    val file = state.currentPath?.let(::File)
    val here = state.pageShown.first
    val score = remember(state.currentPath, Transcriber.shown, Transcriber.busy) { ScoreTools.scoreHere(state) }
    val busy = Transcriber.busy
    val ready = score?.hasRead(here) == true
    val available = score != null && score.measures.isNotEmpty()
    val older = remember(file, Transcriber.shown, busy) { file?.let { Transcriber.olderPages(state, it) }.orEmpty() }
    var sounds by remember { mutableStateOf(false) }
    var speeding by remember { mutableStateOf(false) }
    var printingFor by remember { mutableStateOf(false) }
    var bar by remember { mutableStateOf("") }

    fun usePage(action: () -> Unit) {
        ScoreTools.open = true
        action()
        onClose()
    }
    fun read(pages: Set<Int>? = null, again: Boolean = false) {
        if (file != null) Transcriber.read(state, file, again = again, pages = pages) { Perform.marksChanged() }
    }
    fun go(play: Boolean) {
        val number = bar.toIntOrNull() ?: return
        if (ScoreTools.goTo(state, number)) usePage {
            if (play) {
                if (ScoreTools.bandMode || ScoreTools.band) ScoreTools.playBand(state, number) else ScoreTools.play(state, number)
            }
        }
    }
    if (printingFor && file != null && score != null) {
        PrintFor(state, file, score) { printingFor = false }
        return
    }
    SheetDialog(title = "Score tools", wide = true, onDismiss = onClose,
        buttons = { TextButton(onClick = onClose) { Text("Done") } }) {
        Column(Modifier.verticalScroll(rememberScrollState()).testTag("score-tools-body"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val statusColor = when { busy != null -> MaterialTheme.colorScheme.primary; ready -> Color(0xFF2C8A6B); else -> MaterialTheme.colorScheme.tertiary }
            Surface(color = statusColor.copy(alpha = .09f), shape = RoundedCornerShape(10.dp)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(if (busy != null) Icons.Default.HourglassTop else if (ready) Icons.Default.CheckCircle else Icons.Default.MenuBook,
                        null, tint = statusColor)
                    Column(Modifier.weight(1f)) {
                        Text(when { busy != null -> busy; ready -> "Page ${here + 1} is read"; else -> "Page ${here + 1} is not read yet" },
                            style = MaterialTheme.typography.titleSmall, color = statusColor)
                        Text(when {
                            file == null -> "Open a part to use score tools."
                            busy != null -> "Stopping keeps pages already read."
                            !ready -> "Read this page to review, clean or practise its music."
                            else -> "${score?.readPages?.size ?: score?.pages} of ${score?.pages} pages read · your corrections are kept"
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (busy != null) TextButton(onClick = { Transcriber.stopAsked = true }, enabled = !Transcriber.stopAsked) {
                        Text(if (Transcriber.stopAsked) "Stopping" else "Stop")
                    }
                }
            }
            ScoreSection("Read & review", Icons.Default.Spellcheck, MaterialTheme.colorScheme.primary) {
                ScoreActionGrid(listOf(
                    ScoreAction("Read page ${here + 1}", "This page only", Icons.Default.Description,
                        enabled = file != null && busy == null, action = { read(setOf(here)) }),
                    ScoreAction("Read whole part", "Every page in this file", Icons.Default.MenuBook,
                        enabled = file != null && busy == null, action = { read() }),
                    ScoreAction("Review reading", "Colour confidence; tap a bar to correct it", Icons.Default.Spellcheck,
                        enabled = ready && busy == null, selected = ScoreTools.colours,
                        action = { usePage { ScoreTools.toggleCheck(state) } }),
                    ScoreAction("Clean view", "Show or hide the cleaned notation", Icons.Default.AutoFixHigh,
                        enabled = ready, selected = ScoreTools.cleanEnabled(state), action = { ScoreTools.toggleClean(state) }),
                    ScoreAction("Clean whole part", "Redraw every read bar, including outside your brush mask", Icons.Default.AutoAwesome,
                        enabled = ready, selected = ScoreTools.cleanedWhole(state), action = { ScoreTools.cleanWhole(state, true) }),
                    ScoreAction("Mask brush", "Brush cleaned notation in; erase to restore print", Icons.Default.Brush,
                        enabled = ready, selected = ScoreTools.maskBrushEnabled,
                        action = { usePage { ScoreTools.toggleMaskBrush(state) } }),
                    ScoreAction("Select bars", ScoreTools.selection?.let { "Selected ${it.first}–${it.last} · tap to return" } ?: "Drag across the bars to work on",
                        Icons.Default.SelectAll, enabled = ready, selected = ScoreTools.tool == ScoreTools.Tool.SELECT,
                        action = { usePage { ScoreTools.choose(ScoreTools.Tool.SELECT) } })
                ))
                ScoreTools.selection?.let { selection ->
                    TextButton(onClick = { ScoreTools.clearSelection() }) { Text("Clear selection ${selection.first}–${selection.last}") }
                }
                if (older.isNotEmpty()) Text("${older.size} pages have an older reading" + if (here in older) ", including this page." else ".",
                    color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
            }
            ScoreSection("Practice", Icons.Default.PlayArrow, Color(0xFF2C8A6B)) {
                val playing = ScoreTools.playing != null
                val listening = Listener.active
                ScoreActionGrid(listOf(
                    ScoreAction(if (playing) "Stop playback" else "Play", "Selected bars, or from this page", if (playing) Icons.Default.Stop else Icons.Default.PlayArrow,
                        enabled = ready || playing, selected = playing,
                        action = { if (playing) ScoreTools.stop(state) else usePage { if (ScoreTools.band) ScoreTools.playBand(state) else ScoreTools.play(state) } }),
                    ScoreAction("Band accompaniment", "Other parts play alongside you", Icons.Default.Groups,
                        enabled = ready, selected = ScoreTools.band, action = { ScoreTools.loadBandWithMe(state); ScoreTools.band = !ScoreTools.band }),
                    ScoreAction("Include my part", if (ScoreTools.bandWithMe) "Your part plays with the band" else "Leave your part for you to play", Icons.Default.Person,
                        enabled = ready && ScoreTools.band, selected = ScoreTools.bandWithMe,
                        action = { ScoreTools.chooseBandWithMe(state, !ScoreTools.bandWithMe) }),
                    ScoreAction("Loop", "Repeat the chosen passage", Icons.Default.Repeat,
                        enabled = ready, selected = ScoreTools.loop, action = { ScoreTools.loop = !ScoreTools.loop }),
                    ScoreAction("Tempo ramp", "${ScoreTools.rampFrom}% start · +${ScoreTools.rampStep} BPM each loop", Icons.Default.FastForward,
                        enabled = ready, selected = ScoreTools.ramp && ScoreTools.loop, action = { speeding = true },
                        popup = { SpeedUpMenu(speeding) { speeding = false } }),
                    ScoreAction("Playback sound", ScoreTools.soundAs?.removePrefix(SampledInstruments.PICKED) ?: "The part's own instrument", Icons.Default.LibraryMusic,
                        enabled = available, selected = ScoreTools.soundAs != null, action = { sounds = true },
                        popup = { SoundMenu(state, sounds) { sounds = false } }),
                    ScoreAction("Follow my playing", "Turn pages by listening to your instrument", Icons.Default.Hearing,
                        enabled = ready && state.listenTurns, selected = listening && !Listener.practising,
                        action = { if (listening && !Listener.practising) Listener.stop(state) else usePage { Listener.start(state) } }),
                    ScoreAction("Check my playing", if (state.listenTurns) "Follow along and mark bars that sound off" else "Enable listening page turns in Settings", Icons.Default.FactCheck,
                        enabled = ready && state.listenTurns, selected = listening && Listener.practising,
                        action = { if (listening && Listener.practising) Listener.stop(state) else usePage {
                            Listener.offBars = emptyList(); ScoreTools.marksMoved(); Listener.start(state, practice = true)
                        } })
                ), accent = Color(0xFF2C8A6B))
                ScoreTools.bandReading?.let { Text("Reading $it", style = MaterialTheme.typography.bodySmall) }
                if (Listener.offBars.isNotEmpty()) {
                    Text("Bars that sounded off", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Listener.offBars.forEach { number -> AssistChip(onClick = { usePage { ScoreTools.goTo(state, number) } }, label = { Text("Bar $number") }) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(bar, { bar = it.filter(Char::isDigit).take(5) }, label = { Text("Go to bar") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f).testTag("score-go-bar"), enabled = available)
                    TextButton(enabled = available && bar.toIntOrNull() != null, onClick = { go(false) }) { Text("Go") }
                    TextButton(enabled = available && bar.toIntOrNull() != null, onClick = { go(true) }) { Text("Play") }
                }
            }
            ScoreSection("Arrange & export", Icons.Default.IosShare, MaterialTheme.colorScheme.tertiary) {
                ScoreActionGrid(listOf(
                    ScoreAction("Cue notes", "Show notes before each entry", Icons.Default.MusicNote, enabled = available,
                        selected = ScoreTools.cues, action = { ScoreTools.showCues(state, !ScoreTools.cues) }),
                    ScoreAction("Save MIDI", "Export the notes read from this part", Icons.Default.Piano, enabled = available,
                        action = { if (file != null && score != null) ScoreTools.said = exportMidi(state, file, score) }),
                    ScoreAction("Clean PDF", "Print the notation afresh", Icons.Default.PictureAsPdf, enabled = available,
                        action = { if (file != null && score != null) ScoreTools.said = cleanPrint(state, file, score) }),
                    ScoreAction("PDF for another instrument", "Transpose and print for a different instrument", Icons.Default.SwapHoriz, enabled = available,
                        action = { printingFor = true }),
                    ScoreAction("Reading details", "Notes read and bars worth checking", Icons.Default.FindInPage, enabled = available,
                        action = { state.readMusicOpen = true; onClose() }),
                    ScoreAction("Read page ${here + 1} again", "Refresh this page; keep manual corrections", Icons.Default.Refresh,
                        enabled = file != null && busy == null, action = { read(setOf(here), again = true) }),
                    ScoreAction("Read part again", "Refresh every page; keep manual corrections", Icons.Default.RestartAlt,
                        enabled = file != null && busy == null, action = { read(again = true) })
                ) + if (older.isNotEmpty()) listOf(ScoreAction("Update older readings", "Read all ${older.size} older pages with the newer reader", Icons.Default.Update,
                    enabled = file != null && busy == null, action = { read(older) })) else emptyList(), accent = MaterialTheme.colorScheme.tertiary)
            }
            ScoreTools.said?.let { message ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(8.dp)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { ScoreTools.said = null }) { Icon(Icons.Default.Close, "Dismiss message") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScoreSection(title: String, icon: ImageVector, accent: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(19.dp), tint = accent)
            Text(title, style = MaterialTheme.typography.titleSmall, color = accent)
            HorizontalDivider(Modifier.weight(1f), color = accent.copy(alpha = .2f))
        }
        content()
    }
}

@Composable
private fun ScoreActionGrid(actions: List<ScoreAction>, accent: Color = MaterialTheme.colorScheme.primary) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // A 640dp phone in landscape has about 584dp inside the dialog. Two columns there
        // keep useful tools visible rather than spending the whole viewport on two read rows.
        val columns = if (maxWidth >= 520.dp) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            actions.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { action -> Box(Modifier.weight(1f)) {
                        ScoreActionRow(action, accent)
                        action.popup?.invoke()
                    } }
                    if (row.size < columns) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ScoreActionRow(action: ScoreAction, accent: Color) {
    val ink = if (action.enabled) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
    Surface(shape = RoundedCornerShape(8.dp), color = if (action.selected) accent.copy(alpha = .1f) else Color.Transparent) {
        Row(Modifier.fillMaxWidth().clickable(enabled = action.enabled, onClick = action.action)
            .testTag("score-action-${action.title}").padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(action.icon, null, Modifier.size(22.dp), tint = ink)
            Column(Modifier.weight(1f)) {
                Text(action.title, style = MaterialTheme.typography.titleSmall, color = if (action.enabled) MaterialTheme.colorScheme.onSurface else ink)
                Text(action.detail, style = MaterialTheme.typography.bodySmall, color = if (action.enabled) MaterialTheme.colorScheme.onSurfaceVariant else ink,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (action.selected) Icon(Icons.Default.Check, "Active", Modifier.size(17.dp), tint = ink)
        }
    }
}
