package com.inkslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning a page moves every mark on it. Getting the direction or the axis wrong does not look
 * like a bug, it looks like the handwriting was never there - which is why this is pinned down by
 * arithmetic rather than by eye.
 */
class PageTurnTest {

    private val w = 600f
    private val h = 800f

    private fun stroke(points: List<InkPoint>, kind: Stroke.Kind = Stroke.Kind.FREEHAND) = Stroke(
        id = "s", kind = kind, color = -0x1000000, baseWidth = 2f, points = points
    )

    @Test
    fun `one clockwise turn sends the top-left corner to the top-right`() {
        val p = PageTurn.point(0f, 0f, w, h, 1)
        // the page is now h wide, and the old top-left is at its top-right
        assertEquals(h, p[0], 0.001f)
        assertEquals(0f, p[1], 0.001f)
    }

    @Test
    fun `the bottom-left corner becomes the top-left`() {
        val p = PageTurn.point(0f, h, w, h, 1)
        assertEquals(0f, p[0], 0.001f)
        assertEquals(0f, p[1], 0.001f)
    }

    @Test
    fun `four turns are no turn at all`() {
        for (x in listOf(0f, 13f, 599f)) for (y in listOf(0f, 250f, 799f)) {
            val p = PageTurn.point(x, y, w, h, 4)
            assertEquals(x, p[0], 0.001f)
            assertEquals(y, p[1], 0.001f)
        }
    }

    @Test
    fun `a turn stays inside the turned page`() {
        for (turns in 0..3) {
            val pw = if (PageTurn.swapsDimensions(turns)) h else w
            val ph = if (PageTurn.swapsDimensions(turns)) w else h
            for (x in listOf(0f, 1f, 300f, w)) for (y in listOf(0f, 1f, 400f, h)) {
                val p = PageTurn.point(x, y, w, h, turns)
                assertTrue("x out of the page at $turns turns", p[0] in -0.01f..(pw + 0.01f))
                assertTrue("y out of the page at $turns turns", p[1] in -0.01f..(ph + 0.01f))
            }
        }
    }

    @Test
    fun `negative and oversized turn counts normalise`() {
        assertEquals(3, PageTurn.normalise(-1))
        assertEquals(1, PageTurn.normalise(5))
        assertEquals(0, PageTurn.normalise(-4))
    }

    @Test
    fun `handwriting turns point by point`() {
        val s = stroke(listOf(InkPoint(0f, 0f, 2f), InkPoint(0f, h, 2f)))
        val t = s.turnedWithPage(1, w, h)
        // a stroke down the left edge becomes a stroke along the top edge
        assertEquals(h, t.points[0].x, 0.001f)
        assertEquals(0f, t.points[0].y, 0.001f)
        assertEquals(0f, t.points[1].x, 0.001f)
        assertEquals(0f, t.points[1].y, 0.001f)
        assertEquals("a path carries no rotation of its own", 0f, t.rotation, 0.001f)
    }

    @Test
    fun `a text box keeps its shape and carries the turn as a rotation`() {
        val s = stroke(listOf(InkPoint(100f, 200f, 1f)), Stroke.Kind.TEXT)
            .copy(text = "hello", boxWidth = 120f, boxHeight = 40f)
        val t = s.turnedWithPage(1, w, h)

        assertEquals("the box is not reshaped, it is rotated", 90f, t.rotation, 0.001f)
        val before = s.rawBoundsBox()
        val after = t.rawBoundsBox()
        assertEquals(before.width, after.width, 0.001f)
        assertEquals(before.height, after.height, 0.001f)

        // its centre lands where the turn puts it
        val c = PageTurn.point(before.centerX, before.centerY, w, h, 1)
        assertEquals(c[0], after.centerX, 0.001f)
        assertEquals(c[1], after.centerY, 0.001f)
    }

    @Test
    fun `turning a stroke four times returns it exactly`() {
        var s = stroke(listOf(InkPoint(37f, 411f, 2f), InkPoint(512f, 90f, 3f)))
        val original = s
        var pw = w
        var ph = h
        repeat(4) {
            s = s.turnedWithPage(1, pw, ph)
            val t = pw; pw = ph; ph = t
        }
        for (i in original.points.indices) {
            assertEquals(original.points[i].x, s.points[i].x, 0.01f)
            assertEquals(original.points[i].y, s.points[i].y, 0.01f)
        }
    }

    @Test
    fun `no turn returns the very same object`() {
        val s = stroke(listOf(InkPoint(1f, 2f, 2f)))
        assertTrue(s.turnedWithPage(0, w, h) === s)
    }
}
