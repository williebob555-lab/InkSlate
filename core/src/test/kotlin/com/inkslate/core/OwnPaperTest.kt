package com.inkslate.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which whiteboards rule their own paper, and whether that survives being written down.
 *
 * It has to travel in the document rather than sit in a setting: the same whiteboard is opened on
 * the tablet and on the laptop, and a seam that is invisible on one and visible on the other is
 * the thing this was meant to stop.
 */
class OwnPaperTest {

    @Test
    fun `a whiteboard started from nothing rules its own paper`() {
        val canvas = InkCanvas.startingAt(612f, 792f, "GRAPH", ownPaper = true)

        assertTrue(canvas.ownPaper)
    }

    /** A document somebody brought with them has a page that must be shown as it is. */
    @Test
    fun `a canvas made from a document does not`() {
        assertFalse(InkCanvas.startingAt(612f, 792f).ownPaper)
    }

    @Test
    fun `it travels with the document`() {
        val doc = InkDocument.create(
            sourceName = "Notes.pdf", kind = "pdf", pageCount = 1, sizeBytes = 1, fingerprint = ""
        ).copy(canvas = InkCanvas.startingAt(612f, 792f, "DOTS", ownPaper = true))

        val text = Json.encodeToString(InkDocument.serializer(), doc)
        val back = Json.decodeFromString(InkDocument.serializer(), text)

        assertTrue("the flag should survive being written down", back.canvas!!.ownPaper)
        assertEquals("DOTS", back.canvas!!.background)
    }

    /**
     * A whiteboard written by a build that had never heard of this reads as a page-and-canvas.
     *
     * Written by taking a real document apart rather than by hand: a document typed out in a test
     * is a guess at the format, and the guess is what gets tested instead of the format.
     */
    @Test
    fun `a whiteboard from an older build is unchanged`() {
        val doc = InkDocument.create(
            sourceName = "Notes.pdf", kind = "pdf", pageCount = 1, sizeBytes = 1, fingerprint = ""
        ).copy(canvas = InkCanvas.startingAt(612f, 792f, "GRID", ownPaper = true))

        val older = Json.encodeToString(InkDocument.serializer(), doc)
            .replace("\"ownPaper\":true", "\"ownPaper\":false")
            .replace(",\"ownPaper\":false", "")
        assertTrue("the field should have been removed for this to test anything",
            !older.contains("ownPaper"))

        val back = Json.decodeFromString(InkDocument.serializer(), older)

        assertFalse(
            "an older whiteboard has a page picture and must keep being drawn with one",
            back.canvas!!.ownPaper
        )
    }
}
