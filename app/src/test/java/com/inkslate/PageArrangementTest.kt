package com.inkslate

import com.inkslate.core.InkDocument
import com.inkslate.core.InkPoint
import com.inkslate.core.Stroke
import com.inkslate.pdf.ImportedPage
import com.inkslate.pdf.PageArrangement
import com.inkslate.pdf.PlannedPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rearranging pages moves handwriting between page indices, which is the one operation in the app
 * that can silently put a term's notes on the wrong pages. The PDF half needs a real document to
 * exercise; this half is pure and worth pinning down exactly.
 */
class PageArrangementTest {

    private var n = 0

    private fun stroke(id: String, page: Int) = Stroke(
        id = id, kind = Stroke.Kind.FREEHAND, color = -0x1000000, baseWidth = 2f,
        points = listOf(InkPoint(1f, 1f, 2f), InkPoint(5f, 5f, 2f)),
        pageIndex = page, updatedUtc = 100L
    )

    private fun doc(pages: Int) = InkDocument(
        docId = "d",
        source = InkDocument.SourceRef(
            name = "notes.pdf", kind = "pdf", sizeBytes = 10,
            fingerprint = "fp", geometry = "pdf:3", pageCount = pages
        ),
        pageSizes = (0 until pages).map { InkDocument.PageSize(612f, 792f) },
        pages = (0 until pages).associate { p -> p.toString() to listOf(stroke("s$p", p)) },
        bookmarks = listOf(InkDocument.Bookmark(1, "Chapter 2"))
    )

    private fun plan(vararg sources: Int) =
        sources.mapIndexed { i, s -> PlannedPage(source = s, uid = i.toLong()) }

    private fun ids(): () -> String = { "new-${++n}" }

    @Test
    fun `reordering moves the ink with its page`() {
        val out = PageArrangement.remapInk(doc(3), plan(2, 0, 1), ids())
        // page 2's mark is now on page 0, and so on
        assertEquals(1, out.strokesOn(0).size)
        assertEquals(0, out.strokesOn(0).first().pageIndex)
        assertEquals(3, out.pages.values.sumOf { it.size })
    }

    @Test
    fun `a removed page takes its handwriting with it`() {
        val out = PageArrangement.remapInk(doc(3), plan(0, 2), ids())
        assertEquals(2, out.pages.size)
        assertEquals(2, out.pages.values.sumOf { it.size })
        assertEquals(2, out.source.pageCount)
    }

    @Test
    fun `a duplicated page duplicates its handwriting under fresh ids`() {
        val out = PageArrangement.remapInk(doc(2), plan(0, 0, 1), ids())
        assertEquals(3, out.pages.size)
        val all = out.pages.values.flatten()
        assertEquals(3, all.size)
        assertEquals(
            "two copies of a page must not share a stroke id",
            3, all.map { it.id }.toSet().size
        )
    }

    @Test
    fun `every old id is tombstoned so a stale device cannot resurrect it`() {
        val before = doc(3)
        val out = PageArrangement.remapInk(before, plan(2, 1, 0), ids())
        for (old in before.pages.values.flatten()) {
            assertTrue("$old.id must be tombstoned", out.deleted.containsKey(old.id))
        }
        for (fresh in out.pages.values.flatten()) {
            assertFalse(
                "a re-issued stroke must not be tombstoned",
                out.deleted.containsKey(fresh.id)
            )
        }
    }

    @Test
    fun `merging a stale copy back in adds nothing`() {
        val stale = doc(3)
        val rearranged = PageArrangement.remapInk(stale, plan(2, 0, 1), ids())
        val merged = rearranged.mergeWith(stale)
        assertEquals(
            "the old marks are tombstoned, so the merge is a no-op",
            rearranged.pages.values.sumOf { it.size },
            merged.pages.values.sumOf { it.size }
        )
    }

    @Test
    fun `a bookmark follows its page and dies with it`() {
        val moved = PageArrangement.remapInk(doc(3), plan(2, 1, 0), ids())
        assertEquals(listOf(1), moved.bookmarks.map { it.page })

        val removed = PageArrangement.remapInk(doc(3), plan(0, 2), ids())
        assertTrue(removed.bookmarks.isEmpty())
    }

