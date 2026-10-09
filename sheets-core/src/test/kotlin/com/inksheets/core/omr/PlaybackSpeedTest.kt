package com.inksheets.core.omr

import com.inksheets.core.RenderAhead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Playback must never starve the sound card: cheap to make, made ahead, and never a click if it runs dry. */
class PlaybackSpeedTest {
    private val rate = 48_000

    private fun pitchOf(midi: Int): Pitch {
        val names = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6)
        val alters = intArrayOf(0, 1, 0, 1, 0, 0, 1, 0, 1, 0, 1, 0)
        return Pitch(names[midi % 12], midi / 12 - 1, alters[midi % 12])
    }

    /** [bars] bars of eight eighths, pairs slurred, a staccato here and there, a chord now and then. */
    private fun part(bars: Int, base: Int, shift: Int): Score {
        val ms = (1..bars).map { b ->
            val events = (0 until 8).map { i ->
                val m = base + shift + intArrayOf(0, 2, 4, 5, 7, 5, 4, 2)[(i + b) % 8]
                val chord = if (i == 0 && b % 4 == 0) listOf(pitchOf(m), pitchOf(m + 4)) else listOf(pitchOf(m))
                Note(listOf(0), chord, Duration(8), 20f + i * 40f, articulations = if (i % 4 == 3) listOf("staccato") else emptyList())
            }
            val slurs = (0 until 4).map { k -> Direction("slur", 20f + k * 80f, 20f + k * 80f + 45f) }
            Measure(b, 0, 0, Box(0, 0, 400, 40), 10f, Clef.TREBLE, Key(0), TimeSig(4, 4), events, directions = slurs)
        }
        return Score(ms, 1, ms.map { 1000 })
    }

    private fun renderAll(fill: (FloatArray) -> Unit, seconds: Double): Double {
        val buf = FloatArray(480)
        val n = (seconds * rate / buf.size).toInt()
        val t0 = System.nanoTime()
        repeat(n) { java.util.Arrays.fill(buf, 0f); fill(buf) }
        return seconds / ((System.nanoTime() - t0) / 1e9)
    }

    @Test
    fun `an eight part band renders at least twenty times faster than it plays, a solo part a hundred`() {
        val bars = 32
        val seconds = bars * 2.0   // 4/4 at 120
        val band = (0 until 8).map { EnsemblePlayer.Voice(part(bars, 48 + it * 3, 0), 0, listOf(Synth.LOW_BRASS, Synth.BRASS, Synth.HORN, Synth.CLARINET, Synth.SAX, Synth.FLUTE, Synth.STRINGS, Synth.BASS)[it]) }
        val mine = part(bars, 60, 0)
        fun ensemble() = EnsemblePlayer(Synth(rate), mine, band, 1, bars, 120.0)
        fun solo() = ScorePlayer(Synth(rate), part(bars, 55, 0), 1, bars, 120.0, 0, Synth.LOW_BRASS)
        // Warm up the JIT, then measure the best of three.
        ensemble().let { p -> renderAll({ p.fill(it) }, 8.0) }
        solo().let { p -> renderAll({ p.fill(it) }, 8.0) }
        val bandX = (1..3).maxOf { ensemble().let { p -> renderAll({ b -> p.fill(b) }, seconds) } }
        val soloX = (1..3).maxOf { solo().let { p -> renderAll({ b -> p.fill(b) }, seconds) } }
        println("PS speed: 8-part band ${"%.0f".format(bandX)}x real time, solo ${"%.0f".format(soloX)}x")
        assertTrue("band $bandX x", bandX >= 20)
        assertTrue("solo $soloX x", soloX >= 100)
    }

    @Test
    fun `render-ahead delivers the same sound, unbroken, while other threads burn the processor`() {
        // The reference: the sound rendered straight.
        fun player() = ScorePlayer(Synth(rate), part(8, 55, 0), 1, 8, 120.0, 0, Synth.LOW_BRASS)
        val seconds = 3.0
        val total = (seconds * rate).toInt()
        val reference = FloatArray(total)
        player().let { p -> val b = FloatArray(480); var i = 0; while (i < total) { java.util.Arrays.fill(b, 0f); p.fill(b); System.arraycopy(b, 0, reference, i, minOf(480, total - i)); i += 480 } }

        val burning = java.util.concurrent.atomic.AtomicBoolean(true)
        val burners = (1..Runtime.getRuntime().availableProcessors() * 2).map {
            Thread { var x = 1.0; while (burning.get()) { x = x * 1.0000001 + sin(x) } }.apply { isDaemon = true; start() }
        }
        val p = player()
        val ahead = RenderAhead(rate, 200, 480) { p.fill(it) }
        val out = FloatArray(total)
        var worstWait = 0L
        try {
            ahead.start()
            // The "sound card": takes 10 ms of sound every 10 ms, on a thread of its own.
            val card = Thread {
                val b = FloatArray(480)
                val t0 = System.nanoTime()
                var i = 0; var k = 0
                while (i < total) {
                    val due = t0 + k * 10_000_000L
                    while (System.nanoTime() < due) Thread.sleep(0, 200_000)
                    worstWait = maxOf(worstWait, System.nanoTime() - due)
                    ahead.read(b)
                    System.arraycopy(b, 0, out, i, minOf(480, total - i))
                    i += 480; k++
                }
            }.apply { priority = Thread.MAX_PRIORITY; start() }
            card.join()
        } finally { burning.set(false); ahead.stop(); burners.forEach { it.join(100) } }
        var worstJump = 0f
        for (i in 1 until total) worstJump = maxOf(worstJump, abs(out[i] - out[i - 1]))
        var differs = 0
        for (i in 0 until total) if (abs(out[i] - reference[i]) > 1e-6f) differs++
        println("PS ahead: underruns ${ahead.underruns}, samples differing from straight render $differs of $total, worst card wake-up lateness ${worstWait / 1_000_000} ms, biggest step $worstJump")
        assertEquals("the ring never ran dry", 0, ahead.underruns)
        assertEquals("the sound is exactly the straight render", 0, differs)
    }

    @Test
    fun `if the ring does run dry the sound fades out and back, never a click`() {
        // A source too slow for real time: 25 ms to make 10 ms of a steady tone.
        var phase = 0.0
        val ahead = RenderAhead(rate, 100, 480) {
            Thread.sleep(25)
            for (i in it.indices) { phase += 2 * PI * 440 / rate; it[i] = (0.5 * sin(phase)).toFloat() }
        }
        ahead.start(prefillMs = 20)
        val b = FloatArray(480)
        val got = ArrayList<Float>()
        val t0 = System.nanoTime()
        for (k in 0 until 100) {
            val due = t0 + k * 10_000_000L
            while (System.nanoTime() < due) Thread.sleep(1)
            ahead.read(b); got.addAll(b.toList())
        }
        ahead.stop()
        var worst = 0f
        for (i in 1 until got.size) worst = maxOf(worst, abs(got[i] - got[i - 1]))
        println("PS dry: underruns ${ahead.underruns}, biggest step $worst (a 440 Hz tone at 0.5 steps ${"%.3f".format(0.5 * 2 * PI * 440 / rate)})")
        assertTrue("it did run dry", ahead.underruns > 0)
        assertTrue("and never clicked: $worst", worst < 0.1f)
    }
}
