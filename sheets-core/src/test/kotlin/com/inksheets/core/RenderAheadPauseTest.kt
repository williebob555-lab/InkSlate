package com.inksheets.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.inksheets.core.omr.*

class RenderAheadPauseTest {
    @Test fun sustainedScoreResumesWithIdenticalSamplesToUninterruptedPlayback() {
        val bar=Measure(1,0,0,Box(0,0,300,40),10f,Clef.TREBLE,Key(0),TimeSig(4,4),
            listOf(Note(listOf(4),listOf(Pitch(0,4)),Duration(1),30f,tie=true)))
        val score=Score(listOf(bar,bar.copy(number=2)),1,listOf(400))
        fun player()=ScorePlayer(Synth(8000),score,1,2,120.0,0,Synth.PIANO,loop=true,rampTo=140.0,rampStep=5.0)
        val reference=player()
        val expected=FloatArray(1600)
        repeat(20) { block -> val samples=FloatArray(80);reference.fill(samples);samples.copyInto(expected,block*80) }
        val actualPlayer=player()
        val ahead=RenderAhead(8000,200,80) { actualPlayer.fill(it) }
        try {
            ahead.start(prefillMs=200)
            val first=FloatArray(800);ahead.read(first)
            assertTrue(ahead.pause())
            val frozen=actualPlayer.synth.position
            Thread.sleep(40)
            assertEquals(frozen,actualPlayer.synth.position)
            val round=actualPlayer.round
            val tempo=actualPlayer.bpm
            assertTrue(ahead.resume())
            val second=FloatArray(800);ahead.read(second)
            assertArrayEquals(expected,first+second,0f)
            assertEquals(round,actualPlayer.round)
            assertEquals(tempo,actualPlayer.bpm,0.0)
        } finally { ahead.stop() }
    }
    @Test fun slowInFlightFillCannotBlockPauseOrStartASecondProducer() {
        val entered=CountDownLatch(1);val finish=CountDownLatch(1)
        val calls=AtomicInteger()
        val ahead=RenderAhead(1000,200,20) { samples ->
            if(calls.incrementAndGet()==1) { entered.countDown();finish.await(2,TimeUnit.SECONDS) }
            samples.fill(.2f)
        }
        try {
            ahead.start(prefillMs=0)
            assertTrue(entered.await(1,TimeUnit.SECONDS))
            val at=System.nanoTime()
            assertFalse(ahead.pause())
            assertTrue("Pause must use a bounded wait",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-at)<500)
            assertFalse("Do not race the old producer",ahead.resume())
            assertEquals(1,calls.get())
            finish.countDown()
        } finally { finish.countDown();ahead.stop() }
    }
    @Test fun pauseFreezesProducerAndResumeKeepsEveryQueuedSample() {
        val generated=AtomicInteger()
        val ahead=RenderAhead(1000,200,20) { samples -> samples.indices.forEach { samples[it]=generated.getAndIncrement()/10000f } }
        try {
            ahead.start(prefillMs=180)
            val first=FloatArray(60);ahead.read(first)
            assertArrayEquals(FloatArray(60) { it/10000f },first,0f)
            ahead.pause()
            val frozen=generated.get()
            Thread.sleep(40)
            assertEquals("Paused synthesis must not advance its note/repeat state",frozen,generated.get())
            ahead.resume()
            val next=FloatArray(100);ahead.read(next)
            assertArrayEquals("Queued audio must continue at sample60, without a repeat or200ms skip",FloatArray(100) { (it+60)/10000f },next,0f)
        } finally { ahead.stop() }
    }

    @Test fun repeatedPauseDoesNotResetQueueAndNeverCallsProducerWhilePaused() {
        val generated=AtomicInteger()
        val ahead=RenderAhead(1000,200,20) { samples -> samples.indices.forEach { samples[it]=generated.getAndIncrement()/10000f } }
        try {
            ahead.start(prefillMs=180)
            var consumed=0
            repeat(4) {
                ahead.pause()
                val frozen=generated.get()
                ahead.pause()
                assertEquals(frozen,generated.get())
                ahead.resume()
                val chunk=FloatArray(20);ahead.read(chunk)
                assertArrayEquals(FloatArray(20) { (it+consumed)/10000f },chunk,0f)
                consumed+=20
            }
        } finally { ahead.stop() }
    }
}
