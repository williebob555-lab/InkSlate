package com.inkslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScribbleEraseTest {
    @Test fun `back and forth scratch is a scribble`() {
        val points = buildList {
            repeat(7) { pass ->
                val from = if (pass % 2 == 0) 0 else 30
                val to = if (pass % 2 == 0) 30 else 0
                repeat(5) { step -> add(InkPoint(from + (to - from) * step / 4f, pass * 1.5f, 2f)) }
            }
        }
        assertTrue(ScribbleErase.isScribble(points))
    }

    @Test fun `a quick five pass scratch is enough`() {
        val points = buildList {
            repeat(5) { pass ->
                val from = if (pass % 2 == 0) 0 else 24
                val to = if (pass % 2 == 0) 24 else 0
                repeat(4) { step -> add(InkPoint(from + (to - from) * step / 3f, pass.toFloat(), 2f)) }
            }
        }
        assertTrue(ScribbleErase.isScribble(points))
    }

    @Test fun `writing and a straight line are not scribbles`() {
        val line = (0..30).map { InkPoint(it.toFloat(), it * .2f, 2f) }
        val curve = (0..30).map { InkPoint(it.toFloat(), (it % 6).toFloat(), 2f) }
        assertFalse(ScribbleErase.isScribble(line))
        assertFalse(ScribbleErase.isScribble(curve))
    }

    @Test fun `pen and highlighter remember independent color and width`() {
        val c = ToolConfig(color = 0xFF2563EB.toInt(), strokeWidth = 2.5f)
        c.toggleHighlighter()
        c.color = 0xFFFDE047.toInt(); c.strokeWidth = 13f
        c.toggleHighlighter()
        assertEquals(0xFF2563EB.toInt(), c.color)
        assertEquals(2.5f, c.strokeWidth)
        c.toggleHighlighter()
        assertEquals(0xFFFDE047.toInt(), c.color)
        assertEquals(13f, c.strokeWidth)
    }
}
