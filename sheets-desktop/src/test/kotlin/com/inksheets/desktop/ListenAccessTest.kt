package com.inksheets.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.inksheets.core.AudioTrack
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.ui.ActionStrip
import com.inksheets.ui.AudioOut
import com.inksheets.ui.Microphone
import com.inksheets.ui.SheetsPlatform
import com.inksheets.ui.SheetsState
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Recording-based listening remains independent of experimental score recognition. */
@OptIn(ExperimentalTestApi::class)
class ListenAccessTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `recording listen remains directly reachable without score reading in both phone orientations`() {
        for ((width, height) in listOf(390 to 844, 844 to 390)) {
            val root = tmp.newFolder("listen-$width")
            val prefs = hashMapOf<String, String?>("sheets_library" to root.absolutePath, "sheets_strip_collapsed" to "false")
            val platform = object : SheetsPlatform {
                override val deviceId = "listen-access-$width"
                override val deviceName = "Test stand"
                override val startFolder = root
                override val localFolder = File(root, ".local")
                override val audioOut: AudioOut? = null
                override val microphone: Microphone? = null
                override fun pref(key: String) = prefs[key]
                override fun setPref(key: String, value: String?) { prefs[key] = value }
                override fun onMain(block: () -> Unit) = block()
                override fun pageText(file: File, page: Int): String? = null
                override fun openPart(song: Song, part: Part, file: File) {}
            }
            val state = SheetsState(platform)
            val file = File(root, "Nocturne.pdf").also { it.writeText("fixture") }
            state.change {
                addSong("Nocturne", listOf(Part(file = file.name, instrument = "euphonium"))) {
                    audio = listOf(AudioTrack(file = "recording.wav", turnsMs = listOf(10_000L, 20_000L)))
                }
            }
            state.current = state.library!!.songs.first { it.title == "Nocturne" }
            state.currentPath = file.absolutePath
            state.pageShown = 0 to 3
            state.homeInFront = false
            state.stripCollapsed = false
            state.readMusic = false
            state.listenTurns = true
            runDesktopComposeUiTest(width = width, height = height) {
                setContent { MaterialTheme { Box(Modifier.fillMaxSize()) { ActionStrip(state) } } }
                waitForIdle()
                onNodeWithText("Listen").assertIsDisplayed()
            }
        }
    }
}
