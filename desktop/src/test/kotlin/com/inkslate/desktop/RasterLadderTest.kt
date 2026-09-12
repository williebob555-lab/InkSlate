package com.inkslate.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a page is rendered again, and - the point of the thing - when it is not.
 *
 * Every re-render is a page rendered from scratch and a bitmap of tens of megabytes dropped for
 * the collector to deal with, which is felt as a dropped frame while zooming.
 */
class RasterLadderTest {

    @Test
    fun `a rung is never smaller than what is wanted`() {
        for (wanted in listOf(1, 100, 161, 500, 1_000, 2_500, 3_999)) {
            assertTrue(
                "asked for $wanted, got ${RasterLadder.rungFor(wanted)}",
                RasterLadder.rungFor(wanted) >= wanted
            )
        }
    }

    @Test
    fun `the ladder has ends`() {
        assertEquals(RasterLadder.SMALLEST, RasterLadder.rungFor(1))
        assertEquals(RasterLadder.SMALLEST, RasterLadder.rungFor(-5))
        assertEquals(RasterLadder.LARGEST, RasterLadder.rungFor(99_999))
    }

    /**
     * The whole reason for spacing them by ratio.
     *
     * Evenly spaced rungs cost more the further in you are, because the step from one to the next
     * is a smaller fraction of where you are standing. Fifteen hundred pixels of magnification
     * used to cross ten of them.
     */
    @Test
    fun `magnifying a page crosses few rungs`() {
        val crossed = generateSequence(RasterLadder.rungFor(600)) { rung ->
            RasterLadder.rungFor(rung + 1).takeIf { it > rung && it < RasterLadder.LARGEST }
        }.count()

        assertTrue("too many re-renders between a page and the cap: $crossed", crossed <= 6)
    }

    @Test
    fun `nothing held is always remade`() {
        assertTrue(RasterLadder.shouldRemake(null, 500))
    }

    @Test
    fun `a raster too small to show is remade`() {
        assertTrue(RasterLadder.shouldRemake(have = 240, wanted = 1_200))
    }

    @Test
    fun `a raster of the right size is left alone`() {
        val rung = RasterLadder.rungFor(1_200)
        assertFalse(RasterLadder.shouldRemake(have = rung, wanted = 1_200))
    }

    /**
     * Zooming out must not re-render, and zooming back in must find what it had.
     *
     * Resting on a boundary and rocking across it is the ordinary way a page is read, and a ladder
     * without slack in it re-renders on every wobble.
     */
    @Test
    fun `rocking across a boundary does not thrash`() {
        val held = RasterLadder.rungFor(1_200)

        assertFalse("zooming out a little", RasterLadder.shouldRemake(held, 1_100))
        assertFalse("and a little more", RasterLadder.shouldRemake(held, 900))
        assertFalse("back where it was", RasterLadder.shouldRemake(held, 1_200))
    }

    @Test
    fun `a wastefully sharp raster is eventually given up`() {
        val held = RasterLadder.LARGEST

        assertTrue(
            "a page shown as a thumbnail should not hold the largest raster",
            RasterLadder.shouldRemake(held, 200)
        )
    }
}
