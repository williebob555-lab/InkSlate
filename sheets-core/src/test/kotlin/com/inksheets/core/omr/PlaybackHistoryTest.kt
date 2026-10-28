package com.inksheets.core.omr

import com.inksheets.core.RenderAhead
import org.junit.Assert.*
import org.junit.Test

class PlaybackHistoryTest {
    private fun cursor(bar: Int=1,progress: Float=0f,tempo: Double=120.0,round: Int=0,finished: Boolean=false)=
        PlaybackCursor(bar,progress,tempo,round,finished)

    @Test fun futureSynthesisDoesNotMoveCursorBeforeAudioIsConsumed() {
        val history=PlaybackHistory(cursor())
        history.record(0,480,cursor(),cursor(progress=.2f))
        history.record(480,960,cursor(progress=.2f),cursor(progress=.4f))
        assertEquals(0f,history.at(0).progress,0f)
        assertEquals(.1f,history.at(240).progress,.0001f)
        assertEquals(.2f,history.at(480).progress,0f)
    }
    @Test fun barAndLoopTransitionsWaitForTheirActualOutputBlocks() {
        val history=PlaybackHistory(cursor(bar=4,progress=.98f,tempo=90.0,round=2))
        history.record(0,480,cursor(bar=4,progress=.98f,tempo=90.0,round=2),cursor(bar=1,tempo=100.0,round=3))
        history.record(480,960,cursor(bar=1,tempo=100.0,round=3),cursor(bar=1,progress=.1f,tempo=100.0,round=3))
        assertEquals(4,history.at(479).bar)
        assertEquals(2,history.at(479).round)
        assertEquals(90.0,history.at(479).tempo,0.0)
        assertEquals(1,history.at(480).bar)
        assertEquals(3,history.at(480).round)
        assertEquals(100.0,history.at(480).tempo,0.0)
        assertEquals(.05f,history.at(720).progress,.0001f)
        history.record(960,1440,cursor(bar=1,progress=.98f,tempo=100.0,round=3),cursor(bar=2,tempo=100.0,round=3))
        assertEquals(1,history.at(1439).bar)
        assertEquals(2,history.at(1440).bar)
    }
    @Test fun finishedMarkerWaitsUntilFinalAudioAndTailAreConsumed() {
        val history=PlaybackHistory(cursor())
        history.record(0,480,cursor(),cursor(progress=1f))
        history.record(480,960,cursor(progress=1f),cursor(progress=1f,finished=true))
        // The producer already finished, but all final buffered samples must still reach output.
        assertFalse(history.at(480).finished)
        assertFalse(history.at(959).finished)
        assertTrue(history.at(960).finished)
        assertTrue(history.at(1920).finished)
    }
    @Test fun historyCannotGrowWithoutBoundDuringLongPlayback() {
        val history=PlaybackHistory(cursor(),capacity=3)
        repeat(10000) { n -> history.record(n*480L,(n+1)*480L,cursor(bar=n+1),cursor(bar=n+2)) }
        assertEquals(3,history.blockCount)
        assertEquals(10001,history.at(10000*480L).bar)
        history.record(10000*480L,10000*480L,cursor(),cursor())
        assertEquals(3,history.blockCount)
    }
    @Test fun naturalScoreCompletionRemainsPendingWhileItsFinalRenderBlocksAreQueued() {
        val bar=Measure(1,0,0,Box(0,0,100,40),10f,Clef.TREBLE,Key(0),TimeSig(1,16),
            listOf(Note(listOf(4),listOf(Pitch(0,4)),Duration(16),30f)))
        val player=ScorePlayer(Synth(8000),Score(listOf(bar),1,listOf(200)),1,1,120.0,0,Synth.PIANO)
        fun state()=PlaybackCursor(player.bar,player.barProgress,player.bpm,player.round,player.finished)
        val history=PlaybackHistory(state())
        val ahead=RenderAhead(8000,200,80) { samples ->
            val at=player.synth.position;val before=state();player.fill(samples)
            history.record(at,player.synth.position,before,state())
        }
        try {
            ahead.start(prefillMs=200)
            val deadline=System.nanoTime()+2_000_000_000L
            while(!player.finished && System.nanoTime()<deadline) { ahead.read(FloatArray(80));Thread.sleep(1) }
            assertTrue(player.finished)
            assertTrue(ahead.pause())
            val end=player.synth.position
            assertTrue("The synth's final block is still buffered",ahead.consumedFrames<end)
            assertFalse(history.at(ahead.consumedFrames).finished)
            while(ahead.consumedFrames<end) {
                assertFalse(history.at(ahead.consumedFrames).finished)
                ahead.read(FloatArray(80))
            }
            assertTrue(history.at(ahead.consumedFrames).finished)
        } finally { ahead.stop() }
    }
    @Test fun renderAheadConsumptionAndPauseControlTheCursorInsteadOfProducerPosition() {
        val history=PlaybackHistory(cursor())
        var made=0L
        val ahead=RenderAhead(48000,200,480) { samples ->
            val before=cursor(progress=made/40000f)
            val start=made;made+=samples.size;samples.fill(.2f)
            history.record(start,made,before,cursor(progress=made/40000f))
        }
        try {
            ahead.start(prefillMs=100)
            assertTrue(made>=4800)
            assertEquals(0L,ahead.consumedFrames)
            assertEquals(0f,history.at(ahead.consumedFrames).progress,0f)
            ahead.read(FloatArray(480))
            assertEquals(480L,ahead.consumedFrames)
            assertEquals(.012f,history.at(ahead.consumedFrames).progress,.0001f)
            assertTrue(ahead.pause())
            val paused=history.at(ahead.consumedFrames)
            Thread.sleep(40)
            assertEquals(paused,history.at(ahead.consumedFrames))
            assertTrue(ahead.resume())
            ahead.read(FloatArray(480))
            assertEquals(.024f,history.at(ahead.consumedFrames).progress,.0001f)
        } finally { ahead.stop() }
    }
}
