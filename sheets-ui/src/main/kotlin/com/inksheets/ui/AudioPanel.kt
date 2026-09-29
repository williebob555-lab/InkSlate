package com.inksheets.ui

import java.io.File
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
        p.volume = track.volume
        p.setLoop(track.loopStartMs, track.loopEndMs)
        return true
    }

    /** The strip's play button and the pedal: the current song's first recording, played or paused. */
    fun toggle(state: SheetsState) {
        val p = playerFor(state) ?: return
        if (p.playing || Click.purpose == Click.Purpose.PLAYBACK) { pause(state); return }
        val track = state.current?.audio?.firstOrNull() ?: return
        val song = state.current
        Thread {
            if (load(state, track)) state.platform.onMain { play(state, track, song) }
        }.apply { isDaemon = true; start() }
    }

    /** The metronome as it was before a recording set it to its own tempo, to put back after. */
    private var before: com.inksheets.core.Metronome.Settings? = null

    /** The song whose recording is playing; its tab closing stops it (and nothing else does). */
    var songId: String? = null
    /** That song had a tab open while it played - one played from Home without a tab is left alone. */
    var hadTab = false

    /**
     * Playback mode: a recording has been played and not put away - playing or paused. The
     * playback column stands beside the strip for as long as it lasts.
     */
    var session by mutableStateOf(false)
        private set
    /** What playback mode plays: the recording played last, and its song. */
    var track: AudioTrack? = null
        private set
    var song: Song? = null
        private set

    /**
     * Play [track] (loaded) from where it is: counted in first when a count-in is set, and with the
     * click under it, in time with it, when that is turned on. The click's tempo is the one the
     * recording was made at, else the song's, else the metronome's - all scaled by the speed.
     */
    fun play(state: SheetsState, track: AudioTrack, song: Song?) {
        val p = playerFor(state) ?: return
        watch(state)
        this.track = track
        this.song = song
        session = true
        songId = song?.id
        hadTab = song != null && state.hasTab(song.id)
        val click = Click.withPlayback(state)
        val e = Click.engine(state)
        if (e == null || (!click && Click.countInBars(state) == 0)) { p.play(); playing = true; return }
        before = before ?: e.settings
        val base = track.clickBpm ?: song?.tempo?.toDouble() ?: e.settings.bpm
        e.settings = e.settings.copy(bpm = (base * track.speed).coerceIn(20.0, 300.0), beatsPerBar = track.beatsPerBar ?: e.settings.beatsPerBar)
        SharedMetronome.bpm = e.settings.bpm
        val first = track.firstBeatMs ?: 0L
        val phase = (p.positionMs - first) / track.speed
        playing = true
        Click.countInThen(state, Click.Purpose.PLAYBACK, keepGoing = click, phaseMs = phase) {
            Thread({ p.play() }, "play-after-count").apply { isDaemon = true; start() }
        }
        if (click) follow(state, p, e, track)
    }

    @Volatile private var watching = false

    /**
     * [playing] kept true to the player while no panel is open to read it, so a recording that
     * ends by itself does not leave a stop button behind (and one still playing always has one).
     */
    private fun watch(state: SheetsState) {
        if (watching) return
        watching = true
        Thread({
            var quiet = 0
            try {
                while (true) {
                    Thread.sleep(300)
                    val p = player ?: break
                    val still = p.playing || Click.purpose == Click.Purpose.PLAYBACK
                    quiet = if (still) 0 else quiet + 1
                    // Twice in a row: a count-in handing over to the player is not an end.
                    if (quiet >= 2) { state.platform.onMain { if (player?.playing != true && Click.purpose != Click.Purpose.PLAYBACK) playing = false }; break }
                }
            } finally { watching = false }
        }, "recording-watch").apply { isDaemon = true; start() }
    }

    fun pause(state: SheetsState) {
        player?.pause()
        playing = false
        if (Click.purpose == Click.Purpose.PLAYBACK) Click.stop(state)
        before?.let { b -> Click.engine(state)?.settings = b; SharedMetronome.bpm = b.bpm }
        before = null
    }

    /** Out of playback mode: stopped, and the column put away. */
    fun end(state: SheetsState) {
        pause(state)
        session = false
    }

    /** Back or on by [seconds] (a minus goes back), within the recording. */
    fun skip(seconds: Double) {
        val p = player ?: return
        p.seek((p.positionMs + (seconds * 1000).toLong()).coerceIn(0L, p.durationMs.coerceAtLeast(0L)))
    }

    /** From the start again - the loop's start where there is a loop - and playing. */
    fun replay(state: SheetsState) {
        val p = player ?: return
        val t = track ?: return
        if (p.playing || Click.purpose == Click.Purpose.PLAYBACK) pause(state)
        p.seek(t.loopStartMs ?: 0L)
        play(state, t, song)
    }

    /** Play or pause what playback mode holds. */
    fun playPause(state: SheetsState) {
        val t = track ?: return
        if (playing) pause(state) else play(state, t, song)
    }

    /**
     * Keep the click in time with the recording while it plays: a loop going round, a seek, or the
     * player catching up after a stall, and the click is put where the music is. When the recording
     * ends, the click stops too.
     */
    private fun follow(state: SheetsState, p: AudioPlayer, e: com.inksheets.core.Metronome, track: AudioTrack) {
        val first = track.firstBeatMs ?: 0L
        Thread({
            var last = -1L
            var lastAt = 0L
            var began = false
            var quietSince = 0L
            while (Click.live == Click.Purpose.PLAYBACK) {
                Thread.sleep(50)
                val now = System.currentTimeMillis()
                if (!p.playing) {
                    last = -1
                    if (began) {
                        if (quietSince == 0L) quietSince = now
                        if (now - quietSince > 600) { state.platform.onMain { if (playing) pause(state) }; return@Thread }
                    }
                    continue
                }
                began = true
                quietSince = 0L
                val pos = p.positionMs
                if (last >= 0) {
                    val expected = last + (now - lastAt) * track.speed
                    if (kotlin.math.abs(pos - expected) > 300) e.phaseTo((pos - first) / track.speed)
                }
                last = pos
                lastAt = now
            }
        }, "click-follow").apply { isDaemon = true; start() }
    }
}

