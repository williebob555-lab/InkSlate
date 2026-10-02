package com.inksheets.desktop

import com.inksheets.core.omr.EnsemblePlayer
import com.inksheets.core.omr.PlayOrder
import com.inksheets.core.omr.Score
import com.inksheets.core.omr.ScorePlayer
import com.inksheets.core.omr.Scores
import com.inksheets.core.omr.Synth
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * How long the instrument takes to make each block of sound - the audio thread's every 256 samples
 * (5.3 ms at 48 kHz) - for a long part from its start, and for a band of a dozen parts: the slowest
 * blocks are where the sound would break up. On the Tyrrell book's real reading.
 */
class SynthLoadTest {
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")

    private fun reading(): Score? {
        val dir = File(music, ".inksheets/readings").listFiles().orEmpty().firstOrNull { it.name.startsWith("r12-43537d") } ?: return null
        val pages = dir.listFiles { f -> f.name.matches(Regex("p\\d+\\.json")) }.orEmpty().mapNotNull { Scores.decode(it.readText()) }
        if (pages.isEmpty()) return null
        return Score(Scores.numberedOnce(pages.flatMap { it.measures }.sortedWith(compareBy({ it.page }, { it.staff }, { it.box.left }))), pages.first().pages, List(pages.first().pages) { 0 })
    }

    private fun time(name: String, seconds: Int, fill: (FloatArray) -> Unit): Double {
        val buf = FloatArray(256)
        val times = ArrayList<Long>()
        repeat(48_000 * seconds / 256) { java.util.Arrays.fill(buf, 0f); val t = System.nanoTime(); fill(buf); times += System.nanoTime() - t }
        times.sort()
        val worst = times.last() / 1e6; val p99 = times[times.size * 99 / 100] / 1e6; val mean = times.average() / 1e6
        println("LOAD $name: block mean ${"%.3f".format(mean)} ms, 99% ${"%.3f".format(p99)} ms, worst ${"%.2f".format(worst)} ms (a block is 5.33 ms; the line holds 40)")
        return p99
    }

    @Test
    fun `sound made in time`() {
        val score = reading()
        assumeTrue("no Tyrrell reading here", score != null)
        score!!
        val first = score.measures.first().number; val last = score.measures.last().number
        val made = System.nanoTime()
        val solo = ScorePlayer(Synth(48_000), score, first, last, 120.0, 0, Synth.BRASS, order = PlayOrder.from(score, first))
        println("LOAD laying out the whole book: ${(System.nanoTime() - made) / 1_000_000} ms")
        val p1 = time("one part, the whole book queued", 20) { solo.fill(it) }
        val band = EnsemblePlayer(Synth(48_000), score, List(12) { k -> EnsemblePlayer.Voice(score, k % 5, if (k % 2 == 0) Synth.BRASS else Synth.SAX) }, first, last, 120.0)
        val p2 = time("a band of 12 parts", 20) { band.fill(it) }
        assertTrue("one part in time", p1 < 2.0)
        assertTrue("the band in time", p2 < 4.0)
    }
}
