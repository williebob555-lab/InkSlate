package com.inksheets.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The automatic sort on the shapes a real pep band library comes in: one folder per song with
 * parts named every which way, the same parts again in a MobileSheets folder, and songs an older
 * version made of single parts left sitting in a setlist. One sort must leave no strays, and two
 * devices sorting apart must end with the same songs.
 */
class LibrarySortTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(root: File, path: String, text: String = path) = File(root, path).apply { parentFile.mkdirs(); writeText(text) }

    private fun band(root: File) {
        val sweet = "Pep Band/Music/Sweet Caroline"
        listOf("SweetC - Trumpet 1", "SweetC - Trombone 1", "SweetC - Tuba", "SweetC - Click track", "Sweet Caroline - Electric Bass", "Sweet Caroline - Full Score")
            .forEach { write(root, "$sweet/$it.pdf") }
        val neck = "Pep Band/Music/Talking Out the Side of Your Neck"
        listOf("neckalto", "necktbn1", "necktbn2", "necktuba", "neckmello", "Talkin Out the Side of Your Neck - Electric Bass")
            .forEach { write(root, "$neck/$it.pdf") }
        val barbie = "Pep Band/Music/Barbie Girl"
        listOf("Barbie Girl - Trombone 1", "Barbie Girl - Tuba", "Barbie Girl - DL - Bass Drums", "Barbie Girl - DL - Snare")
            .forEach { write(root, "$barbie/$it.pdf") }
        // The same trombone part, as MobileSheets exported it.
        write(root, "MobileSheets/Barbie Girl - Trombone 1.pdf", "$barbie/Barbie Girl - Trombone 1.pdf")
        write(root, "Pep Band/Music/River Deep (Waverly)/Piccolo.pdf")
        write(root, "Pep Band/Music/River Deep (Waverly)/Tuba.pdf")
    }

    @Test
    fun `one sort leaves every part in its song and nothing stray`() {
        val root = tmp.newFolder("tablet")
        band(root)
        val library = Library(LibraryLog(root, "tablet"))
        // What an older version left: a song made of a single part, in a setlist.
        val piccolo = library.addSong("Piccolo", listOf(Part(id = Library.partIdFor("Pep Band/Music/River Deep (Waverly)/Piccolo.pdf"), file = "Pep Band/Music/River Deep (Waverly)/Piccolo.pdf", instrument = "piccolo")))
        val list = library.addSetlist("Pep Band")
        library.addToSetlist(list.id, piccolo.id)
        val scan = LibraryScan(root, library, File(tmp.root, "memory.json"))
        scan.run()
        assertEquals(emptyList<String>(), scan.strays())
        val titles = library.songs.map { it.title }.sorted()
        assertEquals(listOf("Barbie Girl", "River Deep", "Sweet Caroline", "Talkin Out the Side of Your Neck"), titles)
        val barbie = library.songs.single { it.title == "Barbie Girl" }
        assertEquals(1, barbie.parts.count { it.instrument == "trombone" })
        assertEquals(1, barbie.duplicates.size)
        assertTrue(barbie.parts.none { it.instrument == "bass-guitar" })
        // The setlist now holds the song the piccolo part went to.
        val entry = library.setlists.single().entries.single()
        assertEquals("River Deep", library.song(entry.songId)?.title)
        // Bass guitar opens the tuba part where there is no bass part.
        val bass = Instruments.defaultProfiles.first { it.id == "bass-guitar" }
        assertEquals("tuba", PartChoice.partFor(barbie, bass)?.instrument)
        assertEquals(PartChoice.Fit.CLOSE, PartChoice.fit(barbie, bass))
        // Sorting again finds nothing to do.
        val again = scan.run()
        assertTrue(again.sorted.toString(), again.sorted.isEmpty())
    }

    @Test
    fun `two devices sorting apart end with the same songs`() {
        val a = tmp.newFolder("a").also { band(it) }
        val b = tmp.newFolder("b").also { band(it) }
        val la = Library(LibraryLog(a, "a"))
        val lb = Library(LibraryLog(b, "b"))
        LibraryScan(a, la, File(tmp.root, "a.json")).run()
        LibraryScan(b, lb, File(tmp.root, "b.json")).run()
        // Each device's records reach the other.
        File(a, ".inksheets/log/a.jsonl").copyTo(File(b, ".inksheets/log/a.jsonl"))
        File(b, ".inksheets/log/b.jsonl").copyTo(File(a, ".inksheets/log/b.jsonl"))
        la.refresh(); lb.refresh()
        LibraryScan(a, la, File(tmp.root, "a.json")).run()
        LibraryScan(b, lb, File(tmp.root, "b.json")).run()
        fun shape(l: Library) = l.songs.map { s -> s.title to s.parts.map { it.file }.sorted() }.sortedBy { it.first }
        assertEquals(shape(la), shape(lb))
        assertEquals(4, la.songs.size)
    }
}
