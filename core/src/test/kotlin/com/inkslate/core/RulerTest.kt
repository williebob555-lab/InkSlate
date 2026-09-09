package com.inkslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The straightedge.
 *
 * Shared geometry, because a stroke drawn against the ruler is *stored* projected onto it - so a
 * ruler that snapped differently on the two builds would put visibly different ink in the same
 * document from the same movement.
 */
class RulerTest {

    private val horizontal = Ruler(0f, 100f, 200f, 100f)

    @Test
    fun `angle reads the way a protractor does`() {
        assertEquals(0f, horizontal.angleDegrees, 0.01f)
        assertEquals(90f, Ruler(0f, 0f, 0f, 100f).angleDegrees, 0.01f)
        // Never negative: a protractor reads 315, not -45.
        assertEquals(315f, Ruler(0f, 100f, 100f, 0f).angleDegrees, 0.01f)
    }

    @Test
    fun `rotating spins about the middle, so the centre stays put`() {
        val turned = horizontal.rotatedBy(90f)
        assertEquals(horizontal.centerX, turned.centerX, 0.01f)
        assertEquals(horizontal.centerY, turned.centerY, 0.01f)
        assertEquals(horizontal.length, turned.length, 0.01f)
        assertEquals(90f, turned.angleDegrees, 0.01f)
    }

    @Test
    fun `snapping lands on a multiple of the step`() {
        val nearlyFlat = Ruler(0f, 100f, 200f, 112f)   // about 3.4 degrees
        assertEquals(0f, nearlyFlat.snappedToAngle(15f).angleDegrees, 0.01f)

        val nearly45 = Ruler(0f, 0f, 100f, 96f)        // about 43.8 degrees
        assertEquals(45f, nearly45.snappedToAngle(15f).angleDegrees, 0.01f)
    }

    // ---- what makes ink follow it --------------------------------------------

    @Test
    fun `a point near the edge is pulled onto the line`() {
        val projected = horizontal.project(50f, 106f, tolerance = Ruler.SNAP_POINTS)
        assertNotNull(projected)
        assertEquals(50f, projected!!.first, 0.01f)
        assertEquals(100f, projected.second, 0.01f)
    }

    @Test
    fun `a point well clear of the edge is left alone`() {
        assertNull(horizontal.project(50f, 400f, tolerance = Ruler.SNAP_POINTS))
    }

    /**
     * Once a stroke is being ruled it stays ruled to the end.
     *
     * A negative tolerance is how the samples after the first ask for that - the same way the pen
     * stays against the edge once it is there, rather than wandering off the moment the hand
     * drifts a few points away.
     */
    @Test
    fun `an unbounded projection ignores distance`() {
        val projected = horizontal.project(50f, 900f, tolerance = -1f)
        assertNotNull(projected)
        assertEquals(100f, projected!!.second, 0.01f)
    }

    /** Projection runs along the whole infinite line, so a ruled stroke can pass its ends. */
    @Test
    fun `projection continues past the ends of the bar`() {
        val projected = horizontal.project(500f, 102f, tolerance = Ruler.SNAP_POINTS)
        assertNotNull(projected)
        assertEquals(500f, projected!!.first, 0.01f)
    }

    // ---- taking hold of it ---------------------------------------------------

    @Test
    fun `the ends rotate and the middle slides`() {
        assertEquals(Ruler.Grab.END_A, horizontal.grabAt(2f, 100f, 12f, 10f))
        assertEquals(Ruler.Grab.END_B, horizontal.grabAt(198f, 100f, 12f, 10f))
        assertEquals(Ruler.Grab.BAR, horizontal.grabAt(100f, 103f, 12f, 10f))
        assertEquals(Ruler.Grab.NONE, horizontal.grabAt(100f, 400f, 12f, 10f))
    }

    /** An end is the smaller target, so it has to win where the two regions overlap. */
    @Test
    fun `an end beats the bar where they overlap`() {
        assertEquals(Ruler.Grab.END_A, horizontal.grabAt(0f, 100f, 12f, 30f))
    }

    /** Off past an end is not the bar, or the whole line would be draggable to infinity. */
    @Test
    fun `well past an end is not a grab on the bar`() {
        assertEquals(Ruler.Grab.NONE, horizontal.grabAt(600f, 100f, 12f, 10f))
    }

    @Test
    fun `moving keeps the length and the angle`() {
        val moved = horizontal.movedBy(30f, -20f)
        assertEquals(horizontal.length, moved.length, 0.01f)
        assertEquals(horizontal.angleDegrees, moved.angleDegrees, 0.01f)
        assertEquals(30f, moved.ax, 0.01f)
        assertEquals(80f, moved.ay, 0.01f)
    }

    @Test
    fun `a new ruler lies across the middle of the page`() {
        val page = Box(0f, 0f, 612f, 792f)
        val ruler = Ruler.across(page)
        assertEquals(page.centerY, ruler.centerY, 0.01f)
        assertEquals(page.centerX, ruler.centerX, 0.01f)
        assertTrue("it should be long enough to be useful", ruler.length > 100f)
    }

    @Test
    fun `angles compare the shorter way round`() {
        assertEquals(10f, Ruler.angleBetween(5f, 355f), 0.01f)
        assertEquals(90f, Ruler.angleBetween(0f, 90f), 0.01f)
    }
}
