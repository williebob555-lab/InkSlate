package com.inksheets.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkslate.core.Perform
import com.inkslate.core.PerformAction
import java.io.File

internal enum class ShelfWidget(val label: String) { TIMING("Timing"), RECORDING("Recording"), SCORE("Score"), DEVICES("Devices") }

/** Device-local layout preferences. A missing/corrupt preference always leaves a usable shelf. */
internal class MusicShelfPreferences(private val platform: SheetsPlatform) {
    var order by mutableStateOf(decode(platform.pref("sheets_shelf_order")))
        private set
    var hidden by mutableStateOf(platform.pref("sheets_shelf_hidden").orEmpty().split(',').mapNotNull { value -> ShelfWidget.entries.firstOrNull { it.name == value } }.toSet())
        private set
    fun move(widget: ShelfWidget, by: Int) {
        val next = order.toMutableList(); val i = next.indexOf(widget); val to = (i + by).coerceIn(0, next.lastIndex)
        if (i == to) return
        next.add(to, next.removeAt(i)); order = next
        platform.setPref("sheets_shelf_order", next.joinToString(",") { it.name })
    }
    fun show(widget: ShelfWidget, on: Boolean) {
        hidden = if (on) hidden - widget else hidden + widget
        platform.setPref("sheets_shelf_hidden", hidden.joinToString(",") { it.name })
    }
    fun reset() { order = ShelfWidget.entries.toList(); hidden = emptySet(); platform.setPref("sheets_shelf_order", null); platform.setPref("sheets_shelf_hidden", null) }
    companion object {
        fun decode(raw: String?): List<ShelfWidget> = (raw.orEmpty().split(',').mapNotNull { v -> ShelfWidget.entries.firstOrNull { it.name == v } } + ShelfWidget.entries).distinct()
    }
}

