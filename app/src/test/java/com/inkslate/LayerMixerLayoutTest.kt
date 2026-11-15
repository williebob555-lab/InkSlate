package com.inkslate

import com.inkslate.editor.layerMixerHeights
import com.inkslate.ui.editor.layerMixerWidthDp
import com.inkslate.core.DesignConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerMixerLayoutTest {
    @Test fun `narrow and enlarged text layouts do not squeeze pen controls beside the mixer`() {
        val config = DesignConfig.Mixer()
        for (screen in listOf(320f, 390f, 600f, 800f, 1280f)) for (fontScale in listOf(1f, 1.3f)) {
            val mixer = layerMixerWidthDp(screen, config, fontScale)
            val beside = screen - mixer - 8f
            if (screen <= 390f) assertTrue(beside < config.minimumPenWidthDp)
            else assertTrue(beside >= config.minimumPenWidthDp)
            assertTrue(mixer >= 3 * (config.controlWidthDp * fontScale + 28f) + 8f)
        }
    }
    @Test fun `landscape fader uses the entire channel height`() {
        // 128 dp toolbar minus the two 4 dp mixer insets.
        val heights = layerMixerHeights(120f, 24f, 28f)
        assertEquals(120f, heights.fader, .001f)
        assertTrue(heights.visibility < 120f / 4f)
        assertTrue(heights.visibility + 2 * heights.actions <= heights.fader)
    }

    @Test fun `minimum toolbar still has a meaningful fader travel`() {
        val heights = layerMixerHeights(100f, 24f, 28f)
        assertEquals(100f, heights.fader, .001f)
        assertTrue(heights.actions > 0f)
        assertTrue(heights.visibility + 2 * heights.actions <= heights.fader)
    }

    @Test fun `short viewport compresses fixed controls before fader and never overflows`() {
        for (available in listOf(0f, 20f, 40f, 60f, 80f)) {
            val heights = layerMixerHeights(available, 24f, 28f)
            assertTrue(heights.visibility >= 0f && heights.actions >= 0f && heights.fader >= 0f)
            assertEquals(available, heights.fader, .001f)
            assertTrue(heights.visibility + 2 * heights.actions <= available + .001f)
        }
    }
}
