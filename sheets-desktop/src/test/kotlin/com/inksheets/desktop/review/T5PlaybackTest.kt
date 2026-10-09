package com.inksheets.desktop.review

import com.inksheets.core.WavWriter
import com.inksheets.core.omr.EnsemblePlayer
import com.inksheets.core.omr.Interpretation
import com.inksheets.core.omr.Note
import com.inksheets.core.omr.Performance
import com.inksheets.core.omr.PlayOrder
import com.inksheets.core.omr.Score
import com.inksheets.core.omr.ScorePlayer
import com.inksheets.core.omr.Synth
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * T5 round 3: the new playback engine on the real library's readings, measured. Prints "T5 PB"
 * lines and writes plans to build/t5/. -Dinksheets.t5pb=<how many readings> (default 60).
 */
class T5PlaybackTest {
    private val out = File("build/t5").apply { mkdirs() }
    private fun pct(v: List<Float>, p: Double) = if (v.isEmpty()) 0f else v.sorted()[((v.size - 1) * p).toInt()]

    private fun dirs(n: Int): List<File> {
        val all = T5Data.readings.listFiles { f -> f.isDirectory && f.name.startsWith("r13-") }.orEmpty().sortedBy { it.name }
        return all.filterIndexed { i, _ -> i % maxOf(1, all.size / n) == 0 }.take(n)
    }

    @Test
    fun `plans of real readings`() {
        assumeTrue(T5Data.readings.isDirectory)
        val n = System.getProperty("inksheets.t5pb")?.toIntOrNull() ?: 60
        var lines = 0
        var worstLurch = 0.0; var worstRatio = 0.0
        val report = StringBuilder()
        var multiBarPins = 0; var pinsTotal = 0; var ritNoTempo = 0; var fermRests = 0; var rests = 0
        val shortPhr = ArrayList<Double>(); val longPhr = ArrayList<Double>()
        val spreads = ArrayList<Float>()
        val stacc = ArrayList<Double>(); val accentLen = ArrayList<Double>()
        for (dir in dirs(n)) {
            val score = T5Data.load(dir) ?: continue
            val order = runCatching { PlayOrder.unrolled(score).measures }.getOrNull() ?: continue
            if (order.size < 4) continue
            val lengths = DoubleArray(order.size) { if (order[it].bars > 1) order[it].time.quarters * order[it].bars else order[it].playedQuarters }
            val plan = runCatching { Interpretation.analyse(order, lengths) }.getOrElse { println("T5 PB CRASH ${dir.name}: $it"); continue }
            val map = Interpretation.tempoMap(listOf(plan), 100.0, false)
            // tempo per bar relative to nominal
            val nominal = 0.6
            val ratios = (0 until order.size).map { i ->
                val a = plan.barQ[i]; val b = plan.barQ[i + 1]
                if (b - a < 1e-6) 1.0 else (map.seconds(b) - map.seconds(a)) / ((b - a) * nominal)
            }
            val lurch = ratios.zipWithNext { a, b -> abs(b - a) }.maxOrNull() ?: 0.0
            worstLurch = maxOf(worstLurch, lurch); worstRatio = maxOf(worstRatio, ratios.max())
            val tones = Interpretation.tones(plan, map, 44100, 0, Synth.BRASS)
            val vel = tones.map { it.velocity }
            spreads += pct(vel, 0.95) - pct(vel, 0.05)
            // hairpins cut at the bar's end and begun again
            for ((i, m) in order.withIndex()) {
                val pins = m.directions.filter { it.kind == "cresc" || it.kind == "dim" }
                pinsTotal += pins.size
                for (p in pins) {
                    val nextPins = order.getOrNull(i + 1)?.directions?.filter { it.kind == p.kind && it.x < order[i + 1].box.left + order[i + 1].space * 6 } ?: emptyList()
                    if (nextPins.isNotEmpty() && p.x2 > m.box.right - m.space * 3) multiBarPins++
                }
                for (e in m.events) if (e is com.inksheets.core.omr.Rest) { rests++ }
                for (d in m.directions) if (d.kind == "text") {
                    val t = d.text.lowercase().trim('.', ' ')
                    if ((t.contains("rit") || t.contains("rall")) && !t.startsWith("rit") && !t.startsWith("rall")) ritNoTempo++
                }
            }
            for (ph in plan.phrases) { val bars = (ph.endQ - ph.startQ) / (plan.totalQ / order.size); if (bars < 1.0) shortPhr += bars; if (bars > 8.6) longPhr += bars }
            // articulation lengths (ms) of tones with staccato/accent
            for ((i, p) in plan.notes.withIndex()) {
                val nominalMs = (map.seconds(p.q + p.len) - map.seconds(p.q)) * 1000
                if ("staccato" in p.articulations && nominalMs > 0) stacc += nominalMs
            }
            report.append("${dir.name} bars=${order.size} phrases=${plan.phrases.size} notes=${plan.notes.size} velP5=%.2f P50=%.2f P95=%.2f maxRatio=%.2f lurch=%.2f lastBar=%.2f words=${plan.words.size}\n".format(pct(vel, 0.05), pct(vel, 0.5), pct(vel, 0.95), ratios.max(), lurch, ratios.last()))
            lines++
        }
        File(out, "plans-summary.txt").writeText(report.toString())
        println("T5 PB readings=$lines worstTempoRatio=%.2f worstAdjacentBarLurch=%.2f meanVelSpread(P95-P5)=%.2f minSpread=%.2f".format(worstRatio, worstLurch, spreads.average(), spreads.minOrNull() ?: 0f))
        println("T5 PB hairpins=$pinsTotal cut-at-barline-and-continued=$multiBarPins shortPhrases(<1 bar)=${shortPhr.size} longPhrases(>8.6 bars)=${longPhr.size} stacc nominal ms median=${stacc.sorted().getOrNull(stacc.size / 2)}")
        println(report.toString().lines().take(25).joinToString("\n") { "T5 PB " + it })
    }

