package com.inksheets.desktop

import com.inksheets.core.omr.Direction
import com.inksheets.core.omr.Ink
import com.inksheets.core.omr.Measure
import com.inksheets.core.omr.Note
import com.inksheets.core.omr.Recognizer
import com.inksheets.core.omr.Strips
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * The marks the reader gets from a heavy old plate-print scan (Trombone Copprash, a CCITT bitmap with no
 * notes in the PDF), against what is printed - read off the page by eye, in the resource
 * copprash-marks.truth: No. 8's staccato dot on the first note of every group of four 16ths and slur
 * from the second to the fourth, the dynamics and hairpins of Nos. 7 and 8. Read as the app reads a scan.
 * Needs the library (skipped without it); -Dinksheets.marks.real=1 also prints every bar's score.
 */
class CopprashMarksTest {
    private class Truth(val file: String, val page: Int, val bars: List<Triple<Int, Int, Int>>, val dyn: Map<Int, List<String>>, val hair: Map<Int, List<String>>, val skipSlur: Set<Triple<Int, Int, Int>>)

    private fun truth(): Truth {
        val lines = CopprashMarksTest::class.java.getResourceAsStream("/copprash-marks.truth")!!.bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        var file = ""; var page = 0
        val bars = ArrayList<Triple<Int, Int, Int>>(); val dyn = HashMap<Int, List<String>>(); val hair = HashMap<Int, List<String>>(); val skip = HashSet<Triple<Int, Int, Int>>()
        for (l in lines) {
            val w = l.split(" ")
            when (w[0]) {
                "file" -> file = l.removePrefix("file ")
                "page" -> page = w[1].toInt()
                "bar" -> bars += Triple(w[1].toInt(), w[2].toInt(), w[3].toInt())
                "dyn" -> dyn[w[1].toInt()] = w.drop(2)
                "hair" -> hair[w[1].toInt()] = w.drop(2)
                "skipslur" -> skip += Triple(w[1].toInt(), w[2].toInt(), w[3].toInt())
            }
        }
        return Truth(file, page, bars, dyn, hair, skip)
    }

    /** What the reader gets from the page, bars in order (as the app reads it: picture only). */
    private fun read(file: File, page: Int): List<Measure> {
        val source = com.inkslate.desktop.DesktopSources.open(file, detached = true)!!
        val carry = Recognizer.Carry(); var number = 1
        var out = emptyList<Measure>()
        for (p in max(0, page - 1)..page) {
            fun draw(width: Int): Pair<IntArray, Ink> {
                val img = source.render(p, width)!!
                val px = IntArray(img.width * img.height); img.readPixels(px)
                return Strips.grey(px) to Ink.fromArgb(img.width, img.height, px)
            }
            val space = Recognizer().metrics(draw(1600).second)?.second
            val width = if (space == null || space <= 0f) 1600 else (1600 * 18f / space).toInt().coerceIn(1000, 5000)
            val (grey, ink) = draw(width)
            val r = Recognizer().read(ink, p, number, carry, null, grey = grey, net = com.inksheets.core.omr.Net.shipped)
            number += r.measures.size
            out = r.measures
        }
        return out
    }

