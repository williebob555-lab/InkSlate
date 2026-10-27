package com.inksheets.core.omr

import org.junit.Assert.*
import org.junit.Test

class PlaybackIndicatorTest {
    @Test fun `solo and ensemble progress follows rendered audio and resets at the next bar`() {
        val bar = Measure(1,0,0,Box(20,40,220,80),10f,Clef.TREBLE,Key(0),TimeSig(4,4),
            listOf(Note(listOf(0),listOf(Pitch(0,4)),Duration(1),30f)))
        val score = Score(listOf(bar,bar.copy(number=2)),1,listOf(250))
        val solo = ScorePlayer(Synth(8000),score,1,2,120.0,0,Synth.PIANO)
        val band = EnsemblePlayer(Synth(8000),score,listOf(EnsemblePlayer.Voice(score,0,Synth.PIANO)),1,2,120.0)
        repeat(50) { solo.fill(FloatArray(80)); band.fill(FloatArray(80)) }
        assertTrue(solo.barProgress in .05f.. .8f)
        assertTrue(band.barProgress in .05f.. .8f)
        repeat(200) { solo.fill(FloatArray(80)); band.fill(FloatArray(80)) }
        assertEquals(2,solo.bar)
        assertEquals(2,band.bar)
        assertTrue(solo.barProgress in 0f..1f)
    }
}