    /** One real part, bar by bar, as a musician would read the plan. */
    @Test
    fun `plan printed for a part`() {
        assumeTrue(T5Data.readings.isDirectory)
        val want = (System.getProperty("inksheets.t5plan") ?: "").ifEmpty { null }
        val pdfs = T5Data.pdfs(File(T5Data.library, "Imported/PEP BAND/Music")).filter { it.name.contains("Trumpet 1") || it.name.contains("Trombone 1") || it.name.contains("Alto Sax 1") }
        var done = 0
        for (pdf in pdfs) {
            if (done >= 6) break
            if (want != null && !pdf.path.contains(want)) continue
            val dir = T5Data.readingOf(pdf) ?: continue
            val score = T5Data.load(dir) ?: continue
            val order = PlayOrder.unrolled(score).measures
            if (order.size < 16) continue
            val lengths = DoubleArray(order.size) { if (order[it].bars > 1) order[it].time.quarters * order[it].bars else order[it].playedQuarters }
            val plan = Interpretation.analyse(order, lengths)
            val map = Interpretation.tempoMap(listOf(plan), 100.0, false)
            val tones = Interpretation.tones(plan, map, 44100, 0, Synth.BRASS)
            val sb = StringBuilder("${pdf.name}  (${order.size} bars, reading ${dir.name})\n")
            sb.append("PHRASES: " + plan.phrases.joinToString(" | ") { "q%.1f-%.1f(peak %.1f%s%s)".format(it.startQ, it.endQ, it.peakQ, if (it.section) " SECTION" else "", if (it.ease > 0) " ease${it.ease}" else "") } + "\n")
            sb.append("WORDS: ${plan.words}\n")
            for ((bi, m) in order.withIndex()) {
                val a = plan.barQ[bi]; val b = plan.barQ[bi + 1]
                val ratio = if (b - a < 1e-6) 1.0 else (map.seconds(b) - map.seconds(a)) / ((b - a) * 0.6)
                val notes = plan.notes.filter { it.bar == bi }
                sb.append("bar %3d tempo x%.2f dir=%s : ".format(m.number, ratio, m.directions.filter { it.kind != "slur" }.joinToString(",") { it.kind + (if (it.text.isNotEmpty()) ":" + it.text else "") }))
                sb.append(notes.joinToString(" ") { "%s%.2f%s%s%s".format(if (it.phraseStart) "[" else "", it.level, if (it.endLevel - it.level > 0.03) "/" else if (it.level - it.endLevel > 0.03) "\\" else "", if (it.tie) "~" else if (it.slurred) "_" else "", if (it.phraseEnd) "]" else "") })
                sb.append("\n")
            }
            val safe = pdf.nameWithoutExtension.replace(' ', '_')
            File(out, "plan-$safe.txt").writeText(sb.toString())
            val vel = tones.map { it.velocity }
            println("T5 PB PLAN ${pdf.name}: ${plan.phrases.size} phrases over ${order.size} bars, vel P5 %.2f P50 %.2f P95 %.2f, tones ${tones.size}".format(pct(vel, .05), pct(vel, .5), pct(vel, .95)))
            done++
        }
    }

