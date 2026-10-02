package com.inksheets.desktop

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors

/** The library's printed words, cut from its parts drawn as scans: -Dinksheets.wordexport=<dir> [-Dinksheets.wordexport.pages=3] */
class WordExport {
    @Test
    fun `cut out every word printed`() {
        val dir = System.getProperty("inksheets.wordexport") ?: return assumeTrue(false)
        val pages = (System.getProperty("inksheets.wordexport.pages") ?: "3").toInt()
        File(dir).mkdirs()
        val writer = File(dir, "real.tsv").bufferedWriter()
        var total = 0
        val bench = ReadingBenchmark().apply { wordSink = { batch -> synchronized(writer) { batch.forEach { writer.write(it); writer.newLine(); total++ } } } }
        val pool = Executors.newFixedThreadPool(3)
        bench.corpus().map { f -> pool.submit { for (p in 0 until pages) runCatching { bench.markPage(f, p, scan = true, shots = false) } } }.forEach { it.get() }
        pool.shutdown(); writer.close()
        println("WORDS: $total")
    }
}
