package com.inksheets.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inksheets.core.AudioTrack
import com.inksheets.core.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "m4a", "aac", "ogg", "flac", "aif", "aiff")

/** The one player, shared by the panel and the play button on the strip, so both see one state. */
internal object Recording {
    var player: AudioPlayer? = null
    var loadedFile by mutableStateOf<String?>(null)
    var playing by mutableStateOf(false)

    fun playerFor(state: SheetsState): AudioPlayer? =
        player ?: state.platform.audioPlayer()?.also { player = it }

    /** Load [track] (off the UI thread) and apply its speed, pitch and loop. */
    fun load(state: SheetsState, track: AudioTrack): Boolean {
        val p = playerFor(state) ?: return false
        if (loadedFile != track.file) {
            val file = state.fileOf(track.file) ?: return false
            if (!p.load(file)) return false
            loadedFile = track.file
        }
        p.speed = track.speed
        p.pitch = track.pitch
        p.setLoop(track.loopStartMs, track.loopEndMs)
        return true
    }

    /** The strip's play button and the pedal: the current song's first recording, played or paused. */
    fun toggle(state: SheetsState) {
        val p = playerFor(state) ?: return
        if (p.playing) { p.pause(); playing = false; return }
        val track = state.current?.audio?.firstOrNull() ?: return
        Thread {
            if (load(state, track)) { p.play(); playing = true }
        }.apply { isDaemon = true; start() }
    }
}

/** A song's recordings: play, loop a passage, slow it down, shift its pitch; and pair new ones. */
@Composable
internal fun AudioDialog(state: SheetsState, song: Song, onClose: () -> Unit) {
    var tracks by remember { mutableStateOf(song.audio) }
    var picking by remember { mutableStateOf(tracks.isEmpty()) }
    var selected by remember { mutableStateOf(0) }

    fun save(updated: List<AudioTrack>) {
        tracks = updated
        state.change { editSong(song.id) { audio = updated } }
    }

    if (picking) {
        AudioFilePicker(
            state,
            onChosen = { rel ->
                save(tracks + AudioTrack(file = rel, label = rel.substringAfterLast('/').substringBeforeLast('.')))
                selected = tracks.lastIndex
                picking = false
            },
            onDismiss = { if (tracks.isEmpty()) onClose() else picking = false }
        )
        return
    }

    val track = tracks.getOrNull(selected) ?: return
    val player = remember { Recording.playerFor(state) }
    var loaded by remember(track.file) { mutableStateOf(Recording.loadedFile == track.file) }
    var failed by remember(track.file) { mutableStateOf(false) }
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }

    LaunchedEffect(track.file) {
        if (!loaded) {
            val ok = withContext(Dispatchers.IO) { Recording.load(state, track) }
            loaded = ok
            failed = !ok
        }
    }
    LaunchedEffect(loaded) {
        while (loaded) {
            position = player?.positionMs ?: 0
            duration = player?.durationMs ?: 0
            Recording.playing = player?.playing == true
            delay(100)
        }
    }

    fun update(change: (AudioTrack) -> AudioTrack) {
        val t = change(track)
        save(tracks.toMutableList().also { it[selected] = t })
        player?.speed = t.speed
        player?.pitch = t.pitch
        player?.setLoop(t.loopStartMs, t.loopEndMs)
    }

    SheetDialog(
        title = "Recordings - ${song.title}",
        onDismiss = onClose,
        wide = true,
        buttons = {
            TextButton(onClick = { picking = true }) { Text("Pair another") }
            TextButton(onClick = onClose) { Text("Close") }
        }
    ) {
        Column {
            if (tracks.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tracks.forEachIndexed { i, t ->
                        FilterChip(selected = i == selected, onClick = { selected = i }, label = { Text(t.label ?: "Track ${i + 1}") })
                    }
                }
            }
            if (player == null) {
                Text("This device cannot play recordings.", color = MaterialTheme.colorScheme.error)
                return@Column
            }
            if (failed) {
                Text("${track.file} could not be played here.", color = MaterialTheme.colorScheme.error)
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(onClick = {
                    if (player.playing) player.pause() else player.play()
                    Recording.playing = player.playing
                }, enabled = loaded) {
                    Icon(if (Recording.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause")
                }
                Spacer(Modifier.width(12.dp))
                Text("${clock(position)} / ${clock(duration)}")
            }
            Slider(
                value = if (duration > 0) position.toFloat() / duration else 0f,
                onValueChange = { player.seek((it * duration).toLong()); position = (it * duration).toLong() },
                enabled = loaded
            )

            Text("Loop a passage", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { update { it.copy(loopStartMs = position) } }) {
                    Text("A " + (track.loopStartMs?.let(::clock) ?: "–"))
                }
                OutlinedButton(onClick = { update { it.copy(loopEndMs = position) } }) {
                    Text("B " + (track.loopEndMs?.let(::clock) ?: "–"))
                }
                TextButton(onClick = { update { it.copy(loopStartMs = null, loopEndMs = null) } }) { Text("No loop") }
            }

            Spacer(Modifier.padding(4.dp))
            Text("Speed ${(track.speed * 100).roundToInt()}%  (the pitch stays)", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = track.speed.toFloat(),
                onValueChange = { v -> update { it.copy(speed = (v * 20).roundToInt() / 20.0) } },
                valueRange = 0.5f..1.25f,
                steps = 14
            )
            Text("Pitch ${if (track.pitch > 0) "+" else ""}${track.pitch} semitones", style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { update { it.copy(pitch = (it.pitch - 1).coerceAtLeast(-12)) } }) { Text("−1") }
                TextButton(onClick = { update { it.copy(pitch = 0) } }) { Text("Original") }
                TextButton(onClick = { update { it.copy(pitch = (it.pitch + 1).coerceAtMost(12)) } }) { Text("+1") }
            }
            TextButton(onClick = {
                player.pause()
                Recording.loadedFile = null
                save(tracks.filterIndexed { i, _ -> i != selected })
                selected = 0
                if (tracks.isEmpty()) onClose()
            }) { Text("Unpair this recording") }
        }
    }
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** Choosing a recording inside the music folder. */
@Composable
private fun AudioFilePicker(state: SheetsState, onChosen: (String) -> Unit, onDismiss: () -> Unit) {
    val root = state.root ?: return
    var at by remember { mutableStateOf(root) }
    val entries = remember(at) {
        at.listFiles { f -> !f.name.startsWith(".") && (f.isDirectory || f.extension.lowercase() in AUDIO_EXTENSIONS) }
            .orEmpty().sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }
    SheetDialog(title = "Pair a recording", onDismiss = onDismiss, wide = true, buttons = {
        TextButton(onClick = onDismiss) { Text("Cancel") }
    }) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { at.parentFile?.let { at = it } }, enabled = at.canonicalPath != root.canonicalPath) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up")
                }
                Text(state.relative(at)?.ifEmpty { "Music folder" } ?: at.name, style = MaterialTheme.typography.bodySmall)
            }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(entries, key = { it.path }) { f ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (f.isDirectory) at = f else state.relative(f)?.let(onChosen)
                        }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(if (f.isDirectory) Icons.Default.Folder else Icons.Default.AudioFile, null)
                        Spacer(Modifier.width(12.dp))
                        Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (entries.none { !it.isDirectory } && at == root) {
                Text(
                    "Recordings need to be in the music folder so they sync with the song.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
