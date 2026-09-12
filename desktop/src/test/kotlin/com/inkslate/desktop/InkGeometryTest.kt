package com.inkslate.desktop

import com.inkslate.core.InkPoint
import com.inkslate.core.Stroke
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The shape of a mark is kept until the mark changes, and not one moment longer.
 *
 * Both halves matter and they fail in opposite directions. Rebuilding a shape that has not changed
 * is the cost this exists to remove - it was being paid for every mark on the page, for every
 * point of the mark being drawn. Keeping a shape that has changed is worse than the cost: the mark
 * is then drawn in a shape it no longer has.
 */
class InkGeometryTest {

    @Before
    fun clean() = InkGeometry.clear()

    private fun mark(id: String, at: Long, points: Int = 3) = Stroke(
        id = id,
        kind = Stroke.Kind.FREEHAND,
        color = 0xFF000000.toInt(),
        baseWidth = 2f,
        points = (0 until points).map { InkPoint(it.toFloat(), it.toFloat(), 1f) },
        pageIndex = 0,
        updatedUtc = at
    )

    @Test
    fun `an unchanged mark keeps its shape`() {
        val s = mark("a", at = 1_000L)

        assertSame(InkGeometry.path(s), InkGeometry.path(s))
    }

    /** The same mark arriving as a new object - from a merge, say - is still the same mark. */
    @Test
    fun `an equal mark rebuilt elsewhere keeps its shape`() {
        val first = InkGeometry.path(mark("a", at = 1_000L))

        assertSame(first, InkGeometry.path(mark("a", at = 1_000L)))
    }

    @Test
    fun `an edited mark gets a new shape`() {
        val before = InkGeometry.path(mark("a", at = 1_000L))

        assertNotSame(before, InkGeometry.path(mark("a", at = 2_000L)))
    }

    /**
     * Two edits inside one millisecond share a stamp, and the point count is what separates them.
     * Without it the second would be drawn with the first one's shape.
     */
    @Test
    fun `a mark edited within the same millisecond gets a new shape`() {
        val before = InkGeometry.path(mark("a", at = 1_000L, points = 3))

        assertNotSame(before, InkGeometry.path(mark("a", at = 1_000L, points = 9)))
    }

    @Test
    fun `reuse is counted, and it is reuse that is being counted`() {
        val s = mark("a", at = 1_000L)
        InkGeometry.path(s)
        val missesAfterFirst = RenderStats.geometryMisses

        repeat(5) { InkGeometry.path(s) }

        assertTrue("no shape should have been rebuilt", RenderStats.geometryMisses == missesAfterFirst)
        assertTrue("reuse should be counted", RenderStats.geometryHits >= 5)
    }

    @Test
    fun `closing a document lets the shapes go`() {
        InkGeometry.path(mark("a", at = 1_000L))
        assertTrue(InkGeometry.held() > 0)

        InkGeometry.clear()

        assertTrue(InkGeometry.held() == 0)
    }
}
