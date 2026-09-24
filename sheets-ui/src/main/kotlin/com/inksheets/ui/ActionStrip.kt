package com.inksheets.ui

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
    var shown by remember { mutableStateOf(state.stripActions()) }

    Surface(
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp),
            modifier = Modifier.verticalScroll(rememberScrollState())
        ) {
            if (!collapsed) {
                // Where in a setlist this song is, when one is being played.
                state.playing?.let { (setlistId, index) ->
                    val total = state.library?.setlist(setlistId)?.entries?.size ?: 0
                    Text(
                        "${index + 1}/$total",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                var lastGroup = -1
                for (action in shown) {
                    val songAction = action == PerformAction.NEXT_SONG || action == PerformAction.PREVIOUS_SONG
                    if (songAction && state.playing == null) continue
                    if (action == PerformAction.PLAY_AUDIO && state.current?.audio.isNullOrEmpty()) continue
                    val group = groupOf(action)
                    if (lastGroup >= 0 && group != lastGroup) {
                        HorizontalDivider(Modifier.width(24.dp).padding(vertical = 2.dp))
                    }
                    lastGroup = group
                    val lit = (action == PerformAction.METRONOME && SharedMetronome.running) ||
                        (action != PerformAction.FULLSCREEN && Perform.on(action))
                    IconButton(
                        onClick = { Perform.run(action) },
                        colors = if (lit) IconButtonDefaults.filledTonalIconButtonColors() else IconButtonDefaults.iconButtonColors(),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(iconOf(action, Perform.on(PerformAction.FULLSCREEN)), action.label)
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Buttons") }
                    StripMenu(state, menu, onDismiss = { menu = false }, onChanged = { shown = state.stripActions() })
                }
            }
            IconButton(onClick = {
                collapsed = !collapsed
                state.platform.setPref(K_COLLAPSED, collapsed.toString())
            }, modifier = Modifier.size(36.dp)) {
                Icon(if (collapsed) Icons.Default.UnfoldMore else Icons.Default.UnfoldLess, if (collapsed) "Show buttons" else "Hide buttons")
            }
        }
    }

    if (state.tunerOpen) TunerDialog(state, onClose = { state.tunerOpen = false })
    if (state.metronomeOpen) MetronomeDialog(state, onClose = { state.metronomeOpen = false })
    val song = state.current
    if (state.audioOpen && song != null) AudioDialog(state, song, onClose = { state.audioOpen = false })
}

@Composable
private fun StripMenu(state: SheetsState, open: Boolean, onDismiss: () -> Unit, onChanged: () -> Unit) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        state.current?.let { song ->
            DropdownMenuItem(
                text = { Text("Recordings...") },
                leadingIcon = { Icon(Icons.Default.PlayCircle, null) },
                onClick = { onDismiss(); state.audioOpen = true }
            )
        }
        DropdownMenuItem(
            text = { Text("Metronome settings...") },
            leadingIcon = { Icon(Icons.Default.Timer, null) },
            onClick = { onDismiss(); state.metronomeOpen = true }
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Tap the sides of the page to turn it") },
            leadingIcon = { Checkbox(checked = state.edgeTaps, onCheckedChange = null) },
            onClick = { state.edgeTaps = !state.edgeTaps }
        )
        HorizontalDivider()
        Text("Buttons", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        val chosen = state.stripActions()
        PerformAction.entries.forEach { action ->
            DropdownMenuItem(
                text = { Text(action.label) },
                leadingIcon = { Checkbox(checked = action in chosen, onCheckedChange = null) },
                onClick = {
                    state.setStripActions(if (action in chosen) chosen - action else (chosen + action).sortedWith(compareBy({ groupOf(it) }, { it.ordinal })))
                    onChanged()
                }
            )
        }
    }
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
    PerformAction.HIGHLIGHTER -> Icons.Default.BorderColor
    PerformAction.ERASER -> Icons.Default.CleaningServices
    PerformAction.UNDO -> Icons.AutoMirrored.Filled.Undo
    PerformAction.REDO -> Icons.AutoMirrored.Filled.Redo
    PerformAction.FULLSCREEN -> if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen
}

private const val K_COLLAPSED = "sheets_strip_collapsed"