    @Test
    fun `an inserted blank page gets its own size and no ink`() {
        val out = PageArrangement.remapInk(
            doc(2),
            listOf(
                PlannedPage(0, 0),
                PlannedPage(-1, 99, blankWidth = 595f, blankHeight = 842f),
                PlannedPage(1, 1)
            ),
            ids()
        )
        assertEquals(3, out.pageSizes.size)
        assertEquals(595f, out.pageSizes[1].w)
        assertTrue("a new page starts empty", out.strokesOn(1).isEmpty())
        assertEquals(1, out.strokesOn(2).size)
    }

    @Test
    fun `the stale page geometry is cleared rather than left to warn about itself`() {
        val out = PageArrangement.remapInk(doc(3), plan(0, 1), ids())
        assertEquals("", out.source.geometry)
    }

    @Test
    fun `an unchanged plan is recognised as unchanged`() {
        assertTrue(PageArrangement.isUnchanged(PageArrangement.identity(4), 4))
        assertFalse(PageArrangement.isUnchanged(plan(1, 0), 2))
        assertFalse(PageArrangement.isUnchanged(plan(0, 1), 3))
    }

    // ---- turning ------------------------------------------------------------

    @Test
    fun `a turn on its own counts as a change`() {
        val turned = PageArrangement.identity(2).mapIndexed { i, p ->
            if (i == 0) p.copy(quarterTurns = 1) else p
        }
        assertFalse(PageArrangement.isUnchanged(turned, 2))
    }

    @Test
    fun `turning a page swaps its recorded size and moves its ink`() {
        val sizes: (Int) -> Pair<Float, Float> = { 600f to 800f }
        val turned = listOf(PlannedPage(0, 0, quarterTurns = 1), PlannedPage(1, 1))
        val out = PageArrangement.remapInk(doc(2), turned, ids(), sizes)

        assertEquals(800f, out.pageSizes[0].w, 0.001f)
        assertEquals(600f, out.pageSizes[0].h, 0.001f)
        assertEquals("the untouched page keeps its size", 600f, out.pageSizes[1].w, 0.001f)

        // the mark at (1,1) on a 600x800 page lands at (800-1, 1) once turned
        val moved = out.strokesOn(0).first()
        assertEquals(799f, moved.points[0].x, 0.01f)
        assertEquals(1f, moved.points[0].y, 0.01f)
    }

    @Test
    fun `a page turned all the way round is where it started`() {
        val sizes: (Int) -> Pair<Float, Float> = { 600f to 800f }
        val out = PageArrangement.remapInk(
            doc(1), listOf(PlannedPage(0, 0, quarterTurns = 4)), ids(), sizes
        )
        assertEquals(600f, out.pageSizes[0].w, 0.001f)
        assertEquals(1f, out.strokesOn(0).first().points[0].x, 0.01f)
    }

    // ---- importing ----------------------------------------------------------

    private fun imported(uid: Long, w: Float = 595f, h: Float = 842f) = PlannedPage(
        source = -1, uid = uid,
        import = ImportedPage(
            path = "/tmp/other.pdf", pageIndex = 3, isImage = false, width = w, height = h
        )
    )

    @Test
    fun `an imported page arrives sized and empty`() {
        val out = PageArrangement.remapInk(
            doc(2),
            listOf(PlannedPage(0, 0), imported(50), PlannedPage(1, 1)),
            ids()
        )
        assertEquals(3, out.pageSizes.size)
        assertEquals(595f, out.pageSizes[1].w, 0.001f)
        assertEquals(842f, out.pageSizes[1].h, 0.001f)
        assertTrue("a page from another file brings no ink of ours", out.strokesOn(1).isEmpty())
        assertEquals("the page after it keeps its marks", 1, out.strokesOn(2).size)
    }

    @Test
    fun `an imported page can be turned before it is committed`() {
        val out = PageArrangement.remapInk(
            doc(1), listOf(imported(50).copy(quarterTurns = 1)), ids()
        )
        assertEquals(842f, out.pageSizes[0].w, 0.001f)
        assertEquals(595f, out.pageSizes[0].h, 0.001f)
    }

    @Test
    fun `importing counts as a change`() {
        assertFalse(
            PageArrangement.isUnchanged(listOf(PlannedPage(0, 0), imported(50)), 1)
        )
    }
}
