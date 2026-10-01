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
        // What opening the app costs: the log read in, the first song list, profiles, the first scan.
        fun once(name: String, block: () -> Unit) { val t = System.nanoTime(); block(); println("COST start $name: ${(System.nanoTime() - t) / 1_000_000.0} ms") }
        lateinit var library: Library
        once("read the log") { library = Library(LibraryLog(work, "cost")) }
        once("first song list") { library.songs }
        once("profiles") { library.profiles() }
        println("COST log: ${File(work, ".inksheets/log").walkTopDown().filter { it.isFile }.count()} files, ${File(work, ".inksheets/log").walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1024} KB, ${library.songs.size} songs")
        repeat(3) { library.songs }
        fun time(name: String, n: Int, block: () -> Unit) {
            val t = System.nanoTime(); repeat(n) { block() }
            println("COST $name: ${(System.nanoTime() - t) / 1_000_000.0 / n} ms each")
        }
        time("listMusic (walk the real folder)", 3) { com.inksheets.core.LibraryScan.listMusic(lib) }
        val scan = com.inksheets.core.LibraryScan(lib, library, File(work, "memory.json"))
        // Real folder, but the library is a copy and deletions need a memory this device lacks: read-only in effect.
        once("first scan") { scan.run() }
        scan.run()
        time("no-op scan", 3) { scan.run() }
        // Where a scan that finds nothing spends its time: its thread sampled every few ms.
        val worker = Thread { repeat(15) { scan.run() } }
        val seen = HashMap<String, Int>()
        worker.start()
        while (worker.isAlive) {
            val st = worker.stackTrace
            st.firstOrNull { it.className.startsWith("com.inksheets") }?.let { top ->
                val key = st.filter { it.className.startsWith("com.inksheets") }.take(3).joinToString(" < ") { it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber }
                seen[key] = (seen[key] ?: 0) + 1
            }
            Thread.sleep(3)
        }
        seen.entries.sortedByDescending { it.value }.take(15).forEach { println("COST sample ${it.value} ${it.key}") }
        val some = library.songs.first().id
        time("songs", 20) { library.songs }
        time("song(id)", 20) { library.song(some) }
        time("setlists", 20) { library.setlists }
        time("folders", 20) { library.folders }
        time("profiles", 20) { library.profiles() }
        time("instruments", 20) { library.instruments() }
        time("sisters", 200) { com.inksheets.core.Instruments.sisters("euphonium") }
        val prof = library.profiles().first()
        time("partFor every song", 5) { library.songs.forEach { com.inksheets.core.PartChoice.partFor(it, prof) } }
        time("fit every song", 5) { library.songs.forEach { com.inksheets.core.PartChoice.fit(it, prof) } }
        val set = library.setlists.maxBy { it.entries.size }
        time("every entry of the biggest set by song(id)", 3) { set.entries.forEach { library.song(it.songId) } }
    }
}
