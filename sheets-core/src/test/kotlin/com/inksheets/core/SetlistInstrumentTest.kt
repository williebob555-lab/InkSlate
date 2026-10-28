package com.inksheets.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SetlistInstrumentTest {
    @get:Rule val tmp = TemporaryFolder()
    private var time = 1000L
    private fun device(name: String): Pair<File, Library> {
        val root = tmp.newFolder(name)
        return root to Library(LibraryLog(root, name)) { time++ }
    }
    private fun sync(from: File, to: File) {
        File(from, ".inksheets/log").listFiles()!!.forEach {
            it.copyTo(File(to, ".inksheets/log/" + it.name), overwrite = true)
        }
    }

    @Test fun defaultInstrumentRoundTripsAndCanBeCleared() {
        val (root, library) = device("a")
        val set = library.addSetlist("Concert")
        assertNull(set.defaultInstrument)
        library.editSetlist(set.id) { defaultInstrument = "trombone" }
        assertEquals("trombone", Library(LibraryLog(root, "reopen")).setlist(set.id)!!.defaultInstrument)
        library.editSetlist(set.id) { defaultInstrument = null }
        assertNull(Library(LibraryLog(root, "again")).setlist(set.id)!!.defaultInstrument)
    }

    @Test fun offlineInstrumentAndOtherMetadataMergeAndResetSurvivesStaleCopy() {
        val (aRoot, a) = device("a")
        val (bRoot, b) = device("b")
        val set = a.addSetlist("Concert")
        sync(aRoot, bRoot); b.refresh()
        a.editSetlist(set.id) { defaultInstrument = "trombone" }
        b.editSetlist(set.id) { name = "Spring"; notes = "Doors at 7" }
        sync(aRoot, bRoot); sync(bRoot, aRoot); a.refresh(); b.refresh()
        for (lib in listOf(a,b)) {
            assertEquals("trombone", lib.setlist(set.id)!!.defaultInstrument)
            assertEquals("Spring", lib.setlist(set.id)!!.name)
            assertEquals("Doors at 7", lib.setlist(set.id)!!.notes)
        }
        a.editSetlist(set.id) { defaultInstrument = null }
        sync(aRoot, bRoot); b.refresh()
        sync(bRoot, aRoot); a.refresh()
        assertNull(a.setlist(set.id)!!.defaultInstrument)
        assertNull(b.setlist(set.id)!!.defaultInstrument)
    }

    @Test fun portableSetlistBundleKeepsItsDefaultInstrument() {
        val (root, library) = device("a")
        val set = library.addSetlist("Concert")
        library.editSetlist(set.id) { defaultInstrument = "flute" }
        val bundle = File(tmp.root, "concert.zip")
        SetlistBundle.export(library, root, set.id, bundle)
        val (otherRoot, other) = device("b")
        SetlistBundle.import(other, otherRoot, bundle)
        assertEquals("flute", other.setlists.single().defaultInstrument)
    }
}
