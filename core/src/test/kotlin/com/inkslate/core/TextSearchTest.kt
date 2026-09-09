package com.inkslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding a phrase on a page, and turning the match into highlight bars.
 *
 * Shared by both builds, so this is also what stops the tablet and the laptop finding different
 * things in the same textbook. The cases below are the ones that were wrong at some point or
 * would be invisible if they broke: a phrase that spans words, a highlight drawn per word instead
 * of per line, and a search that matches inside a word.
 */
class TextSearchTest {

    /** A line of words laid out left to right, 10pt tall, starting at y. */
    private fun line(y: Float, vararg words: String): List<TextRun> {
        var x = 0f
        return words.map { w ->
            val width = w.length * 6f
            val run = TextRun(w, Box(x, y, x + width, y + 10f))
            x += width + 4f
            run
        }
    }

    @Test
    fun `a single word is found and reported on its own page`() {
        val words = line(0f, "the", "quick", "brown", "fox")
        val hits = TextSearch.findIn(words, "brown", page = 3)

        assertEquals(1, hits.size)
        assertEquals(3, hits[0].page)
        assertTrue(hits[0].snippet.contains("brown"))
    }

    /**
     * The reason the page is rebuilt as one string rather than searched word by word.
     *
     * "quick brown" is two words, and is exactly the shape of thing people search for.
     */
    @Test
    fun `a phrase spanning several words is one match`() {
        val words = line(0f, "the", "quick", "brown", "fox")
        val hits = TextSearch.findIn(words, "quick brown", page = 0)

        assertEquals(1, hits.size)
        // The bar covers both words, so it starts at "quick" and ends after "brown".
        val box = hits[0].boxes.single()
        assertEquals(words[1].box.left, box.left, 0.01f)
        assertEquals(words[2].box.right, box.right, 0.01f)
    }

    @Test
    fun `every occurrence is reported`() {
        val words = line(0f, "one", "two", "one", "two", "one")
        assertEquals(3, TextSearch.findIn(words, "one", page = 0).size)
    }

    @Test
    fun `nothing matching gives nothing back`() {
        assertTrue(TextSearch.findIn(line(0f, "alpha", "beta"), "gamma", 0).isEmpty())
        assertTrue(TextSearch.findIn(emptyList(), "alpha", 0).isEmpty())
        assertTrue(TextSearch.findIn(line(0f, "alpha"), "", 0).isEmpty())
    }

    // ---- merging into bars ---------------------------------------------------

    @Test
    fun `words on one line merge into a single bar`() {
        val words = line(0f, "highlight", "all", "of", "this")
        val merged = TextSearch.mergeByLine(words.map { it.box })

        assertEquals(1, merged.size)
        assertEquals(words.first().box.left, merged[0].left, 0.01f)
        assertEquals(words.last().box.right, merged[0].right, 0.01f)
    }

    /** Two lines are two bars, or a highlight would swallow the gap between them. */
    @Test
    fun `words on different lines stay separate`() {
        val boxes = line(0f, "first", "line").map { it.box } +
            line(40f, "second", "line").map { it.box }
        assertEquals(2, TextSearch.mergeByLine(boxes).size)
    }

    /** A wide gap on the same line is a column break, not a space. */
    @Test
    fun `a large gap on one line is not bridged`() {
        val left = Box(0f, 0f, 50f, 10f)
        val right = Box(400f, 0f, 450f, 10f)
        assertEquals(2, TextSearch.mergeByLine(listOf(left, right)).size)
    }

    // ---- the highlighter snapping to text ------------------------------------

    @Test
    fun `a stroke across a line takes the words it crosses`() {
        val words = line(0f, "snap", "to", "these", "words")
        val path = (0..40).map { it * 4f to 5f }
        val bars = TextSearch.wordsUnderPath(words, path, tolerance = 2f)

        assertEquals("one line, one bar", 1, bars.size)
        assertTrue(bars[0].width > 50f)
    }

    @Test
    fun `a stroke clear of the text takes nothing`() {
        val words = line(0f, "nowhere", "near")
        val path = (0..20).map { it * 4f to 300f }
        assertTrue(TextSearch.wordsUnderPath(words, path, tolerance = 2f).isEmpty())
    }

    /**
     * A stroke between two lines should take neither.
     *
     * Tolerance widens the words, not the path, so a near miss stays a miss - otherwise
     * highlighting one line would routinely catch the one under it.
     */
    @Test
    fun `a stroke in the gap between lines takes neither`() {
        val words = line(0f, "upper", "line") + line(40f, "lower", "line")
        val path = (0..20).map { it * 4f to 25f }
        assertTrue(TextSearch.wordsUnderPath(words, path, tolerance = 2f).isEmpty())
    }
}
