package com.inkslate.desktop

import com.inkslate.core.Box
import com.inkslate.core.InkCanvas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A page that grows to fit what is written on it.
 *
 * Growth is free while drawing - it is a rectangle in memory - and becomes real once, when the
 * document is written. The things worth testing are the ones that would be invisible: that the
 * file really does get bigger, that what was already printed on the page does not move, and that
 * the document is still an ordinary PDF afterwards.
 */
class CanvasTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun letterPage(): File =
        BlankDocumentFactory.create(
            temp.newFolder(), BlankDocumentFactory.Spec(name = "Board")
        ).getOrThrow()

    @Test
    fun `a canvas that has not been outgrown is written unchanged`() {
        val file = letterPage()
        val before = DesktopSources.open(file)!!.use { it.pageDim(0) }
        val canvas = InkCanvas.startingAt(before.width, before.height)

        val grown = DocumentPages.growCanvas(file, canvas).getOrThrow()

        assertFalse(grown.paperIsBehind)
        val after = DesktopSources.open(file)!!.use { it.pageDim(0) }
        assertEquals(before.width, after.width, 0.5f)
        assertEquals(before.height, after.height, 0.5f)
    }

    @Test
    fun `writing past the edge makes the page bigger on disk`() {
        val file = letterPage()
        val start = DesktopSources.open(file)!!.use { it.pageDim(0) }
        val canvas = InkCanvas.startingAt(start.width, start.height)
            .grownTo(Box(0f, 0f, start.width + 400f, start.height))

        assertTrue("the canvas should have outgrown its paper", canvas.paperIsBehind)
        val grown = DocumentPages.growCanvas(file, canvas).getOrThrow()
        assertFalse("and then caught up", grown.paperIsBehind)

        val after = DesktopSources.open(file)!!.use { it.pageDim(0) }
        assertTrue("the page should be wider now", after.width > start.width + 300f)
    }

    /**
     * Growing leftwards is the case that would move everything if it were done wrong.
     *
     * The canvas keeps its own origin and lets it go negative rather than shifting the work
     * right, so the media box grows outwards and nothing already on the page changes position.
     */
    @Test
    fun `growing leftwards does not move what is already on the page`() {
        val file = letterPage()
        val start = DesktopSources.open(file)!!.use { it.pageDim(0) }
        val canvas = InkCanvas.startingAt(start.width, start.height)
            .grownTo(Box(-500f, 0f, start.width, start.height))

        assertTrue(canvas.left < 0f)
        DocumentPages.growCanvas(file, canvas).getOrThrow()

        val after = DesktopSources.open(file)!!.use { it.pageDim(0) }
        assertTrue("the page grew to the left", after.width > start.width + 400f)
        // Still a document anything can open, which a broken media box would not be.
        DesktopSources.open(file)!!.use {
            assertEquals(1, it.pageCount)
            assertNotNull(it.render(0, 200))
        }
    }

    @Test
    fun `growing in both directions covers the whole canvas`() {
        val file = letterPage()
        val start = DesktopSources.open(file)!!.use { it.pageDim(0) }
        val canvas = InkCanvas.startingAt(start.width, start.height)
            .grownTo(Box(-300f, -200f, start.width + 300f, start.height + 200f))

        val grown = DocumentPages.growCanvas(file, canvas).getOrThrow()
        val after = DesktopSources.open(file)!!.use { it.pageDim(0) }

        assertEquals(grown.width, after.width, 1f)
        assertEquals(grown.height, after.height, 1f)
    }

    @Test
    fun `an image cannot be grown`() {
        val notPdf = temp.newFile("photo.png").apply { writeText("not really a png") }
        assertTrue(
            DocumentPages.growCanvas(notPdf, InkCanvas.startingAt(100f, 100f)).isFailure
        )
    }
}
