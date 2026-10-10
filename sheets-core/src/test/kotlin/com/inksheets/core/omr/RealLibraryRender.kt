package com.inksheets.core.omr

import com.inksheets.core.InstrumentReader
import com.inksheets.core.Instruments
import com.inksheets.core.WavWriter
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A real euphonium/baritone part from the library, read-only, played solo and with the band, to WAV (never to the
 * speakers), and scanned for what can go wrong without listening: clipping, pops, notes stuck on, silences where a note
 * should sound, the level line's steps, the balance. Runs only with INKSHEETS_REAL=<folder to write to>.
 */
class RealLibraryRender {
    private val library = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")
    private val rate = 44_100

    /** The reading's folder name for [pdf]: as Transcriber.idOf makes it (the first revision's size and checksum). */
    private fun idOf(pdf: File): String {
        val bytes = pdf.readBytes()
        val mark = "%%EOF".toByteArray()
        var end = bytes.size
        var i = 0
        outer@ while (i <= bytes.size - mark.size) {
            for (j in mark.indices) if (bytes[i + j] != mark[j]) { i++; continue@outer }
            end = i + mark.size
            while (end < bytes.size && (bytes[end] == '\r'.code.toByte() || bytes[end] == '\n'.code.toByte())) end++
            break
        }
        val crc = java.util.zip.CRC32(); crc.update(bytes, 0, end)
        return "${java.lang.Long.toHexString(end.toLong())}-${java.lang.Long.toHexString(crc.value)}"
    }

    private fun readingOf(pdf: File): Score? {
        val base = File(library, ".inksheets/readings")
        val id = idOf(pdf)
        val dir = (13 downTo 1).map { File(base, "r$it-$id") }.firstOrNull { d -> d.listFiles { f -> Regex("p\\d+\\.json").matches(f.name) }?.isNotEmpty() == true } ?: return null
        val pages = dir.listFiles { f -> Regex("p\\d+\\.json").matches(f.name) }.orEmpty().sortedBy { it.name.drop(1).removeSuffix(".json").toInt() }
        val parts = pages.mapNotNull { Scores.decode(it.readText()) }
        if (parts.isEmpty()) return null
        return Score(parts.flatMap { it.measures }.sortedWith(compareBy({ it.page }, { it.staff }, { it.box.left })), parts.maxOf { it.pages }, parts.first().pageWidths)
    }

    private class Part(val file: File, val id: String, val transpose: Int, val score: Score, val drums: DrumKind?)

    private fun db(r: Double) = 20 * log10(r.coerceAtLeast(1e-9))

    private fun render(play: (FloatArray) -> Unit, finished: () -> Boolean, wav: File?): FloatArray {
        val out = ArrayList<FloatArray>()
        val buf = FloatArray(rate / 20)
        var n = 0L
        val w = wav?.let { wav.parentFile.mkdirs(); WavWriter(it, rate) }
        while (!finished() && n < rate * 130L) {
            java.util.Arrays.fill(buf, 0f); play(buf); out += buf.copyOf(); w?.write(buf); n += buf.size
        }
        w?.close()
        val all = FloatArray(out.size * buf.size)
        out.forEachIndexed { i, b -> System.arraycopy(b, 0, all, i * buf.size, b.size) }
        return all
    }

