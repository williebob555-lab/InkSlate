package com.inksheets.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A small MobileSheets library, as its database's tables would hand it over. */
class MobileSheetsImportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val tables = mapOf(
        "Songs" to listOf(
            mapOf("Id" to 1, "Title" to "The Liberty Bell", "Difficulty" to 3, "Keywords" to "march, concert"),
            mapOf("Id" to 2, "Title" to "September", "Difficulty" to 0),
            mapOf("Id" to 3, "Title" to "Lost Song")
        ),
        "Files" to listOf(
            mapOf("Id" to 10, "SongId" to 1, "Path" to "/storage/emulated/0/MobileSheets/Band/Liberty Bell - Euphonium.pdf", "PageOrder" to ""),
            mapOf("Id" to 11, "SongId" to 2, "Path" to "/storage/emulated/0/MobileSheets/Jazz/September.pdf", "PageOrder" to "3-4"),
            mapOf("Id" to 12, "SongId" to 3, "Path" to "/storage/emulated/0/MobileSheets/gone.pdf")
        ),
        "Composer" to listOf(mapOf("Id" to 5, "Name" to "John Philip Sousa")),
        "ComposerSongs" to listOf(mapOf("Id" to 1, "ComposerId" to 5, "SongId" to 1)),
        "Artists" to listOf(mapOf("Id" to 6, "Name" to "Earth, Wind & Fire")),
        "ArtistsSongs" to listOf(mapOf("Id" to 1, "ArtistId" to 6, "SongId" to 2)),
        "Key" to listOf(mapOf("Id" to 7, "Name" to "Bb")),
        "KeySongs" to listOf(mapOf("Id" to 1, "KeyId" to 7, "SongId" to 1)),
        "Tempos" to listOf(mapOf("Id" to 1, "SongId" to 1, "Tempo" to 120, "TempoIndex" to 0)),
        "Collections" to listOf(mapOf("Id" to 8, "Name" to "Wind Ensemble")),
        "CollectionSong" to listOf(mapOf("Id" to 1, "CollectionId" to 8, "SongId" to 1)),
        "Bookmarks" to listOf(mapOf("Id" to 1, "SongId" to 1, "Name" to "Trio", "PageNum" to 1)),
        "AudioFiles" to listOf(
            mapOf("Id" to 1, "SongId" to 1, "Title" to "Marine Band", "File" to "/storage/emulated/0/MobileSheets/Audio/liberty.mp3",
                "ABEnabled" to 1, "APosition" to 5000, "BPosition" to 20000, "TempoSpeed" to 0.8, "PitchShift" to 0)
        ),
        "Setlists" to listOf(mapOf("Id" to 20, "Name" to "Spring Concert")),
        "SetlistSong" to listOf(
            mapOf("Id" to 2, "SetlistId" to 20, "SongId" to 2),
            mapOf("Id" to 1, "SetlistId" to 20, "SongId" to 1)
        )
    )

    @Test
    fun `a MobileSheets library comes across with its details, files and setlists`() {
        val root = tmp.newFolder("Music")
        val msFolder = File(root, "MobileSheets").apply { mkdirs() }
        listOf("Band/Liberty Bell - Euphonium.pdf", "Jazz/September.pdf", "Audio/liberty.mp3").forEach {
            File(msFolder, it).apply { parentFile.mkdirs(); writeText("x") }
        }
        val library = Library(LibraryLog(root, "tablet"))
        val byName = msFolder.walkTopDown().filter { it.isFile }.groupBy { it.name.lowercase() }
        val resolve = { path: String ->
            MobileSheetsImport.locate(path, msFolder, byName)?.relativeTo(root)?.invariantSeparatorsPath
        }

        val result = MobileSheetsImport.run({ tables[it].orEmpty() }, library, resolve)
        assertEquals(2, result.songs)
        assertEquals(1, result.setlists)
        assertEquals(listOf("/storage/emulated/0/MobileSheets/gone.pdf"), result.missing)

        val liberty = library.songs.first { it.title == "The Liberty Bell" }
        assertEquals(listOf("John Philip Sousa"), liberty.composers)
        assertEquals("Bb", liberty.key)
        assertEquals(120, liberty.tempo)
        assertEquals(3, liberty.difficulty)
        assertTrue(liberty.tags.containsAll(listOf("Wind Ensemble", "march", "concert")))
        assertEquals("MobileSheets/Band/Liberty Bell - Euphonium.pdf", liberty.parts.single().file)
        assertEquals("euphonium", liberty.parts.single().instrument)
        assertEquals(listOf(Bookmark("Trio", page = 2)), liberty.bookmarks)
        val track = liberty.audio.single()
        assertEquals(5000L, track.loopStartMs)
        assertEquals(0.8, track.speed, 0.001)

        val september = library.songs.first { it.title == "September" }
        assertEquals(3, september.parts.single().firstPage)
        assertEquals(4, september.parts.single().lastPage)

        val folder = library.foldersIn(null).single()
        assertEquals(MobileSheetsImport.FOLDER_NAME, folder.name)
        val set = library.setlistsIn(folder.id).single()
        // In MobileSheets' order, by entry id: Liberty Bell was added first.
        assertEquals(listOf(liberty.id, september.id), set.entries.map { it.songId })

        // Running it again adds nothing.
        val again = MobileSheetsImport.run({ tables[it].orEmpty() }, library, resolve)
        assertEquals(0, again.songs)
        assertEquals(0, again.setlists)
        assertEquals(2, library.songs.size)
    }

    @Test
    fun `one song per instrument in MobileSheets becomes one song with parts here`() {
        val root = tmp.newFolder("Music")
        listOf("magic-bass.pdf", "magic-tbn.pdf", "bass-song.pdf", "1812-euph.pdf", "1812-tbn.pdf").forEach { File(root, it).writeText("x") }
        val t = mapOf(
            "Songs" to listOf(
                mapOf("Id" to 1, "Title" to "24 K Magic - Electric Bass"),
                mapOf("Id" to 2, "Title" to "24 K Magic - Trombone 1"),
                mapOf("Id" to 3, "Title" to "All About That Bass"),
                mapOf("Id" to 4, "Title" to "1812 Euph 2"),
                mapOf("Id" to 5, "Title" to "1812 Trombone")
            ),
            "Files" to listOf(
                mapOf("Id" to 1, "SongId" to 1, "Path" to "magic-bass.pdf"),
                mapOf("Id" to 2, "SongId" to 2, "Path" to "magic-tbn.pdf"),
                mapOf("Id" to 3, "SongId" to 3, "Path" to "bass-song.pdf"),
                mapOf("Id" to 4, "SongId" to 4, "Path" to "1812-euph.pdf"),
                mapOf("Id" to 5, "SongId" to 5, "Path" to "1812-tbn.pdf")
            ),
            "Setlists" to listOf(mapOf("Id" to 1, "Name" to "Pep Band"), mapOf("Id" to 2, "Name" to "Pep Band Electric Bass")),
            "SetlistSong" to listOf(
                mapOf("Id" to 1, "SetlistId" to 1, "SongId" to 2),
                mapOf("Id" to 2, "SetlistId" to 2, "SongId" to 1)
            )
        )
        val library = Library(LibraryLog(root, "t"))
        val result = MobileSheetsImport.run({ t[it].orEmpty() }, library) { it }
        assertEquals(3, result.songs)
        val magic = library.songs.first { it.title == "24 K Magic" }
        assertEquals(setOf("bass-guitar", "trombone"), magic.instruments)
        assertTrue(library.songs.any { it.title == "All About That Bass" })
        assertEquals(setOf("euphonium", "trombone"), library.songs.first { it.title == "1812" }.instruments)
        // Both setlists now point at the one song; the instrument chosen picks the part.
        assertEquals(listOf(magic.id, magic.id), library.setlists.map { it.entries.single().songId })
    }
}