    @Test
    fun `wav of a few readings and a band`() {
        assumeTrue(T5Data.readings.isDirectory)
        val rate = 44100
        val songs = File(T5Data.library, "Imported/PEP BAND/Music").listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }
        var rendered = 0; var bands = 0
        for (song in songs) {
            if (bands >= 3) break
            val parts = T5Data.pdfs(song).mapNotNull { pdf -> T5Data.readingOf(pdf)?.let { d -> T5Data.load(d)?.let { pdf to it } } }
                .filter { (pdf, _) -> !pdf.name.contains("score", true) && (pdf.name.contains("Trumpet") || pdf.name.contains("Sax") || pdf.name.contains("Trombone") || pdf.name.contains("Clarinet") || pdf.name.contains("Bass") || pdf.name.contains("Flute") || pdf.name.contains("Horn") || pdf.name.contains("Euph")) }
            if (parts.size < 4) continue
            fun transpose(name: String) = when {
                name.contains("Alto Sax") -> 9; name.contains("Tenor Sax") -> 14; name.contains("Bari") -> 21
                name.contains("Trumpet") || name.contains("Clarinet") -> 2; name.contains("Horn") -> 7 else -> 0
            }
            fun patch(name: String) = if (name.contains("Sax") || name.contains("Clarinet") || name.contains("Flute")) Synth.BRASS else Synth.BRASS
            val usable = parts.filter { (_, s) -> PlayOrder.unrolled(s).measures.size >= 12 }
            if (usable.size < 4) continue
            val mine = usable.first { it.first.name.contains("Trombone") || it.first.name.contains("Trumpet") || true }
            val order = PlayOrder.unrolled(mine.second).measures.take(40)
            val first = order.first().number; val last = order.last().number
            val voices = usable.filter { it !== mine }.map { (pdf, s) -> EnsemblePlayer.Voice(s, transpose(pdf.name), patch(pdf.name)) }
            val synth = Synth(rate)
            val t0 = System.nanoTime()
            val player = EnsemblePlayer(synth, mine.second, voices, first, last, 110.0, order = order)
            val planMs = (System.nanoTime() - t0) / 1_000_000
            val wav = File(out, "band-${song.name.replace(' ', '_')}.wav")
            val w = WavWriter(wav, rate)
            val buf = FloatArray(rate / 20)
            var peak = 0f; var sum = 0.0; var n = 0L; var clipped = 0
            var windows = ArrayList<Double>(); var winSum = 0.0; var winN = 0
            while (!player.finished && n < rate * 150L) {
                java.util.Arrays.fill(buf, 0f); player.fill(buf)
                for (x in buf) { peak = maxOf(peak, abs(x)); sum += x * x; winSum += x * x; winN++; if (abs(x) >= 0.999f) clipped++ }
                if (winN >= rate) { windows += sqrt(winSum / winN); winSum = 0.0; winN = 0 }
                n += buf.size; w.write(buf)
            }
            w.close()
            val rms = sqrt(sum / n.coerceAtLeast(1))
            val loud = windows.filter { it > 0.005 }
            println("T5 PB BAND ${song.name}: ${voices.size + 1} parts, ${n / rate} s, planned in $planMs ms, peak %.3f rms %.3f clipped $clipped, 1-s rms range %.3f..%.3f (pumping ratio %.1f)".format(peak, rms, loud.minOrNull() ?: 0.0, loud.maxOrNull() ?: 0.0, (loud.maxOrNull() ?: 0.0) / (loud.minOrNull() ?: 1.0).coerceAtLeast(1e-6)))
            bands++
        }
        // Single parts: the first four readings with the most notes.
        val pdfs = T5Data.pdfs(File(T5Data.library, "Imported/PEP BAND/Music")).take(300)
        for (pdf in pdfs) {
            if (rendered >= 4) break
            val score = T5Data.readingOf(pdf)?.let { T5Data.load(it) } ?: continue
            val order = PlayOrder.unrolled(score).measures
            if (order.size < 20 || order.size > 80) continue
            val synth = Synth(rate)
            val player = ScorePlayer(synth, order.let { PlayOrder.unrolled(score) }, order.first().number, order.last().number, 100.0, 0, Synth.BRASS, order = order)
            val wav = File(out, "solo-${pdf.nameWithoutExtension.replace(' ', '_')}.wav")
            val w = WavWriter(wav, rate)
            val buf = FloatArray(rate / 20)
            var peak = 0f; var n = 0L; var clipped = 0
            while (!player.finished && n < rate * 150L) { java.util.Arrays.fill(buf, 0f); player.fill(buf); for (x in buf) { peak = maxOf(peak, abs(x)); if (abs(x) >= 0.999f) clipped++ }; n += buf.size; w.write(buf) }
            w.close()
            println("T5 PB SOLO ${pdf.name}: ${n / rate} s peak %.3f clipped $clipped".format(peak))
            rendered++
        }
    }
}
