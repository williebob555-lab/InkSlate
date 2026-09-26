package com.inksheets.ui

import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Switch
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.material3.VerticalDivider
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LastPage
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.inkslate.core.Perform
import com.inkslate.core.PerformAction

/**
 * The action buttons laid over a song while it is being played: a slim column in the corner of
 * the page. Which actions it carries is chosen from its own menu; each one does exactly what the
 * pedal assigned to it would, through [Perform].
 */
@Composable
fun BoxScope.ActionStrip(state: SheetsState) {
    var collapsed by remember { mutableStateOf(state.platform.pref(K_COLLAPSED) == "true") }
    var menu by remember { mutableStateOf(false) }
    val shown = state.strip
    var customising by remember { mutableStateOf(false) }
    var sendingNote by remember { mutableStateOf(false) }
    var partMenu by remember { mutableStateOf(false) }
    // Folded away, the strip is one button in the corner and the page has the whole width.
    androidx.compose.runtime.LaunchedEffect(collapsed) { state.platform.setStripLane(!collapsed) }

    // Docked in the lane the page is fitted beside - down the right of a landscape screen, along
    // the bottom of a portrait one - so it sits in blank space and never over the music.
    BoxWithConstraints(Modifier.matchParentSize()) {
        // Always down a side: along the bottom, the buttons wrap into two rows and take more room.
        val side = true
        // Every button has to be on screen at once - never a strip to scroll. The buttons shrink
        // to fit a short screen, and only if that is not enough does the strip wrap to a second
        // column (or row).
        val visible = shown.count { a ->
            !((a == PerformAction.NEXT_SONG || a == PerformAction.PREVIOUS_SONG) && state.playing == null) &&
                !(a == PerformAction.PLAY_AUDIO && state.current?.audio.isNullOrEmpty())
        }
        val room = if (side) maxHeight else maxWidth
        val named = state.stripLabels
        val labelRoom = if (named && side) 12.dp else 0.dp
        val extras = 120.dp + (if (state.playing != null) 24.dp else 0.dp) + (if (PerformAction.METRONOME in shown) 28.dp else 0.dp)
        val btn = ((room - extras) / (visible + 3).coerceAtLeast(1) - labelRoom).coerceIn(32.dp, 44.dp)
        val items: @Composable () -> Unit = {
            if (!collapsed) {
                if (SelfRecorder.recording) {
                    Text("● Rec", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(6.dp))
                }
                state.companion.status?.let { status ->
                    Text(status, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(6.dp))
                }
                // Which page, and the way to every page: the page editor, one tap away.
                val (page, count) = state.pageShown
                if (count > 0) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { Perform.openPages?.invoke() }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text("${page + 1}/$count", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        if (named) Text("Pages", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                    }
                }
                // Leading: a message to the band, one tap away.
                if (state.companion.leading) {
                    StripButton(Icons.Default.Campaign, "Message", "Message the band", btn, named) { sendingNote = true }
                }
                // Where in a setlist this song is, when one is being played.
                state.playing?.let { (setlistId, index) ->
                    val total = state.library?.setlist(setlistId)?.entries?.size ?: 0
                    Text(
                        "Song ${index + 1}/$total",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(4.dp)
                    )
                }
                var lastGroup = -1
                for (action in shown) {
                    val songAction = action == PerformAction.NEXT_SONG || action == PerformAction.PREVIOUS_SONG
                    if (songAction && state.playing == null) continue
                    if (action == PerformAction.PLAY_AUDIO && state.current?.audio.isNullOrEmpty()) continue
                    val group = groupOf(action)
                    if (lastGroup >= 0 && group != lastGroup) {
                        if (side) HorizontalDivider(Modifier.width(24.dp).padding(vertical = 2.dp))
                        else VerticalDivider(Modifier.height(24.dp).padding(horizontal = 2.dp))
                    }
                    lastGroup = group
                    val lit = (action == PerformAction.METRONOME && SharedMetronome.running) ||
                        (action != PerformAction.FULLSCREEN && Perform.on(action))
                    val fullscreen = Perform.on(PerformAction.FULLSCREEN)
                    StripButton(iconOf(action, fullscreen), shortName(action, fullscreen), action.label, btn, named, lit) { Perform.run(action) }
                    // The tempo right under the button: one tap to change it, never a menu away.
                    if (action == PerformAction.METRONOME) {
                        Text(
                            "♩ ${SharedMetronome.bpm.roundToInt()}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { state.metronomeOpen = true }
                                .padding(horizontal = 6.dp, vertical = 6.dp)
                        )
                    }
                }
                Box {
                    StripButton(Icons.Default.MoreVert, "More", "More, and changing these buttons", btn, named) { menu = true }
                    StripMenu(state, menu, onDismiss = { menu = false }, onCustomise = { customising = true }, onPart = { partMenu = true })
                    // Another instrument's part, from More: opens where More was.
                    state.current?.let { song -> PartMenu(state, song, state.partShown(), partMenu, onDismiss = { partMenu = false }) }
                }
            }
            IconButton(onClick = {
                collapsed = !collapsed
                state.platform.setPref(K_COLLAPSED, collapsed.toString())
                state.platform.setStripLane(!collapsed)
                Perform.recentre?.invoke()
            }, modifier = Modifier.size(36.dp)) {
                Icon(if (collapsed) Icons.Default.UnfoldMore else Icons.Default.UnfoldLess, if (collapsed) "Show buttons" else "Hide buttons")
            }
        }
        Surface(
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 3.dp,
            shadowElevation = 2.dp,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
            // In the bottom corner, the fold button last - folded away, it is all there is, out of
            // the music's way.
            modifier = Modifier.align(if (state.stripOnLeft) Alignment.BottomStart else Alignment.BottomEnd).padding(6.dp)
        ) {
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            if (side) {
                androidx.compose.foundation.layout.FlowColumn(
                    itemHorizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(vertical = 4.dp)
                ) { items() }
            } else {
                androidx.compose.foundation.layout.FlowRow(
                    itemVerticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) { items() }
            }
        }
        // Following, and wandered off: the way back to the leader, top centre.
        BackToLeader(state, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
        // The leader's messages, where the music is not.
        NotePopup(state)
    }

    if (customising) StripEditor(state, onClose = { customising = false })
    if (sendingNote) SendNoteDialog(state, onClose = { sendingNote = false })
    SaveTabsDialog(state)
    IncomingDialog(state)
    if (state.clearingMarks) {
        val title = state.current?.title ?: "this part"
        SheetDialog(
            title = "Clear all markings?",
            onDismiss = { state.clearingMarks = false },
            buttons = {
                TextButton(onClick = { state.clearingMarks = false }) { Text("Keep them") }
                TextButton(onClick = {
                    state.currentPath?.let { Perform.clearInk?.invoke(it) }
                    state.clearingMarks = false
                }) { Text("Clear them", color = MaterialTheme.colorScheme.error) }
            }
        ) {
            Text("Every mark on every page of $title is taken off, on all your devices. This cannot be undone.")
        }
    }
    if (state.tunerOpen) TunerDialog(state, onClose = { state.tunerOpen = false })
    if (state.metronomeOpen) MetronomeDialog(state, onClose = { state.metronomeOpen = false })
    if (state.companionOpen) CompanionDialog(state, onClose = { state.companionOpen = false })
    val song = state.current
    if (state.audioOpen && song != null) AudioDialog(state, song, onClose = { state.audioOpen = false })
}

/** An instrument's name for a part, or what the part calls itself. */
private fun partName(p: com.inksheets.core.Part): String =
    com.inksheets.core.Instruments.partName(p)

/**
 * Switch part, two ways: for this song only (every other song keeps its part), or for every song
 * - the instrument this device plays.
 */
@Composable
private fun PartMenu(state: SheetsState, song: com.inksheets.core.Song, shown: com.inksheets.core.Part?, open: Boolean, onDismiss: () -> Unit) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        Text(
            "This song only",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
        val names = song.parts.map { partName(it) }
        var query by remember { mutableStateOf("") }
        ListSearch(song.parts.size, query, { query = it }, "Find a part", Modifier.padding(horizontal = 8.dp))
        song.parts.forEachIndexed { i, p ->
            if (!matches(query, names[i], p.file.substringAfterLast('/'))) return@forEachIndexed
            // Two parts for one instrument are told apart by their file.
            val name = if (names.count { it == names[i] } > 1) names[i] + " - " + p.file.substringAfterLast('/').substringBeforeLast('.') else names[i]
            DropdownMenuItem(
                text = { Text(name, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                leadingIcon = { if (p.id == shown?.id) Icon(Icons.Default.Check, null) },
                onClick = { onDismiss(); if (p.id != shown?.id) state.switchThisSong(p) }
            )
        }
        if (state.hasOwnPick(song)) {
            DropdownMenuItem(
                text = { Text("Back to ${state.profile?.name ?: "my instrument"}'s part") },
                onClick = { onDismiss(); state.clearThisSong() }
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text(
            "All songs",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
        state.profiles.forEach { profile ->
            DropdownMenuItem(
                text = { Text(profile.name) },
                leadingIcon = { if (profile.id == state.profileId) Icon(Icons.Default.Check, null) },
                onClick = { onDismiss(); state.switchAllSongs(profile.id) }
            )
        }
    }
}

@Composable
private fun StripMenu(state: SheetsState, open: Boolean, onDismiss: () -> Unit, onCustomise: () -> Unit, onPart: () -> Unit) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        state.current?.let { _ ->
            val part = state.partShown()?.let { partName(it) }
            DropdownMenuItem(
                text = { Text("Switch part" + (part?.let { " (now $it)" } ?: "") + "...") },
                leadingIcon = { Icon(Icons.Default.SwapHoriz, null) },
                onClick = { onDismiss(); onPart() }
            )
        }
        DropdownMenuItem(
            text = { Text("Fit the page to the screen") },
            leadingIcon = { Icon(Icons.Default.CenterFocusStrong, null) },
            onClick = { onDismiss(); Perform.recentre?.invoke() }
        )
        if (state.playing != null) {
            DropdownMenuItem(
                text = { Text("Next song") },
                leadingIcon = { Icon(Icons.Default.SkipNext, null) },
                onClick = { onDismiss(); Perform.run(PerformAction.NEXT_SONG) }
            )
            DropdownMenuItem(
                text = { Text("Previous song") },
                leadingIcon = { Icon(Icons.Default.SkipPrevious, null) },
                onClick = { onDismiss(); Perform.run(PerformAction.PREVIOUS_SONG) }
            )
        }
        DropdownMenuItem(
            text = { Text("Tuner") },
            leadingIcon = { Icon(Icons.Default.GraphicEq, null) },
            onClick = { onDismiss(); Perform.run(PerformAction.TUNER) }
        )
        HorizontalDivider()
        state.current?.let { song ->
            DropdownMenuItem(
                text = { Text("Recordings...") },
                leadingIcon = { Icon(Icons.Default.PlayCircle, null) },
                onClick = { onDismiss(); state.audioOpen = true }
            )
        }
        DropdownMenuItem(
            text = { Text("Play together (lead or follow)...") },
            leadingIcon = { Icon(Icons.Default.Devices, null) },
            onClick = { onDismiss(); state.companionOpen = true }
        )
        state.currentPath?.let {
            DropdownMenuItem(
                text = { Text("Clear all markings on this part...") },
                leadingIcon = { Icon(Icons.Default.CleaningServices, null) },
                onClick = { onDismiss(); state.clearingMarks = true }
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Customise buttons...") },
            leadingIcon = { Icon(Icons.Default.Tune, null) },
            onClick = { onDismiss(); onCustomise() }
        )
    }
}

/**
 * The strip, laid out to be changed: its buttons in order, each dragged by its handle to a new
 * place or taken off with its cross, and every other action below, added with a tap.
 */
@Composable
private fun StripEditor(state: SheetsState, onClose: () -> Unit) {
    SheetDialog(title = "Buttons on the strip", onDismiss = onClose) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { state.edgeTaps = !state.edgeTaps }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Tap the page to turn it (right half on, left half back)", Modifier.weight(1f))
                Switch(checked = state.edgeTaps, onCheckedChange = { state.edgeTaps = it })
            }
            Row(
                Modifier.fillMaxWidth().clickable { state.stripOnLeft = !state.stripOnLeft }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Buttons on the left side", Modifier.weight(1f))
                Switch(checked = state.stripOnLeft, onCheckedChange = { state.stripOnLeft = it })
            }
            Row(
                Modifier.fillMaxWidth().clickable { state.stripLabels = !state.stripLabels }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Names under the buttons", Modifier.weight(1f))
                Switch(checked = state.stripLabels, onCheckedChange = { state.stripLabels = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Text("Show the turn", Modifier.weight(1f))
                for ((style, label) in listOf("slide" to "Slide", "fade" to "Fade", "none" to "None")) {
                    androidx.compose.material3.FilterChip(
                        selected = state.turnStyle == style,
                        onClick = { state.turnStyle = style },
                        label = { Text(label) },
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("On the strip - drag to reorder", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            val order = remember(state.strip) { state.strip.toMutableStateList() }
            var dragging by remember { mutableStateOf<PerformAction?>(null) }
            var dragBy by remember { mutableStateOf(0f) }
            val rowPx = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx() }
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                order.forEach { action ->
                    val lifted = dragging == action
                    androidx.compose.runtime.key(action) {
                        Row(
                            (if (lifted) Modifier.zIndex(1f).graphicsLayer { translationY = dragBy }
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
                            else Modifier).fillMaxWidth().height(48.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.DragHandle, "Drag to reorder",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .pointerInput(action) {
                                        detectDragGestures(
                                            onDragStart = { dragging = action; dragBy = 0f },
                                            onDragEnd = { dragging = null; dragBy = 0f; state.setStripActions(order.toList()) },
                                            onDragCancel = { dragging = null; dragBy = 0f; state.setStripActions(order.toList()) }
                                        ) { change, amount ->
                                            change.consume()
                                            dragBy += amount.y
                                            val from = order.indexOf(action)
                                            val to = (from + (dragBy / rowPx).toInt()).coerceIn(0, order.lastIndex)
                                            if (to != from) {
                                                order.add(to, order.removeAt(from))
                                                dragBy -= (to - from) * rowPx
                                            }
                                        }
                                    }
                                    .padding(12.dp)
                            )
                            Icon(iconOf(action, false), null, Modifier.padding(end = 12.dp))
                            Text(action.label, Modifier.weight(1f))
                            IconButton(onClick = { state.setStripActions(order - action) }) {
                                Icon(Icons.Default.Close, "Take off the strip")
                            }
                        }
                    }
                }
            }
            val rest = PerformAction.entries.filter { it !in order }
            if (rest.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Add a button", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                var query by remember { mutableStateOf("") }
                ListSearch(rest.size, query, { query = it }, "Find a button")
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    rest.filter { matches(query, it.label, shortName(it, false)) }.forEach { action ->
                        AssistChip(
                            onClick = { state.setStripActions(order + action) },
                            label = { Text(action.label) },
                            leadingIcon = { Icon(iconOf(action, false), null, Modifier.size(18.dp)) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * One of the strip's buttons: its icon, and its name under it - so what it does is never a guess.
 * [description] is what a screen reader says.
 */
@Composable
private fun StripButton(
    icon: ImageVector,
    name: String,
    description: String,
    size: androidx.compose.ui.unit.Dp,
    named: Boolean,
    lit: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 2.dp, vertical = 1.dp)
    ) {
        Box(
            Modifier.size(size).then(
                if (lit) Modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp)) else Modifier
            ),
            contentAlignment = Alignment.Center
        ) { Icon(icon, description) }
        if (named) {
            Text(name, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, maxLines = 1, lineHeight = 11.sp)
        }
    }
}

/** A button's name under it: a word or two. */
private fun shortName(action: PerformAction, fullscreen: Boolean): String = when (action) {
    PerformAction.NEXT_PAGE -> "Next"
    PerformAction.PREVIOUS_PAGE -> "Back"
    PerformAction.HALF_PAGE_FORWARD -> "Half on"
    PerformAction.HALF_PAGE_BACK -> "Half back"
    PerformAction.FIRST_PAGE -> "First"
    PerformAction.LAST_PAGE -> "Last"
    PerformAction.NEXT_SONG -> "Next song"
    PerformAction.PREVIOUS_SONG -> "Last song"
    PerformAction.METRONOME -> "Metronome"
    PerformAction.TUNER -> "Tuner"
    PerformAction.PLAY_AUDIO -> "Recording"
    PerformAction.PEN -> "Pen"
    PerformAction.HIGHLIGHTER -> "Highlight"
    PerformAction.ERASER -> "Eraser"
    PerformAction.UNDO -> "Undo"
    PerformAction.REDO -> "Redo"
    PerformAction.FULLSCREEN -> if (fullscreen) "All tools" else "Hide tools"
}

/** Page turns, pen tools, songs, sound, the screen: a thin rule between each. */
private fun groupOf(action: PerformAction): Int = when (action) {
    PerformAction.PEN, PerformAction.HIGHLIGHTER, PerformAction.ERASER, PerformAction.UNDO, PerformAction.REDO -> 1
    PerformAction.NEXT_SONG, PerformAction.PREVIOUS_SONG -> 2
    PerformAction.METRONOME, PerformAction.TUNER, PerformAction.PLAY_AUDIO -> 3
    PerformAction.FULLSCREEN -> 4
    else -> 0
}

private fun iconOf(action: PerformAction, fullscreen: Boolean): ImageVector = when (action) {
    PerformAction.NEXT_PAGE -> Icons.AutoMirrored.Filled.NavigateNext
    PerformAction.PREVIOUS_PAGE -> Icons.AutoMirrored.Filled.NavigateBefore
    PerformAction.HALF_PAGE_FORWARD -> Icons.Default.ExpandMore
    PerformAction.HALF_PAGE_BACK -> Icons.Default.ExpandLess
    PerformAction.FIRST_PAGE -> Icons.Default.FirstPage
    PerformAction.LAST_PAGE -> Icons.Default.LastPage
    PerformAction.NEXT_SONG -> Icons.Default.SkipNext
    PerformAction.PREVIOUS_SONG -> Icons.Default.SkipPrevious
    PerformAction.METRONOME -> Icons.Default.Timer
    PerformAction.TUNER -> Icons.Default.GraphicEq
    PerformAction.PLAY_AUDIO -> if (Recording.playing) Icons.Default.Pause else Icons.Default.PlayCircle
    PerformAction.PEN -> Icons.Default.Edit
    PerformAction.HIGHLIGHTER -> Icons.Default.Highlight
    PerformAction.ERASER -> Icons.Default.CleaningServices
    PerformAction.UNDO -> Icons.AutoMirrored.Filled.Undo
    PerformAction.REDO -> Icons.AutoMirrored.Filled.Redo
    PerformAction.FULLSCREEN -> if (fullscreen) Icons.Default.Construction else Icons.Default.Close
}

private const val K_COLLAPSED = "sheets_strip_collapsed"
