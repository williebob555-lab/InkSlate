package com.inksheets.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Several devices, each with its own copy of the music folder, kept in step the way Syncthing
 * does it: whole files copied across, in whatever order, some later than others. Each device
 * scans its own copy. However the files and the library's logs arrive, every device must end
 * with the same songs - no ghosts, no duplicates.
 */
class LibraryScanTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private inner class Device(val name: String) {
        val root: File = tmp.newFolder(name)
        val memory = File(tmp.root, "$name-memory.json")
        var library = Library(LibraryLog(root, name))
        fun scan(force: Boolean = false) = LibraryScan(root, library, memory).run(force).also { library.refresh() }
        fun write(path: String, text: String = path) = File(root, path).apply { parentFile.mkdirs(); writeText(text) }
        fun delete(path: String) = File(root, path).delete()
        fun move(from: String, to: String) { File(root, to).parentFile.mkdirs(); File(root, from).renameTo(File(root, to)) }
        fun titles() = library.songs.map { it.title }.sorted()
        fun partsOf(title: String) = library.songs.single { it.title == title }.parts.map { it.file }.sorted()
    }

    /** Copy [from]'s library logs to [to] - the records arrive. */
    private fun syncLogs(from: Device, to: Device) {
        File(from.root, ".inksheets/log").listFiles().orEmpty().forEach { log ->
            val target = File(to.root, ".inksheets/log/${log.name}")
            if (log.name.startsWith(from.name)) { target.parentFile.mkdirs(); log.copyTo(target, overwrite = true) }
        }
        to.library.refresh()
    }

    /** Make [to]'s music files match [from]'s - the files arrive, and deletions too. */
    private fun syncFiles(from: Device, to: Device) {
        val theirs = LibraryScan.listMusic(from.root).map { it.path }.toSet()
        val mine = LibraryScan.listMusic(to.root).map { it.path }.toSet()
        for (p in theirs - mine) File(from.root, p).copyTo(File(to.root, p).apply { parentFile.mkdirs() })
        for (p in mine - theirs) File(to.root, p).delete()
    }

    private fun syncAll(vararg devices: Device) {
        repeat(2) {
            for (a in devices) for (b in devices) if (a !== b) { syncLogs(a, b); syncFiles(a, b) }
        }
    }

    @Test
    fun `two devices finding the same new music make one song, not two`() {
        val tablet = Device("tablet")
        val laptop = Device("laptop")
        for (d in listOf(tablet, laptop)) {
            d.write("Band/Liberty Bell - Trombone 1.pdf")
            d.write("Band/Liberty Bell - Euphonium.pdf")
        }
        tablet.scan(); laptop.scan()
        syncAll(tablet, laptop)
        tablet.scan(); laptop.scan()
        assertEquals(listOf("Liberty Bell"), tablet.titles())
        assertEquals(listOf("Liberty Bell"), laptop.titles())
        assertEquals(2, laptop.library.songs.single().parts.size)
    }

    @Test
    fun `a deleted file takes its part and then its song, on every device`() {
        val tablet = Device("tablet")
        val laptop = Device("laptop")
        tablet.write("March - Trombone.pdf")
        tablet.scan()
        syncAll(tablet, laptop)
        laptop.scan()
        assertEquals(listOf("March"), laptop.titles())

        tablet.delete("March - Trombone.pdf")
        tablet.scan()
        assertEquals(emptyList<String>(), tablet.titles())
        syncAll(tablet, laptop)
        laptop.scan()
        assertEquals(emptyList<String>(), laptop.titles())
    }

    @Test
    fun `a record ahead of its file is left alone until the file arrives`() {
        val tablet = Device("tablet")
        val laptop = Device("laptop")
        tablet.write("Sleigh Ride - Tuba.pdf")
        tablet.scan()
        syncLogs(tablet, laptop)          // the library knows it; the file is still on its way
        laptop.scan()
        assertEquals(listOf("Sleigh Ride"), laptop.titles())
        syncFiles(tablet, laptop)
        laptop.scan()
        assertEquals(listOf("Sleigh Ride"), laptop.titles())
        assertEquals(1, laptop.library.songs.single().parts.size)
    }

    @Test
    fun `a removal that arrives before the file's deletion does not bring it back`() {
        val tablet = Device("tablet")
        val laptop = Device("laptop")
        tablet.write("Chester - Trombone.pdf")
        tablet.scan()
        syncAll(tablet, laptop)
        laptop.scan()
        Thread.sleep(20)
        tablet.delete("Chester - Trombone.pdf")
        tablet.scan()
        syncLogs(tablet, laptop)          // the removal arrives; the file is still here
        laptop.scan()
        assertEquals(emptyList<String>(), laptop.titles())
        syncFiles(tablet, laptop)
        laptop.scan()
        assertEquals(emptyList<String>(), laptop.titles())
    }

    @Test
    fun `a moved file is followed, keeping its part and its setlists`() {
        val tablet = Device("tablet")
        val laptop = Device("laptop")
        tablet.write("Loose/Robin Hood - Trombone.pdf")
        tablet.scan()
        val song = tablet.library.songs.single()
        val list = tablet.library.addSetlist("Concert")
        tablet.library.addToSetlist(list.id, song.id)
        syncAll(tablet, laptop)
        laptop.scan()

        laptop.move("Loose/Robin Hood - Trombone.pdf", "Concert/Robin Hood - Trombone.pdf")
        laptop.scan()
        assertEquals(listOf("Concert/Robin Hood - Trombone.pdf"), laptop.partsOf("Robin Hood"))
        // The move reaches the tablet from the laptop, where it was made: the file first here.
        syncFiles(laptop, tablet)
        tablet.scan()
        syncLogs(laptop, tablet)
        tablet.scan()
        assertEquals(listOf("Concert/Robin Hood - Trombone.pdf"), tablet.partsOf("Robin Hood"))
        assertEquals(1, tablet.library.songs.size)
        assertEquals(song.id, tablet.library.setlists.single().entries.single().songId)
    }

    @Test
    fun `a folder that looks unplugged removes nothing`() {
        val tablet = Device("tablet")
        repeat(20) { tablet.write("Song $it - Trombone.pdf") }
        tablet.scan()
        tablet.root.listFiles()!!.filter { it.isFile }.forEach { it.delete() }
        val report = tablet.scan()
        assertEquals(20, report.heldBack)
        assertEquals(20, tablet.library.songs.size)
        tablet.scan(force = true)
        assertEquals(0, tablet.library.songs.size)
    }

    @Test
    fun `songs made twice by older versions are put back together, setlists and all`() {
        val tablet = Device("tablet")
        tablet.write("MobileSheets/Swag Surfin' - Trombone 1.pdf")
        tablet.write("MobileSheets/Swag Surfin_ - Tuba.pdf")
        tablet.write("MobileSheets/Bond___James Bond.pdf")
        val a = tablet.library.addSong("Swag Surfin'", listOf(Part(file = "MobileSheets/Swag Surfin' - Trombone 1.pdf")))
        val b = tablet.library.addSong("Swag Surfin", listOf(Part(file = "MobileSheets/Swag Surfin_ - Tuba.pdf")))
        val c = tablet.library.addSong("Bond...James Bond", listOf(Part(file = "MobileSheets/Bond___James Bond.pdf")))
        val d = tablet.library.addSong("Bond James Bond", listOf(Part(file = "MobileSheets/Bond___James Bond.pdf")))
        val list = tablet.library.addSetlist("Pep Band")
        listOf(a, b, c, d).forEach { tablet.library.addToSetlist(list.id, it.id) }
        tablet.scan()
        assertEquals(2, tablet.library.songs.size)
        assertEquals(2, tablet.library.songs.first { it.title.startsWith("Swag") }.parts.size)
        assertEquals(1, tablet.library.songs.first { it.title.startsWith("Bond") }.parts.size)
        val alive = tablet.library.songs.map { it.id }.toSet()
        assertTrue(tablet.library.setlists.single().entries.all { it.songId in alive })
        assertEquals(2, tablet.library.setlists.single().entries.size)
    }

    @Test
    fun `a song split off on purpose stays apart`() {
        val tablet = Device("tablet")
        tablet.write("Overture - Trombone.pdf")
        tablet.write("Overture - Tuba.pdf")
        tablet.scan()
        val song = tablet.library.songs.single()
        val tuba = song.parts.first { it.instrument == "tuba" }
        tablet.library.movePart(tuba.id, null, "Overture")
        tablet.scan()
        assertEquals(2, tablet.library.songs.size)
    }

    @Test
    fun `parts written as one list by an older version become records, the same on every device`() {
        val tablet = Device("tablet")
        tablet.write("a - Trombone.pdf")
        val legacy = tablet.library.addSong("a", emptyList())
        // What an older version wrote: the list on the song itself.
        LibraryLog(tablet.root, "old").append(listOf(
            Op(Stamp(System.currentTimeMillis(), 0, "old"), Library.SONG, legacy.id, "parts",
                kotlinx.serialization.json.Json.encodeToJsonElement(Library.PART_LIST, listOf(Part(id = "legacy-1", file = "a - Trombone.pdf"))))
        ))
        tablet.library.refresh()
        assertEquals(listOf("legacy-1"), tablet.library.song(legacy.id)!!.parts.map { it.id })
        val report = tablet.scan()
        assertEquals(1, report.migrated)
        assertEquals(listOf("legacy-1"), tablet.library.song(legacy.id)!!.parts.map { it.id })
    }
}

