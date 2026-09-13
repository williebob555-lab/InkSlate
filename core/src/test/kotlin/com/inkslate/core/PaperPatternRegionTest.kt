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

    /**
     * Every pattern, not the three that happened to pass.
     *
     * The first version of this tested ruled lines, grids and dots - which are anchored repeats
     * and so were invariant already. Staves, Cornell rules and isometric paper are laid out
     * against a sheet, and all three were reading the region as if it were the sheet. A staff
     * disappeared the moment its top line went off the top of the window, which is precisely when
     * somebody is looking at it closely; the isometric diagonals slid sideways while panning.
     *
     * So: draw a strip of paper, draw all of it, and require that what the strip contains is what
     * that part of the whole contains. Marks are compared by where they are, to the nearest tenth
     * of a point, because a line drawn in two passes will not be bit-identical.
     */
    private fun marksOf(
        pattern: PaperPattern.Pattern,
        left: Float, top: Float, right: Float, bottom: Float
    ): Set<String> {
        val found = mutableSetOf<String>()
        PaperPattern.emit(
            background = pattern,
            left = left, top = top, right = right, bottom = bottom,
            spacing = 24f,
            anchorX = -137f, anchorY = -211f,
            pageWidth = 612f, pageHeight = 792f,
            sink = object : PaperPattern.Sink {
                override fun paper(left: Float, top: Float, right: Float, bottom: Float) = Unit
                override fun lineWidth(width: Float) = Unit
                override fun line(x0: Float, y0: Float, x1: Float, y1: Float) {
                    // A line is named by the infinite line it lies on, so a segment cut short by
                    // the edge of the strip still counts as the same line.
                    found += if (y0 == y1) {
                        "h:%.1f".format(y0)
                    } else if (x0 == x1) {
                        "v:%.1f".format(x0)
                    } else {
                        val slope = (y1 - y0) / (x1 - x0)
                        "d:%.2f:%.1f".format(slope, y0 - slope * x0)
                    }
                }

                override fun dot(x: Float, y: Float, r: Float) {
                    found += "dot:%.1f,%.1f".format(x, y)
                }
            }
        )
        return found
    }

    @Test
    fun `every pattern draws a strip the same as it draws the whole`() {
        val strip = floatArrayOf(-100f, -100f, 220f, 40f)
        for (pattern in PaperPattern.Pattern.entries) {
            if (pattern == PaperPattern.Pattern.PLAIN) continue

            val all = marksOf(pattern, whole[0], whole[1], whole[2], whole[3])
            val part = marksOf(pattern, strip[0], strip[1], strip[2], strip[3])

            assertTrue("$pattern drew nothing on the strip", part.isNotEmpty())
            val strangers = part - all
            assertTrue(
                "$pattern drew marks on the strip that are not on the page: " +
                    strangers.take(6).joinToString(),
                strangers.isEmpty()
            )
        }
    }

    /**
     * Music paper that has grown past its page is still music paper.
     *
     * The staves are anchored to the page - that is what keeps them still while the view moves -
     * but they must not be bounded by it, or a whiteboard grown downwards has ruled music at the
     * top and blank paper below.
     */
    @Test
    fun `staves continue past the page they are anchored to`() {
        // Well below and to the right of the page the anchor describes.
        val left = 700f
        val right = 1_400f
        var reached = 0f
        PaperPattern.emit(
            background = PaperPattern.Pattern.MUSIC,
            left = left, top = 900f, right = right, bottom = 1_600f,
            spacing = 24f,
            anchorX = -137f, anchorY = -211f,
            pageWidth = 612f, pageHeight = 792f,
            sink = object : PaperPattern.Sink {
                override fun paper(left: Float, top: Float, right: Float, bottom: Float) = Unit
                override fun lineWidth(width: Float) = Unit
                override fun line(x0: Float, y0: Float, x1: Float, y1: Float) {
                    // How far right any staff line actually reaches. Asking only whether lines
                    // were emitted proves nothing: they were, off beyond the page, where nobody
                    // could see them. What broke was where they stopped.
                    reached = maxOf(reached, maxOf(x0, x1))
                }

                override fun dot(x: Float, y: Float, r: Float) = Unit
            }
        )

        assertTrue(
            "music paper stopped short of the paper it is drawn on: reached $reached of $right",
            reached >= right - 1f
        )
    }

    /**
     * And the one the report was about: a staff whose top line is above the strip is still drawn.
     */
    @Test
    fun `a staff running off the top of the view is still drawn`() {
        val all = marksOf(PaperPattern.Pattern.MUSIC, whole[0], whole[1], whole[2], whole[3])
        assertTrue("the page should have staves on it", all.isNotEmpty())

        // A strip so narrow that no staff can lie wholly inside it.
        var drewSomething = false
        var y = -300f
        while (y < 200f) {
            if (marksOf(PaperPattern.Pattern.MUSIC, -100f, y, 220f, y + 6f).isNotEmpty()) {
                drewSomething = true
            }
            y += 6f
        }
        assertTrue("no strip of a music page drew any staff at all", drewSomething)
    }

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
