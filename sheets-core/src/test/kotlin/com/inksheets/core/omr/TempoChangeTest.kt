package com.inksheets.core.omr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tempo changed while playing: the music goes on from where it is at the new pace - nothing stops, nothing starts again. */
class TempoChangeTest {
    private fun note(x: Float) = Note(listOf(4), listOf(Pitch(0, 4)), Duration(4), x)
    private fun bar(n: Int) = Measure(n, 0, 0, Box(0, 0, 100, 10), 10f, Clef.TREBLE, Key(0), TimeSig(4, 4),
        listOf(note(10f), note(30f), note(50f), note(70f)))

    @Test
    fun `slower from now on, the bars after it later, the sound never cut`() {
        val rate = 1000
        val synth = Synth(rate)
        val bars = (1..4).map { bar(it) }
        val player = ScorePlayer(synth, Score(bars, 1, listOf(100)), 1, 4, 60.0, 0, Synth.BRASS, order = bars)
        val buf = FloatArray(100)
        // Into bar 2 (60 bpm: a bar is 4 s).
        repeat(50) { java.util.Arrays.fill(buf, 0f); player.fill(buf) }
        assertEquals(2, player.bar)
        player.setTempo(30.0)
        assertEquals(30.0, player.bpm, 1e-9)
        // At 60 bpm bar 3 would come 3 s on (at 8 s); at 30 bpm from 5 s, it comes 6 s on (at 11 s).
        var at = synth.position
        var silent = 0; var longest = 0
        while (player.bar < 3 && at < 20_000) {
            java.util.Arrays.fill(buf, 0f); player.fill(buf); at = synth.position
            if (buf.maxOf { kotlin.math.abs(it) } < 1e-4f) { silent++; longest = maxOf(longest, silent) } else silent = 0
        }
        assertTrue("bar 3 at about 11 s, not 8 s: ${at / 1000.0}", at in 10_800..11_300)
        assertTrue("it never went silent for long (${longest * 100} ms)", longest <= 3)
    }
}
