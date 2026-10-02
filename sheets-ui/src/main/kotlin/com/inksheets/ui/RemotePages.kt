package com.inksheets.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inkslate.core.PerformAction
import com.inksheets.core.RemoteButton
import com.inksheets.core.RemoteLink
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The remote's pages: the player's own buttons in the middle, and round them four pages that are
 * always the same - the set as a grid of songs to the right, tuner and click above, reading the
 * music below, recordings to the left. [dx], [dy]: where the page is from the middle.
 */
internal enum class RemotePage(val title: String, val tab: String, val dx: Int, val dy: Int, val color: Color) {
    CENTER("Buttons", "Buttons", 0, 0, Color(0xFF546E7A)),
    SET("The set", "Set", 1, 0, Color(0xFF1565C0)),
    TOOLS("Tuner & click", "Tuner & click", 0, -1, Color(0xFF2E7D32)),
    READING("Reading the music", "Reading", 0, 1, Color(0xFF6A1B9A)),
    RECORDING("Recordings", "Recordings", -1, 0, Color(0xFFC62828));

    val icon: ImageVector get() = when (this) {
        CENTER -> Icons.Default.Tune
        SET -> Icons.AutoMirrored.Filled.QueueMusic
        TOOLS -> Icons.Default.GraphicEq
        READING -> Icons.Default.DocumentScanner
        RECORDING -> Icons.Default.FiberManualRecord
    }

    companion object {
        /** The page a swipe of the finger leads to from the middle: the content follows the finger, so a swipe left shows what is right. */
        fun from(dx: Float, dy: Float): RemotePage? = when {
            abs(dx) > abs(dy) -> if (dx < 0) SET else RECORDING
            else -> if (dy < 0) READING else TOOLS
        }
    }
}

/**
 * The remote's pages round [center] (the player's buttons): a swipe, or a tap on a page's tab at
 * the edge, goes to a page; from a page, a swipe any way (sideways only on the set, which scrolls
 * up and down) or its back button comes back. [enabled] off while the buttons are being changed.
 */
@Composable
internal fun RemotePager(state: SheetsState, enabled: Boolean, center: @Composable () -> Unit) {
    var page by remember { mutableStateOf(RemotePage.CENTER) }
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    Box(
        Modifier.fillMaxSize().pointerInput(page, enabled, threshold) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                // Watched before the buttons see it, never taken from them - unless it was a swipe,
                // whose lifting is taken so the button it ended on is not pressed too.
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var end = down.position
                var fingers = 1
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    fingers = maxOf(fingers, ev.changes.count { it.pressed })
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    end = c.position
                    if (!c.pressed) {
                        if (fingers > 1) break
                        val dx = end.x - down.position.x; val dy = end.y - down.position.y
                        val sideways = abs(dx) > threshold && abs(dx) > abs(dy) * 1.4f
                        val upright = abs(dy) > threshold && abs(dy) > abs(dx) * 1.4f
                        val next = when {
                            page == RemotePage.CENTER && (sideways || upright) -> RemotePage.from(dx, dy)
                            page == RemotePage.SET && sideways -> RemotePage.CENTER
                            page != RemotePage.CENTER && page != RemotePage.SET && (sideways || upright) -> RemotePage.CENTER
                            else -> null
                        }
                        if (next != null) { c.consume(); page = next }
                        break
                    }
                }
            }
        }
    ) {
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                // Coming from where the page is (going out to it), or back from it (coming home).
                val p = if (targetState != RemotePage.CENTER) targetState else initialState
                val sign = if (targetState != RemotePage.CENTER) 1 else -1
                val time = tween<androidx.compose.ui.unit.IntOffset>(220)
                if (p.dx != 0) slideInHorizontally(time) { w -> sign * p.dx * w } togetherWith slideOutHorizontally(time) { w -> -sign * p.dx * w }
                else slideInVertically(time) { h -> sign * p.dy * h } togetherWith slideOutVertically(time) { h -> -sign * p.dy * h }
            },
            label = "remote page"
        ) { shownPage ->
            when (shownPage) {
                RemotePage.CENTER -> Box(Modifier.fillMaxSize()) {
                    center()
                    if (enabled) PageTabs { page = it }
                }
                else -> SidePage(state, shownPage, onBack = { page = RemotePage.CENTER }) {
                    when (shownPage) {
                        RemotePage.SET -> SetPage(state, onChosen = { page = RemotePage.CENTER })
                        RemotePage.TOOLS -> ToolsPage(state)
                        RemotePage.READING -> ReadingPage(state)
                        RemotePage.RECORDING -> RecordingPage(state)
                        else -> Unit
                    }
                }
            }
        }
    }
}