/** Recording yourself: the microphone into a WAV in the music folder, paired with the song after. */
internal object SelfRecorder {
    var recording by mutableStateOf(false)
    var seconds by mutableStateOf(0)
    private var writer: com.inksheets.core.WavWriter? = null
    private var song: Song? = null

    /** A title as a folder name: the characters Windows will not have in one taken out. */
    private fun folderName(title: String) = title.map { if (it in "\\/:*?\"<>|") ' ' else it }.joinToString("").trim()

    fun start(state: SheetsState, song: Song): Boolean {
        val mic = state.platform.microphone ?: return false
        val root = state.root ?: return false
        val stamp = java.time.LocalDateTime.now().withNano(0).toString().replace(':', '-')
        val file = java.io.File(root, "Recordings/${folderName(song.title)}/$stamp.wav")
        file.parentFile?.mkdirs()
        val w = com.inksheets.core.WavWriter(file, mic.sampleRate)
        // Counted in first when a count-in is set; the count-in itself is left out of the take, so
        // the recording starts on the first beat - and a click played with it later lines up.
        val e = Click.engine(state)
        val bars = if (e != null) Click.countInBars(state) else 0
        val clickOn = e != null && Click.withRecording(state)
        clicked = e != null && (bars > 0 || clickOn)
        bpm = e?.settings?.bpm
        beatsPerBar = e?.settings?.beatsPerBar
        var skip = if (e != null && bars > 0) (mic.sampleRate * e.msFor(bars) / 1000.0).toLong() else 0L
        val started = mic.start { whole ->
            var chunk = whole
            if (skip > 0) {
                if (whole.size <= skip) { skip -= whole.size; return@start }
                chunk = whole.copyOfRange(skip.toInt(), whole.size)
                skip = 0
            }
            w.write(chunk)
            val s = w.seconds.toInt()
            if (s != seconds) state.platform.onMain { seconds = s }
        }
        if (!started) {
            w.close()
            file.delete()
            return false
        }
        writer = w
        this.song = song
        seconds = 0
        recording = true
        if (clicked) Click.countInThen(state, Click.Purpose.RECORD, keepGoing = clickOn) { }
        return true
    }

    private var clicked = false
    private var bpm: Double? = null
    private var beatsPerBar: Int? = null

