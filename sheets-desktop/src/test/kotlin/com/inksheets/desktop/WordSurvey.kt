package com.inksheets.desktop

import org.junit.Assume.assumeTrue
import org.junit.Test

/** Which italic words the library's parts print, and how often: -Dinksheets.words=1 */
class WordSurvey {
    @Test
    fun `the words printed`() {
        assumeTrue(System.getProperty("inksheets.words") != null)
        val counts = HashMap<String, Int>()
        for (f in ReadingBenchmark().corpus()) for (p in 0 until 3) runCatching {
            PdfPrinted.read(f, p)?.symbols?.filter { it.kind == com.inksheets.core.omr.Printed.Kind.WORD }?.forEach { counts.merge(it.name, 1, Int::plus) }
        }
        println("WORDS: " + counts.entries.sortedByDescending { it.value }.take(60).joinToString { "${it.key} ${it.value}" })
    }
}
