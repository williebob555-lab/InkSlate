package com.inkslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The container that carries handwriting inside a document.
 *
 * A save is now verified by comparing the payload that came back out of the rewritten file with
 * the one that went in, rather than by decoding it and counting strokes. That makes the exact
 * round-trip - same bytes in, same document out - the thing the safety of a save rests on, so it
 * is worth a test of its own.
 */
class InkPayloadTest {

    private fun doc(strokes: Int): InkDocument {
        val points = (0 until 40).map { InkPoint(it * 1.5f, it * 2.25f, 1.4f) }
        return InkDocument(
            docId = "test",
            source = InkDocument.SourceRef(name = "homework.pdf", kind = "pdf", pageCount = 3),
            pages = mapOf(
                "0" to (0 until strokes).map {
                    Stroke(
                        id = "dev-$it",
                        kind = Stroke.Kind.FREEHAND,
                        color = -0x1000000,
                        baseWidth = 2f,
                        points = points,
                        updatedUtc = 1_700_000_000_000L + it
                    )
                }
            )
        )
    }

    @Test
    fun `round trips a document unchanged`() {
        val original = doc(120)
        val decoded = InkPayload.decode(InkPayload.encode(original))
        assertNotNull(decoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `encoding is deterministic, so a verified save can compare bytes`() {
        val original = doc(60)
        assertTrue(InkPayload.encode(original).contentEquals(InkPayload.encode(original)))
    }

    @Test
    fun `still compresses hard enough to be worth embedding`() {
        val original = doc(200)
        val raw = original.serialize().toByteArray(Charsets.UTF_8).size
        assertTrue(
            "payload should stay under a fifth of the raw JSON",
            InkPayload.encode(original).size * 5 < raw
        )
    }

    @Test
    fun `rejects a truncated payload rather than half-reading it`() {
        val bytes = InkPayload.encode(doc(30))
        assertNull(InkPayload.decode(bytes.copyOf(bytes.size - 20)))
    }

    @Test
    fun `rejects a corrupted body`() {
        val bytes = InkPayload.encode(doc(30))
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        assertNull(InkPayload.decode(bytes))
    }
}