    @Test
    fun `a real part solo and with the band`() {
        val outDir = System.getenv("INKSHEETS_REAL")?.takeIf { it.isNotBlank() } ?: return
        val music = File(library, "Imported/PEP BAND/Music")
        if (!music.isDirectory) return
        // Every piece: its euphonium/baritone part and the other parts, where read.
        class Piece(val name: String, val euph: Part, val others: List<Part>, val seconds: Double)
        val pieces = ArrayList<Piece>()
        for (dir in music.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }) {
            val parts = dir.listFiles { f -> f.extension.equals("pdf", true) }.orEmpty().mapNotNull { f ->
                val inst = InstrumentReader.readFileName(f.name)?.instrument ?: return@mapNotNull null
                val score = readingOf(f) ?: return@mapNotNull null
                Part(f, inst.id, inst.transpose, score, DrumKind.of(inst.id, f.nameWithoutExtension))
            }
            val euphs = parts.filter { it.id.startsWith("euphonium") || it.id.startsWith("baritone") }
            // The part with the fullest reading, concert pitch bass clef first.
            val euph = euphs.maxByOrNull { p -> p.score.measures.count { it.sure } + (if (p.id.endsWith("bc") || p.id == "euphonium") 5 else 0) } ?: continue
            val bars = PlayOrder.unrolled(euph.score).measures
            val q = bars.sumOf { it.playedQuarters }
            val others = parts.filter { it !== euph && !(it.id.startsWith("euphonium") || it.id.startsWith("baritone") || it.id == "bari-sax") }
                .distinctBy { it.id }.filter { it.score.measures.isNotEmpty() }
            val marks = euph.score.measures.sumOf { m -> m.directions.size + m.events.count { (it as? Note)?.articulations?.isNotEmpty() == true } }
            println("REAL ${dir.name}: ${euph.file.name} ${euph.score.measures.size} bars (${euph.score.measures.count { it.sure }} sure), $marks marks, ${"%.0f".format(q * 60 / 100)} s at 100 bpm; ${others.size} other parts read")
            if (q * 60 / 100 >= 55 && euph.score.measures.size >= 24) pieces += Piece(dir.name, euph, others, q * 60 / 100)
        }
        // The piece with the most to hear: the fullest band, then the most sure bars and marks.
        val pick = pieces.maxByOrNull { p -> p.others.size * 20 + p.euph.score.measures.count { it.sure } + p.euph.score.measures.sumOf { it.directions.size } + p.euph.score.measures.sumOf { m -> m.events.count { (it as? Note)?.articulations?.isNotEmpty() == true } } } ?: run { println("REAL none found"); return }
        println("REAL picked ${pick.name}: ${pick.euph.file.name} with ${pick.others.joinToString { it.id }}")
        val euph = pick.euph
        val bpm = 100.0
        val all = PlayOrder.unrolled(euph.score).measures
        // About 80 seconds of it: whole bars from the start.
        var t = 0.0; val bars = ArrayList<Measure>()
        for (m in all) { val s = m.playedQuarters * 60 / bpm; if (t + s > 85.0) break; bars += m; t += s }
        val first = bars.first().number; val last = bars.last().number
        val patch = Synth.patchFor(Midi.program(euph.id))
        println("REAL ${bars.size} bars, ${"%.0f".format(t)} s, euphonium patch ${if (patch === Synth.LOW_BRASS) "LOW_BRASS" else "other"}, transposes ${euph.transpose}")

        // Solo.
        val synth = Synth(rate)
        val solo = ScorePlayer(synth, euph.score, first, last, bpm, euph.transpose, patch, order = bars)
        val soloAudio = render({ solo.fill(it) }, { solo.finished }, File(outDir, "I-real-solo.wav"))
        scan("solo", soloAudio, Performance.play(bars, bpm, rate, euph.transpose, patch).tones)

