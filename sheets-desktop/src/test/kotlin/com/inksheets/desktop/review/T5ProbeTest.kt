package com.inksheets.desktop.review

import com.inksheets.core.omr.Interpretation
import com.inksheets.core.omr.Note
import com.inksheets.core.omr.PlayOrder
import com.inksheets.core.omr.Rest
import com.inksheets.core.omr.Synth
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** T5 round 3/4: specific musical suspicions about the playback engine, tested on real readings. */
class T5ProbeTest {
    private fun all() = T5Data.readings.listFiles { f -> f.isDirectory && f.name.startsWith("r13-") }.orEmpty().sortedBy { it.name }

    @Test
    fun `words, fermatas, ties, phrases, average speed`() {
        assumeTrue(T5Data.readings.isDirectory)
        val words = HashMap<String, Int>()
        var fermNote = 0; var fermRest = 0; var tieNotes = 0; var tieNoMatch = 0; var tieAtBarOfRepeat = 0
        var phrasesTotal = 0; var phrasesOneBeat = 0; var barsTotal = 0
        val slow = ArrayList<Double>()
        var ritNoTempoAfter = 0; var ritPieces = 0
        var graceCount = 0
        var piecesAnalysed = 0
        for (dir in all()) {
            val score = T5Data.load(dir) ?: continue
            val order = runCatching { PlayOrder.unrolled(score).measures }.getOrNull() ?: continue
            if (order.size < 4) continue
            piecesAnalysed++
            val lengths = DoubleArray(order.size) { order[it].playedQuarters }
            val plan = Interpretation.analyse(order, lengths)
            val map = Interpretation.tempoMap(listOf(plan), 100.0, false)
            slow += map.seconds(plan.totalQ) / (plan.totalQ * 0.6)
            phrasesTotal += plan.phrases.size; barsTotal += order.size
            phrasesOneBeat += plan.phrases.count { it.endQ - it.startQ <= 1.01 }
            for (m in order) {
                for (d in m.directions) if (d.kind == "text") words.merge(d.text.lowercase().trim(), 1, Int::plus)
                for (e in m.events) {
                    if (e is Note && "fermata" in e.articulations) fermNote++
                    if (e is Note && e.tie) tieNotes++
                }
                graceCount += m.graces.size
            }
            // ties: does the next note carry the pitch?
            for ((i, p) in plan.notes.withIndex()) if (p.tie) {
                val nxt = plan.notes.getOrNull(i + 1)
                if (nxt == null || p.keys.none { it in nxt.keys } || kotlin.math.abs(nxt.q - (p.q + p.len)) > 1e-6) tieNoMatch++
            }
            val wordsHere = plan.words
            if (wordsHere.any { it.second == 1 }) { ritPieces++; if (wordsHere.none { it.second == 0 }) ritNoTempoAfter++ }
        }
        val ritLike = words.filter { (w, _) -> w.contains("rit") || w.contains("rall") || w.contains("accel") || w.contains("tempo") || w.contains("slow") || w.contains("fast") || w.contains("molto") || w.contains("poco") }
        println("T5 PROBE pieces=$piecesAnalysed phrases=$phrasesTotal over $barsTotal bars (%.2f per bar), one-beat phrases=$phrasesOneBeat".format(phrasesTotal.toDouble() / barsTotal))
        println("T5 PROBE mean real duration / nominal duration at a set bpm: mean %.3f min %.3f max %.3f".format(slow.average(), slow.min(), slow.max()))
        println("T5 PROBE fermata notes=$fermNote tied notes=$tieNotes tied-but-next-not-same-pitch-or-not-touching=$tieNoMatch graces=$graceCount")
        println("T5 PROBE pieces with a rit-type word=$ritPieces and no a-tempo anywhere after=$ritNoTempoAfter")
        println("T5 PROBE tempo-ish texts: " + ritLike.entries.sortedByDescending { it.value }.take(40).joinToString { "'${it.key}'x${it.value}" })
        println("T5 PROBE all texts top: " + words.entries.sortedByDescending { it.value }.take(40).joinToString { "'${it.key}'x${it.value}" })
    }

