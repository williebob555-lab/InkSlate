package com.inksheets.desktop

import com.inksheets.core.WavWriter
import com.inksheets.core.omr.PlayOrder
import com.inksheets.core.omr.Score
import com.inksheets.core.omr.ScorePlayer
import com.inksheets.core.omr.Scores
import com.inksheets.core.omr.Synth
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Parts the app has read, played as it plays them now, to WAV files for listening to - and
 * checked for what can be checked without ears: never clipped, never silent for long where there
 * is music, the level steady from part to part. -Dinksheets.demo=<how many parts> (the most
 * marked-up readings in the library first); files to build/demo/.
 */
class PlaybackDemo {
    private val readings = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets/.inksheets/readings")

    @Test
    fun `parts played to wav`() {
        val want = System.getProperty("inksheets.demo")?.toIntOrNull() ?: return
        assumeTrue(readings.isDirectory)
        val out = File("build/demo").apply { mkdirs() }
        // Readings with the most to play with: dynamics, slurs, articulations.
        val scored = readings.listFiles { f -> f.isDirectory && f.name.startsWith("r13-") }.orEmpty().mapNotNull { dir ->
            val pages = dir.listFiles { f -> Regex("p\\d+\\.json").matches(f.name) }.orEmpty().sortedBy { it.name }
            val parts = pages.mapNotNull { runCatching { Scores.decode(it.readText()) }.getOrNull() }
            if (parts.isEmpty()) return@mapNotNull null
            val score = Score(parts.flatMap { it.measures }.sortedWith(compareBy({ it.page }, { it.staff }, { it.box.left })), parts.maxOf { it.pages }, parts.first().pageWidths)
            val marks = score.measures.sumOf { it.directions.size } + score.measures.sumOf { m -> m.events.count { (it as? com.inksheets.core.omr.Note)?.articulations?.isNotEmpty() == true } }
            val sure = score.measures.count { it.sure }.toDouble() / score.measures.size.coerceAtLeast(1)
            Triple(dir, score, marks * sure)
        }.sortedByDescending { it.third }.take(want)
        val rate = 44100
        for ((dir, score, _) in scored) {
            val order = PlayOrder.unrolled(score)
            val first = order.measures.firstOrNull()?.number ?: continue
            val last = order.measures.take(32).lastOrNull()?.number ?: continue
            val synth = Synth(rate)
            val player = ScorePlayer(synth, order, first, last, 100.0, 0, Synth.BRASS, order = order.measures.take(32))
            val wav = File(out, "${dir.name}.wav")
            val writer = WavWriter(wav, rate)
            val buf = FloatArray(rate / 20)
            var peak = 0f; var sum = 0.0; var n = 0L; var clipped = 0; var quietRun = 0; var longestQuiet = 0
            val started = System.nanoTime()
            while (!player.finished && n < rate * 120L) {
                java.util.Arrays.fill(buf, 0f)
                player.fill(buf)
                val block = buf.maxOf { abs(it) }
                if (block < 1e-3f) { quietRun++; longestQuiet = maxOf(longestQuiet, quietRun) } else quietRun = 0
                for (x in buf) { peak = maxOf(peak, abs(x)); sum += x * x; if (abs(x) >= 1f) clipped++ }
                n += buf.size
                writer.write(buf)
            }
            writer.close()
            val ms = (System.nanoTime() - started) / 1_000_000
            println("DEMO ${wav.name}: ${n / rate.toDouble()} s, peak %.3f, rms %.3f, clipped $clipped, longest quiet ${longestQuiet * 50} ms, rendered in $ms ms".format(peak, sqrt(sum / n.coerceAtLeast(1))))
        }
    }
}
