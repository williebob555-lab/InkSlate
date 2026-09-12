package com.inkslate.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reading a point and drawing it back must be the same journey in both directions.
 *
 * This is the arithmetic that put every mark on a canvas a long way from the pen. The reading side
 * turned a screen point into the slot's own coordinates and the drawing side put marks back at the
 * canvas's, which differ by the canvas origin - a number that is negative and grows more negative
 * every time the canvas grows, so the marks moved further away the more there were.
 *
 * Nothing here tests a hard case. It tests that the two halves use the same number, which is the
 * only thing that went wrong.
 */
class PageSlotInkTest {

    private fun slot(inkLeft: Float = 0f, inkTop: Float = 0f) =
        PageSlot(
            index = 0, width = 612f, height = 792f, originX = 40f, originY = 60f,
            inkLeft = inkLeft, inkTop = inkTop
        )

    @Test
    fun `a plain page reads and draws in the same place`() {
        val s = slot()
        val ink = s.toInk(100f, 200f)
        val back = s.fromInk(ink.x, ink.y)
        assertEquals(100f, back.x, 0.001f)
        assertEquals(200f, back.y, 0.001f)
    }

    /** The canvas case: an origin that is negative, which is the usual state of a grown canvas. */
    @Test
    fun `a canvas reads and draws in the same place`() {
        val s = slot(inkLeft = -1_800f, inkTop = -2_400f)
        val ink = s.toInk(100f, 200f)
        val back = s.fromInk(ink.x, ink.y)
        assertEquals(100f, back.x, 0.001f)
        assertEquals(200f, back.y, 0.001f)
    }

    /** A trimmed page moves the origin the other way, and must survive the same round trip. */
    @Test
    fun `a trimmed page reads and draws in the same place`() {
        val s = slot(inkLeft = 36f, inkTop = 48f)
        val ink = s.toInk(500f, 700f)
        val back = s.fromInk(ink.x, ink.y)
        assertEquals(500f, back.x, 0.001f)
        assertEquals(700f, back.y, 0.001f)
    }

    /**
     * And the number itself, not just that it cancels.
     *
     * A pair of conversions that both ignored the canvas origin would round-trip perfectly and
     * still put every mark in the wrong place, because the drawing side does not use this pair -
     * it translates the whole layer by the same figure. So the figure has to be right.
     */
    @Test
    fun `ink coordinates include the canvas origin`() {
        val s = slot(inkLeft = -1_800f, inkTop = -2_400f)

        // A point at the slot's own top-left is the canvas's top-left, which is where the canvas
        // says it starts - not zero.
        val ink = s.toInk(s.originX, s.originY)

        assertEquals(-1_800f, ink.x, 0.001f)
        assertEquals(-2_400f, ink.y, 0.001f)
    }
}
