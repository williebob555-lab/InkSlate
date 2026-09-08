package com.inkslate.core

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The property that matters here is not "does the shape look right" - a test cannot see - but
 * "can the fill rule ever paint a hole". That one is decidable, and it is the bug these polygons
 * exist to make impossible: a handwritten `0` came back from a save filled in solid.
 */
class StrokeOutlineTest {

    private fun stroke(points: List<InkPoint>, brush: BrushType = BrushType.BALLPOINT) = Stroke(
        id = "t", kind = Stroke.Kind.FREEHAND, color = 0xFF000000.toInt(),
        baseWidth = 2f, points = points, brush = brush
    )

    /** Winding number of [contours] about a point, which is what a nonzero fill paints by. */
    private fun winding(contours: List<FloatArray>, x: Float, y: Float): Int {
        var w = 0
        for (poly in contours) {
            val n = poly.size / 2
            for (i in 0 until n) {
                val x0 = poly[i * 2]
                val y0 = poly[i * 2 + 1]
                val x1 = poly[((i + 1) % n) * 2]
                val y1 = poly[((i + 1) % n) * 2 + 1]
                if (y0 <= y) {
                    if (y1 > y && (x1 - x0) * (y - y0) - (x - x0) * (y1 - y0) > 0f) w++
                } else {
                    if (y1 <= y && (x1 - x0) * (y - y0) - (x - x0) * (y1 - y0) < 0f) w--
                }
            }
        }
        return w
    }

    private fun loop(radius: Float, width: Float): List<InkPoint> =
        (0..96).map {
            val t = it / 96.0 * 2 * Math.PI * 1.02      // slightly past closed, as a real 0 is
            InkPoint(
                (100 + cos(t) * radius).toFloat(),
                (100 + sin(t) * radius).toFloat(),
                width
            )
        }

    @Test
    fun `the hole in a closed loop is never filled`() {
        for (brush in BrushType.entries) {
            val s = stroke(loop(radius = 30f, width = 3f), brush)
            val c = StrokeOutline.contours(s)
            assertEquals(
                "${brush.name}: the middle of a 0 must not be inside the ink",
                0, winding(c, 100f, 100f)
            )
            // and not merely at the exact centre
            for (dx in listOf(-12f, 0f, 12f)) for (dy in listOf(-12f, 0f, 12f)) {
                assertEquals(
                    "${brush.name}: the hole must be empty at ($dx, $dy)",
                    0, winding(c, 100f + dx, 100f + dy)
                )
            }
        }
    }

    @Test
    fun `the ink itself is filled`() {
        val s = stroke(loop(radius = 30f, width = 4f))
        val c = StrokeOutline.contours(s)
        // a point on the ring itself, which is where the ink is
        assertTrue(
            "the stroke's own path must be inside the ink",
            winding(c, 130f, 100f) != 0
        )
    }

    @Test
    fun `a stroke crossing itself stays solid where it crosses`() {
        // A cross: right, then a second pass downward straight through the middle of the first.
        val pts = ArrayList<InkPoint>()
        for (i in 0..40) pts.add(InkPoint(60f + i * 2f, 100f, 6f))
        for (i in 0..40) pts.add(InkPoint(100f, 60f + i * 2f, 6f))
        val c = StrokeOutline.contours(stroke(pts))
        assertTrue(
            "the junction of a t or an x must be solid, not notched out",
            winding(c, 100f, 100f) != 0
        )
    }

    @Test
    fun `every contour is wound the same way`() {
        val c = StrokeOutline.contours(stroke(loop(radius = 25f, width = 3f)))
        assertTrue(c.isNotEmpty())
        for (poly in c) {
            var twiceArea = 0f
            val n = poly.size / 2
            for (i in 0 until n) {
                val j = (i + 1) % n
                twiceArea += poly[i * 2] * poly[j * 2 + 1] - poly[j * 2] * poly[i * 2 + 1]
            }
            assertTrue(
                "a contour wound the other way would subtract itself from the ink",
                twiceArea > 0f
            )
        }
    }

    @Test
    fun `a chisel nib is narrow along its own angle and wide across it`() {
        fun extent(dxDir: Double): Float {
            val pts = (0..30).map {
                InkPoint(
                    (100 + cos(dxDir) * it * 2).toFloat(),
                    (100 + sin(dxDir) * it * 2).toFloat(),
                    6f
                )
            }
            val c = StrokeOutline.contours(stroke(pts, BrushType.CALLIGRAPHY))
            // thickness measured across the direction of travel
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            val nx = -sin(dxDir).toFloat()
            val ny = cos(dxDir).toFloat()
            for (poly in c) {
                var i = 0
                while (i < poly.size) {
                    val d = (poly[i] - 100f) * nx + (poly[i + 1] - 100f) * ny
                    if (d < lo) lo = d
                    if (d > hi) hi = d
                    i += 2
                }
            }
            return hi - lo
        }

        val nib = Math.toRadians(BrushType.CALLIGRAPHY.nibAngleDeg.toDouble())
        val along = extent(nib)
        val across = extent(nib + Math.PI / 2)
        assertTrue(
            "a broad nib should be several times wider across than along: $across vs $along",
            across > along * 3f
        )
    }

    @Test
    fun `a single point still draws something`() {
        val c = StrokeOutline.contours(stroke(listOf(InkPoint(10f, 10f, 4f))))
        assertTrue(c.isNotEmpty())
        assertTrue(winding(c, 10f, 10f) != 0)
    }
}
