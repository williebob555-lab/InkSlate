package com.inkslate.core

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding the printed area of a page.
 *
 * Cropping changes the page's visible size, which is what the arrangement lays out and what ink is
 * hit-tested against - so the two builds have to trim the same scan to the same box, or the same
 * document is laid out two different ways.
 *
 * The two refusals matter as much as the detection: a crop that saves nothing is a jump on screen
 * for no gain, and a crop that keeps almost nothing has found a speck of scanner noise rather than
 * the text.
 */
class MarginCropTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    /** A raster with [content] painted black on white, in raster pixels. */
    private fun page(
        w: Int = 600,
        h: Int = 800,
        background: Int = white,
        content: Box?
    ): (Int, Int) -> Int = { x, y ->
        if (content != null && x >= content.left && x <= content.right &&
            y >= content.top && y <= content.bottom
        ) {
            black
        } else {
            background
        }
    }

    @Test
    fun `a page with wide margins is trimmed to its content`() {
        // Content occupying the middle half of a 600x800 raster.
        val box = MarginCrop.detect(
            600, 800, 612f, 792f,
            page(content = Box(150f, 200f, 450f, 600f))
        )

        assertNotNull("the content should have been found", box)
        // Page coordinates, so roughly a quarter in from each side, plus a little padding.
        assertTrue(box!!.left > 100f && box.left < 170f)
        assertTrue(box.right > 440f && box.right < 500f)
        assertTrue(box.top > 150f && box.top < 210f)
        assertTrue(box.bottom > 570f && box.bottom < 630f)
    }

    /** A crop that saves almost nothing is a jump on screen for no gain. */
    @Test
    fun `a page that is almost all content is left alone`() {
        assertNull(
            MarginCrop.detect(
                600, 800, 612f, 792f,
                page(content = Box(4f, 4f, 596f, 796f))
            )
        )
    }

    /** A speck of scanner noise is not the text. */
    @Test
    fun `a tiny mark on an otherwise blank page is not treated as content`() {
        assertNull(
            MarginCrop.detect(
                600, 800, 612f, 792f,
                page(content = Box(300f, 400f, 310f, 410f))
            )
        )
    }

    @Test
    fun `a completely blank page has nothing to crop to`() {
        assertNull(MarginCrop.detect(600, 800, 612f, 792f, page(content = null)))
    }

    /**
     * A scan on tinted or grey paper is still cropped correctly.
     *
     * The background is taken from the corner rather than assumed to be white, which is the
     * difference between trimming a photocopy and reporting the whole page as content.
     */
    @Test
    fun `a page on tinted paper is cropped like any other`() {
        val cream = 0xFFF5EFD8.toInt()
        val box = MarginCrop.detect(
            600, 800, 612f, 792f,
            page(background = cream, content = Box(150f, 200f, 450f, 600f))
        )
        assertNotNull(box)
        assertTrue(box!!.width < 612f * 0.8f)
    }

    @Test
    fun `a raster too small to sample is refused rather than guessed at`() {
        assertNull(MarginCrop.detect(2, 2, 612f, 792f) { _, _ -> black })
        assertNull(MarginCrop.detect(600, 800, 0f, 0f) { _, _ -> black })
    }
}
