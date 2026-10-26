package com.inksheets.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.inksheets.core.Bookmark
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.ui.AudioOut
import com.inksheets.ui.Microphone
import com.inksheets.ui.SheetsHome
import com.inksheets.ui.SheetsPlatform
import com.inksheets.ui.SheetsState
import com.inksheets.ui.resumeHomeSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Home is navigation over the existing lists, not a replacement for their capabilities. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class HomeRedesignTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Platform(val root: File) : SheetsPlatform {
        private val prefs = hashMapOf<String, String?>("sheets_library" to root.absolutePath)
        val opened = mutableListOf<String>()
        override val deviceId = "home-redesign"
        override val deviceName = "Test stand"
        override val startFolder = root
        override val localFolder = File(root, ".local")
        override val cacheFolder = File(root, ".cache")
        override fun pref(key: String) = prefs[key]
        override fun setPref(key: String, value: String?) { prefs[key] = value }
        override fun openPart(song: Song, part: Part, file: File) { opened += file.absolutePath }
        override fun pageText(file: File, page: Int): String? = null
        override val audioOut: AudioOut? = null
        override val microphone: Microphone? = null
        override fun onMain(block: () -> Unit) = block()
    }

    private fun library(name: String): Pair<SheetsState, Platform> {
        val root = tmp.newFolder(name)
        val p = Platform(root)
        val s = SheetsState(p)
        listOf("Nocturne - Euphonium.pdf", "Nocturne - Trombone.pdf").forEach { File(root, it).writeText("fixture") }
        s.change {
            val song = addSong("Nocturne", listOf(
                Part(file = "Nocturne - Euphonium.pdf", instrument = "euphonium"),
                Part(file = "Nocturne - Trombone.pdf", instrument = "trombone")
            ))
            val set = addSetlist("Wind Ensemble F2026-2")
            addToSetlist(set.id, song.id)
            val folder = addFolder("MSOM")
            val child = addFolder("Fall", folder.id)
            addSetlist("Studio recital", child.id)
            editSong(song.id) { bookmarks = listOf(Bookmark("Letter C", part = song.parts.first().id, page = 1)) }
        }
        s.chooseProfile("baritone")
        return s to p
    }

    @Test fun `home destinations and instrument chooser fit all supported orientations`() {
        for ((name, width, height) in UiAudit.SIZES) {
            val (state, _) = library(name)
            runDesktopComposeUiTest(width = width, height = height) {
                setContent { MaterialTheme { Surface { SheetsHome(state, {}) } } }
                waitForIdle()
                onNodeWithText("All music").assertIsDisplayed()
                onNodeWithText("Bookmarks").assertIsDisplayed()
                onNode(hasText("Add music") or hasContentDescription("Add music")).assertIsDisplayed()
                onNodeWithContentDescription("More").assertIsDisplayed()
                val problems = UiAudit.check(this, name, "home-redesign", width, height)
                assertTrue("$name: ${problems.joinToString()}", problems.isEmpty())
            }
        }
    }

    @Test fun `wide home quick links open a set or a nested folder without removing full navigation`() {
        val (state, _) = library("links")
        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent { MaterialTheme { Surface { SheetsHome(state, {}) } } }
            waitForIdle()
            onNodeWithContentDescription("Open setlist Wind Ensemble F2026-2").performClick()
            waitForIdle()
            assertEquals("Wind Ensemble F2026-2", state.library?.setlist(state.setlistShown!!)?.name)
            onNodeWithText("Nocturne").assertIsDisplayed()
            onNodeWithContentDescription("Open setlist folder MSOM").performClick()
            waitForIdle()
            onNodeWithText("Fall").performClick()
            waitForIdle()
            onNodeWithText("Studio recital").assertIsDisplayed()
        }
    }

    @Test fun `resume keeps the page of the currently selected part`() {
        val (state, platform) = library("resume")
        val song = state.library!!.songs.first { it.title == "Nocturne" }
        val file = File(platform.root, "Nocturne - Euphonium.pdf")
        state.current = song
        state.currentPath = file.absolutePath
        state.pageShown = 2 to 4
        resumeHomeSong(state, song)
        assertEquals(listOf(file.absolutePath), platform.opened)
        assertEquals(2, com.inkslate.core.Perform.takePage(file.absolutePath))
    }

    @Test fun `landscape phone retains sort and filter access`() {
        val (state, _) = library("short")
        runDesktopComposeUiTest(width = 640, height = 360) {
            setContent { MaterialTheme { Surface { SheetsHome(state, {}) } } }
            waitForIdle()
            onNodeWithContentDescription("Sort and filter songs").performClick()
            waitForIdle()
            onNodeWithText("Recently opened").assertIsDisplayed()
            onNodeWithText("Has a recording").assertIsDisplayed()
        }
    }

    @Test fun `phone can change instrument search and open the chosen part directly`() {
        val (state, platform) = library("instrument")
        runDesktopComposeUiTest(width = 390, height = 844) {
            setContent { MaterialTheme { Surface { SheetsHome(state, {}) } } }
            waitForIdle()
            onNodeWithText("Baritone / Euphonium").performClick()
            onNodeWithText("Trombone").performClick()
            waitForIdle()
            assertEquals("trombone", state.profileId)
            onNode(hasSetTextAction()).performTextInput("Nocturne")
            // Tap the title itself. The merged row's centre lies on the independent Parts
            // expander with Linux font metrics, which correctly expands parts instead of opening.
            onNode(hasText("Nocturne") and !hasSetTextAction(), useUnmergedTree = true).performClick()
            waitForIdle()
            assertTrue(platform.opened.last().endsWith("Nocturne - Trombone.pdf"))
        }
    }

    @Test fun `song menu opens assignments directly and touch selection still adds several to a set`() {
        val (state, _) = library("menu")
        runDesktopComposeUiTest(width = 1000, height = 800) {
            setContent { MaterialTheme { Surface { SheetsHome(state, {}) } } }
            waitForIdle()
            onNodeWithContentDescription("Song options").performClick()
            onNodeWithText("Part assignments...").performClick()
            waitForIdle()
            onNodeWithText("Part assignments — Nocturne").assertIsDisplayed()
            onNodeWithContentDescription("Close").performClick()
            onNodeWithText("Nocturne").performTouchInput { longClick() }
            waitForIdle()
            onNodeWithText("1 chosen").assertIsDisplayed()
            onNodeWithText("Add to a setlist...").performClick()
            waitForIdle()
            // The chooser remains searchable and lists nested sets as well as the top-level set.
            onNodeWithText("Studio recital").assertExists()
            onNodeWithText("Wind Ensemble F2026-2").performClick()
            waitForIdle()
            val set = state.library!!.setlists.first { it.name == "Wind Ensemble F2026-2" }
            assertEquals(2, set.entries.size)
        }
    }

    @Test fun `setlist removal is in its menu leaves the library song intact and can be undone`() {
        val (state, _) = library("set-removal")
        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent { MaterialTheme { Surface { SheetsHome(state, {}) } } }
            waitForIdle()
            onNodeWithContentDescription("Open setlist Wind Ensemble F2026-2").performClick()
            waitForIdle()
            assertEquals(0, onAllNodesWithContentDescription("Take out of setlist").fetchSemanticsNodes().size)
            onNodeWithContentDescription("Song options").performClick()
            onNodeWithText("Take out of this setlist").performClick()
            waitForIdle()
            assertEquals(0, state.library!!.setlists.first { it.name == "Wind Ensemble F2026-2" }.entries.size)
            assertTrue(state.library!!.songs.any { it.title == "Nocturne" })
            onNodeWithText("Undo").performClick()
            waitForIdle()
            assertEquals(1, state.library!!.setlists.first { it.name == "Wind Ensemble F2026-2" }.entries.size)
        }
    }
}