    /** Hairpins that continue across a barline: do their levels add up twice? */
    @Test
    fun `a hairpin across bars`() {
        assumeTrue(T5Data.readings.isDirectory)
        for (dir in all()) {
            val score = T5Data.load(dir) ?: continue
            val order = runCatching { PlayOrder.unrolled(score).measures }.getOrNull() ?: continue
            val bi = order.indices.firstOrNull { i ->
                val m = order[i]; val nx = order.getOrNull(i + 1) ?: return@firstOrNull false
                val a = m.directions.firstOrNull { it.kind == "cresc" || it.kind == "dim" } ?: return@firstOrNull false
                val b = nx.directions.firstOrNull { it.kind == a.kind } ?: return@firstOrNull false
                a.x2 > m.box.right - m.space * 3 && b.x < nx.box.left + m.space * 6 && m.events.count { it is Note } >= 2 && nx.events.count { it is Note } >= 2
            } ?: continue
            val sub = order.subList(maxOf(0, bi - 1), minOf(order.size, bi + 4))
            val lengths = DoubleArray(sub.size) { sub[it].playedQuarters }
            val plan = Interpretation.analyse(sub, lengths)
            val kinds = sub.map { m -> m.directions.filter { it.kind != "slur" }.joinToString(",") { it.kind + ":" + it.text + "@" + it.x.toInt() + "-" + it.x2.toInt() } }
            println("T5 PROBE HAIRPIN ${dir.name} bars ${sub.map { it.number }} dirs=$kinds")
            println("T5 PROBE HAIRPIN levels " + plan.notes.joinToString(" ") { "b${sub[it.bar].number}:%.2f".format(it.level) })
            return
        }
    }

    /** Fermata over a rest: is it held? Notes of a pickup bar; huge parts; empty. */
    @Test
    fun `engine edge cases do not crash and stay sane`() {
        assumeTrue(T5Data.readings.isDirectory)
        // 200-bar part by repeating a real one, an empty list, a list of rests only.
        val dir = all().firstOrNull { d -> (T5Data.load(d)?.measures?.size ?: 0) in 40..80 } ?: return
        val score = T5Data.load(dir)!!
        val order = PlayOrder.unrolled(score).measures
        val big = (0 until 5).flatMap { order }.take(260)
        val t0 = System.nanoTime()
        val plan = Interpretation.analyse(big, DoubleArray(big.size) { big[it].playedQuarters })
        val map = Interpretation.tempoMap(listOf(plan), 90.0, false)
        val tones = Interpretation.tones(plan, map, 44100, 0, Synth.BRASS)
        println("T5 PROBE BIG ${big.size} bars: ${tones.size} tones, planned in ${(System.nanoTime() - t0) / 1_000_000} ms, length ${"%.0f".format(map.seconds(plan.totalQ))} s")
        val e = Interpretation.analyse(emptyList(), DoubleArray(0))
        val m0 = Interpretation.tempoMap(listOf(e), 90.0, false)
        println("T5 PROBE EMPTY phrases=${e.phrases.size} notes=${e.notes.size} total=${m0.totalQ}")
        val restsOnly = order.filter { o -> o.events.all { it is Rest } }.take(8)
        if (restsOnly.isNotEmpty()) {
            val p = Interpretation.analyse(restsOnly, DoubleArray(restsOnly.size) { restsOnly[it].playedQuarters })
            println("T5 PROBE RESTS ONLY notes=${p.notes.size} phrases=${p.phrases.size}")
            Interpretation.tempoMap(listOf(p), 90.0, false)
        }
        // bpm extremes
        for (bpm in listOf(20.0, 400.0)) { val mm = Interpretation.tempoMap(listOf(plan), bpm, false); println("T5 PROBE bpm $bpm -> ${"%.1f".format(mm.seconds(plan.totalQ))} s") }
    }
}
