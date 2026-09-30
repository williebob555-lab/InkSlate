package com.inksheets.core.omr

import com.inksheets.core.Chroma
import com.inksheets.core.MusicPresence
import com.inksheets.core.ScoreFollower
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/**
 * Page turns from the music alone: a part of four pages "performed" at another tempo, drifting,
 * as tones - the turns found by lining the score up with it, and by following the score by ear.
 */
class ScoreAudioTest {
    private fun score(): Score {
        val r = java.util.Random(11)
        val measures = (0 until 32).map { i ->
            TestPages.randomMeasure(r, i + 1, Clef.TREBLE, Key(0), TimeSig(4, 4), i % 8 == 0).let { m ->
                // Pitches as the reader gives them: treble clef, no key.
                m.copy(page = i / 8, events = m.events.map { e ->
                    if (e is Note) e.copy(pitches = e.steps.map { Pitch.fromDiatonic(Clef.TREBLE.at(it), e.accidentals[it] ?: 0) }) else e
                })
            }
        }
        return Score(measures, 4)
    }

    /** The score played as tones, starting at [bpm] and drifting to [endBpm]; and when each page began. */
    /** When each page's first note sounded, filled in by [perform]. */
    private val firstNotes = ArrayList<Double>()

    private fun perform(score: Score, bpm: Double, endBpm: Double, rate: Int = 11_025): Pair<FloatArray, List<Double>> {
        val out = ArrayList<Float>()
        var t = 0.0
        val pageStarts = ArrayList<Double>()
        firstNotes.clear()
        var page = 0
        var waiting = false
        val n = score.measures.size
        for ((i, m) in score.measures.withIndex()) {
            if (m.page != page) { page = m.page; pageStarts += t; waiting = true }
            val tempo = bpm + (endBpm - bpm) * i / n
            for (e in m.events) {
                if (waiting && e is Note) { firstNotes += t; waiting = false }
                val secs = e.duration.quarters * 60 / tempo
                val count = (secs * rate).toInt()
                for (k in 0 until count) {
                    var s = 0.0
                    if (e is Note) for (p in e.pitches) {
                        val f = 440.0 * 2.0.pow((p.midi - 69) / 12.0)
                        val env = kotlin.math.exp(-k / (rate * 0.6))
                        s += env * (sin(2 * PI * f * k / rate) + 0.4 * sin(4 * PI * f * k / rate) + 0.2 * sin(6 * PI * f * k / rate))
                    }
                    out += (s * 0.2).toFloat()
                }
                t += secs
            }
        }
        return out.toFloatArray() to pageStarts
    }

    @Test
    fun `finds the page turns in a recording from the score alone`() {
        val s = score()
        val (audio, truth) = perform(s, 96.0, 118.0)
        val recording = Chroma.frames(audio)
        // Read at a guessed tempo well off the real one.
        val turns = ScoreAudio.turnsIn(s, recording, 130.0)
        println("turns ${turns.map { it / 1000.0 }} s, truth ${truth.map { "%.2f".format(it) }}")
        assertEquals(3, turns.size)
        turns.zip(truth).forEach { (got, want) -> assertTrue("turn at ${got / 1000.0}, truth $want", abs(got / 1000.0 - want) < 1.0) }
    }

    @Test
    fun `follows the score by ear with no recording`() {
        println(followScore(ScoreFollower.STAY, ScoreFollower.SKIP))
    }

    private fun followScore(stay: Float, skip: Float): String {
        val s = score()
        val bpm = 110.0
        val (audio, truth) = perform(s, 100.0, 100.0)
        val follower = ScoreFollower(ScoreAudio.frames(s, bpm), stayCost = stay, skipCost = skip)
        val presence = MusicPresence()
        val stream = Chroma.Stream(Chroma.RATE)
        val pageAt = ScoreAudio.pageStarts(s, bpm)
        val heardAt = ArrayList<Double>()
        var next = 0
        var fed = 0
        var at = 0
        while (at < audio.size) {
            val block = audio.copyOfRange(at, minOf(audio.size, at + 1024)); at += block.size; fed += block.size
            for (f in stream.feed(block)) {
                presence.hear(f)
                val pos = follower.hear(f, presence.quiet(f))
                if (next < pageAt.size && pos >= pageAt[next]) { heardAt += fed.toDouble() / Chroma.RATE; next++ }
            }
        }
        val report = "turns heard at ${heardAt.map { "%.2f".format(it) }}, pages begin ${truth.map { "%.2f".format(it) }}, first notes ${firstNotes.map { "%.2f".format(it) }}"
        assertEquals(report, 3, heardAt.size)
        // In time for each page's first note (with the half second Listen turns early), and not
        // long before its bar begins.
        heardAt.indices.forEach { k -> assertTrue(report, heardAt[k] - 1.5 <= firstNotes[k] + 0.5 && heardAt[k] >= truth[k] - 3.0) }
        return report
    }
}
