package com.inksheets.desktop

import com.inksheets.core.Instruments
import com.inksheets.core.Library
import com.inksheets.core.LibraryLog
import com.inksheets.core.LibraryScan
import com.inksheets.core.PartChoice
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * One automatic sort of a real library - a copy of its records, and a copy of every file, in a
 * temporary folder - and what is left looking out of place afterwards:
 *
 *     ./gradlew :sheets-desktop:test --tests '*RealSort*' -Dinksheets.lib="C:/.../InkSheets"
 *
 * The aim is no strays after one sort. Never touches the library itself. Skipped unless one is
 * named. Writes build/sort-report.txt.
 */
class RealSortTest {

    @Test
    fun `one sort of a real library leaves no strays`() {
        val lib = System.getProperty("inksheets.lib")?.let(::File)
        assumeTrue(lib != null && lib.isDirectory)
        val work = File(System.getProperty("java.io.tmpdir"), "inksheets-sort-test").apply { deleteRecursively(); mkdirs() }
        File(lib!!, ".inksheets/log").copyRecursively(File(work, ".inksheets/log"))
        File(lib, ".inksheets/imported-marks.json").takeIf { it.isFile }?.copyTo(File(work, ".inksheets/imported-marks.json"))
        // Real files, so copies can be told apart by what is in them and whether they open.
        LibraryScan.listMusic(lib).forEach { f -> File(lib, f.path).copyTo(File(work, f.path)).setLastModified(File(lib, f.path).lastModified()) }
        // What this laptop had already seen, so nothing looks newly deleted.
        val memory = File(work, "memory.json")

        val library = Library(LibraryLog(work, "sort-test"))
        Instruments.use(library.instruments())
        val out = StringBuilder()
        val scan = LibraryScan(work, library, memory, pages = { f -> DesktopPages.count(f) })
        out.appendLine("Before: ${library.songs.size} songs; strays:")
        val before = scan.strays()
        before.forEach { out.appendLine("  $it") }
        val t0 = System.currentTimeMillis()
        val report = scan.run()
        out.appendLine("Sort took ${System.currentTimeMillis() - t0} ms: added ${report.added.size}, moved ${report.moved.size}, removed ${report.removed.size}")
        report.sorted.forEach { out.appendLine("  sorted: $it") }
        report.merged.forEach { out.appendLine("  merged: $it") }
        report.removed.forEach { out.appendLine("  removed: $it") }
        val after = scan.strays()
        out.appendLine("After: ${library.songs.size} songs; ${after.size} strays (was ${before.size}):")
        after.forEach { out.appendLine("  $it") }
        val t1 = System.currentTimeMillis()
        val again = scan.run()
        out.appendLine("Second scan took ${System.currentTimeMillis() - t1} ms")
        out.appendLine("A second sort changes: ${again.sorted.size + again.merged.size + again.added.size}")
        again.sorted.forEach { out.appendLine("  again: $it") }

        // What each of the owner's instruments opens, song by song, in the pep band setlist.
        for (profile in library.profiles()) {
            out.appendLine("\n== ${profile.name}")
            for (list in library.setlists.filter { it.name.contains("PEP", ignoreCase = true) }) {
                for (e in list.entries) {
                    val s = library.song(e.songId) ?: continue
                    val p = PartChoice.partFor(s, profile)
                    val stand = PartChoice.standIn(s, profile)
                    out.appendLine("  ${s.title}: ${p?.let { Instruments.partName(it) } ?: "-"}${if (stand != null) "  (stand-in)" else ""}  ${p?.file ?: ""}")
                }
            }
        }
        out.appendLine("\n== ALL SONGS")
        for (s in library.songs) {
            out.appendLine("  ${s.title}")
            s.parts.forEach { p -> out.appendLine("      ${Instruments.partName(p).padEnd(22)} ${p.file}") }
            s.duplicates.forEach { p -> out.appendLine("      (copy) ${p.file}") }
        }
        File("build/sort-report.txt").writeText(out.toString())
        println("strays before ${before.size}, after ${after.size}")
    }
}
