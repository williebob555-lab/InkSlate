package com.inkslate.desktop

import com.inkslate.core.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera, and the two scrolling settings that act on it.
 *
 * Momentum is the one piece of the view that keeps moving after the hand has gone, which makes it
 * the one worth pinning down: "off" has to mean the page stops where it was left, not that it
 * coasts a shorter distance.
 */
class ViewportTest {

    private fun viewport() = Viewport().apply {
        viewSize = androidx.compose.ui.geometry.Size(800f, 600f)
        content = Box(0f, 0f, 612f, 792f)
    }

    @Test
    fun `momentum off means a flick does not carry`() {
        val vp = viewport()
        vp.flingEnabled = false
        vp.throwBy(900f, 0f)

        assertEquals(0f, vp.velocity.x, 0.001f)
        assertFalse("nothing should be left to advance", vp.advanceMomentum(0.016f))
    }

    @Test
    fun `flick distance scales the throw`() {
        val vp = viewport()
        vp.flingScale = 2f
        vp.throwBy(100f, -50f)

        assertEquals(200f, vp.velocity.x, 0.001f)
        assertEquals(-100f, vp.velocity.y, 0.001f)
    }

    /** Friction is per second, so the same flick travels the same way on a slow machine. */
    @Test
    fun `a throw runs down and then stops`() {
        val vp = viewport()
        vp.throwBy(600f, 0f)

        var frames = 0
        while (vp.advanceMomentum(0.016f) && frames < 10_000) frames++

        assertTrue("a throw should come to rest", frames in 1 until 10_000)
        assertEquals(0f, vp.velocity.x, 0.001f)
    }

    @Test
    fun `zooming keeps the point under the pointer where it was`() {
        val vp = viewport()
        val at = androidx.compose.ui.geometry.Offset(300f, 220f)
        val before = vp.screenToDoc(at)

        vp.zoomBy(2.5f, at)

        val after = vp.screenToDoc(at)
        assertEquals(before.x, after.x, 0.5f)
        assertEquals(before.y, after.y, 0.5f)
    }
}
