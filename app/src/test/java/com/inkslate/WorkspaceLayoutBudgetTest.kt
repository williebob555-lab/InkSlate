package com.inkslate

import com.inkslate.ui.tabIslandMaxWidthDp
import com.inkslate.ui.compactActionIslandWidthDp
import com.inkslate.ui.editor.layerMixerWidthDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceLayoutBudgetTest {
    @Test fun `portrait top islands do not overlap`() {
        val width = 320f
        val actions = compactActionIslandWidthDp(width)
        val tabs = tabIslandMaxWidthDp(width, actions, fullscreenControl = true)
        val occupied = 38f + 5f + tabs + 8f + actions + 43f
        assertTrue("top islands must fit within the viewport", occupied <= width)
        assertEquals(140f, tabs, .001f)
    }

    @Test fun `very narrow layout preserves controls and scrolls tab contents`() {
        val width = 280f
        val actions = compactActionIslandWidthDp(width)
        val tabs = tabIslandMaxWidthDp(width, actions, fullscreenControl = true)
        val occupied = 38f + 5f + tabs + 8f + actions + 43f
        assertEquals(100f, tabs, .001f)
        assertTrue(occupied <= width)
    }

    @Test fun `tablet mixer leaves the colour strip most of the toolbar`() {
        val mixer = layerMixerWidthDp(1460f)
        assertEquals(280f, mixer, .001f)
        assertTrue(1460f - mixer >= 1100f)
    }

    @Test fun `portrait mixer retains fingertip sized channels without taking half the bar`() {
        val mixer = layerMixerWidthDp(800f)
        assertEquals(220f, mixer, .001f)
        assertTrue(mixer / 3f >= 70f)
        assertTrue(mixer < 800f * .30f)
    }
}
