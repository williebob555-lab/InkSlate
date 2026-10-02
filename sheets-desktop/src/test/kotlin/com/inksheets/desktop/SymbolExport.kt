package com.inksheets.desktop

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors

/**
 * Training data for the symbol reader (SymbolReader): every rest and accidental the trained reader
 * finds on the library's parts drawn as scans, labelled from what the PDF says is printed there -
 * or "other" where nothing is (what it must learn to turn down) - and every one printed that it
 * missed. -Dinksheets.symexport=<dir> -Dinksheets.bench.net=<reader.bin> [-Dinksheets.symexport.pages=2]
 */
class SymbolExport {
    @Test
    fun `cut out every rest and accidental found`() {
        val dir = System.getProperty("inksheets.symexport") ?: return assumeTrue(false)
        val pages = (System.getProperty("inksheets.symexport.pages") ?: "2").toInt()
        val lines = java.util.Collections.synchronizedList(ArrayList<String>())
        val bench = ReadingBenchmark().apply { symbolSink = { lines += it } }
        val parts = bench.corpus()
        val pool = Executors.newFixedThreadPool(3)
        val t0 = System.currentTimeMillis()
        parts.map { f -> pool.submit { for (p in 0 until pages) runCatching { bench.markPage(f, p, scan = true, shots = false) }.onFailure { println("  failed ${f.name} p$p: ${it.message}") } } }.forEach { it.get() }
        pool.shutdown()
        File(dir).mkdirs()
        File(dir, "symbols.tsv").writeText(lines.joinToString("\n"))
        val counts = lines.groupingBy { it.substringBefore('\t') }.eachCount()
        println("SYMBOLS: ${parts.size} parts, ${lines.size} samples in ${(System.currentTimeMillis() - t0) / 1000}s: $counts")
    }
}
