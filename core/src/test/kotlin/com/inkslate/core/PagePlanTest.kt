package com.inkslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rearranging pages, and what happens to the handwriting when they move.
 *
 * The failure here is silent and total: handwriting is stored per page index, so a plan that
 * rewrites the pages without rewriting the ink leaves every mark on the wrong page - and the
 * document still opens, still looks like a document, and is simply wrong.
 */
class PagePlanTest {

    private fun stroke(id: String, page: Int) = Stroke(
        id = id,
        kind = Stroke.Kind.FREEHAND,
        color = 0xFF000000.toInt(),
        baseWidth = 2f,
        pageIndex = page,
        points = listOf(InkPoint(10f, 20f, 2f), InkPoint(30f, 40f, 2f))
    )

    /** Three pages, one mark on each, named for the page it started on. */
    private fun threePages(): InkDocument {
        var doc = InkDocument.create("doc.pdf", "pdf", 3, 0L, "")
        for (page in 0 until 3) {
            doc = doc.withPage(page, listOf(stroke("p$page", page)), "test")
        }
        return doc.copy(
            pageSizes = List(3) { InkDocument.PageSize(612f, 792f) }
        )
    }

    private var minted = 0
    private fun newId(): String = "new-${++minted}"

    // ---- the plan itself -----------------------------------------------------

    @Test
    fun `an untouched plan is recognised as changing nothing`() {
        assertTrue(PagePlan.isUnchanged(PagePlan.identity(5), 5))
    }

    @Test
    fun `a reordered plan is not unchanged`() {
        val plan = PagePlan.moved(PagePlan.identity(3), from = 0, to = 2)
        assertFalse(PagePlan.isUnchanged(plan, 3))
        assertEquals(listOf(1, 2, 0), plan.map { it.source })
    }

    @Test
    fun `a turned page counts as a change even in the same order`() {
        val plan = PagePlan.turned(PagePlan.identity(3), at = 1, quarterTurns = 1)
        assertFalse(PagePlan.isUnchanged(plan, 3))
    }

    @Test
    fun `a duplicate sits straight after its original`() {
        val plan = PagePlan.duplicated(PagePlan.identity(3), at = 1, uid = 99L)
        assertEquals(listOf(0, 1, 1, 2), plan.map { it.source })
        assertEquals(99L, plan[2].uid)
    }

    /** A document with no pages is not a document. */
    @Test
    fun `the last page cannot be removed`() {
        val one = PagePlan.identity(1)
        assertEquals(one, PagePlan.removed(one, 0))
        assertEquals(1, PagePlan.removed(PagePlan.identity(2), 0).size)
    }

    @Test
    fun `turns wrap round rather than accumulating`() {
        var plan = PagePlan.identity(1)
        repeat(4) { plan = PagePlan.turned(plan, 0, 1) }
        assertEquals(0, plan[0].quarterTurns)
    }

    // ---- the ink follows -----------------------------------------------------

    @Test
    fun `moving a page takes its handwriting with it`() {
        val ink = threePages()
        val plan = PagePlan.moved(PagePlan.identity(3), from = 0, to = 2)
        val remapped = PagePlan.remapInk(ink, plan, ::newId)

        // Page 0 went to the end, so what was on it is now on the last page.
        assertEquals(1, remapped.strokesOn(2).size)
        assertEquals(1, remapped.strokesOn(0).size)
        assertEquals(3, remapped.totalStrokes)
    }

    @Test
    fun `a removed page takes its handwriting with it`() {
        val ink = threePages()
        val plan = PagePlan.removed(PagePlan.identity(3), at = 1)
        val remapped = PagePlan.remapInk(ink, plan, ::newId)

        assertEquals(2, remapped.totalStrokes)
        assertEquals(2, remapped.pageSizes.size)
    }

