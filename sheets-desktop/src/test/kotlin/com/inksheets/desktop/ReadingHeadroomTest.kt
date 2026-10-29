package com.inksheets.desktop

import org.junit.Assert.*
import org.junit.Test

class ReadingHeadroomTest {
    private val gib=1024L*1024*1024
    @Test fun `CPU pressure pauses immediately and requires sustained headroom to resume`() {
        var now=0L;var cpu=.3;var probes=0
        val gate=ReadingHeadroom({probes++;ReadingHeadroom.Sample(cpu,4*gib,8*gib,gib,2*gib)},{now})
        assertTrue(gate.allowed());assertTrue(gate.allowed());assertEquals(1,probes)
        cpu=.85;now=2000;assertFalse(gate.allowed())
        cpu=.7;now=4000;assertFalse(gate.allowed())
        cpu=.3;now=6000;assertFalse(gate.allowed())
        now=14000;assertFalse(gate.allowed())
        now=16000;assertTrue(gate.allowed())
    }
    @Test fun `system memory and JVM headroom independently block scanning`() {
        var now=0L
        var reading=ReadingHeadroom.Sample(.1,gib/4,8*gib,gib,2*gib)
        val gate=ReadingHeadroom({reading},{now})
        assertFalse(gate.allowed())
        reading=reading.copy(freeMemory=4*gib);now=2000;assertFalse(gate.allowed())
        now=12000;assertTrue(gate.allowed())
        reading=reading.copy(heapHeadroom=64*1024*1024);now=14000;assertFalse(gate.allowed())
    }
    @Test fun `missing system telemetry conservatively pauses automatic work`() {
        val gate=ReadingHeadroom({ReadingHeadroom.Sample(null,null,null,gib,2*gib)},{0L})
        assertFalse(gate.allowed())
    }
}
