package com.inkslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A rearrangement of pages, and a device that has not caught up with it.
 *
 * Everything here is one question asked several ways: when one copy of a document had its pages
 * moved and the other was written on in the meantime, does the merge put every mark on the page it
 * was written on - wherever that page went - on both devices, whichever merges first?
 */
class PageStructureTest {

    private fun stroke(id: String, page: Int, at: Long = 100, color: Int = 0xFF000000.toInt()) = Stroke(
        id = id, kind = Stroke.Kind.FREEHAND, color = color, baseWidth = 2f,
        points = listOf(InkPoint(10f, 20f, 2f), InkPoint(30f, 40f, 2f)),
        pageIndex = page, updatedUtc = at
    )

    /** Three pages, one mark on each named for the page it started on. */
    private fun threePages() = InkDocument(
        docId = "d",
        source = InkDocument.SourceRef(name = "doc.pdf", kind = "pdf", pageCount = 3),
        pageSizes = List(3) { InkDocument.PageSize(600f, 800f) },
        pages = (0 until 3).associate { it.toString() to listOf(stroke("p$it", it)) }
    )

    private fun plan(vararg sources: Int) =
        sources.mapIndexed { i, s -> PlannedPage(source = s, uid = i.toLong()) }

    private val sizes: (Int) -> Pair<Float, Float> = { 600f to 800f }

    private fun rearranged(doc: InkDocument, vararg sources: Int, at: Long = 1_000, to: String = "L$at") =
        PageStructure.restructure(doc, plan(*sources), sizes, at = at, to = to).first

    /** Page index of every mark, by what the mark looks like rather than its id. */
    private fun where(doc: InkDocument): Map<Int, List<Int>> =
        doc.pages.mapKeys { it.key.toInt() }.mapValues { (_, s) -> s.map { it.color }.sorted() }
            .filterValues { it.isNotEmpty() }.toSortedMap()

    private fun assertMergesBothWays(a: InkDocument, b: InkDocument): InkDocument {
        val ab = a.mergeWith(b)
        val ba = b.mergeWith(a)
        assertEquals("the layout must not depend on who merges", ab.layout, ba.layout)
        assertEquals(
            "the marks must not depend on who merges",
            ab.pages.mapValues { p -> p.value.sortedBy { it.id } },
            ba.pages.mapValues { p -> p.value.sortedBy { it.id } }
        )
        assertEquals(ab.bookmarks, ba.bookmarks)
        assertEquals(ab.pageSizes, ba.pageSizes)
        return ab
    }

    @Test
    fun `the same rearrangement gives the same marks on every device`() {
        val change = PageStructure.restructure(threePages(), plan(2, 0, 1), sizes, at = 1_000, to = "L").second
        val here = PageStructure.apply(threePages(), change)
        val there = PageStructure.apply(threePages(), change)
        assertEquals(here, there)
        assertEquals(listOf("p2", "p0", "p1").map { PageStructure.remappedId(it, change, 0) },
            (0 until 3).map { here.strokesOn(it).single().id })
    }

    @Test
    fun `a mark drawn on the other device before it heard lands on its page wherever that went`() {
        val start = threePages()
        val moved = rearranged(start, 2, 0, 1)
        // Page 0 of the old order is page 1 of the new one.
        val behind = start.copy(pages = start.pages + ("0" to start.strokesOn(0) + stroke("late", 0, color = 7)))

        val merged = assertMergesBothWays(moved, behind)
        assertEquals("L1000", merged.layout)
        assertTrue(merged.strokesOn(1).any { it.color == 7 })
        assertEquals(4, merged.totalStrokes)
    }

    @Test
    fun `an erase made before hearing of the rearrangement still erases`() {
        val start = threePages()
        val moved = rearranged(start, 2, 0, 1)
        val behind = start.withPage(0, emptyList(), "tablet")

        val merged = assertMergesBothWays(moved, behind)
        assertTrue("the erased mark must stay erased on its new page", merged.strokesOn(1).isEmpty())
        assertEquals(2, merged.totalStrokes)
    }

    @Test
    fun `an edit made before hearing wins over the moved copy, and one made after wins over it`() {
        val start = threePages()
        val moved = rearranged(start, 1, 0, 2)
        val behind = start.copy(pages = start.pages + ("0" to listOf(stroke("p0", 0, at = 2_000, color = 9))))
        val merged = assertMergesBothWays(moved, behind)
        assertEquals(listOf(9), merged.strokesOn(1).map { it.color })

        // And the other way: recoloured on the rearranged copy after the stale edit.
        val recoloured = moved.copy(pages = moved.pages + ("1" to moved.strokesOn(1).map { it.copy(color = 5, updatedUtc = 3_000) }))
        assertEquals(listOf(5), assertMergesBothWays(recoloured, behind).strokesOn(1).map { it.color })
    }