    @Test
    fun `a duplicated page's handwriting is duplicated with it`() {
        val ink = threePages()
        val plan = PagePlan.duplicated(PagePlan.identity(3), at = 0, uid = 77L)
        val remapped = PagePlan.remapInk(ink, plan, ::newId)

        assertEquals(4, remapped.totalStrokes)
        assertEquals(1, remapped.strokesOn(0).size)
        assertEquals(1, remapped.strokesOn(1).size)
    }

    @Test
    fun `a blank page inserted in the middle carries no ink and shifts the rest`() {
        val ink = threePages()
        val plan = PagePlan.inserted(
            PagePlan.identity(3), at = 1, pages = listOf(PlannedPage(source = -1, uid = 50L))
        )
        val remapped = PagePlan.remapInk(ink, plan, ::newId)

        assertEquals(4, remapped.pageSizes.size)
        assertTrue("the new page is blank", remapped.strokesOn(1).isEmpty())
        assertEquals(3, remapped.totalStrokes)
    }

    /**
     * Turning a page turns what is written on it.
     *
     * Otherwise the marks stay where they were and the page rotates out from under them, which
     * leaves the handwriting beside the work rather than on it.
     */
    @Test
    fun `turning a page turns its handwriting too`() {
        val ink = threePages()
        val plan = PagePlan.turned(PagePlan.identity(3), at = 0, quarterTurns = 1)
        val remapped = PagePlan.remapInk(ink, plan, ::newId)

        val moved = remapped.strokesOn(0).single()
        val original = ink.strokesOn(0).single()
        assertTrue(
            "the mark should have moved with the page",
            moved.points[0].x != original.points[0].x ||
                moved.points[0].y != original.points[0].y
        )
        // A quarter turn swaps the page's own dimensions.
        assertEquals(792f, remapped.pageSizes[0].w, 0.5f)
        assertEquals(612f, remapped.pageSizes[0].h, 0.5f)
    }

    /**
     * Every stroke is re-issued and the old ones tombstoned.
     *
     * A device that has not seen the rearrangement will merge its copy back in. Tombstones are
     * unioned from both sides of a merge, so retiring the old ids is what stops that stale copy
     * scattering the old marks across the new page order.
     */
    @Test
    fun `rearranging retires every old stroke id`() {
        val ink = threePages()
        val plan = PagePlan.moved(PagePlan.identity(3), from = 2, to = 0)
        val remapped = PagePlan.remapInk(ink, plan, ::newId)

        for (page in 0 until 3) {
            for (s in ink.strokesOn(page)) {
                assertTrue("${s.id} should be tombstoned", s.id in remapped.deleted)
            }
        }
        assertTrue(
            "and nothing should still be wearing an old id",
            remapped.pages.values.flatten().none { it.id.startsWith("p") }
        )
    }

    @Test
    fun `a stale copy merged back in cannot resurrect the old order`() {
        val ink = threePages()
        val plan = PagePlan.moved(PagePlan.identity(3), from = 0, to = 2)
        val remapped = PagePlan.remapInk(ink, plan, ::newId)

        // The other device still has the document as it was, and syncs it back.
        val merged = remapped.mergeWith(ink)
        assertEquals(
            "the rearranged document is what survives",
            remapped.totalStrokes,
            merged.totalStrokes
        )
    }

    @Test
    fun `bookmarks follow their pages, and go when their page does`() {
        val ink = threePages()
            .withBookmarkAdded(0, "first")
            .withBookmarkAdded(2, "last")

        val moved = PagePlan.remapInk(
            ink, PagePlan.moved(PagePlan.identity(3), from = 0, to = 2), ::newId
        )
        assertEquals(setOf(1, 2), moved.bookmarks.map { it.page }.toSet())

        val dropped = PagePlan.remapInk(
            ink, PagePlan.removed(PagePlan.identity(3), at = 0), ::newId
        )
        assertEquals(listOf("last"), dropped.bookmarks.map { it.label })
    }

    @Test
    fun `the recorded page count follows the plan`() {
        val ink = threePages()
        val plan = PagePlan.removed(PagePlan.identity(3), at = 0)
        assertEquals(2, PagePlan.remapInk(ink, plan, ::newId).source.pageCount)
    }
}
