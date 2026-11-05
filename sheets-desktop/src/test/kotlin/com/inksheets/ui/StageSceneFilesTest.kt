package com.inksheets.ui

import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.core.omr.Score
import com.inksheets.core.omr.ScoreDocument
import com.inksheets.core.omr.ScorePart
import com.inksheets.core.omr.StageSceneEditing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StageSceneFilesTest {
    @get:Rule val temp = TemporaryFolder()

    private class Platform(override val startFolder: File) : SheetsPlatform {
        private val prefs = mutableMapOf("sheets_library" to startFolder.absolutePath)
        override val deviceId = "stage-scene-test"
        override val deviceName = "Test stand"
        override val localFolder = File(startFolder, "local")
        override val audioOut: AudioOut? = null
        override val microphone: Microphone? = null
        override fun pref(key: String) = prefs[key]
        override fun setPref(key: String, value: String?) { if (value == null) prefs.remove(key) else prefs[key] = value }
        override fun openPart(song: Song, part: Part, file: File) {}
        override fun pageText(file: File, page: Int): String? = null
        override fun onMain(block: () -> Unit) = block()
    }

    @Test fun `stage sidecar saves atomically reloads and corrupt data falls back to a preset`() {
        val root = temp.newFolder("stage")
        val state = SheetsState(Platform(root))
        state.change { addSong("Stage song", listOf(Part(id = "flute", file = "flute.pdf", instrument = "flute"))) }
        state.current = state.library!!.songs.single { it.title == "Stage song" }
        val document = ScoreDocument(listOf(ScorePart("flute", listOf(Score(emptyList(), 1)), instrument = "flute")))
        val songId = state.current!!.id
        val initial = StageSceneFiles.load(state, songId, document)
        assertEquals(listOf("flute"), initial.performers.map { it.partId })

        val moved = StageSceneEditing.movePerformer(initial, 0, 3.0, 4.0)
        val saved = StageSceneFiles.save(state, songId, moved).getOrThrow()
        assertTrue(saved.isFile)
        assertEquals(moved, StageSceneFiles.load(state, songId, document))

        saved.writeText("truncated")
        val fallback = StageSceneFiles.load(state, songId, document)
        assertEquals(listOf("flute"), fallback.performers.map { it.partId })
        assertTrue(fallback.microphones.isNotEmpty())
    }
}
