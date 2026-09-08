package com.inkslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The width that follows the zoom.
 *
 * The property that matters is the one the user sees: the mark on the glass is the same thickness
 * wherever the view is. That is `pageWidth * currentScale` staying constant, and it is what these
 * assert rather than the formula's internals.
 */
class DynamicWidthTest {

    @Test
    fun `off, the slider is a page width and nothing touches it`() {
        assertEquals(
            2f,
            DynamicWidth.resolve(2f, referenceScale = 1.5f, currentScale = 6f, enabled = false),
            1e-4f
        )
    }

    @Test
    fun `at the reference zoom the two settings agree exactly`() {
        val fixed = 2.4f
        val dynamic =
            DynamicWidth.resolve(2.4f, referenceScale = 1.37f, currentScale = 1.37f, enabled = true)
        assertEquals(fixed, dynamic, 1e-4f)
    }

    @Test
    fun `zooming in thins the stroke by the same factor`() {
        val w = DynamicWidth.resolve(3f, referenceScale = 1f, currentScale = 4f, enabled = true)
        assertEquals(0.75f, w, 1e-4f)
    }

    @Test
    fun `zooming out thickens it, so the mark on screen stays put`() {
        val w = DynamicWidth.resolve(3f, referenceScale = 1f, currentScale = 0.5f, enabled = true)
        assertEquals(6f, w, 1e-4f)
    }

    @Test
    fun `thickness on screen is constant across the whole zoom range`() {
        val reference = 1.2f
        val nominal = 2f
        val onScreen = (0..24).map { i ->
            val scale = 0.25f * Math.pow(1.25, i.toDouble()).toFloat()
            DynamicWidth.resolve(nominal, reference, scale, enabled = true) * scale
        }
        val expected = nominal * reference
        // Away from the clamps this is exact; the clamps only bite at absurd zooms.
        onScreen.forEach { assertEquals(expected, it, 1e-2f) }
    }

    @Test
    fun `a view with no sensible scale falls back to the slider rather than to nonsense`() {
        assertEquals(2f, DynamicWidth.resolve(2f, 0f, 3f, enabled = true), 1e-4f)
        assertEquals(2f, DynamicWidth.resolve(2f, 1f, 0f, enabled = true), 1e-4f)
        assertEquals(2f, DynamicWidth.resolve(2f, Float.NaN, 3f, enabled = true), 1e-4f)
        assertEquals(2f, DynamicWidth.resolve(2f, 1f, Float.POSITIVE_INFINITY, true), 1e-4f)
    }

    @Test
    fun `the result is always a width something can be drawn with`() {
        val extreme = DynamicWidth.resolve(64f, referenceScale = 1f, currentScale = 0.01f, enabled = true)
        assertTrue(extreme.isFinite())
        assertTrue(extreme <= 600f)
        val hairline = DynamicWidth.resolve(0.05f, referenceScale = 1f, currentScale = 16f, enabled = true)
        assertTrue(hairline >= 0.02f)
    }
}
