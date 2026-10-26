package com.inksheets.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import com.inkslate.library.SettingsWorkspace
import com.inksheets.core.InstrumentSource
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Real Compose dialogs, isolated library, phone/tablet/laptop dimensions. */
@OptIn(ExperimentalTestApi::class)
class PanelsRedesignTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Platform(override val startFolder: File) : SheetsPlatform {
        private val prefs = mutableMapOf("sheets_library" to startFolder.absolutePath)
        override val deviceId = "panels-test"
        override val deviceName = "Panels test"
        override val localFolder = File(startFolder, "local")
        override fun pref(key: String) = prefs[key]
        override fun setPref(key: String, value: String?) { if (value == null) prefs.remove(key) else prefs[key] = value }
        override fun openPart(song: Song, part: Part, file: File) {}
        override fun pageText(file: File, page: Int): String? = null
        override val audioOut: AudioOut? = null
        override val microphone: Microphone? = null
        override fun onMain(block: () -> Unit) = block()
    }

    @Test fun `page ranges accept full files and reject reversed or nonpositive ranges`() {
        assertTrue(validPartPageRange(null, null))
        assertTrue(validPartPageRange(2, null))
        assertTrue(validPartPageRange(null, 3))
        assertTrue(validPartPageRange(3, 3))
        assertFalse(validPartPageRange(0, 3))
        assertFalse(validPartPageRange(3, 2))
        assertFalse(validPartPageRange(null, 0))
    }

    @Test fun `range editing validates and preserves an assignment arriving while dialog is open`() {
        val state = SheetsState(Platform(tmp.newFolder("editing")))
        lateinit var song: Song
        state.change { song = addSong("Nocturne", listOf(Part(id = "part-a", file = "Nocturne.pdf", instrument = "euphonium", firstPage = 1, lastPage = 3))) }
        runDesktopComposeUiTest(width = 1000, height = 800) {
            setContent { MaterialTheme { SongEditorDialog(state, song, onClose = {}, initialParts = true) } }
            onNodeWithTag("part-first-part-a").performTextReplacement("4")
            onNodeWithText("Save").assertIsNotEnabled()
            onNodeWithTag("part-first-part-a").performTextReplacement("2")
            runOnIdle {
                state.change { writePart(song.id, song.parts.first().copy(instrument = "trombone", source = InstrumentSource.PERSON)) }
            }
            onNodeWithText("Save").assertIsEnabled().performClick()
            runOnIdle {
                val saved = state.library!!.song(song.id)!!.parts.first()
                assertEquals("trombone", saved.instrument)
                assertEquals(2, saved.firstPage)
                assertEquals(3, saved.lastPage)
            }
        }
    }

    @Test fun `assignment dialog and settings navigation fit every supported orientation`() {
        val state = SheetsState(Platform(tmp.newFolder("sizes")))
        lateinit var song: Song
        state.change { song = addSong("Nocturne with a long rehearsal title", listOf(
            Part(id = "part-b", file = "Band/Nocturne - Euphonium first edition.pdf", instrument = "euphonium", firstPage = 1, lastPage = 3),
            Part(id = "part-c", file = "Band/Nocturne - Trombone.pdf", instrument = "trombone", chair = 2)
        )) }
        val only = System.getProperty("inksheets.panels.sizes")?.split(',')?.toSet()
        for ((name, width, height) in UiAudit.SIZES) {
            if (only != null && name !in only) continue
            runDesktopComposeUiTest(width = width, height = height) {
                setContent { MaterialTheme { SongEditorDialog(state, song, onClose = {}, initialParts = true) } }
                onNodeWithText("Save").assertIsDisplayed()
                onNodeWithText("Cancel").assertIsDisplayed()
                onNodeWithContentDescription("Close").assertIsDisplayed()
                val issues = UiAudit.check(this, name, "redesign-assignments", width, height,
                    required = listOf("Save", "Cancel", "Parts (2)"))
                assertTrue("$name: $issues", issues.isEmpty())
            }
            runDesktopComposeUiTest(width = width, height = height) {
                setContent { MaterialTheme {
                    SettingsWorkspace(listOf("Music & library", "Saving", "Reading & annotation", "Devices", "Updates & diagnostics"),
                        "Devices", {}) { androidx.compose.material3.Text("Page pedal connected") }
                } }
                onNodeWithText("Devices").assertIsDisplayed()
                onNodeWithText("Page pedal connected").assertIsDisplayed()
                val issues = UiAudit.check(this, name, "redesign-settings", width, height)
                assertTrue("$name: $issues", issues.isEmpty())
            }
        }
    }
}
