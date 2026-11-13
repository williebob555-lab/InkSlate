package com.inkslate

import com.inkslate.ui.compactActionIslandWidthDp
import com.inkslate.ui.tabIslandMaxWidthDp
import com.inkslate.ui.tabStackZIndex
import com.inkslate.ui.tabStripSpacingDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopChromeBudgetTest {
    @Test fun `next connected tab fills the prior shoulder gutter exactly`() {
        for (height in listOf(40f, 44f, 64f)) {
            for (fraction in listOf(.1f, .32f, .4f)) {
                val previousWidth = 180f
                val previousBodyRight = previousWidth - height * fraction
                val nextOrigin = previousWidth + tabStripSpacingDp(true, height, fraction)
                assertEquals(previousBodyRight, nextOrigin, .001f)
            }
        }
        assertEquals(4f, tabStripSpacingDp(false, 44f, .32f), .001f)
    }

    @Test fun `earlier tab owns shared shoulder even when the next tab is selected`() {
        for (position in 0..2) {
            assertTrue(tabStackZIndex(position, 4, false) > tabStackZIndex(position + 1, 4, false))
        }
        assertTrue(tabStackZIndex(3, 4, true) > tabStackZIndex(0, 4, false))
    }

    @Test fun `fullscreen reserves both concave shoulders beside fullscreen control`() {
        assertEquals(148f, compactActionIslandWidthDp(800f, connected = true), .001f)
        assertEquals(126f, compactActionIslandWidthDp(800f, connected = false), .001f)
    }

    @Test fun `all top controls fit portrait in either presentation`() {
        for (width in listOf(280f, 320f, 360f, 800f)) {
            for (connected in listOf(false, true)) {
                val actions = compactActionIslandWidthDp(width, connected)
                val tabs = tabIslandMaxWidthDp(width, actions, fullscreenControl = true)
                val occupied = 38f + 5f + tabs + 8f + actions + 43f
                assertTrue("width=$width connected=$connected", occupied <= width + .001f)
            }
        }
    }
}
