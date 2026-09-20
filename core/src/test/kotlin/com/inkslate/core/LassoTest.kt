package com.inkslate.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Selecting by drawing a ring.
 *
 * The failure worth guarding against is the quiet one: a ring that also picks up the line above it,
 * or that drops the word it was drawn around because the hand clipped a letter on the way past.
 */
class LassoTest {

    private fun ring(vararg points: Float) = points.toList()

    /** A square ring from (0,0) to (100,100). */
    private val square = ring(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f)

    private fun mark(vararg points: Pair<Float, Float>) = Stroke(
        id = "s", kind = Stroke.Kind.FREEHAND, color = 0, baseWidth = 1f,
        points = points.map { InkPoint(it.first, it.second, 1f) }
    )

    @Test
    fun `a point inside the ring is inside, and one outside is not`() {
        assertTrue(Lasso.contains(square, 50f, 50f))
        assertFalse(Lasso.contains(square, 150f, 50f))
        assertFalse(Lasso.contains(square, 50f, -10f))
    }

    @Test
    fun `a ring with almost no shape catches nothing`() {
        assertFalse(Lasso.contains(ring(0f, 0f, 10f, 10f), 5f, 5f))
    }

    @Test
    fun `a mark drawn inside the ring is caught, and its neighbour is left alone`() {
        assertTrue(Lasso.catches(mark(20f to 20f, 40f to 40f, 60f to 60f), square))
        assertFalse(Lasso.catches(mark(220f to 20f, 240f to 40f), square))
    }

    @Test
    fun `a ring that clips the end of a word still catches the word`() {
        // Nine tenths inside, the tail hanging out past the right edge.
        val word = mark(
            10f to 50f, 20f to 50f, 30f to 50f, 40f to 50f, 50f to 50f,
            60f to 50f, 70f to 50f, 80f to 50f, 95f to 50f, 130f to 50f
        )
        assertTrue(Lasso.catches(word, square))
    }

    @Test
    fun `a line that merely crosses the ring is not caught`() {
        val through = mark(-100f to 50f, -50f to 50f, 50f to 50f, 200f to 50f, 300f to 50f)
        assertFalse(Lasso.catches(through, square))
    }

    @Test
    fun `a long diagonal is judged by itself, not by the box around it`() {
        // Its bounding box covers the ring, but the line itself runs well clear of it.
        val diagonal = mark(-50f to 300f, 0f to 250f, 150f to 150f, 300f to -50f)
        assertFalse(Lasso.catches(diagonal, square))
    }

    @Test
    fun `a text box is caught when it sits inside the ring`() {
        val text = Stroke(
            id = "t", kind = Stroke.Kind.TEXT, color = 0, baseWidth = 1f,
            points = listOf(InkPoint(20f, 20f, 1f)), text = "hi", textSize = 10f,
            boxWidth = 40f, boxHeight = 20f
        )
        assertTrue(Lasso.catches(text, square))
        val far = text.copy(points = listOf(InkPoint(400f, 400f, 1f)))
        assertFalse(Lasso.catches(far, square))
    }

    @Test
    fun `an unfinished ring still closes itself`() {
        // Three quarters of a circle: the ends never meet, but the middle is plainly inside.
        val open = ArrayList<Float>()
        for (i in 0..27) {
            val a = Math.toRadians(i * 10.0)
            open.add((50 + 50 * Math.cos(a)).toFloat())
            open.add((50 + 50 * Math.sin(a)).toFloat())
        }
        assertTrue(Lasso.contains(open, 50f, 50f))
    }
}