        // With the band: everyone else read, the euphonium as the guide in the band at full.
        val voices = pick.others.map { EnsemblePlayer.Voice(it.score, it.transpose, it.drums?.patch?.let { _ -> Synth.patchFor(Midi.program(it.id)) } ?: Synth.patchFor(Midi.program(it.id)), it.drums) }
        val me = EnsemblePlayer.Voice(euph.score, euph.transpose, patch)
        val band = EnsemblePlayer(Synth(rate), euph.score, voices, first, last, bpm, guide = me to 1f, order = bars)
        val bandAudio = render({ band.fill(it) }, { band.finished }, File(outDir, "I-real-band.wav"))
        scan("band", bandAudio, emptyList())
        // Without the drums: do the steps come from them (a stick is a step)?
        val pitched = voices.filter { it.drums == null }
        val noDrums = EnsemblePlayer(Synth(rate), euph.score, pitched, first, last, bpm, guide = me to 1f, order = bars)
        scan("band without the drums", render({ noDrums.fill(it) }, { noDrums.finished }, null), emptyList())
        // The balance, part by part and unlimited (a mix is turned down as a whole, which hides who is how loud): each part
        // alone on the band's own scale; the euphonium against all the others' power.
        fun alone(v: EnsemblePlayer.Voice, asGuide: Boolean): Double {
            val ep = if (asGuide) EnsemblePlayer(Synth(rate), euph.score, emptyList(), first, last, bpm, guide = v to 1f, order = bars)
                     else EnsemblePlayer(Synth(rate), euph.score, listOf(v), first, last, bpm, order = bars)
            val x = render({ ep.fill(it) }, { ep.finished }, null)
            return x.sumOf { it.toDouble() * it } / x.size
        }
        val pe = alone(me, true)
        val parts = voices.map { v -> v to alone(v, false) / (1.2 * 1.2) * (0.88 * 0.88) }
        val po = parts.sumOf { it.second }
        println("REAL balance: euphonium ${"%.1f".format(10 * log10(pe / po))} dB against the rest of the band together; against each part: ${parts.withIndex().joinToString { (i, pp) -> "${pick.others[i].id} ${"%.1f".format(10 * log10(pe / pp.second))}" }}")
    }

    private fun scan(name: String, x: FloatArray, tones: List<Synth.Tone>) {
        val peak = x.maxOf { abs(it) }
        val clipped = x.count { abs(it) > 0.98f }
        // Isolated steps: a sample-to-sample jump far above what the sound round it makes.
        var pops = 0; var worst = 0.0; var worstAt = 0
        val w = rate / 100
        var k = w
        while (k < x.size - w) {
            var local = 0.0
            for (i in k - w until k + w) local += abs((x[i + 1] - x[i]).toDouble())
            local /= (2 * w)
            val step = abs((x[k] - x[k - 1]).toDouble())
            if (local > 1e-5 && step > 12 * local && step > 0.05) { pops++; if (step / local > worst) { worst = step / local; worstAt = k } }
            k += 1
        }
        // The tail dies away (nothing stuck on).
        val tail = db(sqrt(x.takeLast(rate / 2).sumOf { it.toDouble() * it } / (rate / 2)))
        println("REAL scan $name: ${x.size / rate} s, peak ${"%.3f".format(peak)}, samples over 0.98: $clipped, isolated steps: $pops (worst ${"%.0f".format(worst)}x its surroundings at ${"%.2f".format(worstAt / rate.toDouble())} s), last half second ${"%.1f".format(tail)} dBFS")
        if (tones.isNotEmpty()) {
            val stuck = tones.filter { it.length > rate * 8L && !it.patch.drum }
            // Silences where a note should sound: 100 ms windows with a note of at least that long over them, and nothing heard.
            var silent = 0; var firstSilent = -1.0
            for (tn in tones) {
                if (tn.length < rate * 0.4 || tn.patch.drum) continue
                val a = tn.start.toInt() + rate / 5; val b = min(x.size, (tn.start + tn.length).toInt() - rate / 10)
                var p = a
                while (p + rate / 10 <= b) {
                    val r = sqrt((p until p + rate / 10).sumOf { x[it].toDouble() * x[it] } / (rate / 10))
                    if (db(r) < -70) { silent++; if (firstSilent < 0) firstSilent = p / rate.toDouble() }
                    p += rate / 10
                }
            }
            // The level line's steps between notes joined to each other (a step is only for a detached note).
            var steps = 0; var maxStep = 0.0
            for (i in 0 until tones.size - 1) {
                val a = tones[i]; val b = tones[i + 1]
                if (b.legato && abs(a.start + a.length - b.start) <= rate / 50) {
                    val d = abs(db(Synth.amplitude(b.velocity).toDouble()) - db(Synth.amplitude(a.endVelocity).toDouble()))
                    if (d > 3.0) steps++
                    maxStep = max(maxStep, d)
                }
            }
            var steepest = 0.0
            for (tn in tones) { val secs = tn.length / rate.toDouble(); if (secs >= 0.05) steepest = max(steepest, abs(db(Synth.amplitude(tn.endVelocity).toDouble()) - db(Synth.amplitude(tn.velocity).toDouble())) / secs / 10) }
            println("REAL scan $name: ${tones.size} notes, stuck-on (over 8 s): ${stuck.size}, silent 100 ms stretches inside notes: $silent${if (firstSilent >= 0) " (first at ${"%.1f".format(firstSilent)} s)" else ""}, joined-note level steps over 3 dB: $steps (largest ${"%.1f".format(maxStep)} dB), steepest level change inside a note ${"%.2f".format(steepest)} dB per 100 ms")
        }
    }
}