class LibraryTrashTest {
    @get:org.junit.Rule val tmp = TemporaryFolder()

    @Test
    fun `a removed song leaves the folder, stays gone after a scan, and comes back whole`() {
        val root = tmp.newFolder("lib")
        val library = Library(LibraryLog(root, "me"))
        val memory = File(tmp.root, "mem.json")
        File(root, "Band").mkdirs()
        File(root, "Band/Sleigh Ride - Trombone.pdf").writeText("t")
        File(root, "Band/Sleigh Ride - Tuba.pdf").writeText("u")
        LibraryScan(root, library, memory).run()
        val song = library.songs.single()
        val list = library.addSetlist("Winter")
        library.addToSetlist(list.id, song.id)

        val trash = LibraryTrash(root, library)
        val entry = trash.remove(song)
        assertTrue(!File(root, "Band/Sleigh Ride - Trombone.pdf").exists())
        LibraryScan(root, library, memory).run()
        assertEquals(0, library.songs.size)

        assertTrue(trash.restore(trash.entries().single()))
        LibraryScan(root, library, memory).run()
        assertEquals(listOf(song.id), library.songs.map { it.id })
        assertEquals(2, library.songs.single().parts.size)
        assertEquals(entry.songId, song.id)
        assertEquals(listOf(song.id), library.setlists.single().entries.map { it.songId })
    }
}

class BandPackScanTest {
    @get:org.junit.Rule val tmp = TemporaryFolder()

    @Test
    fun `parts of one band pack share a file, and all follow it when it moves`() {
        val root = tmp.newFolder("lib")
        val library = Library(LibraryLog(root, "me"))
        val memory = File(tmp.root, "mem.json")
        File(root, "Pack/March.pdf").apply { parentFile.mkdirs(); writeText("pack") }
        LibraryScan(root, library, memory).run()
        val song = library.songs.single()
        library.writePart(song.id, Part(id = "tbn", file = "Pack/March.pdf", firstPage = 3, lastPage = 4, instrument = "trombone"))
        library.writePart(song.id, Part(id = "tuba", file = "Pack/March.pdf", firstPage = 5, lastPage = 5, instrument = "tuba"))
        LibraryScan(root, library, memory).run()
        assertEquals(3, library.songs.single().parts.size)
        File(root, "Moved").mkdirs()
        File(root, "Pack/March.pdf").renameTo(File(root, "Moved/March.pdf"))
        LibraryScan(root, library, memory).run()
        assertEquals(listOf("Moved/March.pdf"), library.songs.single().parts.map { it.file }.distinct())
        assertEquals(3, library.songs.single().parts.size)
    }
}
