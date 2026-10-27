package com.inksheets.desktop

import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HomeSetResumeTest {
    @get:Rule val tmp = TemporaryFolder()
    private class Platform(override val startFolder: File) : SheetsPlatform {
        val prefs = mutableMapOf("sheets_library" to startFolder.absolutePath)
        var opens = 0
        override val deviceId = "resume-test"
        override val deviceName = "Test"
        override val localFolder = File(startFolder, ".local")
        override fun pref(key: String) = prefs[key]
        override fun setPref(key: String, value: String?) { if (value == null) prefs.remove(key) else prefs[key] = value }
        override fun openPart(song: Song, part: Part, file: File) {}
        override fun pageText(file: File, page: Int): String? = null
        override fun pageCount(file: File) = 10
        override val audioOut: AudioOut? = null
        override val microphone: Microphone? = null
        override fun onMain(block: () -> Unit) = block()
        override fun openSet(parts: List<Pair<File, String>>, focus: Int) { opens++ }
    }

    @Test fun `last set resumes stable entry and page after reorder and app restart`() {
        val folder = tmp.newFolder("music")
        File(folder, "Score.pdf").writeText("fixture")
        val p = Platform(folder)
        val s = SheetsState(p)
        var setId = ""
        s.change {
            val song = addSong("Score", listOf(Part(file = "Score.pdf")))
            val set = addSetlist("Rehearsal"); setId = set.id
            addToSetlist(set.id, song.id); addToSetlist(set.id, song.id)
        }
        s.playSetlist(setId, 1)
        val original = s.library!!.setlist(setId)!!.entries
        s.rememberSetlistPosition(original[1].id, 4, File(folder, "Score.pdf").absolutePath)
        s.change { editSetlist(setId) { entries = original.reversed() } }
        val reopened = SheetsState(p)
        val place = reopened.homeSetResume()!!
        assertEquals(0, place.index)
        assertEquals(4, place.page)
        reopened.resumeHomeSetlist()
        assertEquals(setId to 0, reopened.playing)
        assertEquals(4, reopened.homeSetResume()!!.page)
    }

    @Test fun `missing part never resumes a different entry and deleted entry clears continue`() {
        val folder = tmp.newFolder("missing")
        val file = File(folder, "Score.pdf").apply { writeText("fixture") }
        val p = Platform(folder); val s = SheetsState(p)
        var setId = ""
        s.change {
            val song = addSong("Score", listOf(Part(file = "Score.pdf")))
            val set = addSetlist("Rehearsal"); setId = set.id; addToSetlist(set.id, song.id)
        }
        s.playSetlist(setId, 0)
        val before = p.opens
        file.delete()
        s.resumeHomeSetlist()
        assertEquals(before, p.opens)
        assertTrue(s.edgeNotice.orEmpty().contains("unavailable"))
        s.change { editSetlist(setId) { entries = emptyList() } }
        assertNull(s.homeSetResume())
    }
}
