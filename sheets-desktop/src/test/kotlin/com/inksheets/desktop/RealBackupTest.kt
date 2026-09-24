package com.inksheets.desktop

import com.inksheets.core.Library
import com.inksheets.core.LibraryLog
import com.inksheets.core.MobileSheetsImport
import com.inksheets.core.MsbBackup
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * A real MobileSheets backup, unpacked and imported into a throwaway library:
 *
 *     ./gradlew :sheets-desktop:test --tests '*RealBackup*' -Dinksheets.msb="C:/path/backup.msb"
 *
 * Writes only under a temporary folder. Skipped unless a backup is named.
 */
class RealBackupTest {

    @Test
    fun `a real backup unpacks and imports`() {
        val msb = System.getProperty("inksheets.msb")?.let(::File)
        assumeTrue(msb != null && msb.isFile)
        val work = File(System.getProperty("java.io.tmpdir"), "inksheets-msb-test").apply { deleteRecursively(); mkdirs() }
        val platform = DesktopSheetsPlatform {}
        val started = System.currentTimeMillis()
        val result = MsbBackup.extract(msb!!, File(work, ".inksheets/mobilesheets.db"), File(work, "MobileSheets"), platform::openMobileSheets)
        println("MSB version ${result.version}: ${result.files} files in ${(System.currentTimeMillis() - started) / 1000}s; stopped early: ${result.stoppedEarly}")

        val tables = platform.openMobileSheets(result.database)!!
        println("Songs ${tables.rows("Songs").size}, setlists ${tables.rows("Setlists").size}, files ${tables.rows("Files").size}, audio ${tables.rows("AudioFiles").size}")
        tables.rows("Files").take(5).forEach { println("  ${it["Path"]}  pages=${it["PageOrder"]}") }

        val msFolder = File(work, "MobileSheets")
        val byName = msFolder.walkTopDown().filter { it.isFile }.groupBy { it.name.lowercase() }
        val library = Library(LibraryLog(work, "test"))
        val imported = MobileSheetsImport.run(tables, library) { path ->
            MobileSheetsImport.locate(path, msFolder, byName)?.relativeTo(work)?.invariantSeparatorsPath
        }
        println("Imported: $imported")
        println("Songs with several parts: ${library.songs.count { it.parts.size > 1 }}; parts with no instrument: ${library.songs.sumOf { s -> s.parts.count { it.instrument == null } }}")
        library.songs.filter { it.parts.size > 1 }.take(12).forEach { s -> println("  ${s.title}: ${s.parts.map { it.instrument ?: "?" }}") }
        library.songs.filter { s -> s.parts.any { it.instrument == null } }.take(12).forEach { s -> println("  unknown: ${s.title}") }
        library.setlists.forEach { println("  setlist ${it.name}: ${it.entries.size} songs") }
    }
}
