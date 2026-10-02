package com.inksheets.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The music playing, steered as it goes: back or on a line or a bar, paused and gone on with, a
 * little slower or faster - at once, from the bar it is in - and stopped. A tap on any bar of the
 * page plays from there. Along the foot of the page, between the strips, while it plays or waits.
 */
@Composable
fun BoxScope.PlaybackBar(state: SheetsState) {
    val playing = ScoreTools.playing
    val paused = ScoreTools.paused
    if (playing == null && paused == null) return
    val bar = playing?.first ?: paused ?: return
    // A window over the music like any other: where the strips leave it too little room (a small
    // phone stood up), they step aside to their tabs while it plays.
    Opened("Playing", 300.dp)
    Surface(
        shape = RoundedCornerShape(26.dp),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        modifier = Modifier.align(Alignment.BottomCenter)
            .padding(start = Overlays.left + 8.dp, end = Overlays.right + 8.dp, bottom = 14.dp).widthIn(max = 720.dp)
    ) {
        BoxWithConstraints {
            // A phone stood up, the strips out either side: smaller buttons, all of them still there.
            val narrow = maxWidth < 300.dp
            @Composable
            fun Btn(icon: ImageVector, what: String, main: Boolean = false, onClick: () -> Unit) =
                if (main) FilledIconButton(onClick = onClick, modifier = Modifier.size(if (narrow) 40.dp else 48.dp)) { Icon(icon, what) }
                else FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(if (narrow) 34.dp else 42.dp)) { Icon(icon, what) }
            val moves: @Composable () -> Unit = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Btn(Icons.Default.SkipPrevious, "Back a line") { ScoreTools.stepLines(state, -1) }
                    Btn(Icons.Default.FastRewind, "Back a bar") { ScoreTools.stepBars(state, -1) }
                    if (playing != null) Btn(Icons.Default.Pause, "Pause", main = true) { ScoreTools.pause(state) }
                    else Btn(Icons.Default.PlayArrow, "Go on from bar $bar", main = true) { ScoreTools.resume(state) }
                    Btn(Icons.Default.FastForward, "On a bar") { ScoreTools.stepBars(state, 1) }
                    Btn(Icons.Default.SkipNext, "On a line") { ScoreTools.stepLines(state, 1) }
                }
            }
            val tempo: @Composable () -> Unit = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Btn(Icons.Default.Remove, "Slower") { ScoreTools.nudgeTempo(state, -4) }
                    Text("♩ ${playing?.second ?: SharedMetronome.bpm.toInt()}", style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 52.dp))
                    Btn(Icons.Default.Add, "Faster") { ScoreTools.nudgeTempo(state, 4) }
                    Btn(Icons.Default.Stop, "Stop") { ScoreTools.stop(state) }
                }
            }
            val where: @Composable () -> Unit = {
                Text((if (playing != null) "Bar $bar" else "Paused at bar $bar") + " · tap a bar to play from it",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            // One row where there is room for it all; else the moves over the tempo.
            if (maxWidth >= 600.dp) Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) { moves(); tempo() }
                where()
            } else Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                moves(); tempo(); where()
            }
        }
    }
}
