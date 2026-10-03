package com.inksheets.desktop

import com.inksheets.desktop.AnswerKey.Kind
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Which of the library's parts that state their notes print grace notes - a head set well smaller
 * than the part's own, one of the part's own just after it - and how many: for finding grace notes
 * to measure the reader on. -Dinksheets.gracesurvey=1 [-Dinksheets.strips.pages=2]
 */
class GraceSurvey {
    @Test
    fun `parts with grace notes`() {
        assumeTrue(System.getProperty("inksheets.gracesurvey") != null)
        val pages = (System.getProperty("inksheets.strips.pages") ?: "2").toInt()
        val bench = ReadingBenchmark()
        val heads = setOf(Kind.HEAD_BLACK, Kind.HEAD_HALF, Kind.HEAD_WHOLE)
        var total = 0
        for (f in bench.corpus()) for (p in 0 until pages) {
            val key = runCatching { AnswerKey.read(f, p, 72f) }.getOrNull() ?: continue
            val hs = key.filter { it.kind in heads && it.size > 0f }
            if (hs.size < 8) continue
            val normal = hs.map { it.size }.sorted()[hs.size / 2]
            val w = hs.filter { it.size >= normal * 0.9f }.map { it.width }.sorted().let { it[it.size / 2] }
            val sp = w / 1.18f
            val graces = hs.filter { g -> g.size < normal * 0.75f && hs.any { h -> h.size >= normal * 0.9f && h.x - g.x in sp * 0.5f..sp * 3.5f && abs(h.y - g.y) < sp * 4 } }
            if (graces.isNotEmpty()) {
                total += graces.size
                println("GRACES ${f.relativeTo(java.io.File(System.getenv("USERPROFILE"), "Music/Sheet Music/InkSheets")).path} p$p: ${graces.size} (song ${bench.song(f)}) at " + graces.take(4).joinToString(" ") { "${it.x.toInt()},${it.y.toInt()}" })
            }
        }
        println("GRACES total $total")
    }

    @Test
    fun `parts with breath marks`() {
        assumeTrue(System.getProperty("inksheets.breathsurvey") != null)
        val bench = ReadingBenchmark()
        var total = 0
        for (f in bench.corpus()) for (p in 0 until 2) {
            val printed = runCatching { PdfPrinted.read(f, p) }.getOrNull() ?: continue
            val b = printed.symbols.filter { it.kind == com.inksheets.core.omr.Printed.Kind.BREATH }
            if (b.isNotEmpty()) { total += b.size; println("BREATHS ${f.name} p$p: ${b.size} at " + b.take(3).joinToString(" ") { "${it.x.toInt()},${it.y.toInt()}" }) }
        }
        println("BREATHS total $total")
    }
}
