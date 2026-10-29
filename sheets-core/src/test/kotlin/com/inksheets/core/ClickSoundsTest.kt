package com.inksheets.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class ClickSoundsTest {
    @Test fun `voices are distinct finite bounded and cached`() {
        val waves=ClickSound.entries.map { ClickSounds.samples(it,Metronome.Accent.STRONG,24000) }
        waves.forEach { wave ->
            assertTrue(wave.isNotEmpty())
            assertTrue(wave.all { it.isFinite() && it in -1f..1f })
            assertTrue(wave.any { kotlin.math.abs(it)>.01f })
        }
        for(i in waves.indices) for(j in 0 until i) assertFalse(waves[i].contentEquals(waves[j]))
        assertSame(waves[0],ClickSounds.samples(ClickSound.TONE,Metronome.Accent.STRONG,24000))
    }
    @Test fun `subdivision is quieter and silent beats produce no samples`() {
        fun rms(x:FloatArray)=sqrt(x.sumOf { it.toDouble()*it }/x.size)
        ClickSound.entries.forEach { sound ->
            assertTrue(rms(ClickSounds.samples(sound,Metronome.Accent.SUB,24000)) <
                rms(ClickSounds.samples(sound,Metronome.Accent.STRONG,24000)))
            assertEquals(0,ClickSounds.samples(sound,Metronome.Accent.SILENT,24000).size)
        }
    }
}
