package com.inkslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ruling part of a canvas must put the lines exactly where ruling all of it would have.
 *
 * The desktop rules only the part of a canvas on screen, because ruling the whole of one costs the
 * same thousands of lines whether you are looking at them or not. That is only sound if the ruling
 * is anchored to the page rather than to the region asked for: if it were anchored to the region,
 * every pan would slide the lines under the handwriting already sitting on them.
 */
class PaperPatternRegionTest {

    private class Collector : PaperPattern.Sink {
        val horizontals = mutableListOf<Float>()
        val verticals = mutableListOf<Float>()
        val dots = mutableListOf<Pair<Float, Float>>()
        override fun paper(left: Float, top: Float, right: Float, bottom: Float) = Unit
        override fun lineWidth(width: Float) = Unit
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float) {
            if (y0 == y1) horizontals += y0 else if (x0 == x1) verticals += x0
        }
        override fun dot(x: Float, y: Float, r: Float) {
            dots += x to y
        }
    }

    private fun rule(
        pattern: PaperPattern.Pattern,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): Collector = Collector().also {
        PaperPattern.emit(
            background = pattern,
            left = left, top = top, right = right, bottom = bottom,
            spacing = 24f,
            // The page's corner, which is where a canvas anchors its ruling - and it is nowhere
            // near the canvas corner, because a canvas grows away from the page in every
            // direction. That offset is exactly what a region-anchored ruling would get wrong.
            anchorX = -137f, anchorY = -211f,
            sink = it
        )
    }

    private val whole = floatArrayOf(-600f, -600f, 600f, 600f)

    @Test
    fun `ruled lines sit where they would have on the whole canvas`() {
        val all = rule(PaperPattern.Pattern.RULED, whole[0], whole[1], whole[2], whole[3])
        val part = rule(PaperPattern.Pattern.RULED, -100f, -100f, 220f, 220f)

        val expected = all.horizontals.filter { it >= -100f && it <= 220f }

        assertTrue("the part should rule something", part.horizontals.isNotEmpty())
        assertEquals(expected, part.horizontals)
    }

    @Test
    fun `grid lines sit where they would have on the whole canvas`() {
        val all = rule(PaperPattern.Pattern.GRID, whole[0], whole[1], whole[2], whole[3])
        val part = rule(PaperPattern.Pattern.GRID, -100f, -100f, 220f, 220f)

        assertEquals(all.horizontals.filter { it >= -100f && it <= 220f }, part.horizontals)
        assertEquals(all.verticals.filter { it >= -100f && it <= 220f }, part.verticals)
    }

    @Test
    fun `dots sit where they would have on the whole canvas`() {
        val all = rule(PaperPattern.Pattern.DOTS, whole[0], whole[1], whole[2], whole[3])
        val part = rule(PaperPattern.Pattern.DOTS, -100f, -100f, 220f, 220f)

        val expected = all.dots.filter {
            it.first >= -100f && it.first <= 220f && it.second >= -100f && it.second <= 220f
        }

        assertTrue("the part should have dots", part.dots.isNotEmpty())
        assertEquals(expected, part.dots)
    }

    /** And the saving is real, which is the only reason for any of this. */
    @Test
    fun `ruling a part of the canvas is much less work than ruling all of it`() {
        val all = rule(PaperPattern.Pattern.DOTS, -6_000f, -6_000f, 6_000f, 6_000f)
        val part = rule(PaperPattern.Pattern.DOTS, -100f, -100f, 220f, 220f)

        assertTrue(
            "a screenful should be a small fraction of a grown canvas: " +
                "${part.dots.size} against ${all.dots.size}",
            part.dots.size * 100 < all.dots.size
        )
    }
}
