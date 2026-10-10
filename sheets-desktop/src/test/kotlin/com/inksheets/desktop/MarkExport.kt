package com.inksheets.desktop

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors

/**
 * Training data for the mark reader (MarkReader): every shape the reading of the library's parts
 * drawn as scans keeps as printed, labelled from what the PDF says is there - written as it comes.
 * -Dinksheets.markexport=<dir> -Dinksheets.bench.net=<reader.bin> [-Dinksheets.markexport.pages=2]
 */
class MarkExport {
    @Test
    fun `cut out every mark kept`() {
        val dir = System.getProperty("inksheets.markexport") ?: return assumeTrue(false)
        val pages = (System.getProperty("inksheets.markexport.pages") ?: "2").toInt()
        File(dir).mkdirs()
        // -Dinksheets.markexport.skip=N .take=M: a slice of the parts (so a long run goes in short ones); appended to the file.
        val name = System.getProperty("inksheets.markexport.name") ?: "marks.tsv"
        val writer = java.io.FileWriter(File(dir, name), true).buffered()
        val counts = java.util.concurrent.ConcurrentHashMap<String, Int>()
        var total = 0
        val bench = ReadingBenchmark().apply {
            markSink = { batch -> synchronized(writer) { for (l in batch) { writer.write(l); writer.newLine(); total++; counts.merge(l.substringBefore('\t'), 1, Int::plus) } } }
        }
        val parts = bench.corpus().drop((System.getProperty("inksheets.markexport.skip") ?: "0").toInt()).take((System.getProperty("inksheets.markexport.take") ?: "100000").toInt())
        val pool = Executors.newFixedThreadPool((System.getProperty("inksheets.markexport.threads") ?: "3").toInt())
        val t0 = System.currentTimeMillis()
        parts.map { f -> pool.submit { for (p in 0 until pages) runCatching { bench.markPage(f, p, scan = true, shots = false) }.onFailure { println("  failed ${f.name} p$p: ${it.message}") } } }.forEach { it.get() }
        pool.shutdown()
        writer.close()
        println("MARKS: ${parts.size} parts, $total samples in ${(System.currentTimeMillis() - t0) / 1000}s: $counts")
    }
}
