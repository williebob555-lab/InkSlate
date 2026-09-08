package com.inkslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cropping is stored as fractions of the source picture, and turned into pixel bounds only at
 * the moment of drawing. A crop that comes back inverted or outside the bitmap does not draw
 * wrongly - it throws, or renders nothing, which reads as a lost picture.
 */
class ImageCropTest {

    private fun image(l: Float, t: Float, r: Float, b: Float) = Stroke(
        id = "i", kind = Stroke.Kind.IMAGE, color = 0, baseWidth = 1f,
        points = listOf(InkPoint(0f, 0f, 1f), InkPoint(100f, 100f, 1f)),
        imageId = "pic", cropLeft = l, cropTop = t, cropRight = r, cropBottom = b
    )

    @Test
    fun `an untouched image reports no crop at all`() {
        val s = image(0f, 0f, 1f, 1f)
        assertTrue(!s.isCropped)
        // null means "the whole picture", which lets the renderer skip allocating a source rect.
        assertNull(s.cropPixels(800, 600))
    }

    @Test
    fun `fractions become pixel bounds in the source`() {
        val px = image(0.25f, 0.5f, 0.75f, 1f).cropPixels(800, 600)!!
        assertEquals(200, px[0])
        assertEquals(300, px[1])
        assertEquals(600, px[2])
        assertEquals(600, px[3])
    }

    @Test
    fun `a crop that covers everything and more is simply not a crop`() {
        // Nonsense bounds that still contain the whole picture mean "show all of it", so this
        // takes the cheap path rather than building a source rect covering the entire bitmap.
        assertTrue(!image(-0.5f, -0.5f, 2f, 2f).isCropped)
        assertNull(image(-0.5f, -0.5f, 2f, 2f).cropPixels(400, 400))
    }

    @Test
    fun `a real crop with out-of-range edges is clamped to the bitmap`() {
        val px = image(-0.5f, 0.25f, 0.75f, 2f).cropPixels(400, 400)!!
        assertEquals(0, px[0])
        assertEquals(100, px[1])
        assertEquals(300, px[2])
        assertEquals(400, px[3])
    }

    @Test
    fun `an inverted or empty crop asks for the whole picture rather than nothing`() {
        // Drawing a zero-width source rect renders blank, which looks like the picture was lost.
        assertNull(image(0.8f, 0.1f, 0.2f, 0.9f).cropPixels(500, 500))
        assertNull(image(0.5f, 0.5f, 0.5001f, 0.9f).cropPixels(100, 100))
    }

    @Test
    fun `a degenerate bitmap size never produces bounds`() {
        assertNull(image(0.1f, 0.1f, 0.9f, 0.9f).cropPixels(0, 0))
    }
}
