package com.inksheets.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BulkImportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A class website's "download all": concerts as folders, some songs as folders of parts. */
    private val download = listOf(
        "Fall Concert/03 - Sleigh Ride - Trombone 1.pdf",
        "Fall Concert/03 - Sleigh Ride - Euphonium.pdf",
        "Fall Concert/01 - Liberty Bell/Trombone 1.pdf",
        "Fall Concert/01 - Liberty Bell/Full Score.pdf",
        "Fall Concert/02_Semper_Fidelis_Tbn1.pdf",
        "Fall Concert/Sleigh Ride recording.mp3",
        "Spring Concert/Liberty Bell - Trombone 1.pdf",
        "Spring Concert/Stars and Stripes - Tbn. 1.pdf",
        "Syllabus.docx",
        "__MACOSX/Fall Concert/._Sleigh Ride.pdf",
        ".DS_Store"
    )

    @Test
    fun `songs gather across folders, and each concert folder is a setlist in track order`() {
        val plan = BulkImport.plan("Band", download)
        assertEquals(
            listOf("Liberty Bell", "Semper Fidelis", "Sleigh Ride", "Stars and Stripes"),
            plan.songs.map { it.title }.sorted()
        )
        val liberty = plan.songs.first { it.title == "Liberty Bell" }
        assertEquals(3, liberty.parts.size)
        assertEquals(listOf("Sleigh Ride recording.mp3"), plan.songs.first { it.title == "Sleigh Ride" }.audio.map { it.substringAfterLast('/') })
        assertEquals(
            listOf(
                BulkImport.SetlistPlan("Fall Concert", listOf("Liberty Bell", "Semper Fidelis", "Sleigh Ride")),
                BulkImport.SetlistPlan("Spring Concert", listOf("Liberty Bell", "Stars and Stripes"))
            ),
            plan.setlists
        )
    }

    @Test
    fun `a zip goes in once, and again only adds what is new`() {
        val zip = tmp.newFile("Fall Concert.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            for (name in listOf("01 - Liberty Bell - Trombone 1.pdf", "02 - Sleigh Ride - Trombone 1.pdf")) {
                out.putNextEntry(ZipEntry(name)); out.write(name.toByteArray()); out.closeEntry()
            }
        }
        val root = tmp.newFolder("music")
        val library = Library(LibraryLog(root, "me"))
        val source = BulkImport.ZipSource(zip)
        val plan = BulkImport.plan(source.name, source.list())
        val first = BulkImport.apply(plan, source, library, root, makeSetlists = true)
        assertEquals(2, first.songsAdded)
        assertEquals(1, first.setlistsMade)
        assertTrue(File(root, "Imported/Fall Concert/01 - Liberty Bell - Trombone 1.pdf").isFile)
        val setlist = library.setlists.single()
        assertEquals("Fall Concert", setlist.name)
        assertEquals(listOf("Liberty Bell", "Sleigh Ride"), setlist.entries.map { library.song(it.songId)!!.title })

        // The same download a second time: nothing doubled.
        val again = BulkImport.apply(BulkImport.plan(source.name, source.list()), source, library, root, makeSetlists = true)
        assertEquals(0, again.songsAdded)
        assertEquals(0, again.setlistsMade)
        assertEquals(2, library.songs.size)
        assertEquals(1, library.songs.first { it.title == "Liberty Bell" }.parts.size)
        assertEquals(2, library.setlists.single().entries.size)
    }

    @Test
    fun `names Windows refuses are made ones it takes, and the import carries on`() {
        val zip = tmp.newFile("PEP BAND.zip")
        val odd = "PEP BAND/Music/*Student Arrangements/Replay (Henry)/replay-Alto_Saxophone.pdf"
        ZipOutputStream(zip.outputStream()).use { out ->
            for (name in listOf(odd, "PEP BAND/Music/Hey: Baby?/Trumpet.pdf", "PEP BAND/CON/Tuba.pdf")) {
                out.putNextEntry(ZipEntry(name)); out.write(name.toByteArray()); out.closeEntry()
            }
        }
        val source = BulkImport.ZipSource(zip)
        assertEquals(
            listOf(
                "PEP BAND/Music/Student Arrangements/Replay (Henry)/replay-Alto_Saxophone.pdf",
                "PEP BAND/Music/Hey Baby/Trumpet.pdf",
                "PEP BAND/_CON/Tuba.pdf"
            ),
            source.list()
        )
        val staging = tmp.newFolder("staging")
        source.list().forEach { source.copy(it, File(staging, it)) }
        assertEquals(odd, File(staging, source.list().first()).readText())
    }

    @Test
    fun `a folder per song is not a setlist per song`() {
        val files = listOf(
            "PEP BAND/Music/24K Magic/24K Magic - Alto Sax 1.pdf",
            "PEP BAND/Music/24K Magic/24K Magic - Trumpet 1.pdf",
            "PEP BAND/Music/99 Red Balloons/99 Red Balloons - Tuba.pdf",
            "PEP BAND/Music/99 Red Balloons/99 Red Balloons.pdf",
            "PEP BAND/Music/Student Arrangements/Bills (Marli)/bills-Trumpet_in_Bb_1.pdf",
            "PEP BAND/Music/Student Arrangements/Bills (Marli)/bills-Mellophone.pdf",
            "PEP BAND/Music/Student Arrangements/Replay (Henry)/Trombone 1.pdf"
        )
        val plan = BulkImport.plan("PEP BAND", files)
        assertEquals(4, plan.songs.size)
        assertEquals(listOf("PEP BAND", "Student Arrangements"), plan.setlists.map { it.name })
        assertEquals(2, plan.setlists.first().songTitles.size)
        assertEquals(2, plan.setlists.last().songTitles.size)
    }

    @Test
    fun `a song already in the library gains the new parts`() {
        val root = tmp.newFolder("music2")
        val library = Library(LibraryLog(root, "me"))
        library.addSong("The Liberty Bell", listOf(Part(file = "old/Liberty Bell - Euphonium.pdf", instrument = "euphonium")))
        val folder = tmp.newFolder("dl")
        File(folder, "LIBERTY BELL - TROMBONE 1.pdf").writeText("x")
        val source = BulkImport.FolderSource(folder)
        BulkImport.apply(BulkImport.plan(source.name, source.list()), source, library, root, makeSetlists = false)
        assertEquals(1, library.songs.size)
        assertEquals(listOf("euphonium", "trombone"), library.songs.single().parts.map { it.instrument })
    }
}
