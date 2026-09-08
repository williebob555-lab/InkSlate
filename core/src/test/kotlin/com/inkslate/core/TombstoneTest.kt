package com.inkslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stroke that is on the page is not deleted.
 *
 * Erase-then-undo, and undo-then-redo, both put a stroke back under the id it already had. While
 * the document stayed in memory that looked fine - the page is what gets drawn and what gets
 * exported. The tombstone only got its say on the next open, in [InkDocument.mergeWith], which
 * is why the handwriting was written out correctly and then read back short.
 */
class TombstoneTest {

    private fun stroke(id: String) = Stroke(
        id = id, kind = Stroke.Kind.FREEHAND, color = -0x1000000, baseWidth = 2f,
        points = listOf(InkPoint(1f, 1f, 1f), InkPoint(2f, 2f, 1f)), updatedUtc = 1L
    )

    private fun empty() = InkDocument(
        docId = "d",
        source = InkDocument.SourceRef(name = "hw.pdf", kind = "pdf", pageCount = 1)
    )

    @Test
    fun `a stroke erased and then restored is not left tombstoned`() {
        val a = stroke("dev-1")
        val b = stroke("dev-2")
        var doc = empty().withPage(0, listOf(a, b), "dev")
        doc = doc.withPage(0, listOf(a), "dev")            // erase b
        assertTrue("erasing should tombstone", "dev-2" in doc.deleted)
        doc = doc.withPage(0, listOf(a, b), "dev")         // undo the erase
        assertTrue("restoring should clear the tombstone", "dev-2" !in doc.deleted)
    }

    @Test
    fun `a restored stroke survives being reloaded and merged`() {
        val a = stroke("dev-1")
        val b = stroke("dev-2")
        var doc = empty().withPage(0, listOf(a, b), "dev")
        doc = doc.withPage(0, listOf(a), "dev")
        doc = doc.withPage(0, listOf(a, b), "dev")

        // What opening the document does: the copy inside the file, merged with the working copy.
        val reloaded = InkDocument.parse(doc.serialize())!!
        val merged = reloaded.mergeWith(doc)
        assertEquals(2, merged.strokesOn(0).size)
        assertTrue(merged.strokesOn(0).any { it.id == "dev-2" })
    }

    @Test
    fun `a document already damaged is repaired when it is read`() {
        val a = stroke("dev-1")
        val b = stroke("dev-2")
        // The shape an older build wrote: b on the page and tombstoned at the same time.
        val damaged = empty()
            .withPage(0, listOf(a, b), "dev")
            .let { it.copy(deleted = it.deleted + ("dev-2" to 5L)) }
        assertEquals(2, damaged.strokesOn(0).size)

        val repaired = damaged.withoutSelfContradiction()
        assertTrue("dev-2" !in repaired.deleted)
        assertEquals(2, repaired.mergeWith(repaired).strokesOn(0).size)
    }

    @Test
    fun `repairing per source does not resurrect another device's delete`() {
        val a = stroke("dev-1")
        val b = stroke("dev-2")
        val mine = empty().withPage(0, listOf(a, b), "mine")       // still has b
        val theirs = mine.withPage(0, listOf(a), "theirs")         // erased b over there

        // Each side repaired first, exactly as loadInk does it, then merged.
        val merged = mine.withoutSelfContradiction()
            .mergeWith(theirs.withoutSelfContradiction())
        assertEquals("their erase must still win", 1, merged.strokesOn(0).size)
        assertTrue(merged.strokesOn(0).none { it.id == "dev-2" })
    }

    @Test
    fun `a genuine erase still survives a merge with a copy that predates it`() {
        val a = stroke("dev-1")
        val b = stroke("dev-2")
        val before = empty().withPage(0, listOf(a, b), "dev")
        val after = before.withPage(0, listOf(a), "dev")
        // The stale copy still has b; the tombstone has to win, or erasing never sticks.
        assertEquals(1, after.mergeWith(before).strokesOn(0).size)
        assertEquals(1, before.mergeWith(after).strokesOn(0).size)
    }
}