/** Bottom space is measured, not guessed: both renderers fit the page above exactly this area. */
@Composable
internal fun BoxScope.MusicShelf(state: SheetsState) {
    var measured by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val active = Recording.session || SharedMetronome.running || SelfRecorder.recording || ScoreTools.playing != null || ScoreTools.paused != null || Listener.active
    val visible = (!state.stripCollapsed || active) && !state.homeInFront
    DisposableEffect(state) { onDispose { state.shelfHeight = 0f; Overlays.bottomOut = 0.dp; state.platform.setMusicShelfHeight(0f) } }
    LaunchedEffect(visible, measured) {
        state.shelfHeight = if (visible) measured else 0f
        Overlays.bottomOut = if (visible) measured.dp else 0.dp
        state.platform.setMusicShelfHeight(if (visible) measured else 0f)
    }
    if (!visible) return
    var arrange by state::arrangingShelf
    var controllers by remember { mutableStateOf(false) }
    var goTo by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(0L) }
    LaunchedEffect(Recording.session) {
        while (Recording.session) { position = Recording.player?.positionMs ?: 0L; kotlinx.coroutines.delay(250) }
    }
    BoxWithConstraints(Modifier.matchParentSize()) {
        val short = maxHeight < 440.dp
        // The original annotation palette already owns much of a landscape phone. Keep the
        // rehearsal essentials direct, and return to the full shelf with one contextual switch.
        val annotating = !state.stripCollapsed && short && Perform.isOn != null && !Perform.on(PerformAction.FULLSCREEN)
        val shelfWidth = (maxWidth - 74.dp).coerceAtLeast(160.dp)
        val widgets = if (state.stripCollapsed) emptyList() else state.shelf.order.filter { it !in state.shelf.hidden && (it != ShelfWidget.SCORE || state.readMusic) }
        val minimumWidth = if (short) 270.dp else 236.dp
        val width = if (shelfWidth >= 800.dp && widgets.isNotEmpty()) maxOf(minimumWidth, shelfWidth / widgets.size) else minimumWidth
        val panelHeight = if (short) 78.dp else 128.dp
        Surface(tonalElevation = 2.dp, modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
            .onSizeChanged { measured = with(density) { it.height.toDp().value }; state.shelfHeight = measured }) {
            Column {
                if (annotating) Row(Modifier.fillMaxWidth().height(40.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { Perform.run(PerformAction.FULLSCREEN) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Icon(Icons.Default.LibraryMusic, null, Modifier.size(18.dp))
                        Text("Music tools", modifier = Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { state.metronomeOpen = true }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("${SharedMetronome.bpm.toInt()} BPM", style = MaterialTheme.typography.labelSmall) }
                    ShelfIcon(if (SharedMetronome.running) Icons.Default.Pause else Icons.Default.PlayArrow, "Start or pause metronome", 36.dp) { Click.toggle(state) }
                    ShelfIcon(Icons.Default.GraphicEq, "Tuner", 36.dp) { state.tunerOpen = !state.tunerOpen }
                    ShelfIcon(if (Recording.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause recording", 36.dp) {
                        if (Recording.session) Recording.playPause(state) else if (state.current?.audio?.isNotEmpty() == true) Recording.toggle(state) else state.audioOpen = true
                    }
                    ShelfIcon(Icons.Default.Mic, "Recordings and microphone", 36.dp) { state.audioOpen = !state.audioOpen }
                    ShelfIcon(Icons.Default.Devices, "Play together", 36.dp) { state.companionOpen = !state.companionOpen }
                }
                if (!annotating && widgets.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(end = 68.dp)) {
                    widgets.forEach { widget ->
                        val color = when (widget) {
                            ShelfWidget.TIMING -> MaterialTheme.colorScheme.tertiary
                            ShelfWidget.RECORDING -> MaterialTheme.colorScheme.primary
                            ShelfWidget.SCORE -> MaterialTheme.colorScheme.secondary
                            ShelfWidget.DEVICES -> MaterialTheme.colorScheme.primary
                        }
                        Column(Modifier.width(width).height(panelHeight).padding(horizontal = 10.dp, vertical = 5.dp)) {
                            Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(4.dp, 13.dp).background(color, CircleShape))
                                Text(widget.label, color = color, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp).weight(1f))
                                if (widget == ShelfWidget.DEVICES) ShelfIcon(Icons.Default.Tune, "Arrange music shelf", 24.dp) { arrange = true }
                            }
                            when (widget) {
                                ShelfWidget.TIMING -> {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("${SharedMetronome.bpm.toInt()}", fontSize = if (short) 24.sp else 30.sp, fontWeight = FontWeight.Light,
                                            modifier = Modifier.semantics { contentDescription = "Metronome settings" }.clickable { state.metronomeOpen = !state.metronomeOpen }.padding(end = 6.dp))
                                        Text("BPM", style = MaterialTheme.typography.labelSmall)
                                        ShelfIcon(if (SharedMetronome.running) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            if (SharedMetronome.running) "Stop metronome" else "Start metronome", 36.dp) { Click.toggle(state) }
                                        ShelfIcon(Icons.Default.GraphicEq, "Tuner", 36.dp) { state.tunerOpen = !state.tunerOpen }
                                    }
                                    if (!short) Row(verticalAlignment = Alignment.CenterVertically) {
                                        val beats = SharedMetronome.engine?.settings?.beatsPerBar ?: 4
                                        repeat(beats.coerceAtMost(8)) { beat -> Box(Modifier.padding(end = 5.dp).size(6.dp).background(if (SharedMetronome.running && beat == SharedMetronome.beat) color else MaterialTheme.colorScheme.surfaceVariant, CircleShape)) }
                                        TextButton(onClick = { state.metronomeOpen = true }, contentPadding = PaddingValues(4.dp)) { Text("Meter / count-in", style = MaterialTheme.typography.labelSmall) }
                                    }
                                }
                                ShelfWidget.RECORDING -> {
                                    val title = Recording.track?.label ?: state.current?.audio?.firstOrNull()?.label ?: if (state.current?.audio?.isNotEmpty() == true) "Song recording" else "Add a recording"
                                    if (!short) Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable { state.audioOpen = !state.audioOpen })
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        ShelfIcon(Icons.Default.Replay, "Replay recording", 32.dp) { Recording.replay(state) }
                                        ShelfIcon(if (Recording.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause recording", 36.dp) {
                                            if (Recording.session) Recording.playPause(state) else if (state.current?.audio?.isNotEmpty() == true) Recording.toggle(state) else state.audioOpen = true
                                        }
                                        ShelfIcon(Icons.Default.Repeat, "Recording loop and speed", 32.dp) { state.audioOpen = !state.audioOpen }
                                        ShelfIcon(Icons.Default.Mic, "Record myself", 32.dp) { state.audioOpen = !state.audioOpen }
                                        ShelfIcon(Icons.Default.LibraryMusic, "Choose recording", 32.dp) { state.audioOpen = !state.audioOpen }
                                    }
                                    if (!short) TextButton(onClick = { state.audioOpen = !state.audioOpen }, contentPadding = PaddingValues(0.dp)) {
                                        Text(if (Recording.session) "${shelfClock(position)} · ${Recording.player?.speed ?: 1.0}×" else "Recordings & practice loops", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                ShelfWidget.SCORE -> {
                                    val file = state.currentPath?.let(::File)
                                    val score = remember(state.currentPath, Transcriber.shown, Transcriber.busy) { ScoreTools.scoreHere(state) }
                                    val read = score?.hasRead(state.pageShown.first) == true
                                    Row {
                                        ShelfCommand(Icons.Default.MusicNote, "Read", "Read notes") { if (file != null && Transcriber.busy == null) Transcriber.read(state, file) { Perform.marksChanged() } else state.readMusicOpen = true }
                                        ShelfCommand(Icons.Default.Spellcheck, "Review", "Review recognized notes") { if (read) ScoreTools.toggleCheck(state) else state.readMusicOpen = true }
                                        ShelfCommand(Icons.Default.AutoFixHigh, "Clean", "Clean view") { if (read) ScoreTools.cleanWhole(state, !ScoreTools.cleanedWhole(state)) else state.readMusicOpen = true }
                                        ShelfCommand(if (ScoreTools.playing != null) Icons.Default.Stop else Icons.Default.PlayArrow, "Play", "Play notes") { if (ScoreTools.playing != null) ScoreTools.stop(state) else if (read) ScoreTools.play(state) else state.readMusicOpen = true }
                                        ShelfCommand(Icons.Default.Tag, "Bar", "Go to bar") { goTo = true }
                                        if (short) ShelfCommand(Icons.Default.Tune, "Tools", "All score tools") { ScoreTools.open = !ScoreTools.open }
                                    }
                                    if (!short) Row {
                                        TextButton(onClick = { ScoreTools.open = !ScoreTools.open }, contentPadding = PaddingValues(3.dp)) { Text("All score tools", style = MaterialTheme.typography.labelSmall) }
                                        if (Transcriber.busy != null) Text("Reading…", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                ShelfWidget.DEVICES -> {
                                    if (!short) Text(state.companion.status ?: if (state.controllers.devices.isNotEmpty()) "${state.controllers.devices.size} controller(s)" else "Connect a stand or controller", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Row {
                                        ShelfIcon(Icons.Default.Devices, "Play together", 36.dp) { state.companionOpen = !state.companionOpen }
                                        ShelfIcon(Icons.Default.Gamepad, "Remote", 36.dp) { state.remote.remoteOpen = !state.remote.remoteOpen }
                                        ShelfIcon(Icons.Default.SettingsInputComponent, "Pedals and watch", 36.dp) { controllers = true }
                                        ShelfIcon(Icons.Default.Tune, "Arrange music shelf", 36.dp) { arrange = true }
                                    }
                                }
                            }
                        }
                        VerticalDivider(Modifier.height(panelHeight).padding(vertical = 8.dp))
                    }
                }
                // Active transport never disappears when its widget is hidden or a tool panel is closed.
                if (active || widgets.isEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 6.dp, end = 68.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (Recording.session) {
                        ShelfIcon(Icons.Default.Replay, "From the start again", 32.dp) { Recording.replay(state) }
                        ShelfIcon(if (Recording.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Pause or resume active recording", 36.dp) { Recording.playPause(state) }
                        Text("${Recording.song?.title ?: "Recording"} · ${shelfClock(position)}", style = MaterialTheme.typography.labelSmall)
                        ShelfIcon(Icons.Default.FastRewind, "Recording back five seconds", 32.dp) { Recording.skip(-5.0) }
                        ShelfIcon(Icons.Default.FastForward, "Recording forward five seconds", 32.dp) { Recording.skip(5.0) }
                        ShelfIcon(Icons.Default.Close, "Stop recording playback", 32.dp) { Recording.end(state) }
                        PlayAlong.hint?.let { hint -> Text(hint, style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable { PlayAlong.toggle(state) }.padding(6.dp)) }
                    }
                    if (SharedMetronome.running) {
                        ShelfIcon(Icons.Default.Stop, "Stop active metronome", 32.dp) { Click.stop(state) }
                        Text("♩ ${SharedMetronome.bpm.toInt()}", style = MaterialTheme.typography.labelSmall)
                    }
                    if (SelfRecorder.recording) TextButton(onClick = { SelfRecorder.stop(state) }) { Text("Stop recording · ${SelfRecorder.seconds}s") }
                    if (ScoreTools.playing != null || ScoreTools.paused != null) {
                        ShelfIcon(if (ScoreTools.playing != null) Icons.Default.Pause else Icons.Default.PlayArrow, "Pause or resume notes", 32.dp) { if (ScoreTools.playing != null) ScoreTools.pause(state) else ScoreTools.resume(state) }
                        Text("Bar ${ScoreTools.playing?.first ?: ScoreTools.paused}", style = MaterialTheme.typography.labelSmall)
                        ShelfIcon(Icons.Default.Stop, "Stop notes", 32.dp) { ScoreTools.stop(state) }
                    }
                    if (Listener.active) TextButton(onClick = { Listener.stop(state) }) { Text("Stop following") }
                    if (widgets.isEmpty()) TextButton(onClick = { if (state.stripCollapsed) state.stripCollapsed = false else arrange = true }) { Text("Show music widgets") }
                }
            }
        }
    }
    if (arrange) ShelfEditor(state) { arrange = false }
    if (controllers) SheetDialog(title = "Pedals & watch", onDismiss = { controllers = false }, wide = true) { Column(Modifier.verticalScroll(rememberScrollState())) { ControllerSettings(state); WatchSettings(state) } }
    if (goTo) GoToBar(state) { goTo = false }
}

@Composable
private fun ShelfIcon(icon: ImageVector, description: String, size: Dp, action: () -> Unit) {
    IconButton(onClick = action, modifier = Modifier.size(size)) { Icon(icon, description, Modifier.size(20.dp)) }
}

@Composable
private fun ShelfCommand(icon: ImageVector, label: String, description: String, action: () -> Unit) {
    Column(Modifier.width(40.dp).clickable(onClick = action).padding(vertical = 3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, description, Modifier.size(20.dp))
        Text(label, fontSize = 10.sp, maxLines = 1)
    }
}

private fun shelfClock(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

@Composable
private fun ShelfEditor(state: SheetsState, close: () -> Unit) {
    SheetDialog(title = "Arrange music shelf", onDismiss = close, buttons = {
        TextButton(onClick = { state.shelf.reset() }) { Text("Reset") }
        TextButton(onClick = close) { Text("Done") }
    }) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            state.shelf.order.forEachIndexed { index, widget ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(widget !in state.shelf.hidden, { state.shelf.show(widget, it) })
                    Text(widget.label, Modifier.weight(1f))
                    IconButton(onClick = { state.shelf.move(widget, -1) }, enabled = index > 0) { Icon(Icons.Default.ArrowUpward, "Move ${widget.label} earlier") }
                    IconButton(onClick = { state.shelf.move(widget, 1) }, enabled = index < state.shelf.order.lastIndex) { Icon(Icons.Default.ArrowDownward, "Move ${widget.label} later") }
                }
            }
            Text("Active playback controls remain available even when a widget is hidden.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