    /** Stop, and pair the take with the song, labelled with when it was made. */
    fun stop(state: SheetsState) {
        state.platform.microphone?.stop()
        if (Click.purpose == Click.Purpose.RECORD) Click.stop(state)
        val w = writer ?: return
        writer = null
        recording = false
        w.close()
        val s = song ?: return
        val rel = state.relative(w.file) ?: return
        val label = "Me, " + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm"))
        val current = state.library?.song(s.id) ?: return
        // Made to a click: its tempo kept, so a click played with it later is in time.
        val track = if (clicked) AudioTrack(file = rel, label = label, clickBpm = bpm, beatsPerBar = beatsPerBar, firstBeatMs = 0)
            else AudioTrack(file = rel, label = label)
        state.change { editSong(s.id) { audio = current.audio + track } }
    }
}

/**
 * A song's recordings, laid out like its parts: Record yourself and Pair a recording always at
 * the top - with no recording yet, too - then the recordings in order. The first is the one the
 * Play button plays; a recording's menu puts another first, or moves it up or down. Under the
 * list, the one picked: play, loop a passage, slow it down, shift its pitch.
 */
@Composable
internal fun AudioDialog(state: SheetsState, song: Song, movable: Boolean = false, onClose: () -> Unit) {
    var tracks by remember { mutableStateOf(state.library?.song(song.id)?.audio ?: song.audio) }
    var picking by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(0) }
    var renaming by remember { mutableStateOf<Int?>(null) }
    var couldNotRecord by remember { mutableStateOf(false) }

    fun save(updated: List<AudioTrack>) {
        tracks = updated
        state.change { editSong(song.id) { audio = updated } }
    }

    if (picking) {
        AudioFilePicker(
            state,
            song,
            onChosen = { rel ->
                save(tracks + AudioTrack(file = rel, label = rel.substringAfterLast('/').substringBeforeLast('.')))
                selected = tracks.lastIndex
                picking = false
            },
            onDismiss = { picking = false }
        )
        return
    }

    SheetDialog(
        title = "Recordings - ${song.title}",
        onDismiss = onClose,
        wide = true,
        movable = movable,
        buttons = if (movable) null else ({ TextButton(onClick = onClose) { Text("Close") } })
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (SelfRecorder.recording) {
                    Button(
                        onClick = {
                            SelfRecorder.stop(state)
                            tracks = state.library?.song(song.id)?.audio ?: tracks
                            selected = tracks.lastIndex.coerceAtLeast(0)
                        },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Stop, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Stop recording (${clock(SelfRecorder.seconds * 1000L)})")
                    }
                } else {
                    Button(
                        onClick = { couldNotRecord = !SelfRecorder.start(state, song) },
                        enabled = state.platform.microphone != null
                    ) {
                        Icon(Icons.Default.Mic, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Record yourself")
                    }
                }
                OutlinedButton(onClick = { picking = true }) {
                    Icon(Icons.Default.AudioFile, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Pair a recording")
                }
            }
            if (state.platform.microphone != null && state.platform.audioOut != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { Click.setWithRecording(state, !Click.withRecording(state)) }) {
                    Text("Click while recording", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    androidx.compose.material3.Switch(checked = Click.withRecording(state), onCheckedChange = { Click.setWithRecording(state, it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Count in", style = MaterialTheme.typography.labelSmall)
                    listOf(0 to "Off", 1 to "1 bar", 2 to "2 bars").forEach { (n, label) ->
                        FilterChip(selected = Click.countInBars(state) == n, onClick = { Click.setCountInBars(state, n) }, label = { Text(label) })
                    }
                    Text("\u2669 = ${SharedMetronome.bpm.roundToInt()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (SelfRecorder.recording && Click.counting > 0) {
                    Text("Counting in... ${Click.counting}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
                }
            }
            when {
                state.platform.microphone == null -> Hint("This device has no microphone to record with.")
                couldNotRecord -> Hint("The microphone could not be opened. Is it allowed for InkSheets?", error = true)
            }

            Spacer(Modifier.size(12.dp))
            Text("Recordings", style = MaterialTheme.typography.titleMedium)
            if (tracks.isEmpty()) {
                Hint("None yet. Record yourself playing it, or pair a recording from your music folder. The first one here is the one the Play button plays.")
            } else {
                if (tracks.size > 1) Hint("The first one plays from the Play button. Use a recording's menu to put another first.")
                tracks.forEachIndexed { i, t ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (i == selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { selected = i }
                            .padding(start = 10.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(24.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.label ?: "Recording ${i + 1}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                (if (i == 0) "Plays first  ·  " else "") + t.file.substringAfterLast('/'),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        var menu by remember { mutableStateOf(false) }
                        androidx.compose.foundation.layout.Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Recording options") }
                            androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                fun move(to: Int) {
                                    val list = tracks.toMutableList()
                                    list.add(to, list.removeAt(i))
                                    save(list)
                                    selected = to
                                }
                                if (i > 0) {
                                    androidx.compose.material3.DropdownMenuItem(text = { Text("Play this one first") }, onClick = { menu = false; move(0) })
                                    androidx.compose.material3.DropdownMenuItem(text = { Text("Move up") }, onClick = { menu = false; move(i - 1) })
                                }
                                if (i < tracks.lastIndex) {
                                    androidx.compose.material3.DropdownMenuItem(text = { Text("Move down") }, onClick = { menu = false; move(i + 1) })
                                }
                                androidx.compose.material3.DropdownMenuItem(text = { Text("Rename...") }, onClick = { menu = false; renaming = i })
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("Unpair", color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        menu = false
                                        if (Recording.loadedFile == t.file) {
                                            Recording.end(state); Recording.loadedFile = null
                                        }
                                        save(tracks.filterIndexed { j, _ -> j != i })
                                        selected = selected.coerceAtMost(tracks.lastIndex).coerceAtLeast(0)
                                    }
                                )
                            }
                        }
                    }
                }
                tracks.getOrNull(selected)?.let { track ->
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    TrackControls(state, song.id, track) { change ->
                        val at = tracks.indexOfFirst { it.file == track.file }.takeIf { it >= 0 } ?: return@TrackControls
                        val t = change(track)
                        save(tracks.toMutableList().also { it[at] = t })
                        Recording.player?.let { p -> p.speed = t.speed; p.pitch = t.pitch; p.volume = t.volume; p.setLoop(t.loopStartMs, t.loopEndMs) }
                    }
                }
            }
        }
    }

    renaming?.let { i ->
        val t = tracks.getOrNull(i)
        if (t == null) renaming = null else AskName(
            title = "Name this recording", initial = t.label.orEmpty(), confirm = "Rename",
            onDone = { name -> save(tracks.toMutableList().also { it[i] = t.copy(label = name.trim().ifEmpty { null }) }); renaming = null },
            onDismiss = { renaming = null }
        )
    }
}

@Composable
private fun Hint(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

/** The picked recording: play, loop a passage, slow it down, shift its pitch. */
@Composable
private fun TrackControls(state: SheetsState, songId: String, track: AudioTrack, update: ((AudioTrack) -> AudioTrack) -> Unit) {
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
            // Counting in, it is not playing yet, but it is on its way.
            if (Click.purpose != Click.Purpose.PLAYBACK) Recording.playing = player?.playing == true
            delay(100)
        }
    }

    Column {
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
                if (Recording.playing) Recording.pause(state) else Recording.play(state, track, state.library?.song(songId))
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
        // Heard at once while dragging; kept with the recording, like its speed.
        Text("Volume ${(track.volume * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = kotlin.math.sqrt(track.volume).toFloat(),
            onValueChange = { v ->
                val vol = ((v * v) * 100).roundToInt() / 100.0
                player.volume = vol
                update { it.copy(volume = vol) }
            },
            valueRange = 0f..1f
        )
        Text("Speed ${(track.speed * 100).roundToInt()}%  (the pitch stays)", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = track.speed.toFloat(),
            onValueChange = { v -> update { it.copy(speed = (v * 20).roundToInt() / 20.0) } },
            valueRange = 0.5f..1.25f,
            steps = 14
        )
        ClickWithTrack(state, songId, track, position, update)
        Text("Pitch ${if (track.pitch > 0) "+" else ""}${track.pitch} semitones", style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { update { it.copy(pitch = (it.pitch - 1).coerceAtLeast(-12)) } }) { Text("−1") }
            TextButton(onClick = { update { it.copy(pitch = 0) } }) { Text("Original") }
            TextButton(onClick = { update { it.copy(pitch = (it.pitch + 1).coerceAtMost(12)) } }) { Text("+1") }
        }
    }
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** Choosing a recording inside the music folder, so it syncs with the song. */
@Composable
private fun AudioFilePicker(state: SheetsState, song: Song, onChosen: (String) -> Unit, onDismiss: () -> Unit) {
    val root = state.root ?: return
    // The system's picker: one already in the music folder is paired, one from anywhere else is
    // copied in first.
    NativePickers.file?.let { pick ->
        NativeChoice(pick = { pick("Choose a recording", root, AUDIO_EXTENSIONS) }) { f ->
            when {
                f == null -> onDismiss()
                f.isInside(root) -> state.relative(f)?.let(onChosen) ?: onDismiss()
                else -> runCatching { importRecording(root, song, f) }.getOrNull()?.let { state.relative(it) }?.let(onChosen) ?: onDismiss()
            }
        }
        return
    }
    var importing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    if (importing) {
        // Anywhere at all; what is chosen is copied into the music folder, so it syncs with the song.
        val home = File(System.getProperty("user.home") ?: "/")
        val start = listOf(File("/storage/emulated/0/Download"), File(home, "Downloads"), File(home, "Music"), home)
            .first { it.isDirectory }
        FilePickerDialog(
            title = "Import a recording",
            start = start,
            extensions = AUDIO_EXTENSIONS,
            onChosen = { f ->
                runCatching { importRecording(root, song, f) }
                    .onSuccess { state.relative(it)?.let(onChosen) }
                    .onFailure { failed = "${f.name} could not be copied: ${it.message}" }
            },
            onDismiss = { importing = false },
            note = failed ?: "A copy goes into the Recordings folder of your music folder."
        )
        return
    }
    FilePickerDialog(
        title = "Pair a recording",
        start = root,
        within = root,
        extensions = AUDIO_EXTENSIONS,
        onChosen = { f -> state.relative(f)?.let(onChosen) },
        onDismiss = onDismiss,
        extra = { TextButton(onClick = { importing = true }) { Text("Import from elsewhere...") } }
    )
}

/** Copy a recording into `Recordings/` in the music folder, named for the song, never over another. */
internal fun importRecording(root: File, song: Song, from: File): File {
    val dir = File(root, "Recordings").apply { mkdirs() }
    val base = (song.title + " - " + from.nameWithoutExtension).replace(Regex("[\\\\/:*?\"<>|]"), "_")
    var target = File(dir, "$base.${from.extension}")
    var n = 2
    while (target.exists()) target = File(dir, "$base ($n).${from.extension}").also { n++ }
    from.copyTo(target)
    return target
}

/**
 * The click under a recording: on or off, the count-in before it, and the tempo and first beat it
 * keeps to - read from how it was made, else the song's tempo, and set here for any other.
 */
@Composable
private fun ClickWithTrack(state: SheetsState, songId: String, track: AudioTrack, position: Long, update: ((AudioTrack) -> AudioTrack) -> Unit) {
    val songTempo = state.library?.song(songId)?.tempo
    val bpm = track.clickBpm ?: songTempo?.toDouble()
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { Click.setWithPlayback(state, !Click.withPlayback(state)) }) {
            Column(Modifier.weight(1f)) {
                Text("Click with the recording", style = MaterialTheme.typography.labelMedium)
                Text(
                    (bpm?.let { "\u2669 = ${it.roundToInt()}" + if (track.clickBpm == null) " (the song's tempo)" else "" } ?: "\u2669 = the metronome's tempo") +
                        (track.firstBeatMs?.takeIf { it > 0 }?.let { "  \u00B7  first beat at ${clock(it)}" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            androidx.compose.material3.Switch(checked = Click.withPlayback(state), onCheckedChange = { Click.setWithPlayback(state, it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Tempo", style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { update { it.copy(clickBpm = ((bpm ?: SharedMetronome.bpm) - 1).coerceAtLeast(20.0)) } }) { Text("\u22121") }
            Text("${(bpm ?: SharedMetronome.bpm).roundToInt()}", style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { update { it.copy(clickBpm = ((bpm ?: SharedMetronome.bpm) + 1).coerceAtMost(300.0)) } }) { Text("+1") }
            TextButton(onClick = { update { it.copy(firstBeatMs = position) } }) { Text("First beat here") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Count in", style = MaterialTheme.typography.labelSmall)
            listOf(0 to "Off", 1 to "1 bar", 2 to "2 bars").forEach { (n, label) ->
                FilterChip(selected = Click.countInBars(state) == n, onClick = { Click.setCountInBars(state, n) }, label = { Text(label) })
            }
        }
    }
}
