package com.inksheets.desktop

import com.inksheets.core.Library
import com.inksheets.core.LibraryLog
import com.inksheets.core.LibraryScan
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The folder scan run once on a copy of a real library - its records, and an empty stand-in for
 * every file, in a temporary folder - to see what the first scan after updating will do:
 *
 *     ./gradlew :sheets-desktop:test --tests '*RealScan*' -Dinksheets.lib="C:/.../InkSheets"
 *
 * Never touches the library itself. Skipped unless one is named.
 */
class RealScanTest {

    @Test
    fun `the first scan of a real library`() {
        val lib = System.getProperty("inksheets.lib")?.let(::File)
        assumeTrue(lib != null && lib.isDirectory)
        val work = File(System.getProperty("java.io.tmpdir"), "inksheets-scan-test").apply { deleteRecursively(); mkdirs() }
        File(lib!!, ".inksheets/log").copyRecursively(File(work, ".inksheets/log"))
        LibraryScan.listMusic(lib).forEach { f -> File(work, f.path).apply { parentFile.mkdirs(); writeText(f.path) } }

        val library = Library(LibraryLog(work, "scan-test"))
        println("Before: ${library.songs.size} songs, ${library.songs.sumOf { it.parts.size }} parts, ${library.setlists.size} setlists")
        val report = LibraryScan(work, library, File(work, "memory.json")).run()
        println("Migrated ${report.migrated} parts; added ${report.added.size}; moved ${report.moved.size}; removed ${report.removed.size}; held back ${report.heldBack}")
        report.merged.forEach { println("  merged: $it") }
        report.added.take(20).forEach { println("  added: $it") }
        report.removed.forEach { println("  removed: $it") }
        println("After: ${library.songs.size} songs, ${library.songs.sumOf { it.parts.size }} parts")
        val again = LibraryScan(work, library, File(work, "memory.json")).run()
        println("Second scan changes anything: ${again.changed} $again")
        val entriesAlive = library.setlists.sumOf { l -> l.entries.count { e -> library.song(e.songId) != null } }
        println("Setlist entries pointing at songs: $entriesAlive of ${library.setlists.sumOf { it.entries.size }}")
    }
}
