package com.inksheets.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SetlistBundleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a setlist travels to a bandmate with its parts, and a song they have gains parts`() {
        val mine = tmp.newFolder("mine")
        File(mine, "Band").mkdirs()
        File(mine, "Band/liberty-tbn.pdf").writeText("trombone part")
        File(mine, "Band/liberty-euph.pdf").writeText("euphonium part")
        File(mine, "Band/anthem.pdf").writeText("anthem")
        val me = Library(LibraryLog(mine, "me"))
        val liberty = me.addSong("The Liberty Bell", listOf(
            Part(file = "Band/liberty-tbn.pdf", instrument = "trombone"),
            Part(file = "Band/liberty-euph.pdf", instrument = "euphonium")
        )) { tempo = 120 }
        val anthem = me.addSong("Anthem", listOf(Part(file = "Band/anthem.pdf")))
        val set = me.addSetlist("Spring Concert")
        me.addToSetlist(set.id, liberty.id)
        me.addToSetlist(set.id, anthem.id)

        val bundle = File(tmp.root, "Spring Concert.inksheets")
        assertEquals(3, SetlistBundle.export(me, mine, set.id, bundle))

        val theirs = tmp.newFolder("theirs")
        val them = Library(LibraryLog(theirs, "them"))
        val had = them.addSong("Anthem")   // they already have this one, with no parts
        val result = SetlistBundle.import(them, theirs, bundle)
        assertEquals(1, result.songsAdded)
        assertEquals(1, result.songsMatched)

        val got = them.songs.first { it.title == "The Liberty Bell" }
        assertEquals(setOf("trombone", "euphonium"), got.instruments)
        assertEquals(120, got.tempo)
        assertTrue(File(theirs, got.parts.first().file).readText().endsWith("part"))
        assertEquals(1, them.song(had.id)!!.parts.size)
        val folder = them.foldersIn(null).single { it.name == SetlistBundle.SHARED_FOLDER }
        val imported = them.setlistsIn(folder.id).single()
        assertEquals(listOf(got.id, had.id), imported.entries.map { it.songId })
    }
}
