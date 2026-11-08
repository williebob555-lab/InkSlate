package com.inkslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class StrokeRegionsTest {
    private fun mark(id: String, x: Float, end: Float = x + 10f) = Stroke(
        id = id, kind = Stroke.Kind.FREEHAND, color = 0, baseWidth = 2f,
        points = listOf(InkPoint(x, 10f, 2f), InkPoint(end, 20f, 2f)))

    @Test fun `dense documents return local strokes in paint order without duplicates`() {
        val marks = (0..5000).map { mark("$it", it * 30f) }
        val index = StrokeRegions(marks)
        val region = Box(1000f, 0f, 1500f, 100f)
        val expected = marks.filter { it.boundsBox().expanded(it.baseWidth + 2f).right >= region.left && it.boundsBox().expanded(it.baseWidth + 2f).left <= region.right }
        assertEquals(expected, index.query(region))
        assertSame(index.query(region), index.query(region))
    }

    @Test fun `negative coordinates and cross chunk strokes survive jumps`() {
        val marks = listOf(mark("negative", -300f), mark("long", -5000f, 5000f), mark("near", 0f))
        val index = StrokeRegions(marks)
        assertEquals(listOf(marks[1], marks[2]), index.query(Box(-5f, 0f, 300f, 100f)))
        assertEquals(listOf(marks[0], marks[1]), index.query(Box(-310f, 0f, -280f, 100f)))
    }

    @Test fun `rebuilding after a move or deletion cannot return a cached old view`() {
        val original = mark("moving", 0f)
        val view = Box(-10f, 0f, 100f, 100f)
        assertEquals(listOf(original), StrokeRegions(listOf(original)).query(view))
        val moved = original.copy(points = listOf(InkPoint(2000f, 10f, 2f)))
        assertEquals(emptyList<Stroke>(), StrokeRegions(listOf(moved)).query(view))
        assertEquals(emptyList<Stroke>(), StrokeRegions(emptyList()).query(view))
    }
}


