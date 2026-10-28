package com.inksheets.ui

import org.junit.Assert.*
import org.junit.Test

class RecordingWaveformTest {
    @Test fun independentHandlesClampWithoutCrossing() {
        val region=LoopRegion(2000,5000)
        assertEquals(LoopRegion(4900,5000),moveLoop(region,LoopDrag.START,9000,10000))
        assertEquals(LoopRegion(0,5000),moveLoop(region,LoopDrag.START,-9000,10000))
        assertEquals(LoopRegion(2000,2100),moveLoop(region,LoopDrag.END,-9000,10000))
        assertEquals(LoopRegion(2000,10000),moveLoop(region,LoopDrag.END,9000,10000))
    }
    @Test fun bodyDragPreservesLengthAtBothRecordingBoundaries() {
        val region=LoopRegion(2000,5000)
        assertEquals(LoopRegion(7000,10000),moveLoop(region,LoopDrag.BODY,100000,10000))
        assertEquals(LoopRegion(0,3000),moveLoop(region,LoopDrag.BODY,-100000,10000))
    }
    @Test fun nearestHandleWinsWhenTouchTargetsOverlap() {
        val region=LoopRegion(2000,2100)
        assertEquals(LoopDrag.START,loopDragTarget(2010,region,500))
        assertEquals(LoopDrag.END,loopDragTarget(2090,region,500))
        assertEquals(LoopDrag.BODY,loopDragTarget(4000,region,500))
    }
    @Test fun zoomedTapSeeksWithinVisibleWindowAndClampsOutsideCanvas() {
        val window=waveformWindow(10000,0,LoopRegion(4000,6000),4f)
        assertEquals(3750L to 6250L,window)
        assertEquals(5000,waveformTime(50f,100f,window))
        assertEquals(3750,waveformTime(-40f,100f,window))
        assertEquals(6250,waveformTime(140f,100f,window))
        assertEquals(0L to 2500L,waveformWindow(10000,0,null,4f))
        assertEquals(7500L to 10000L,waveformWindow(10000,10000,null,4f))
    }
    @Test fun envelopeRetainsActualTransientAndBoundedHighResolutionForLongAudio() {
        val envelope=WaveformEnvelope()
        repeat(4000) { i -> envelope.add(FloatArray(1024) { if(i==2700) .8f else .04f }) }
        val peaks=envelope.finish()
        assertEquals(4096,peaks.size)
        assertEquals(1f,peaks.max(),0f)
        assertTrue(peaks.min()<.1f)
        val small=waveformPeaks(peaks,80)
        assertEquals(80,small.size)
        assertEquals(1f,small.max(),0f)
    }
    @Test fun shortRecordingAndEmptyAudioAreSafe() {
        assertTrue(WaveformEnvelope().finish().isEmpty())
        val envelope=WaveformEnvelope();envelope.add(floatArrayOf(.5f))
        assertTrue(envelope.finish().all { it==1f })
        assertEquals(LoopRegion(0,30),moveLoop(LoopRegion(0,30),LoopDrag.END,-100,30))
    }
}
