package com.inksheets.desktop

import com.inksheets.core.Instruments
import com.inksheets.core.Library
import com.inksheets.core.LibraryLog
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Prints a real library as it stands - every setlist, its songs and their parts - from a copy of
 * its records, to see what a person sees:
 *
 *     ./gradlew :sheets-desktop:test --tests '*RealLibraryDump*' -Dinksheets.lib="C:/.../InkSheets"
 *
 * Never touches the library itself. Skipped unless one is named. Written to build/library-dump.txt.
 */
class RealLibraryDump {

    @Test
    fun `dump a real library`() {
        val lib = System.getProperty("inksheets.lib")?.let(::File)
        assumeTrue(lib != null && lib.isDirectory)
        val work = File(System.getProperty("java.io.tmpdir"), "inksheets-dump").apply { deleteRecursively(); mkdirs() }
        File(lib!!, ".inksheets/log").copyRecursively(File(work, ".inksheets/log"))
        val library = Library(LibraryLog(work, "dump"))
        Instruments.use(library.instruments())
        val out = StringBuilder()
        fun song(id: String) {
            val s = library.song(id) ?: return out.appendLine("    (missing song $id)").let { }
            out.appendLine("    ${s.title}  [${s.id}]")
            for (p in s.parts) {
                val exists = File(lib, p.file).isFile
                out.appendLine("        ${Instruments.partName(p).padEnd(22)} ${p.source.name.padEnd(9)} ${if (exists) "" else "MISSING "}${p.file}${p.firstPage?.let { " p$it-${p.lastPage}" } ?: ""}${if (p.also.isNotEmpty()) " also=${p.also}" else ""}")
            }
        }
        for (list in library.setlists) {
            out.appendLine("SETLIST ${list.name} (${list.entries.size})")
            list.entries.forEach { song(it.songId) }
        }
        out.appendLine("ALL SONGS (${library.songs.size})")
        library.songs.forEach { song(it.id) }
        File("build/library-dump.txt").writeText(out.toString())
    }
}
