package com.inkslate

import com.inkslate.ui.editor.layerMixerHeights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerMixerLayoutTest {
    @Test fun `landscape mixer gives the fader more space than the visibility header and actions together`() {
        // 128 dp toolbar minus the two 4 dp mixer insets.
        val heights = layerMixerHeights(120f, 24f, 28f, 48f)
        assertTrue(heights.fader > heights.visibility + heights.actions)
        assertTrue(heights.visibility < 120f / 4f)
        assertEquals(120f, heights.visibility + heights.actions + heights.fader, .001f)
    }

    @Test fun `minimum toolbar still has a meaningful fader travel`() {
        val heights = layerMixerHeights(100f, 24f, 28f, 48f)
        assertTrue(heights.fader >= 48f)
        assertTrue(heights.actions > 0f)
        assertEquals(100f, heights.visibility + heights.actions + heights.fader, .001f)
    }

    @Test fun `short viewport compresses fixed controls before fader and never overflows`() {
        for (available in listOf(0f, 20f, 40f, 60f, 80f)) {
            val heights = layerMixerHeights(available, 24f, 28f, 48f)
            assertTrue(heights.visibility >= 0f && heights.actions >= 0f && heights.fader >= 0f)
            assertTrue(heights.fader >= available / 2f - .001f)
            assertEquals(available, heights.visibility + heights.actions + heights.fader, .001f)
        }
    }
}