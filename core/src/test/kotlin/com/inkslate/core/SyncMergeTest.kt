package com.inkslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the cross-device merge.
 *
 * This is the logic that decides whether work survives a Syncthing round trip, and it is
 * invisible when it goes wrong: you notice a missing stroke days later. Worth pinning down.
 */
class SyncMergeTest {

    private fun stroke(id: String, x: Float = 0f, updated: Long = 1000L) = Stroke(
        id = id,
        kind = Stroke.Kind.FREEHAND,
        color = -0x1000000,
        baseWidth = 2f,
        points = listOf(InkPoint(x, 0f, 2f), InkPoint(x + 10f, 10f, 2f)),
        updatedUtc = updated
    )

    private fun doc(vararg strokes: Stroke) = InkDocument(
        docId = "doc-1",
        source = InkDocument.SourceRef(
            name = "homework.pdf", kind = "pdf", sizeBytes = 100,
            fingerprint = "fp", pageCount = 1
        ),
        pages = mapOf("0" to strokes.toList())
    )

    @Test
    fun `concurrent additions from two devices both survive`() {
        val tablet = doc(stroke("tab-1"), stroke("tab-2"))
        val laptop = doc(stroke("lap-1"))

        val merged = tablet.mergeWith(laptop)

        assertEquals(3, merged.strokesOn(0).size)
        assertEquals(
            setOf("tab-1", "tab-2", "lap-1"),
            merged.strokesOn(0).map { it.id }.toSet()
        )
    }

    @Test
    fun `merge is commutative regardless of which device runs it`() {
        val a = doc(stroke("a-1"), stroke("a-2"))
        val b = doc(stroke("b-1"))

        assertEquals(
            a.mergeWith(b).strokesOn(0).map { it.id }.toSet(),
            b.mergeWith(a).strokesOn(0).map { it.id }.toSet()
        )
    }

    @Test
    fun `a delete on one device is not resurrected by the other`() {
        // tablet drew two strokes, then erased one - erasing leaves a tombstone
        val tablet = doc(stroke("s-1"), stroke("s-2"))
            .withPage(0, listOf(stroke("s-1")), "tablet")
        // laptop never saw the delete and still holds both
        val laptop = doc(stroke("s-1"), stroke("s-2"))

        val merged = tablet.mergeWith(laptop)

        assertEquals(listOf("s-1"), merged.strokesOn(0).map { it.id })
        assertTrue("tombstone should be recorded", merged.deleted.containsKey("s-2"))
    }

    @Test
    fun `delete survives the merge in either direction`() {
        val tablet = doc(stroke("s-1"), stroke("s-2"))
            .withPage(0, listOf(stroke("s-1")), "tablet")
        val laptop = doc(stroke("s-1"), stroke("s-2"))

        assertFalse(laptop.mergeWith(tablet).strokesOn(0).any { it.id == "s-2" })
        assertFalse(tablet.mergeWith(laptop).strokesOn(0).any { it.id == "s-2" })
    }

    @Test
    fun `editing the same stroke on both devices keeps the newer edit`() {
        val older = doc(stroke("s-1", x = 0f, updated = 1_000L))
        val newer = doc(stroke("s-1", x = 500f, updated = 9_000L))

        assertEquals(500f, older.mergeWith(newer).strokesOn(0).first().points.first().x, 0.01f)
        assertEquals(500f, newer.mergeWith(older).strokesOn(0).first().points.first().x, 0.01f)
    }

    @Test
    fun `round trips through json without losing anything`() {
        val original = doc(stroke("s-1"), stroke("s-2"))
            .withPage(0, listOf(stroke("s-1")), "tablet")

        val restored = InkDocument.parse(original.serialize())

        requireNotNull(restored)
        assertEquals(original.strokesOn(0).map { it.id }, restored.strokesOn(0).map { it.id })
        assertEquals(original.deleted.keys, restored.deleted.keys)
    }

    @Test
    fun `unknown fields from a newer app version are tolerated`() {
        // forward compatibility: a future version adding a field must not brick older installs
        val json = """
            {"format":"inkdoc","version":99,"docId":"d","futureField":{"nested":true},
             "source":{"name":"a.pdf","kind":"pdf","pageCount":1},
             "pages":{"0":[]},"deleted":{},"clocks":{}}
        """.trimIndent()

        assertTrue(InkDocument.parse(json) != null)
    }

    @Test
    fun `a document from a different format is rejected rather than misread`() {
        assertTrue(InkDocument.parse("""{"format":"something-else","docId":"d"}""") == null)
        assertTrue(InkDocument.parse("not json at all") == null)
    }
}
