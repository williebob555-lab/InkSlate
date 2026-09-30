package com.inksheets.core.omr

import com.inksheets.core.Chroma
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Right notes pass, a bar played a semitone off is found - played on the app's own instrument,
 * every family, heard as the microphone would hear it (with a little noise).
 */
class NoteCheckTest {
    private val rate = 48_000

    private fun pitchOf(midi: Int): Pitch {
        val names = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6)
        val alters = intArrayOf(0, 1, 0, 1, 0, 0, 1, 0, 1, 0, 1, 0)
        return Pitch(names[midi % 12], midi / 12 - 1, alters[midi % 12])
    }

    private fun bar(n: Int, vararg midis: Int) = Measure(n, 0, 0, Box(0, 0, 1, 1), 10f, Clef.TREBLE, Key(0), TimeSig(4, 4),
        midis.mapIndexed { i, m -> Note(listOf(0), listOf(pitchOf(m)), Duration(4), i.toFloat()) })

    /** [score] played on [patch] at [bpm], as frames heard. */
    private fun heard(score: Score, bpm: Double, patch: Synth.Patch): List<Chroma.Frame> {
        val p = ScorePlayer(Synth(rate), score, 1, score.measures.last().number, bpm, 0, patch)
        val stream = Chroma.Stream(rate)
        val out = ArrayList<Chroma.Frame>()
        val block = FloatArray(rate / 20)
        val noise = java.util.Random(1)
        repeat(((score.measures.size * 4 * 60.0 / bpm) * 20).toInt()) {
            java.util.Arrays.fill(block, 0f)
            p.fill(block)
            for (i in block.indices) block[i] += (noise.nextGaussian() * 0.003).toFloat()
            out += stream.feed(block)
        }
        return out
    }

    @Test
    fun `right notes pass and a wrong bar is found`() {
        val written = Score(listOf(bar(1, 72, 74, 76, 77), bar(2, 79, 77, 76, 74), bar(3, 72, 76, 79, 76), bar(4, 74, 71, 72, 72)), 1)
        // Bar 3 played a semitone high throughout.
        val playedWrong = Score(written.measures.mapIndexed { i, m -> if (i != 2) m else bar(3, 73, 77, 80, 77) }, 1)
        // And one wrong note on its own: bar 2's third note a tone low.
        val oneWrong = Score(written.measures.mapIndexed { i, m -> if (i != 1) m else bar(2, 79, 77, 74, 74) }, 1)
        for (patch in listOf(Synth.BRASS, Synth.CLARINET, Synth.FLUTE, Synth.SAX, Synth.STRINGS)) {
            for ((sound, wrong) in listOf(written to emptyList(), playedWrong to listOf(3), oneWrong to listOf(2))) {
                val check = NoteCheck(written, 100.0, 0)
                // Heard exactly where the music is: the follower's job, tested elsewhere.
                heard(sound, 100.0, patch).forEachIndexed { i, f -> check.hear(i * Chroma.FRAME_MS, f) }
                println("${patch.harmonics.size} harmonics, ${if (wrong.isEmpty()) "right" else "bar $wrong wrong"}: " +
                    check.likeness().entries.sortedBy { it.key }.joinToString { "${it.key}=${"%.2f".format(it.value)}" })
                assertEquals(wrong, check.doubtful())
            }
        }
        assertTrue(true)
    }
}