    private fun lcs(a: List<String>, b: List<String>): Int {
        val t = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices) for (j in b.indices) t[i + 1][j + 1] = if (a[i] == b[j]) t[i][j] + 1 else max(t[i][j + 1], t[i + 1][j])
        return t[a.size][b.size]
    }

    class Score(var stacTrue: Int = 0, var stacHit: Int = 0, var stacFalse: Int = 0, var bars: Int = 0, var barsSkipped: Int = 0,
                var slurTrue: Int = 0, var slurExact: Int = 0, var slurShort: Int = 0, var slurOver: Int = 0, var slurMissed: Int = 0, var slurExtra: Int = 0,
                var dynTrue: Int = 0, var dynHit: Int = 0, var dynFalse: Int = 0, var hairTrue: Int = 0, var hairHit: Int = 0, var hairFalse: Int = 0) {
        override fun toString() = ("staccato: %d/%d right note, %d false (precision %.0f%%, recall %.0f%%); slurs: %d of %d exact (start..end notes right), %d short, %d over, %d missed, %d extra; " +
            "dynamics: %d/%d found, %d false; hairpins: %d/%d found, %d false; bars scored %d (skipped %d: not the expected notes)")
            .format(stacHit, stacTrue, stacFalse, 100.0 * stacHit / max(1, stacHit + stacFalse), 100.0 * stacHit / max(1, stacTrue),
                slurExact, slurTrue, slurShort, slurOver, slurMissed, slurExtra, dynHit, dynTrue, dynFalse, hairHit, hairTrue, hairFalse, bars, barsSkipped)
    }

    fun score(): Score? {
        val t = truth()
        val file = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets/${t.file}")
        if (!file.isFile) return null
        val measures = read(file, t.page)
        val s = Score()
        val verbose = System.getProperty("inksheets.marks.real") != null
        fun staffBars(staff: Int) = measures.filter { it.staff == staff && it.bars <= 1 }.sortedBy { it.box.left }
        for ((staff, bi, groups) in t.bars) {
            val m = staffBars(staff).getOrNull(bi) ?: run { s.barsSkipped++; continue }
            val sp = m.space
            val notes = m.events.filterIsInstance<Note>()
            // The bar as expected: 4 notes a group, then (two groups) an eighth.
            val want = groups * 4 + if (groups == 2) 1 else 0
            if (notes.size != want || notes.take(groups * 4).any { it.duration.base != 16 }) { s.barsSkipped++; if (verbose) println("TRUTH bar $staff/$bi: ${notes.size} notes, wanted $want - not scored"); continue }
            s.bars++
            val slurs = staffBars(staff).flatMap { it.directions }.filter { it.kind == "slur" }.distinct()
            val centre = notes.map { it.x + sp * 0.6f }
            val used = HashSet<Direction>()
            for (g in 0 until groups) {
                for (k in 0..3) {
                    val n = notes[g * 4 + k]; val has = "staccato" in n.articulations
                    if (k == 0) { s.stacTrue++; if (has) s.stacHit++ else if (verbose) {
                        // What is kept near where the dot should be (over or under the note, a space or two off).
                        val near = m.kept.map { com.inksheets.core.omr.MarkReader.box(it) }.filter { b -> abs((b[0] + b[2]) / 2f - centre[g * 4]) < sp * 0.8f && (b[3] - b[1]) < sp * 1.2f && (b[2] - b[0]) < sp * 1.2f }
                        println("TRUTH $staff/$bi group $g: staccato missed; kept near: ${near.map { "${it[2] - it[0] + 1}x${it[3] - it[1] + 1}@${it[0]},${it[1]}" }} (sp ${sp.toInt()}, note at ${centre[g * 4].toInt()})")
                    } } else if (has) s.stacFalse++
                }
                if (Triple(staff, bi, g) in t.skipSlur) continue
                s.slurTrue++
                val from = centre[g * 4 + 1]; val to = centre[g * 4 + 3]
                // The slur that lies most over this group's.
                val best = slurs.filter { it !in used }.maxByOrNull { d -> minOf(d.x2, to) - maxOf(d.x, from) }
                if (best == null || minOf(best.x2, to) - maxOf(best.x, from) <= 0f) { s.slurMissed++; if (verbose) println("TRUTH $staff/$bi group $g: slur missed"); continue }
                used += best
                val covered = (g * 4 until g * 4 + 4).filter { centre[it] >= best.x - sp * 0.8f && centre[it] <= best.x2 + sp * 0.8f }.map { it - g * 4 }
                val also = centre.indices.filter { it !in g * 4 until g * 4 + 4 && centre[it] >= best.x - sp * 0.8f && centre[it] <= best.x2 + sp * 0.8f }
                when {
                    covered == listOf(1, 2, 3) && also.isEmpty() -> s.slurExact++
                    also.isNotEmpty() || 0 in covered -> s.slurOver++
                    else -> s.slurShort++
                }
                if (verbose && !(covered == listOf(1, 2, 3) && also.isEmpty())) println("TRUTH $staff/$bi group $g: slur covers notes $covered (+${also.size} others) x ${best.x.toInt()}..${best.x2.toInt()} vs ${from.toInt()}..${to.toInt()}")
            }
            s.slurExtra += slurs.count { it !in used && it.x2 > m.box.left && it.x < m.box.right && centre.count { c -> c >= it.x - sp * 0.8f && c <= it.x2 + sp * 0.8f } >= 2 }
        }
        for ((staff, want) in t.dyn) {
            val found = staffBars(staff).flatMap { it.directions }.filter { it.kind == "dynamic" }.distinct().sortedBy { it.x }.map { it.text }
            val l = lcs(want, found)
            s.dynTrue += want.size; s.dynHit += l; s.dynFalse += found.size - l
            if (verbose) println("TRUTH dynamics staff $staff: want $want got $found")
        }
        for ((staff, want) in t.hair) {
            val found = staffBars(staff).flatMap { it.directions }.filter { it.kind == "cresc" || it.kind == "dim" }.distinct().sortedBy { it.x }.map { it.kind }
            val l = lcs(want, found)
            s.hairTrue += want.size; s.hairHit += l; s.hairFalse += found.size - l
            if (verbose) println("TRUTH hairpins staff $staff: want $want got $found")
        }
        return s
    }

    @Test
    fun `Copprash's marks are read as printed`() {
        val s = score()
        assumeTrue("no Copprash here", s != null)
        println("TRUTH SCORE $s")
        // The level reached (2026-10-10; was staccato 16/39 with 9 false, slurs 13/38 exact, dynamics 16/22, hairpins 0/18; ScanMarks was taken back out for making scans worse elsewhere - test.292 level): the least that must stay so.
        assertTrue("bars scored ${s!!.bars}", s.bars >= MIN_BARS)
        assertTrue("staccato recall ${s.stacHit}/${s.stacTrue}", s.stacHit >= s.stacTrue * MIN_STACCATO_RECALL)
        assertTrue("staccato false ${s.stacFalse}", s.stacFalse <= s.stacHit * MAX_STACCATO_FALSE)
        assertTrue("slurs exact ${s.slurExact}/${s.slurTrue}", s.slurExact >= s.slurTrue * MIN_SLUR_EXACT)
        assertTrue("dynamics ${s.dynHit}/${s.dynTrue}", s.dynHit >= s.dynTrue * MIN_DYNAMICS && s.dynFalse <= s.dynTrue * MAX_DYNAMICS_FALSE)
        assertTrue("hairpins ${s.hairHit}/${s.hairTrue}", s.hairHit >= s.hairTrue * MIN_HAIRPINS)
    }

    private companion object {
        const val MIN_BARS = 16
        const val MIN_STACCATO_RECALL = 0.35
        const val MAX_STACCATO_FALSE = 0.7
        const val MIN_SLUR_EXACT = 0.3
        const val MIN_DYNAMICS = 0.7
        const val MAX_DYNAMICS_FALSE = 0.15
        const val MIN_HAIRPINS = 0.0
    }
}
