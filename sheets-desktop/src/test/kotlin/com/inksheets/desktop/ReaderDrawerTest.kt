package com.inksheets.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.ui.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalTestApi::class)
class ReaderDrawerTest {
    @get:Rule val tmp = TemporaryFolder()
    @After fun cleanup() {
        ReaderPanels.heights.clear(); ReaderPanels.order.clear(); ReaderPanels.base = 0f
        com.inkslate.core.Perform.isOn = null
    }
    private class Mic : Microphone {
        override val sampleRate = 48000
        override fun start(onChunk: (FloatArray) -> Unit) = true
        override fun stop() {}
    }
    private class Platform(override val startFolder: File) : SheetsPlatform {
        val prefs = mutableMapOf("sheets_library" to startFolder.absolutePath)
        override val deviceId = "drawer-test"
        override val deviceName = "Test stand"
        override val localFolder = File(startFolder, ".local")
        override val audioOut: AudioOut? = null
        override val microphone: Microphone = Mic()
        override fun pref(key: String) = prefs[key]
        override fun setPref(key: String, value: String?) { if (value == null) prefs.remove(key) else prefs[key] = value }
        override fun onMain(block: () -> Unit) = block()
        override fun pageText(file: File, page: Int): String? = null
        override fun openPart(song: Song, part: Part, file: File) {}
    }
    private fun setup(name: String): SheetsState {
        val root = tmp.newFolder(name)
        val file = File(root, "Nocturne.pdf").apply { writeText("fixture") }
        val state = SheetsState(Platform(root))
        state.change { addSong("Nocturne", listOf(Part(file = file.name))) }
        state.current = state.library!!.songs.first { it.title == "Nocturne" }
        state.currentPath = file.absolutePath
        state.pageShown = 0 to 3
        state.homeInFront = false; state.stripCollapsed = false; state.readMusic = true
        state.shelf.chooseView(MusicDockView.STUDIO); state.shelf.chooseRailSide(StudioRailSide.RIGHT)
        return state
    }

    @Test fun `recording drawer reserves score space and keeps controls reachable across all sizes`() {
        for ((name, width, height) in UiAudit.SIZES) {
            val state = setup(name)
            state.audioOpen = true
            runDesktopComposeUiTest(width = width, height = height) {
                setContent { MaterialTheme { Box(Modifier.fillMaxSize()) { ActionStrip(state) } } }
                waitForIdle()
                runOnIdle {
                    assertEquals("$name: recordings replace the category bar", ShelfWidget.RECORDING, state.expandedMusicCategory)
                    assertFalse("$name: no separate popup drawer", ReaderPanels.open)
                    assertTrue("$name: category bar reserves score space", state.shelfHeight > 0f)
                    assertTrue("$name: score retains usable height", state.shelfHeight < height * .75f)
                }
                onNodeWithContentDescription("Compact categories").assertIsDisplayed()
                val issues = UiAudit.check(this, name, "reader-recording-drawer", width, height)
                assertTrue("$name: $issues", issues.isEmpty())
                onNodeWithContentDescription("Compact categories").performClick()
                runOnIdle { state.stripCollapsed = true }
                waitForIdle()
                runOnIdle {
                    assertFalse(ReaderPanels.open)
                    assertEquals(0f, state.shelfHeight, .01f)
                }
            }
        }
    }
}
