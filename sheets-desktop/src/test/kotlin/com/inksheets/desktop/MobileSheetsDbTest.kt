package com.inksheets.desktop

import com.inksheets.core.Library
import com.inksheets.core.LibraryLog
import com.inksheets.core.MobileSheetsImport
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.sql.DriverManager

/** A real mobilesheets.db, written with MobileSheets' own table layout and read the way the app does. */
class MobileSheetsDbTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a MobileSheets database file imports`() {
        val ms = tmp.newFolder("MobileSheets")
        File(ms, "Band").mkdirs()
        File(ms, "Band/Liberty Bell - Trombone.pdf").writeText("x")
        val db = File(ms, "mobilesheets.db")
        DriverManager.getConnection("jdbc:sqlite:" + db.absolutePath.replace(File.separatorChar, '/')).use { c ->
            c.createStatement().use { st ->
                listOf(
                    "CREATE TABLE Songs (Id INTEGER PRIMARY KEY, Title VARCHAR(255), Difficulty INTEGER, Keywords VARCHAR(255) DEFAULT '')",
                    "CREATE TABLE Files (Id INTEGER PRIMARY KEY, SongId INTEGER, Path VARCHAR(255), PageOrder VARCHAR(255))",
                    "CREATE TABLE Setlists (Id INTEGER PRIMARY KEY, Name VARCHAR(255))",
                    "CREATE TABLE SetlistSong (Id INTEGER PRIMARY KEY, SetlistId INTEGER, SongId INTEGER)",
                    "CREATE TABLE \"Key\" (Id INTEGER PRIMARY KEY, Name VARCHAR(255))",
                    "CREATE TABLE KeySongs (Id INTEGER PRIMARY KEY, KeyId INTEGER, SongId INTEGER)",
                    "INSERT INTO Songs VALUES (1, 'The Liberty Bell', 2, '')",
                    "INSERT INTO Files VALUES (1, 1, '/storage/emulated/0/MobileSheets/Band/Liberty Bell - Trombone.pdf', '')",
                    "INSERT INTO Setlists VALUES (1, 'Pops Concert')",
                    "INSERT INTO SetlistSong VALUES (1, 1, 1)",
                    "INSERT INTO \"Key\" VALUES (1, 'Bb')",
                    "INSERT INTO KeySongs VALUES (1, 1, 1)"
                ).forEach { st.execute(it) }
            }
        }

        val tables = DesktopSheetsPlatform {}.openMobileSheets(db)!!
        val root = ms.parentFile
        val library = Library(LibraryLog(root, "laptop"))
        val byName = ms.walkTopDown().filter { it.isFile }.groupBy { it.name.lowercase() }
        val result = MobileSheetsImport.run(tables, library) { path ->
            MobileSheetsImport.locate(path, ms, byName)?.relativeTo(root)?.invariantSeparatorsPath
        }
        assertEquals(1, result.songs)
        assertEquals(1, result.setlists)
        val song = library.songs.single()
        assertEquals("Bb", song.key)
        assertEquals("trombone", song.parts.single().instrument)
        // A table MobileSheets did not write (no AudioFiles here) is simply empty.
        assertEquals(emptyList<Map<String, Any?>>(), tables.rows("AudioFiles"))
    }
}
