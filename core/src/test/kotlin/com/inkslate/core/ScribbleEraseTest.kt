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

    @Test fun `default recognises a sparse real world four pass scratch`() {
        val points = listOf(
            InkPoint(3f, 2f, 2f), InkPoint(19f, 4f, 2f), InkPoint(34f, 7f, 2f),
            InkPoint(18f, 8f, 2f), InkPoint(2f, 10f, 2f), InkPoint(17f, 12f, 2f),
            InkPoint(33f, 13f, 2f), InkPoint(16f, 14f, 2f), InkPoint(1f, 15f, 2f)
        )
        assertTrue(ScribbleErase.isScribble(points))
    }

    @Test fun `slider extremes are genuinely different`() {
        val shortScratch = listOf(
            InkPoint(0f, 0f, 2f), InkPoint(28f, 2f, 2f),
            InkPoint(0f, 4f, 2f), InkPoint(28f, 6f, 2f), InkPoint(20f, 7f, 2f)
        )
        assertFalse(ScribbleErase.isScribble(shortScratch, 0f))
        assertTrue(ScribbleErase.isScribble(shortScratch, 1f))
        val calibrated = ScribbleErase.calibratedSensitivity(shortScratch)
        assertTrue(calibrated != null && calibrated > .75f)
    }

    @Test fun `writing and a straight line are not scribbles`() {
        val line = (0..30).map { InkPoint(it.toFloat(), it * .2f, 2f) }
        val curve = (0..30).map { InkPoint(it.toFloat(), (it % 6).toFloat(), 2f) }
        assertFalse(ScribbleErase.isScribble(line))
        assertFalse(ScribbleErase.isScribble(curve))
    }

    @Test fun `dense back and forth fill remains ink`() {
        val fill = buildList {
            repeat(10) { pass ->
                val from = if (pass % 2 == 0) 0 else 32
                val to = if (pass % 2 == 0) 32 else 0
                repeat(6) { step ->
                    add(InkPoint(from + (to - from) * step / 5f, pass * 4.5f, 2f))
                }
            }
        }
        assertFalse(ScribbleErase.isScribble(fill, 1f))
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