/** The four pages' tabs at the middle's edges, each in its page's colour: tap to go, or swipe that way. */
@Composable
private fun BoxScope.PageTabs(go: (RemotePage) -> Unit) = androidx.compose.foundation.layout.BoxWithConstraints(Modifier.matchParentSize()) {
    // A narrow screen: the tabs along the top and foot say what they are by their icon alone.
    val narrow = maxWidth < 420.dp
    @Composable
    fun tab(p: RemotePage, align: Alignment, upright: Boolean) {
        val fg = if (p.color.luminance() > 0.5f) Color.Black else Color.White
        // What is under it is carved round it (see Notches): the tab nests in its notch.
        Surface(
            color = p.color.copy(alpha = 0.95f),
            shape = RoundedCornerShape(12.dp),
            shadowElevation = 2.dp,
            modifier = Modifier.align(align).padding(2.dp).notchTab(p.name, 12.dp).clip(RoundedCornerShape(12.dp)).clickable { go(p) }
                .semantics { contentDescription = p.title }
        ) {
            if (upright) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(24.dp).padding(vertical = 8.dp)) {
                Icon(if (p.dx > 0) Icons.AutoMirrored.Filled.ArrowForward else Icons.AutoMirrored.Filled.ArrowBack, null, tint = fg, modifier = Modifier.size(14.dp))
                Icon(p.icon, null, tint = fg, modifier = Modifier.size(16.dp))
                Text(p.tab.take(3).uppercase(), color = fg, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            } else Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)) {
                Icon(if (p.dy < 0) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward, null, tint = fg, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Icon(p.icon, null, tint = fg, modifier = Modifier.size(14.dp))
                if (!narrow) {
                    Spacer(Modifier.width(4.dp))
                    Text(p.tab, color = fg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    tab(RemotePage.TOOLS, Alignment.TopCenter, upright = false)
    tab(RemotePage.READING, Alignment.BottomCenter, upright = false)
    tab(RemotePage.SET, Alignment.CenterEnd, upright = true)
    tab(RemotePage.RECORDING, Alignment.CenterStart, upright = true)
}

/**
 * A page round the middle: a bar in its own colour - what it is, where it is (a little map of the
 * five), and the way back with an arrow pointing to the middle - over its content.
 */
@Composable
private fun SidePage(state: SheetsState, page: RemotePage, onBack: () -> Unit, content: @Composable () -> Unit) {
    val fg = if (page.color.luminance() > 0.5f) Color.Black else Color.White
    Column(Modifier.fillMaxSize()) {
        Surface(color = page.color, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                // Back, pointing where the buttons are: the middle is the other way from this page.
                val back = when {
                    page.dx > 0 -> Icons.AutoMirrored.Filled.ArrowBack
                    page.dx < 0 -> Icons.AutoMirrored.Filled.ArrowForward
                    page.dy < 0 -> Icons.Default.ArrowDownward
                    else -> Icons.Default.ArrowUpward
                }
                Surface(
                    color = fg.copy(alpha = 0.18f), shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onBack)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Icon(back, null, tint = fg)
                        Spacer(Modifier.width(6.dp))
                        Text("Buttons", color = fg, style = MaterialTheme.typography.labelLarge)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Icon(page.icon, null, tint = fg)
                Spacer(Modifier.width(6.dp))
                Text(page.title, color = fg, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                PageMap(page, fg)
            }
        }
        // What the other device is on, in a line.
        state.remote.shown?.let { shown ->
            val line = listOfNotNull(
                shown.title ?: if (shown.home) "On Home" else "No song open",
                if (shown.pages > 0) "p. ${shown.page + 1}/${shown.pages}" else null,
                shown.setlist?.let { "song ${shown.setIndex + 1}/${shown.set.size}" }
            ).joinToString("  ·  ")
            Text(line, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
        state.remote.lastPress?.let { p ->
            Text(
                when (p.got) { true -> "${p.name} ✓"; null -> "${p.name}..."; false -> "${p.name} - not received" },
                style = MaterialTheme.typography.labelSmall,
                color = if (p.got == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) { content() }
        Text(
            if (page == RemotePage.SET) "Swipe sideways to go back to your buttons" else "Swipe any way to go back to your buttons",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
        )
    }
}

/** The five pages as a little cross, this one filled: where it is, at a glance. */
@Composable
private fun PageMap(page: RemotePage, fg: Color) {
    val cell = 9.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        for (y in -1..1) Row {
            for (x in -1..1) {
                val p = RemotePage.entries.firstOrNull { it.dx == x && it.dy == y }
                Box(Modifier.size(cell).padding(1.dp).then(
                    if (p == null) Modifier
                    else if (p == page) Modifier.background(fg, CircleShape)
                    else Modifier.border(1.dp, fg.copy(alpha = 0.7f), CircleShape)
                ))
            }
        }
    }
}

private fun send(state: SheetsState, action: String, name: String, value: Double? = null, index: Int? = null, id: String? = null) =
    state.remote.send(RemoteLink.Command(action = action, value = value, index = index, id = id), name)

// ---- the set ---------------------------------------------------------------------------------

/**
 * The set as a grid: every song a big numbered tile, the one in front filled and the next marked,
 * in the set's order or A-Z, as large or small as wanted; letters along the top to jump to. No set
 * being played: the setlists to start one, and every song.
 */
@Composable
private fun SetPage(state: SheetsState, onChosen: () -> Unit) {
    val remote = state.remote
    val shown = remote.shown
    val set = shown?.set.orEmpty()
    var view by remember { mutableStateOf(if (set.isNotEmpty()) 0 else 1) }   // 0 the set, 1 setlists, 2 all songs
    var az by remember { mutableStateOf(state.platform.pref(K_SET_AZ) == "true") }
    var columns by remember { mutableStateOf(state.platform.pref(K_SET_COLUMNS)?.toIntOrNull()?.coerceIn(1, 6) ?: 3) }
    val scope = rememberCoroutineScope()
    val accent = RemotePage.SET.color

    // What is shown: (number in the set or -1, title, colour, what a tap sends).
    data class Tile(val number: Int, val title: String, val color: Int?, val now: Boolean, val next: Boolean, val tap: () -> Unit)
    val tiles: List<Tile> = when (view) {
        0 -> set.mapIndexed { i, it ->
            Tile(i + 1, it.title, it.color, i == shown?.setIndex, i == (shown?.setIndex ?: -2) + 1) {
                send(state, RemoteLink.SET_ENTRY, it.title, index = i)
            }
        }.let { if (az) it.sortedBy { t -> t.title.lowercase() } else it }
        1 -> remote.hostLibrary?.setlists.orEmpty().map { it ->
            Tile(-1, it.title, it.color, it.id == shown?.setlistId, false) { send(state, RemoteLink.SETLIST, it.title, id = it.id, index = 0) }
        }
        else -> remote.hostLibrary?.songs.orEmpty().map { it ->
            Tile(-1, it.title, it.color, it.id == shown?.songId, false) { send(state, RemoteLink.SONG, it.title, id = it.id) }
        }
    }
    val grid = rememberLazyGridState()
    // The song in front in view as the page opens.
    LaunchedEffect(view, az) {
        val at = tiles.indexOfFirst { it.now }
        if (at > 0) grid.scrollToItem((at - columns).coerceAtLeast(0))
    }

    Column(Modifier.fillMaxSize()) {
        // Which list, which order, how big.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            if (set.isNotEmpty()) Chip(shown?.setlist ?: "This set", view == 0, accent) { view = 0 }
            Chip("Setlists", view == 1, accent) { view = 1 }
            Chip("All songs", view == 2, accent) { view = 2 }
        }
        // In what order, how big: the tiles as large as the player wants them to be found at a glance.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            if (view == 0) {
                Chip("Set order", !az, accent) { az = false; state.platform.setPref(K_SET_AZ, "false") }
                Chip("A–Z", az, accent) { az = true; state.platform.setPref(K_SET_AZ, "true") }
            }
            Spacer(Modifier.weight(1f))
            Chip("Bigger", false, accent) { columns = (columns - 1).coerceAtLeast(1); state.platform.setPref(K_SET_COLUMNS, columns.toString()) }
            Chip("Smaller", false, accent) { columns = (columns + 1).coerceAtMost(6); state.platform.setPref(K_SET_COLUMNS, columns.toString()) }
        }
        // Letters to jump to, in A-Z order (and the songs list), where there are enough to need them.
        if ((view != 0 || az) && tiles.size > 12) {
            val letters = tiles.map { it.title.trim().firstOrNull()?.uppercaseChar() ?: '#' }.map { if (it.isLetter()) it else '#' }.distinct().sorted()
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                for (l in letters) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 2.dp).size(34.dp).clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable {
                                val at = tiles.indexOfFirst { t -> (t.title.trim().firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() } ?: '#') == l }
                                if (at >= 0) scope.launch { grid.animateScrollToItem(at) }
                            }
                    ) { Text("$l", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                }
            }
        }
        if (tiles.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    when (view) { 0 -> "No set is being played there."; 1 -> "No setlists yet."; else -> if (shown == null) "Waiting for the other device..." else "No songs yet." },
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        // A phone on its side: twice the columns, so the tiles stay short and rows of them fit.
        val across = if (maxWidth > maxHeight * 1.2f) (columns * 2).coerceAtMost(8) else columns
        LazyVerticalGrid(
            columns = GridCells.Fixed(across), state = grid,
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().padding(top = 4.dp)
        ) {
            itemsIndexed(tiles) { _, t ->
                SongTile(t.number, t.title, t.color, t.now, t.next, accent, big = across <= 2) {
                    t.tap()
                    scope.launch { kotlinx.coroutines.delay(250); onChosen() }
                }
            }
        }
        }
    }
}

/** A song of the set: its number big, its name, its colour down the side; filled when it is the one in front. */
@Composable
private fun SongTile(number: Int, title: String, color: Int?, now: Boolean, next: Boolean, accent: Color, big: Boolean, onTap: () -> Unit) {
    val bg = if (now) accent else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (now) Color.White else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().aspectRatio(if (big) 2.2f else 1.25f).clip(RoundedCornerShape(14.dp)).background(bg)
            .then(if (next) Modifier.border(2.dp, accent, RoundedCornerShape(14.dp)) else Modifier)
            .clickable(onClick = onTap)
    ) {
        Box(Modifier.width(7.dp).fillMaxHeight().background(color?.let { Color(it) } ?: Color.Transparent))
        Column(Modifier.padding(8.dp).fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (number > 0) Text("$number", color = if (now) fg else accent, fontSize = if (big) 26.sp else 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (now) Text("NOW", color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                else if (next) Text("NEXT", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Text(title, color = fg, style = if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Chip(text: String, on: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.padding(end = 6.dp).height(36.dp).clip(RoundedCornerShape(18.dp))
            .background(if (on) accent else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick).padding(horizontal = 14.dp)
    ) { Text(text, color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
}

// ---- tuner and click -------------------------------------------------------------------------

/** The tuner on this device (its own microphone - the phone at the stand), and the other device's click. */
@Composable
private fun ToolsPage(state: SheetsState) {
    val shown = state.remote.shown
    val accent = RemotePage.TOOLS.color
    val taps = remember { mutableStateListOf<Long>() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                Text("Tuner - on this phone", style = MaterialTheme.typography.labelLarge, color = accent)
                TunerBody(state, large = true)
            }
        }
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Click - on ${state.remote.target?.name ?: "the other device"}", style = MaterialTheme.typography.labelLarge, color = accent, modifier = Modifier.weight(1f))
                    if ((shown?.counting ?: 0) > 0) Text("${shown?.counting}", fontSize = 28.sp, color = accent, fontWeight = FontWeight.Bold)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("♩ ${shown?.bpm ?: "–"}", fontSize = 40.sp, fontWeight = FontWeight.Light, modifier = Modifier.weight(1f))
                    BigButton(if (shown?.metronome == true) "Stop" else "Start", if (shown?.metronome == true) Icons.Default.Stop else Icons.Default.PlayArrow,
                        accent, lit = shown?.metronome == true, modifier = Modifier.width(140.dp)) {
                        state.remote.send(RemoteLink.Command(action = PerformAction.METRONOME.name), "Metronome")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (by in listOf(-5, -1, 1, 5)) SmallButton(if (by < 0) "−${-by}" else "+$by", accent) {
                        send(state, RemoteButton.TEMPO, "Tempo ${if (by < 0) "−" else "+"}${abs(by)}", value = by.toDouble())
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallButton("Tap", accent) {
                        taps += System.currentTimeMillis()
                        while (taps.size > 12) taps.removeAt(0)
                        com.inksheets.core.Metronome.tapTempo(taps)?.let { bpm ->
                            send(state, RemoteButton.TAP, "Tap tempo", value = kotlin.math.round(bpm).coerceIn(20.0, 300.0))
                        }
                    }
                    SmallButton("Count in", accent) { send(state, RemoteButton.COUNT_IN, "Count in") }
                    SmallButton("Bars: ${shown?.countInBars ?: 1}", accent) {
                        val next = ((shown?.countInBars ?: 1) % 4) + 1
                        send(state, RemoteButton.COUNT_BARS, "Count-in $next bar${if (next == 1) "" else "s"}", value = next.toDouble())
                    }
                }
            }
        }
    }
}

// ---- reading the music ----------------------------------------------------------------------

/** The other device's music reading: how far it has got, and its buttons - read, clean view, fix, play. */
@Composable
private fun ReadingPage(state: SheetsState) {
    val shown = state.remote.shown
    val accent = RemotePage.READING.color
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                val doubt = (shown?.readBars ?: 0) - (shown?.readSure ?: 0)
                Text(
                    when {
                        shown == null -> "Waiting for the other device..."
                        shown.title == null -> "Open a song there first"
                        shown.readBusy != null -> shown.readBusy!!
                        !shown.readAny -> "Not read yet"
                        else -> "${shown.readSure} of ${shown.readBars} bars sure" + if (doubt > 0) " - $doubt in doubt" else ""
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                if (shown?.readAny == true && shown.readBusy == null)
                    Text(if (shown.readThisPage) "This page is read" else "This page is not read yet", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val busy = shown?.readBusy != null
        val open = shown?.title != null
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton("Read this page", Icons.Default.DocumentScanner, accent, enabled = open && !busy, modifier = Modifier.weight(1f)) { send(state, RemoteButton.READ_PAGE, "Read this page") }
            BigButton("Read the part", Icons.Default.LibraryMusic, accent, enabled = open && !busy, modifier = Modifier.weight(1f)) { send(state, RemoteButton.READ_PART, "Read the part") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton(if (shown?.cleanShown == true) "Hide clean view" else "Clean view", Icons.Default.Visibility, accent, lit = shown?.cleanShown == true,
                enabled = shown?.readAny == true, modifier = Modifier.weight(1f)) { send(state, RemoteButton.CLEAN, "Clean view") }
            val doubt = (shown?.readBars ?: 0) - (shown?.readSure ?: 0)
            BigButton(if (shown?.fixing == true) "Stop fixing" else "Fix $doubt in doubt", Icons.Default.Build, accent, lit = shown?.fixing == true,
                enabled = shown?.readAny == true && (doubt > 0 || shown.fixing), modifier = Modifier.weight(1f)) { send(state, RemoteButton.FIX, "Fix bars in doubt") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton(if (shown?.scorePlaying == true) "Stop the music" else "Play the music", if (shown?.scorePlaying == true) Icons.Default.Stop else Icons.Default.PlayArrow,
                accent, lit = shown?.scorePlaying == true, enabled = shown?.readAny == true, modifier = Modifier.weight(1f)) { send(state, RemoteButton.SCORE_PLAY, "Play the music read") }
            BigButton(if (shown?.musicTools == true) "Hide music tools" else "Music tools", Icons.Default.Tune, accent, lit = shown?.musicTools == true,
                enabled = open, modifier = Modifier.weight(1f)) { send(state, RemoteButton.MUSIC_TOOLS, "Music tools") }
        }
        if (shown?.fixing == true) Text("The bars in doubt are on the other device's screen: pick there.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- recordings ------------------------------------------------------------------------------

/** The other device's recordings: play, skip, speed and volume; recording yourself; the click with them. */
@Composable
private fun RecordingPage(state: SheetsState) {
    val shown = state.remote.shown
    val accent = RemotePage.RECORDING.color
    fun clock(s: Int) = "${s / 60}:${"%02d".format(s % 60)}"
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                when {
                    shown?.recording == true -> Text("● Recording  ${clock(shown.recordingSeconds)}", style = MaterialTheme.typography.titleLarge, color = accent)
                    shown?.hasRecording == true -> {
                        Text(if (shown.recordingPlaying) "Playing" else "Paused", style = MaterialTheme.typography.titleMedium)
                        Text("${clock(shown.audioSeconds)} / ${clock(shown.audioLength)}   ·   speed ${shown.audioSpeed}%   ·   volume ${shown.audioVolume}%",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (shown.audioLength > 0) Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            Box(Modifier.fillMaxWidth((shown.audioSeconds.toFloat() / shown.audioLength).coerceIn(0f, 1f)).fillMaxHeight().background(accent))
                        }
                    }
                    else -> Text("No recording of this song there yet", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        val has = shown?.hasRecording == true
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton(if (shown?.recordingPlaying == true) "Pause" else "Play", if (shown?.recordingPlaying == true) Icons.Default.Pause else Icons.Default.PlayArrow,
                accent, lit = shown?.recordingPlaying == true, enabled = has, modifier = Modifier.weight(1f)) {
                state.remote.send(RemoteLink.Command(action = PerformAction.PLAY_AUDIO.name), "Play the recording")
            }
            BigButton(if (shown?.recording == true) "Stop recording" else "Record yourself", Icons.Default.FiberManualRecord, accent,
                lit = shown?.recording == true, enabled = shown?.title != null, modifier = Modifier.weight(1f)) { send(state, RemoteButton.RECORD, "Record yourself") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallButton("Start", accent, enabled = has) { send(state, RemoteButton.AUDIO_RESTART, "From the start") }
            for (by in listOf(-10, -5, 5, 10)) SmallButton(if (by < 0) "−${-by}s" else "+${by}s", accent, enabled = has) {
                send(state, RemoteButton.AUDIO_SEEK, if (by < 0) "Back ${-by} s" else "On $by s", value = by.toDouble())
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.SlowMotionVideo, null, tint = accent)
            SmallButton("Slower", accent, enabled = has) { send(state, RemoteButton.AUDIO_SPEED, "Slower 5%", value = -5.0) }
            SmallButton("Faster", accent, enabled = has) { send(state, RemoteButton.AUDIO_SPEED, "Faster 5%", value = 5.0) }
            Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = accent)
            SmallButton("−", accent, enabled = has) { send(state, RemoteButton.AUDIO_VOLUME, "Quieter", value = -10.0) }
            SmallButton("+", accent, enabled = has) { send(state, RemoteButton.AUDIO_VOLUME, "Louder", value = 10.0) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton("Click when recording", Icons.Default.Speed, accent, lit = shown?.clickRecording == true, modifier = Modifier.weight(1f)) {
                send(state, RemoteButton.CLICK_RECORDING, "Click when recording")
            }
            BigButton("Click with playback", Icons.Default.GraphicEq, accent, lit = shown?.clickPlayback == true, modifier = Modifier.weight(1f)) {
                send(state, RemoteButton.CLICK_PLAYBACK, "Click with recordings")
            }
        }
        SmallButton("Open the recordings there", accent) { state.remote.send(RemoteLink.Command(action = PerformAction.RECORDINGS.name), "Recordings") }
    }
}

// ---- buttons for the pages -------------------------------------------------------------------

@Composable
private fun BigButton(text: String, icon: ImageVector, accent: Color, lit: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg = when { !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f); lit -> accent; else -> MaterialTheme.colorScheme.surfaceContainerHigh }
    val fg = when { !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f); lit -> Color.White; else -> MaterialTheme.colorScheme.onSurface }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        modifier = modifier.height(76.dp).clip(RoundedCornerShape(16.dp)).background(bg)
            .then(if (!lit && enabled) Modifier.border(1.5.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(16.dp)) else Modifier)
            .clickable(enabled = enabled, onClick = onClick).padding(6.dp)
    ) {
        Icon(icon, null, tint = if (lit || !enabled) fg else accent)
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RowScope.SmallButton(text: String, accent: Color, enabled: Boolean = true, onClick: () -> Unit) =
    SmallButtonBox(text, accent, enabled, Modifier.weight(1f), onClick)

@Composable
private fun SmallButton(text: String, accent: Color, enabled: Boolean = true, onClick: () -> Unit) =
    SmallButtonBox(text, accent, enabled, Modifier.fillMaxWidth(), onClick)

@Composable
private fun SmallButtonBox(text: String, accent: Color, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.height(52.dp).clip(RoundedCornerShape(12.dp))
            .background(if (enabled) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(1.dp, accent.copy(alpha = if (enabled) 0.45f else 0.15f), RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
    }
}

private const val K_SET_AZ = "sheets_remote_set_az"
private const val K_SET_COLUMNS = "sheets_remote_set_columns"
