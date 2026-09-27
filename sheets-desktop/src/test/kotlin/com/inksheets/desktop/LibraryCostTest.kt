package com.inksheets.desktop

import com.inksheets.core.Library
import com.inksheets.core.LibraryLog
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** How long the library's reads take on a real library - the ones a song change makes. */
class LibraryCostTest {
    @Test
    fun `reading a real library`() {
        val lib = System.getProperty("inksheets.lib")?.let(::File)
        assumeTrue(lib != null && lib.isDirectory)
        val work = File(System.getProperty("java.io.tmpdir"), "inksheets-cost").apply { deleteRecursively(); mkdirs() }
        File(lib!!, ".inksheets/log").copyRecursively(File(work, ".inksheets/log"))
        val library = Library(LibraryLog(work, "cost"))
        repeat(3) { library.songs }
        fun time(name: String, n: Int, block: () -> Unit) {
            val t = System.nanoTime(); repeat(n) { block() }
            println("COST $name: ${(System.nanoTime() - t) / 1_000_000.0 / n} ms each")
        }
        val some = library.songs.first().id
        time("songs", 20) { library.songs }
        time("song(id)", 20) { library.song(some) }
        time("setlists", 20) { library.setlists }
        val set = library.setlists.maxBy { it.entries.size }
        time("every entry of the biggest set by song(id)", 3) { set.entries.forEach { library.song(it.songId) } }
    }
}
