package com.inksheets.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The library as several devices see it through one synced folder.
 *
 * Syncing is modelled the way Syncthing does it: whole files copied from one device's folder to
 * another's, at whatever moment. Each device is its own [Library] over its own copy of the folder.
 */
class LibraryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var time = 1_000L

    private fun device(name: String): Pair<File, Library> {
        val root = tmp.newFolder(name)
        return root to Library(LibraryLog(root, name)) { time++ }
    }

    /** Copy every log from one device's folder to another's, as a sync would. */
    private fun sync(from: File, to: File) {
        File(from, ".inksheets/log").listFiles()!!.forEach {
            it.copyTo(File(to, ".inksheets/log/${it.name}"), overwrite = true)
        }
    }

    @Test
    fun `songs, setlists and nested folders round-trip through the log`() {
        val (root, lib) = device("tablet")
        val ensemble = lib.addFolder("Wind Ensemble")
        val year = lib.addFolder("2025-26", ensemble.id)
        val spring = lib.addSetlist("Spring Concert", year.id)
        val song = lib.addSong("The Liberty Bell", listOf(Part(file = "band/liberty.pdf", instrument = "trombone"))) {
            composers = listOf("John Philip Sousa")
            tempo = 120
        }
        lib.addToSetlist(spring.id, song.id)

        // A fresh reader of the same folder sees exactly the same library.
        val again = Library(LibraryLog(root, "tablet"))
        assertEquals(listOf("The Liberty Bell"), again.songs.map { it.title })
        assertEquals(listOf("John Philip Sousa"), again.songs[0].composers)
        assertEquals(120, again.songs[0].tempo)
        assertEquals(listOf("Wind Ensemble", "2025-26"), again.pathTo(year.id).map { it.name })
        assertEquals(listOf("Spring Concert"), again.setlistsIn(year.id).map { it.name })
        assertEquals(listOf("Spring Concert"), again.setlistsUnder(ensemble.id).map { it.name })
        assertEquals(song.id, again.setlist(spring.id)!!.entries.single().songId)
    }

    @Test
    fun `edits made apart to different fields both survive`() {
        val (tabletRoot, tablet) = device("tablet")
        val (laptopRoot, laptop) = device("laptop")
        val song = tablet.addSong("Stars and Stripes")
        sync(tabletRoot, laptopRoot)
        laptop.refresh()

        tablet.editSong(song.id) { tempo = 116 }
        laptop.editSong(song.id) { key = "Eb" }
        sync(tabletRoot, laptopRoot)
        sync(laptopRoot, tabletRoot)
        tablet.refresh()
        laptop.refresh()

        for (lib in listOf(tablet, laptop)) {
            val s = lib.song(song.id)!!
            assertEquals(116, s.tempo)
            assertEquals("Eb", s.key)
        }
    }

    @Test
    fun `the later edit to one field wins on every device, whatever order the logs arrive in`() {
        val (aRoot, a) = device("a")
        val (bRoot, b) = device("b")
        val (cRoot, c) = device("c")
        val song = a.addSong("First title")
        sync(aRoot, bRoot); b.refresh()
        a.editSong(song.id) { title = "Second title" }
        b.editSong(song.id) { title = "Third title" }   // later
        // c hears from b before a.
        sync(bRoot, cRoot); c.refresh()
        sync(aRoot, cRoot); c.refresh()
        sync(cRoot, aRoot); a.refresh()
        sync(cRoot, bRoot); b.refresh()
        for (lib in listOf(a, b, c)) assertEquals("Third title", lib.song(song.id)!!.title)
    }

    @Test
    fun `a deleted folder hands its setlists up rather than taking them with it`() {
        val (_, lib) = device("tablet")
        val ensemble = lib.addFolder("Jazz Band")
        val year = lib.addFolder("2023-24", ensemble.id)
        val gig = lib.addSetlist("Winter Gig", year.id)
        lib.deleteFolder(year.id)
        assertEquals(listOf(gig.id), lib.setlistsIn(ensemble.id).map { it.id })
        assertTrue(lib.foldersIn(ensemble.id).isEmpty())
    }

    @Test
    fun `a folder cannot be moved inside itself, and a loop made apart is shown at the top`() {
        val (aRoot, a) = device("a")
        val (bRoot, b) = device("b")
        val x = a.addFolder("X")
        val y = a.addFolder("Y")
        assertFalse(a.moveFolder(x.id, x.id))
        sync(aRoot, bRoot); b.refresh()
        a.moveFolder(x.id, y.id)
        b.moveFolder(y.id, x.id)
        sync(aRoot, bRoot); b.refresh()
        sync(bRoot, aRoot); a.refresh()
        // Each device allowed its own move; together they loop. Nothing disappears.
        assertEquals(2, a.folders.size)
        assertTrue(a.folders.any { it.parentId == null })
    }

    @Test
    fun `setlists keep the same song twice, in order, and reorder`() {
        val (_, lib) = device("tablet")
        val set = lib.addSetlist("Pep Band")
        val fight = lib.addSong("Fight Song")
        val anthem = lib.addSong("Anthem")
        val first = lib.addToSetlist(set.id, fight.id)
        lib.addToSetlist(set.id, anthem.id)
        lib.addToSetlist(set.id, fight.id)
        assertEquals(listOf(fight.id, anthem.id, fight.id), lib.setlist(set.id)!!.entries.map { it.songId })
        lib.moveInSetlist(set.id, first.id, 2)
        assertEquals(listOf(anthem.id, fight.id, fight.id), lib.setlist(set.id)!!.entries.map { it.songId })
        lib.removeFromSetlist(set.id, first.id)
        assertEquals(listOf(anthem.id, fight.id), lib.setlist(set.id)!!.entries.map { it.songId })
    }

    @Test
    fun `compacting keeps the library identical and makes the log smaller`() {
        val (root, lib) = device("tablet")
        val song = lib.addSong("Tempo test")
        repeat(200) { lib.editSong(song.id) { tempo = 60 + it } }
        val before = lib.ownLogSize()
        lib.compactIfLarge(threshold = 0)
        assertTrue(lib.ownLogSize() < before)
        val again = Library(LibraryLog(root, "tablet"))
        assertEquals(259, again.song(song.id)!!.tempo)
        assertEquals("Tempo test", again.song(song.id)!!.title)
    }

    @Test
    fun `a half-written log line is left for the next read`() {
        val (root, lib) = device("tablet")
        lib.addSong("Complete")
        File(root, ".inksheets/log/other.jsonl").writeText("""{"at":{"ms":5,"n":0,"device":"other"},"kind":"song","id":"x","field":"title","value":"Hal""")
        lib.refresh()
        assertNull(lib.song("x"))
        File(root, ".inksheets/log/other.jsonl").appendText("f\"}\n")
        lib.refresh()
        assertEquals("Half", lib.song("x")!!.title)
    }

    @Test
    fun `practice adds up across days and devices`() {
        val (tabletRoot, tablet) = device("tablet")
        val (laptopRoot, laptop) = device("laptop")
        val song = tablet.addSong("Etude")
        tablet.addPractice(song.id, "2026-09-20", 600)
        tablet.addPractice(song.id, "2026-09-20", 300)
        sync(tabletRoot, laptopRoot); laptop.refresh()
        laptop.addPractice(song.id, "2026-09-22", 1200)
        sync(laptopRoot, tabletRoot); tablet.refresh()
        val p = tablet.practiceOf(song.id)
        assertEquals(2100L, p.totalSeconds)
        assertEquals("2026-09-22", p.lastDay)
        assertEquals(900L, p.byDay["2026-09-20"])
    }

    private fun Library.ownLogSize(): Long =
        File(tmp.root, "tablet/.inksheets/log/tablet.jsonl").length()
}
