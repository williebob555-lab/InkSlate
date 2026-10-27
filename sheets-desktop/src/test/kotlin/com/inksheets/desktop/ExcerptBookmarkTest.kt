package com.inksheets.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import com.inksheets.core.*
import com.inksheets.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalTestApi::class)
class ExcerptBookmarkTest {
    @get:Rule val tmp = TemporaryFolder()
    private class Platform(override val startFolder: File) : SheetsPlatform {
        private val prefs = mutableMapOf("sheets_library" to startFolder.absolutePath)
        override val deviceId = "excerpt-test"
        override val deviceName = "Excerpt test"
        override val localFolder = File(startFolder, "local")
        override fun pref(key: String) = prefs[key]
        override fun setPref(key: String, value: String?) { if (value == null) prefs.remove(key) else prefs[key] = value }
        override fun openPart(song: Song, part: Part, file: File) {}
        override fun pageText(file: File, page: Int): String? = null
        override val audioOut: AudioOut? = null
        override val microphone: Microphone? = null
        override fun onMain(block: () -> Unit) = block()
    }

    @Test fun `excerpt range uses inclusive book pages and validates known limits`() {
        assertTrue(validBookmarkRange(12, 15, maximum = 40))
        assertTrue(validBookmarkRange(12, null, maximum = 40))
        assertFalse(validBookmarkRange(0, 2))
        assertFalse(validBookmarkRange(15, 12))
        assertFalse(validBookmarkRange(12, 41, maximum = 40))
        assertFalse(validBookmarkRange(4, 12, minimum = 5))
        assertFalse(validBookmarkRange(null, 12))
    }

    @Test fun `excerpt details persist without changing book notes or concurrent bookmark colour`() {
        val folder = tmp.newFolder()
        val state = SheetsState(Platform(folder))
        lateinit var song: Song
        val original = Bookmark("Page 12", part = "book-part", page = 12)
        state.change {
            song = addSong("Collected solos", listOf(Part(id = "book-part", file = "solos.pdf", instrument = "euphonium", lastPage = 40)))
            editSong(song.id) { notes = "Keep these book notes"; reminder = "Book reminder"; bookmarks = listOf(original) }
        }
        state.setBookmarkColour(song.id, original, 0xFF00897B.toInt())
        saveBookmarkDraft(state, song.id, original, original.copy(label = "Nocturne", excerptTitle = "Nocturne", endPage = 15,
            reminder = "Soft entrance", notes = "Breathe before the final phrase"))
        val reopened = Library(LibraryLog(folder, "excerpt-reader")).song(song.id)!!
        assertEquals("Keep these book notes", reopened.notes)
        assertEquals("Book reminder", reopened.reminder)
        val excerpt = reopened.bookmarks.single()
        assertEquals("Nocturne", excerpt.excerptTitle)
        assertEquals(12, excerpt.page)
        assertEquals(15, excerpt.endPage)
        assertEquals("Soft entrance", excerpt.reminder)
        assertEquals("Breathe before the final phrase", excerpt.notes)
        assertEquals(0xFF00897B.toInt(), excerpt.color)
        // A later save of an editor whose bookmark was removed must not recreate it.
        state.change { editSong(song.id) { bookmarks = emptyList() } }
        saveBookmarkDraft(state, song.id, excerpt, excerpt.copy(notes = "Stale edit"))
        assertTrue(state.library!!.song(song.id)!!.bookmarks.isEmpty())
    }

    @Test fun `excerpt editor keeps save reachable in every supported orientation`() {
        val state = SheetsState(Platform(tmp.newFolder()))
        lateinit var song: Song
        state.change { song = addSong("Collected solos for euphonium and piano", listOf(
            Part(id = "book-part", file = "Library/Collected solos - Euphonium.pdf", instrument = "euphonium", lastPage = 40))) }
        val bookmark = Bookmark("Page 12", "book-part", 12, excerptTitle = "Nocturne", endPage = 15, reminder = "Soft entrance")
        val only = System.getProperty("inksheets.panels.sizes")?.split(',')?.toSet()
        for ((name, width, height) in UiAudit.SIZES) {
            if (only != null && name !in only) continue
            runDesktopComposeUiTest(width = width, height = height) {
                setContent { MaterialTheme { BookmarkEditorDialog(state, song, bookmark, onClose = {}) } }
                onNodeWithText("Save").assertIsDisplayed().assertIsEnabled()
                onNodeWithText("Cancel").assertIsDisplayed()
                onNodeWithContentDescription("Close").assertIsDisplayed()
                val issues = UiAudit.check(this, name, "excerpt-bookmark", width, height, required = listOf("Save", "Cancel"))
                assertTrue("$name: $issues", issues.isEmpty())
            }
        }
    }

    @Test fun `new excerpt from reader uses visible part and page instead of book beginning`() {
        val folder = tmp.newFolder()
        val visibleFile = File(folder, "trombone.pdf").apply { writeText("%PDF-1.4\n%%EOF") }
        val state = SheetsState(Platform(folder))
        lateinit var song: Song
        state.change { song = addSong("Collected solos", listOf(
            Part(id = "first", file = "euphonium.pdf", instrument = "euphonium"),
            Part(id = "visible", file = "trombone.pdf", instrument = "trombone", firstPage = 5, lastPage = 40))) }
        state.current = song
        state.currentPath = visibleFile.absolutePath
        state.pageShown = 11 to 40
        runDesktopComposeUiTest(width = 1000, height = 800) {
            setContent { MaterialTheme { BookmarkEditorDialog(state, song, null, onClose = {}) } }
            onNodeWithTag("bookmark-first").assertTextContains("12")
            onNodeWithTag("bookmark-title").performTextReplacement("Nocturne")
            onNodeWithText("Save").performClick()
            runOnIdle {
                val mark = state.library!!.song(song.id)!!.bookmarks.single()
                assertEquals("visible", mark.part)
                assertEquals(12, mark.page)
                assertEquals("Nocturne", mark.excerptTitle)
            }
        }
    }
}
