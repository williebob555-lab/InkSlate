package com.inksheets.desktop

import com.inksheets.ui.smoothSoundMix
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class SoundMixTest {
    @Test fun `ordinary passages retain their level and mixed peaks remain bounded`() {
        for(i in -900..900) {
            val sample=i/1000f
            assertEquals(sample,smoothSoundMix(sample),0f)
        }
        for(i in -10000..10000) {
            val sample=i/1000f
            val mixed=smoothSoundMix(sample)
            assertTrue(mixed in -1f..1f)
            assertEquals(-mixed,smoothSoundMix(-sample),0f)
        }
    }

    @Test fun `metronome peaks crossing full scale never introduce a waveform step`() {
        // A note plus click can cross full scale on consecutive output samples.
        val before=smoothSoundMix(.9999f)
        val after=smoothSoundMix(1.0001f)
        assertTrue("A tiny input change must not create an audible discontinuity",abs(after-before)<.00021f)
        var previous=smoothSoundMix(.85f)
        for(i in 8501..30000) {
            val current=smoothSoundMix(i/10000f)
            assertTrue("Compression remains monotonic",current>=previous)
            assertTrue("No sudden edge at either knee",current-previous<=.00011f)
            previous=current
        }
    }
}
