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

    /** A backup in MobileSheets' version-6 layout, unpacked: database first, then the song's files. */
    @Test
    fun `a MobileSheets backup unpacks`() {
        val db = tmp.newFile("source.db")
        db.delete()
        DriverManager.getConnection("jdbc:sqlite:" + db.absolutePath.replace(File.separatorChar, '/')).use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE Files (Id INTEGER PRIMARY KEY, SongId INTEGER, Path VARCHAR(255))")
                st.execute("CREATE TABLE AudioFiles (Id INTEGER PRIMARY KEY, SongId INTEGER, File VARCHAR(255))")
                st.execute("INSERT INTO Files VALUES (1, 7, '/storage/emulated/0/MobileSheets/Band/Tune - Trombone.pdf')")
                st.execute("INSERT INTO AudioFiles VALUES (1, 7, '/storage/emulated/0/MobileSheets/Audio/tune.mp3')")
            }
        }
        val msb = tmp.newFile("backup.msb")
        java.io.DataOutputStream(msb.outputStream()).use { out ->
            out.writeInt((1234567892L + 3).toInt())                   // version 6
            out.writeInt(1); out.writeShort(7); out.write("default".toByteArray()); out.writeLong(3); out.write("<x>".toByteArray())
            out.writeLong(0)                                          // user filters
            out.writeLong(0)                                          // annotation favourites
            out.writeLong(0)                                          // stamp lists
            out.writeInt(0)                                           // custom stamps
            val dbBytes = db.readBytes()
            out.writeLong(dbBytes.size.toLong()); out.write(dbBytes)
            out.writeLong(1287706427353294236L); out.writeLong(7)
            out.writeLong(9); out.write("pdf bytes".toByteArray())
            out.writeLong(9); out.write("mp3 bytes".toByteArray())
            repeat(8) { out.writeByte(0xFF) }
        }
        val out = tmp.newFolder("out")
        val result = com.inksheets.core.MsbBackup.extract(msb, File(out, "ms.db"), File(out, "MobileSheets"), DesktopSheetsPlatform {}::openMobileSheets)
        assertEquals(6, result.version)
        assertEquals(2, result.files)
        assertEquals(null, result.stoppedEarly)
        assertEquals("pdf bytes", File(out, "MobileSheets/Band/Tune - Trombone.pdf").readText())
        assertEquals("mp3 bytes", File(out, "MobileSheets/Audio/tune.mp3").readText())
    }
}
