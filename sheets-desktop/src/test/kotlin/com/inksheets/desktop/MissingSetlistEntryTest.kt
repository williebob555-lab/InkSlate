package com.inksheets.desktop

import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MissingSetlistEntryTest {
    @get:Rule val tmp = TemporaryFolder()
    private class Platform(override val startFolder: File) : SheetsPlatform {
        val prefs = mutableMapOf("sheets_library" to startFolder.absolutePath)
        var openedSets = 0
        override val deviceId = "missing-set-test"
        override val deviceName = "Test"
        override val localFolder = File(startFolder, ".local")
        override fun pref(key: String) = prefs[key]
        override fun setPref(key: String, value: String?) { if (value == null) prefs.remove(key) else prefs[key] = value }
        override fun openPart(song: Song, part: Part, file: File) {}
        override fun pageText(file: File, page: Int): String? = null
        override val audioOut: AudioOut? = null
        override val microphone: Microphone? = null
        override fun onMain(block: () -> Unit) = block()
        override fun openSet(parts: List<Pair<File, String>>, focus: Int) { openedSets++ }
    }
    @Test fun `missing selection never substitutes another available song or advances the set`() {
        val folder=tmp.newFolder("music")
        File(folder,"Available.pdf").writeText("fixture")
        val platform=Platform(folder)
        val state=SheetsState(platform)
        lateinit var available:Song
        var setId=""
        state.change {
            available=addSong("Available",listOf(Part(file="Available.pdf")))
            val missing=addSong("Missing",listOf(Part(file="Gone.pdf")))
            val set=addSetlist("Rehearsal");setId=set.id
            addToSetlist(set.id,available.id);addToSetlist(set.id,missing.id)
        }
        state.current=available
        state.playSetlist(setId,1)
        assertEquals(0,platform.openedSets)
        assertEquals(available.id,state.current?.id)
        assertTrue(state.edgeNotice.orEmpty().contains("Missing"))
        state.playSetlist(setId,0)
        assertEquals(1,platform.openedSets)
        assertFalse(state.step(1))
        assertEquals(setId to 0,state.playing)
        assertEquals(available.id,state.current?.id)
        assertEquals(1,platform.openedSets)
    }
}
