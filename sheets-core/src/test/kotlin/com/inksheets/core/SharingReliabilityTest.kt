package com.inksheets.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SharingReliabilityTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun bundle(composer: String? = null, arranger: String? = null): File {
        val dir = tmp.newFolder()
        File(dir, "part.pdf").writeText("publisher's clean part")
        val lib = Library(LibraryLog(dir, "publisher"))
        val song = lib.addSong("Nocturne", listOf(Part(file = "part.pdf", instrument = "euphonium"))) {
            composers = listOfNotNull(composer); arrangers = listOfNotNull(arranger)
        }
        val set = lib.addSetlist("Concert")
        lib.addToSetlist(set.id, song.id)
        return File(tmp.newFolder(), "Concert.zip").also { SetlistBundle.export(lib, dir, set.id, it) }
    }

    @Test fun `same title with another arranger remains a separate piece through repeated imports`() {
        val zip = bundle("Composer", "Arranger B")
        val dir = tmp.newFolder()
        File(dir, "mine.pdf").writeText("my other arrangement")
        val lib = Library(LibraryLog(dir, "recipient"))
        val original = lib.addSong("Nocturne", listOf(Part(file = "mine.pdf", instrument = "euphonium"))) {
            composers = listOf("Composer"); arrangers = listOf("Arranger A")
        }
        val result = SetlistBundle.import(lib, dir, zip)
        assertEquals(1, result.songsAdded)
        val imported = lib.song(result.setlist.entries.single().songId)!!
        assertNotEquals(original.id, imported.id)
        assertTrue(imported.apart)
        assertEquals(listOf("mine.pdf"), lib.song(original.id)!!.parts.map { it.file })
        assertEquals(0, SetlistBundle.import(lib, dir, zip).songsAdded)
        assertEquals(2, lib.songs.size)
    }

    @Test fun `title alone does not merge unrelated music with no identifying metadata`() {
        val zip = bundle()
        val dir = tmp.newFolder()
        val lib = Library(LibraryLog(dir, "recipient"))
        val original = lib.addSong("Nocturne", listOf(Part(file = "local.pdf")))
        val result = SetlistBundle.import(lib, dir, zip)
        assertEquals(1, result.songsAdded)
        assertNotEquals(original.id, result.setlist.entries.single().songId)
    }

    @Test fun `identical exported paths and bytes do not move a part between conflicting arrangements`() {
        val a = bundle("Composer", "Arranger A")
        val b = bundle("Composer", "Arranger B")
        val dir = tmp.newFolder()
        val lib = Library(LibraryLog(dir, "recipient"))
        val first = SetlistBundle.import(lib, dir, a)
        val firstId = first.setlist.entries.single().songId
        val originalPart = lib.song(firstId)!!.parts.single()
        val second = SetlistBundle.import(lib, dir, b)
        val secondId = second.setlist.entries.single().songId
        assertNotEquals(firstId, secondId)
        assertEquals(originalPart.id, lib.song(firstId)!!.parts.single().id)
        assertNotEquals(originalPart.file, lib.song(secondId)!!.parts.single().file)
        assertEquals(0, SetlistBundle.import(lib, dir, b).songsAdded)
        LibraryScan(dir, lib, File(tmp.root, "scan-memory.json")).run()
        assertEquals(2, lib.songs.size)
        assertEquals(originalPart.id, lib.song(firstId)!!.parts.single().id)
    }

    @Test fun `reimport preserves marked active part and adds a clean edition only once`() {
        val zip = bundle()
        val dir = tmp.newFolder()
        val lib = Library(LibraryLog(dir, "recipient"))
        val first = SetlistBundle.import(lib, dir, zip)
        val id = first.setlist.entries.single().songId
        val marked = lib.song(id)!!.parts.single().file
        File(dir, marked).writeText("my annotations and original music")
        val again = SetlistBundle.import(lib, dir, zip)
        assertEquals(0, again.songsAdded)
        assertEquals(id, again.setlist.entries.single().songId)
        assertEquals("my annotations and original music", File(dir, marked).readText())
        assertEquals(marked, lib.song(id)!!.parts.first().file)
        assertEquals(2, lib.song(id)!!.parts.size)
        val clean = lib.song(id)!!.parts.last().file
        assertEquals("publisher's clean part", File(dir, clean).readText())
        SetlistBundle.import(lib, dir, zip)
        LibraryScan(dir, lib, File(tmp.root, "reimport-memory.json")).run()
        assertEquals(2, lib.song(id)!!.parts.size)
        assertEquals(1, lib.songs.size)
    }

    @Test fun `missing music refuses export before replacing an existing package`() {
        val dir = tmp.newFolder()
        val lib = Library(LibraryLog(dir, "me"))
        val song = lib.addSong("Missing Piece", listOf(Part(file = "missing.pdf"))) {
            audio = listOf(AudioTrack(file = "recording.mp3"))
        }
        val set = lib.addSetlist("Concert")
        lib.addToSetlist(set.id, song.id)
        val out = File(tmp.root, "existing.zip").also { it.writeText("previous complete package") }
        val error = runCatching { SetlistBundle.export(lib, dir, set.id, out) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("Missing Piece: missing.pdf"))
        assertTrue(error.message!!.contains("recording.mp3"))
        assertEquals("previous complete package", out.readText())
    }

    @Test fun `a removed setlist song is an export error not a silent omission`() {
        val dir = tmp.newFolder()
        val lib = Library(LibraryLog(dir, "me"))
        val song = lib.addSong("Removed")
        val set = lib.addSetlist("Concert")
        lib.addToSetlist(set.id, song.id); lib.deleteSong(song.id)
        val error = runCatching { SetlistBundle.export(lib, dir, set.id, File(tmp.root, "out.zip")) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("Removed song"))
    }

    @Test fun `one shared band pack retains every songs page range after import scan and reimport`() {
        val publisherRoot = tmp.newFolder()
        File(publisherRoot, "band-pack.pdf").writeText("all the music in one pack")
        val publisher = Library(LibraryLog(publisherRoot, "publisher"))
        val first = publisher.addSong("First Piece", listOf(Part(file = "band-pack.pdf", instrument = "euphonium", firstPage = 1, lastPage = 2)))
        val second = publisher.addSong("Second Piece", listOf(Part(file = "band-pack.pdf", instrument = "euphonium", firstPage = 3, lastPage = 4)))
        val set = publisher.addSetlist("Band pack")
        publisher.addToSetlist(set.id, first.id); publisher.addToSetlist(set.id, second.id)
        val zip = File(tmp.newFolder(), "band-pack.zip")
        assertEquals(1, SetlistBundle.export(publisher, publisherRoot, set.id, zip))

        val recipientRoot = tmp.newFolder()
        val recipient = Library(LibraryLog(recipientRoot, "recipient"))
        val got = SetlistBundle.import(recipient, recipientRoot, zip)
        val firstId = got.setlist.entries[0].songId
        val secondId = got.setlist.entries[1].songId
        val firstPart = recipient.song(firstId)!!.parts.single()
        val secondPart = recipient.song(secondId)!!.parts.single()
        assertEquals(firstPart.file, secondPart.file)
        assertNotEquals(firstPart.id, secondPart.id)
        assertEquals(1, firstPart.firstPage); assertEquals(2, firstPart.lastPage)
        assertEquals(3, secondPart.firstPage); assertEquals(4, secondPart.lastPage)
        LibraryScan(recipientRoot, recipient, File(tmp.root, "pack-scan.json")).run()
        assertEquals(firstPart.id, recipient.song(firstId)!!.parts.single().id)
        assertEquals(secondPart.id, recipient.song(secondId)!!.parts.single().id)
        assertEquals(0, SetlistBundle.import(recipient, recipientRoot, zip).songsAdded)
        assertEquals(1, recipient.song(firstId)!!.parts.size)
        assertEquals(1, recipient.song(secondId)!!.parts.size)
    }

    @Test fun `shared parts retain explicit chairs labels and alternate instruments`() {
        val publisherRoot = tmp.newFolder()
        File(publisherRoot, "low-brass.pdf").writeText("shared flexible part")
        val publisher = Library(LibraryLog(publisherRoot, "publisher"))
        val part = Part(file = "low-brass.pdf", instrument = "trombone", chair = 2,
            label = "Trombone II / Euphonium / Bassoon", also = listOf("euphonium", "bassoon"), firstPage = 2, lastPage = 3)
        val song = publisher.addSong("Flexible Band", listOf(part))
        val set = publisher.addSetlist("Concert"); publisher.addToSetlist(set.id, song.id)
        val zip = File(tmp.newFolder(), "Concert.zip")
        SetlistBundle.export(publisher, publisherRoot, set.id, zip)
        val zipNames = java.util.zip.ZipFile(zip).use { it.entries().toList().map { entry -> entry.name } }
        assertTrue(zipNames.any { it.contains("Trombone 2") })
        val dir = tmp.newFolder()
        val recipient = Library(LibraryLog(dir, "recipient"))
        val got = SetlistBundle.import(recipient, dir, zip)
        val imported = recipient.song(got.setlist.entries.single().songId)!!.parts.single()
        assertEquals(part.chair, imported.chair)
        assertEquals(part.label, imported.label)
        assertEquals(part.also, imported.also)
        assertEquals(part.firstPage, imported.firstPage)
        assertEquals(part.lastPage, imported.lastPage)
        SetlistBundle.import(recipient, dir, zip)
        assertEquals(imported, recipient.song(got.setlist.entries.single().songId)!!.parts.single())
    }
}
