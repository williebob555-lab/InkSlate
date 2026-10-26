package com.inksheets.desktop

import com.inksheets.core.AudioTrack
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.ui.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlayAlongOffTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `browsing while page turns are off does not replace learned timings`() {
        val root = tmp.newFolder()
        val file = File(root, "part.pdf").also { it.writeText("fixture") }
        val prefs = hashMapOf<String, String?>("sheets_library" to root.absolutePath, "sheets_play_along" to "false")
        val platform = object : SheetsPlatform {
            override val deviceId = "playalong-off-test"
            override val startFolder = root
            override val localFolder = tmp.newFolder()
            override val deviceName = "Test stand"
            override val audioOut: AudioOut? = null
            override val microphone: Microphone? = null
            override fun pref(key: String) = prefs[key]
            override fun setPref(key: String, value: String?) { prefs[key] = value }
            override fun openPart(song: Song, part: Part, file: File) {}
            override fun pageText(file: File, page: Int): String? = null
            override fun onMain(block: () -> Unit) = block()
        }
        val state = SheetsState(platform)
        val part = Part(file = "part.pdf", instrument = "euphonium")
        val track = AudioTrack(file = "recording.wav", partTurns = mapOf(part.id to listOf(1200L, 2500L)))
        val song = state.library!!.addSong("Fixture", listOf(part)) { audio = listOf(track) }
        state.current = song
        state.currentPath = file.absolutePath
        state.pageShown = 0 to 3
        val player = object : AudioPlayer {
            override fun load(file: File) = true
            override fun play() { playing = true }
            override fun pause() { playing = false }
            override var playing = false
            override fun seek(ms: Long) { positionMs = ms }
            override var positionMs = 10_000L
            override val durationMs = 60_000L
            override var speed = 1.0
            override var pitch = 0
            override fun setLoop(startMs: Long?, endMs: Long?) {}
            override fun release() {}
        }
        Recording.player = player
        PlayAlong.load(state)
        try {
            Recording.play(state, track, song)
            PlayAlong.pageChanged(state, file.absolutePath, 0)
            state.pageShown = 1 to 3
            PlayAlong.pageChanged(state, file.absolutePath, 1)
            assertEquals(listOf(1200L, 2500L), state.library!!.song(song.id)!!.audio.single().partTurns[part.id])
            // Re-enabling restores intentional teaching without needing a new recording session.
            PlayAlong.toggle(state)
            player.positionMs = 15_000L
            state.pageShown = 2 to 3
            PlayAlong.pageChanged(state, file.absolutePath, 2)
            assertEquals(listOf(1200L, 15_000L), state.library!!.song(song.id)!!.audio.single().partTurns[part.id])
        } finally {
            Recording.end(state)
            Recording.player = null
            Recording.loadedFile = null
            if (!PlayAlong.on) PlayAlong.toggle(state)
        }
    }
}
