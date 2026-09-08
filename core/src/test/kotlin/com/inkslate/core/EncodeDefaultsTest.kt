package com.inkslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * The payload stopped writing fields that hold their default value, which is most of what a
 * handwritten stroke's twenty-eight fields contain.
 *
 * That is only safe if a document still round-trips exactly, and if a file written this way is
 * still readable by a build that expects the old shape - these files sync between the tablet and
 * the desktop app, and a version skew between them is normal rather than exceptional.
 */
class EncodeDefaultsTest {

    private fun stroke(id: String) = Stroke(
        id = id,
        kind = Stroke.Kind.FREEHAND,
        color = -0x1000000,
        baseWidth = 2.2f,
        points = (0 until 30).map { InkPoint(it * 1.4f, it * 2.1f, 1.3f) },
        updatedUtc = 1_700_000_000_000L
    )

    private fun doc(n: Int) = InkDocument(
        docId = "d",
        source = InkDocument.SourceRef(name = "hw.pdf", kind = "pdf", pageCount = 3),
        pages = mapOf("0" to (0 until n).map { stroke("dev-$it") })
    )

    @Test
    fun `a document survives the round trip unchanged`() {
        val original = doc(50)
        val back = InkDocument.parse(original.serialize())
        assertNotNull(back)
        assertEquals(original, back)
    }

    @Test
    fun `defaults omitted from the text are restored on read`() {
        val text = doc(1).serialize()
        // The fields a pen stroke never sets should not be spelled out for it.
        assertTrue("rows should be omitted", !text.contains("\"rows\""))
        assertTrue("cells should be omitted", !text.contains("\"cells\""))
        assertTrue("boxWidth should be omitted", !text.contains("\"boxWidth\""))
        val back = InkDocument.parse(text)!!
        val s = back.strokesOn(0).single()
        assertEquals(0, s.rows)
        assertEquals(emptyList<String>(), s.cells)
        assertEquals(0f, s.boxWidth, 0f)
    }

    @Test
    fun `a file written the old way still reads`() {
        // Every field spelled out, the way older builds and the desktop app wrote them.
        val verbose = Json {
            ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true
        }.encodeToString(InkDocument.serializer(), doc(20))
        val back = InkDocument.parse(verbose)
        assertNotNull(back)
        assertEquals(doc(20), back)
    }

    /**
     * Worth pinning the size down, and worth being honest about how much this buys.
     *
     * Omitting defaults takes roughly a fifth off, not a half: a freehand stroke's twenty-eight
     * fields are a fixed overhead per stroke, while the bulk of the payload is its points, and a
     * point has three values none of which have a default to omit. Getting the points smaller is
     * a change to how they are written, not to which fields are written, and that is a format
     * change these documents cannot take casually - they are read by the desktop build too.
     */
    @Test
    fun `the payload gets materially smaller`() {
        val d = doc(200)
        val verbose = Json {
            ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true
        }.encodeToString(InkDocument.serializer(), d).length
        val lean = d.serialize().length
        assertTrue(
            "expected at least a sixth off, got $verbose -> $lean",
            lean * 6 < verbose * 5
        )
    }
}
