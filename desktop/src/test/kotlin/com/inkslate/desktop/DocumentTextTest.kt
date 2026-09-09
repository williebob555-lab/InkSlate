package com.inkslate.desktop

import com.inkslate.core.InkDocument
import com.inkslate.core.InkPoint
import com.inkslate.core.SearchHit
import com.inkslate.core.Stroke
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pulling text and its geometry back out of a real PDF.
 *
 * The search arithmetic is covered in `:core`; what is worth testing here is the half that is
 * written twice - that this build's extractor hands the shared code words with sensible boxes,
 * in the same top-left space the ink uses. A coordinate convention that is quietly upside down
 * produces a search that finds everything and highlights nothing.
 */
class DocumentTextTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** A page carrying known words, written through the app's own text export. */
    private fun pageSaying(vararg lines: String): File {
        val source = BlankDocumentFactory.create(
            temp.newFolder(), BlankDocumentFactory.Spec(name = "Words")
        ).getOrThrow()

        val strokes = lines.mapIndexed { i, body ->
            Stroke(
                id = "t$i",
                kind = Stroke.Kind.TEXT,
                color = 0xFF000000.toInt(),
                baseWidth = 1f,
                points = listOf(InkPoint(72f, 100f + i * 40f, 1f)),
                text = body,
                textSize = 14f
            )
        }
        val doc = InkDocument.create("Words.pdf", "pdf", 1, 0L, "").withPage(0, strokes, "test")
        return DocumentIO.exportFlattened(source, doc).getOrThrow()
    }

    @Test
    fun `words come back with boxes on the page`() {
        val pdf = pageSaying("Fourier series expansion")
        val words = DocumentText.wordsOn(pdf, 0)

        assertTrue("some words should be found", words.isNotEmpty())
        assertTrue(words.any { it.text.contains("Fourier") })

        val fourier = words.first { it.text.contains("Fourier") }
        assertTrue("the box should have real width", fourier.box.width > 1f)
        assertTrue("the box should have real height", fourier.box.height > 1f)
        // Top-left origin, the same space ink is stored in: the word was written near the top of
        // a 792pt page, so it must be nearer the top than the bottom.
        assertTrue("y should be measured from the top", fourier.box.top < 396f)
    }

    @Test
    fun `searching the document reports the page a phrase is on`() = runBlocking {
        val pdf = pageSaying("Complex exponentials", "Polar multiplication")
        val hits = ArrayList<SearchHit>()
        DocumentText.search(pdf, "polar", pageCount = 1, onProgress = {}, onHit = { hits.add(it) })

        assertEquals(1, hits.size)
        assertEquals(0, hits[0].page)
        assertTrue(hits[0].boxes.isNotEmpty())
    }

    @Test
    fun `a phrase that is not there finds nothing`() = runBlocking {
        val pdf = pageSaying("Complex exponentials")
        val hits = ArrayList<SearchHit>()
        DocumentText.search(pdf, "eigenvector", 1, onProgress = {}, onHit = { hits.add(it) })
        assertTrue(hits.isEmpty())
    }

    /** A stroke drawn across a line of text should take that line, which is what snapping is. */
    @Test
    fun `a path across the words picks them up`() {
        val pdf = pageSaying("Highlight this whole line please")
        val words = DocumentText.wordsOn(pdf, 0)
        assertTrue(words.isNotEmpty())

        val row = words.first().box
        val path = (0..60).map { (row.left + it * 4f) to row.centerY }
        val bars = DocumentText.wordsUnderPath(pdf, 0, path, tolerance = 2f)

        assertTrue("the stroke should snap to something", bars.isNotEmpty())
        assertTrue("and it should be a bar, not a dot", bars.first().width > 10f)
    }

    @Test
    fun `a document with no table of contents reports none rather than failing`() {
        val pdf = pageSaying("No contents here")
        assertTrue(DocumentText.outline(pdf).isEmpty())
    }
}
