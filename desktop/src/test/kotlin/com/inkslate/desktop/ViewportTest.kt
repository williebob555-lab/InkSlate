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
    @Test fun `studio rail can reserve either edge without moving music under it`() {
        val page=Box(0f,0f,612f,792f)
        for(right in listOf(false,true)) {
            val vp=Viewport().apply{viewSize=androidx.compose.ui.geometry.Size(1280f,800f);content=page}
            vp.fitClear(page,8f,0f,bottomLane=40f,studioLane=236f,studioOnRight=right)
            val a=vp.docToScreen(0f,0f);val b=vp.docToScreen(612f,792f)
            assertTrue(if(right)b.x<=1044f else a.x>=236f)
            assertTrue(b.y<=760f)
        }
    }

    @Test
    fun `music shelf and either annotation side leave every page wholly clear`() {
        for ((width, height) in listOf(360f to 640f, 640f to 360f, 800f to 1280f, 1280f to 800f)) {
            for (left in listOf(false, true)) {
                val page = Box(0f, 0f, 612f, 792f)
                val vp = Viewport().apply { viewSize = androidx.compose.ui.geometry.Size(width, height); content = page }
                vp.fitClear(page, 8f, 64f, laneOnLeft = left, bottomLane = 128f)
                val topLeft = vp.docToScreen(page.left, page.top)
                val bottomRight = vp.docToScreen(page.right, page.bottom)
                assertTrue("page must be above shelf", bottomRight.y <= height - 128f + .01f)
                assertTrue("page top visible", topLeft.y >= 0f)
                assertTrue("annotation side clear", if (left) topLeft.x >= 64f else bottomRight.x <= width - 64f + .01f)
            }
        }
    }

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

    // A 612pt page in an 800px window at 1x: it fits across, so with the pan tool it can sit
    // anywhere from flush left (offset 0) to flush right (offset -188), and no further.

    @Test
    fun `the pan tool stops at the edge of the page`() {
        val vp = viewport()
        vp.panBy(500f, 0f)
        assertEquals(-188f, vp.offset.x, 0.01f)
    }

    @Test
    fun `two fingers take the page past the edge and nothing pulls it back`() {
        val vp = viewport()
        vp.panBy(500f, 0f, freely = true)
        assertEquals(-500f, vp.offset.x, 0.01f)
        // A zoom afterwards leaves it out there too.
        vp.zoomBy(1f, vp.centreOfView())
        assertEquals(-500f, vp.offset.x, 0.01f)
    }

    @Test
    fun `the pan tool goes no further off than two fingers left it, but can come back`() {
        val vp = viewport()
        vp.panBy(500f, 0f, freely = true)
        vp.panBy(100f, 0f)
        assertEquals("held where the fingers left it", -500f, vp.offset.x, 0.01f)
        vp.panBy(-100f, 0f)
        assertEquals("free to come back toward the page", -400f, vp.offset.x, 0.01f)
        vp.panBy(50f, 0f)
        assertEquals("and no further out than it now is", -400f, vp.offset.x, 0.01f)
    }

    @Test
    fun `two fingers cannot lose the page entirely`() {
        val vp = viewport()
        vp.panBy(5000f, 0f, freely = true)
        val keep = 600f * Viewport.KEEP_VISIBLE_FRACTION
        assertEquals("a strip of it stays in the window", -(800f - keep), vp.offset.x, 0.01f)
    }
}