    @Test
    fun `a mark drawn before hearing appears on every copy of a duplicated page`() {
        val start = threePages()
        val moved = rearranged(start, 0, 0, 1, 2)
        val behind = start.copy(pages = start.pages + ("0" to start.strokesOn(0) + stroke("late", 0, color = 7)))
        val merged = assertMergesBothWays(moved, behind)
        assertEquals(listOf(listOf(0xFF000000.toInt(), 7), listOf(0xFF000000.toInt(), 7)),
            listOf(where(merged)[0], where(merged)[1]))
        assertEquals(6, merged.totalStrokes)
    }

    @Test
    fun `a mark drawn before hearing on a page that was removed goes with the page`() {
        val start = threePages()
        val moved = rearranged(start, 1, 2)
        val behind = start.copy(pages = start.pages + ("0" to start.strokesOn(0) + stroke("late", 0, color = 7)))
        val merged = assertMergesBothWays(moved, behind)
        assertEquals(2, merged.totalStrokes)
        assertTrue(merged.pages.values.flatten().none { it.color == 7 })
    }

    @Test
    fun `a copy two rearrangements behind is brought all the way forward`() {
        val start = threePages()
        val once = rearranged(start, 2, 0, 1, at = 1_000)
        val twice = rearranged(once, 2, 0, 1, at = 2_000)
        val behind = start.copy(pages = start.pages + ("0" to start.strokesOn(0) + stroke("late", 0, color = 7)))

        val merged = assertMergesBothWays(twice, behind)
        assertEquals("L2000", merged.layout)
        // Old page 0 -> page 1 -> page 2.
        assertTrue(merged.strokesOn(2).any { it.color == 7 })
        assertEquals(4, merged.totalStrokes)
    }

    @Test
    fun `turning a page turns a mark drawn before hearing of the turn`() {
        val start = threePages()
        val turned = PageStructure.restructure(
            start, plan(0, 1, 2).mapIndexed { i, p -> if (i == 0) p.copy(quarterTurns = 1) else p },
            sizes, at = 1_000, to = "T"
        ).first
        val behind = start.copy(pages = start.pages + ("0" to start.strokesOn(0) + stroke("late", 0, color = 7)))
        val merged = assertMergesBothWays(turned, behind)
        val late = merged.strokesOn(0).single { it.color == 7 }
        val original = merged.strokesOn(0).single { it.color != 7 }
        assertEquals("both marks on the page turned the same way", original.points, late.points)
    }

    @Test
    fun `bookmarks added before hearing follow their page`() {
        val start = threePages()
        val moved = rearranged(start, 2, 0, 1)
        val behind = start.withBookmarkAdded(0, "Question 1")
        val merged = assertMergesBothWays(moved, behind)
        assertEquals(listOf(1), merged.bookmarks.map { it.page })
    }

    @Test
    fun `devices that both rearranged while apart settle on the later arrangement`() {
        val start = threePages()
        val here = rearranged(start, 2, 1, 0, at = 1_000, to = "A")
        val there = rearranged(start, 1, 2, at = 2_000, to = "B")
        val merged = assertMergesBothWays(here, there)
        assertEquals("B", merged.layout)
        assertEquals(2, merged.totalStrokes)
    }

    @Test
    fun `a copy written by a build that dropped the layout is not moved a second time`() {
        val start = threePages()
        val moved = rearranged(start, 2, 0, 1)
        val oldBuild = moved.copy(layout = "", structureHistory = emptyList())
            .let { it.copy(pages = it.pages + ("0" to it.strokesOn(0) + stroke("late", 0, color = 7))) }

        val merged = assertMergesBothWays(moved, oldBuild)
        assertEquals(4, merged.totalStrokes)
        assertTrue(merged.strokesOn(0).any { it.color == 7 })
        assertEquals(listOf("p2", "p0", "p1").map { id -> moved.strokesOn(listOf("p2", "p0", "p1").indexOf(id)).single().id },
            (0 until 3).map { merged.strokesOn(it).single { s -> s.color != 7 }.id })
    }

    @Test
    fun `tombstones do not multiply with every rearrangement`() {
        var doc = threePages()
        doc = doc.copy(deleted = mapOf("gone" to 500L, "also-gone" to 900L))
        val counts = mutableListOf<Int>()
        for (i in 1..8) {
            doc = rearranged(doc, 2, 0, 1, at = 10_000_000L * i)
            counts += doc.deleted.size
        }
        val growth = counts.zipWithNext { a, b -> b - a }
        assertTrue("each rearrangement retires the marks it moves and no more: $counts", growth.all { it <= 3 })
    }

    @Test
    fun `layout and history survive being written out`() {
        val moved = rearranged(threePages(), 2, 0, 1)
        val back = InkDocument.parse(moved.serialize())
        assertNotNull(back)
        assertEquals(moved.layout, back!!.layout)
        assertEquals(moved.structureHistory, back.structureHistory)
    }

    @Test
    fun `merging the same thing twice changes nothing`() {
        val start = threePages()
        val moved = rearranged(start, 2, 0, 1)
        val behind = start.copy(pages = start.pages + ("0" to start.strokesOn(0) + stroke("late", 0, color = 7)))
        val once = moved.mergeWith(behind)
        assertEquals(once.pages, once.mergeWith(behind).pages)
        assertEquals(once.pages, once.mergeWith(moved).pages)
    }
}
