package com.inkslate

import com.inkslate.core.InkPoint
import com.inkslate.core.Stroke
import com.inkslate.data.InkDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide whether handwriting survives.
 *
 * A document full of work once emptied itself because an editor that had not finished loading
 * wrote its empty state back, and every stroke was marked deleted. Deletion markers are permanent
 * and travel between devices, so the damage was not recoverable by re-syncing. These tests pin
 * down the behaviour that made that possible, and the behaviour that recovery depends on.
 */
class InkMergeTest {

    private fun stroke(id: String, page: Int = 0) = Stroke(
        id = id,
        kind = Stroke.Kind.FREEHAND,
        color = 0xFF000000.toInt(),
        baseWidth = 2f,
        pageIndex = page,
        points = listOf(InkPoint(1f, 1f, 1f), InkPoint(2f, 2f, 1f))
    )

    private fun docWith(vararg ids: String): InkDocument {
        var doc = InkDocument.create("a.pdf", "pdf", 1, 10L, "fp")
        doc = doc.withPage(0, ids.map { stroke(it) }, "deviceA")
        return doc
    }

    @Test fun `writing a page marks anything no longer on it as deleted`() {
        val before = docWith("a-1", "a-2", "a-3")
        val after = before.withPage(0, listOf(stroke("a-1")), "deviceA")

        assertEquals(1, after.totalStrokes)
        assertTrue("a-2 should be tombstoned", "a-2" in after.deleted)
        assertTrue("a-3 should be tombstoned", "a-3" in after.deleted)
    }

    @Test fun `writing an empty page deletes the whole page`() {
        // This is the mechanism behind the data loss: not a bug in itself - erasing everything is
        // a real thing to do - but catastrophic when the caller did not mean it.
        val emptied = docWith("a-1", "a-2").withPage(0, emptyList(), "deviceA")

        assertEquals(0, emptied.totalStrokes)
        assertEquals(2, emptied.deleted.size)
    }

    @Test fun `a deletion survives merging and spreads to the other copy`() {
        val original = docWith("a-1", "a-2")
        val emptied = original.withPage(0, emptyList(), "deviceA")

        // The other device still has everything. Merging does not bring the strokes back; the
        // deletion wins, on both sides, in either order. That is why the loss was permanent.
        assertEquals(0, emptied.mergeWith(original).totalStrokes)
        assertEquals(0, original.mergeWith(emptied).totalStrokes)
    }

    @Test fun `restoring a stroke under its old id does not survive`() {
        val original = docWith("a-1", "a-2")
        val emptied = original.withPage(0, emptyList(), "deviceA")

        // Put the strokes back exactly as they were, which is the obvious way to write a restore.
        val naive = emptied.copy(pages = original.pages)

        // ...and the moment anything merges, the tombstones remove them again.
        assertEquals(
            "a naive restore is undone by the deletions it left in place",
            0, naive.mergeWith(emptied).totalStrokes
        )
    }

    @Test fun `restoring under fresh ids survives merging`() {
        val original = docWith("a-1", "a-2")
        val emptied = original.withPage(0, emptyList(), "deviceA")

        // What the app actually does: re-issue every tombstoned stroke under an id that has never
        // been deleted anywhere, so no copy of the deletion can reach it.
        val reissued = emptied.copy(
            pages = mapOf("0" to listOf(stroke("b-100"), stroke("b-101")))
        )

        assertEquals(2, reissued.mergeWith(emptied).totalStrokes)
        assertEquals(2, emptied.mergeWith(reissued).totalStrokes)
        assertFalse("b-100" in reissued.deleted)
    }

    @Test fun `untouched pages keep their strokes when another page is written`() {
        var doc = InkDocument.create("a.pdf", "pdf", 2, 10L, "fp")
        doc = doc.withPage(0, listOf(stroke("a-1", 0)), "deviceA")
        doc = doc.withPage(1, listOf(stroke("a-2", 1)), "deviceA")

        val after = doc.withPage(1, emptyList(), "deviceA")

        assertEquals("page 0 must be untouched", 1, after.strokesOn(0).size)
        assertEquals(0, after.strokesOn(1).size)
    }

    @Test fun `merging two devices keeps both sets of work`() {
        val laptop = docWith("a-1", "a-2")
        val tablet = laptop.withPage(0, laptop.strokesOn(0) + stroke("b-1"), "deviceB")

        val merged = laptop.mergeWith(tablet)
        assertEquals(3, merged.totalStrokes)
        assertEquals(merged.totalStrokes, tablet.mergeWith(laptop).totalStrokes)
    }

    @Test fun `the newer copy of an edited stroke wins`() {
        val base = docWith("a-1")
        val edited = base.copy(
            pages = mapOf("0" to listOf(stroke("a-1").copy(baseWidth = 9f, updatedUtc = 5_000L)))
        )
        val older = base.copy(
            pages = mapOf("0" to listOf(stroke("a-1").copy(baseWidth = 1f, updatedUtc = 1_000L)))
        )

        assertEquals(9f, older.mergeWith(edited).strokesOn(0).first().baseWidth, 0.001f)
        assertEquals(9f, edited.mergeWith(older).strokesOn(0).first().baseWidth, 0.001f)
    }
}
