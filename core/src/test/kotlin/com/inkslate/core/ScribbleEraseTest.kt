package com.inkslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScribbleEraseTest {
    // Hand-traced control points matching the tall, slanted loops in the attached screenshot.
    // These spread sideways far enough that positional PCA used to follow the drift instead.
    private fun driftingLoops(scale: Float = 1f, angle: Float = 0f): List<InkPoint> {
        val trace = listOf(
            0f to 52f, 18f to 4f, 23f to 0f, 21f to 12f,
            12f to 58f, 36f to 4f, 43f to -2f, 41f to 10f,
            26f to 57f, 53f to 8f, 61f to 4f, 61f to 17f,
            43f to 65f
        )
        val c = kotlin.math.cos(angle)
        val s = kotlin.math.sin(angle)
        return trace.map { (x, y) -> InkPoint((x * c - y * s) * scale, (x * s + y * c) * scale, 2f) }
    }

    @Test fun `sideways drifting tall loops match screenshot scribbles`() {
        assertTrue(ScribbleErase.isScribble(driftingLoops()))
    }

    @Test fun `drifting loops work diagonally horizontally and at small size`() {
        listOf(0f, .8f, 1.57f, 2.5f).forEach { angle ->
            listOf(.2f, 1f, 3f).forEach { scale ->
                assertTrue("scale=$scale angle=$angle", ScribbleErase.isScribble(driftingLoops(scale, angle)))
            }
        }
    }

    @Test fun `design config can disable the drifting recogniser`() {
        val before = DesignConfig.current
        try {
            DesignConfig.install(before.copy(scribble = before.scribble.copy(allowDrifting = false)))
            assertFalse(ScribbleErase.isScribble(driftingLoops()))
        } finally {
            DesignConfig.install(before)
        }
    }

    @Test fun `ordinary m and w are not long repeated scratches`() {
        val m = listOf(0f to 20f, 0f to 0f, 10f to 20f, 20f to 0f, 30f to 20f)
            .map { (x, y) -> InkPoint(x, y, 2f) }
        assertFalse(ScribbleErase.isScribble(m))
        assertFalse(ScribbleErase.isScribble(m.map { it.copy(y = -it.y) }))
    }

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

    @Test fun `angled unevenly sampled scratch is recognised`() {
        val points = listOf(
            InkPoint(4f, 7f, 2f), InkPoint(8f, 9f, 2f), InkPoint(19f, 14f, 2f),
            InkPoint(38f, 24f, 2f), InkPoint(29f, 21f, 2f), InkPoint(13f, 13f, 2f),
            InkPoint(5f, 10f, 2f), InkPoint(16f, 16f, 2f), InkPoint(35f, 26f, 2f),
            InkPoint(24f, 22f, 2f), InkPoint(8f, 14f, 2f)
        )
        assertTrue(ScribbleErase.isScribble(points, 1f))
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

    @Test fun `hooks in cursive writing do not become full scribble traversals`() {
        val writing = listOf(
            InkPoint(0f, 9f, 2f), InkPoint(2f, 2f, 2f), InkPoint(4f, 10f, 2f),
            InkPoint(6f, 4f, 2f), InkPoint(8f, 9f, 2f), InkPoint(11f, 3f, 2f),
            InkPoint(14f, 9f, 2f), InkPoint(17f, 5f, 2f), InkPoint(20f, 9f, 2f),
            InkPoint(24f, 4f, 2f), InkPoint(28f, 8f, 2f), InkPoint(32f, 6f, 2f)
        )
        assertFalse(ScribbleErase.isScribble(writing, 1f))
    }

    @Test fun `small dense fill is protected at maximum sensitivity`() {
        val fill = buildList {
            repeat(7) { pass ->
                val from = if (pass % 2 == 0) 0f else 11f
                val to = if (pass % 2 == 0) 11f else 0f
                repeat(4) { step ->
                    add(InkPoint(from + (to - from) * step / 3f, pass * 1.8f, 2f))
                }
            }
        }
        assertFalse(ScribbleErase.isScribble(fill, 1f))
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
