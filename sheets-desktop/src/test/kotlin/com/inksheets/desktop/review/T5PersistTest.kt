package com.inksheets.desktop.review

import com.inksheets.ui.Transcriber
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** T5 round 2: do the real library's readings still belong to their files under the new key? */
class T5PersistTest {
    @Test
    fun `how many real parts have a reading under the current key`() {
        assumeTrue(T5Data.readings.isDirectory)
        val all = T5Data.pdfs()
        var current = 0; var wholeOnly = 0; var older = 0; var none = 0; var marked = 0; var markedLost = 0
        val lostExamples = ArrayList<String>()
        for (f in all) {
            val first = Transcriber.idOf(f); val whole = T5Data.whole(f)
            val isMarked = first != whole
            if (isMarked) marked++
            fun has(v: Int, id: String) = File(T5Data.readings, "r$v-$id").let { d -> d.isDirectory && d.listFiles()?.any { x -> x.name.matches(Regex("p[0-9]+[.]json")) } == true }
            when {
                has(13, first) -> current++
                has(13, whole) -> wholeOnly++
                (12 downTo 5).any { has(it, first) || has(it, whole) } -> older++
                else -> { none++; if (isMarked) { markedLost++; if (lostExamples.size < 8) lostExamples += f.name } }
            }
        }
        println("T5 PERSIST pdfs=${all.size} current(first-rev key)=$current wholeKeyOnly=$wholeOnly olderReader=$older noReading=$none; marked(incremental update)=$marked, marked with no reading=$markedLost $lostExamples")
        // Readings folders with an edits dir
        val edits = T5Data.readings.listFiles()!!.filter { File(it, "edits").isDirectory }
        println("T5 PERSIST reading folders with edits/: ${edits.size}")
        // Reading folders that no file points at (orphans).
        val keys = HashSet<String>(); for (f in all) { keys += Transcriber.idOf(f); keys += T5Data.whole(f) }
        val orphans = T5Data.readings.listFiles()!!.filter { it.isDirectory && it.name.substringAfter('-') !in keys }
        println("T5 PERSIST reading folders with no file in the library: ${orphans.size} of ${T5Data.readings.listFiles()!!.size}")
    }
}
