package com.inkslate.core

import com.inkslate.core.DocumentShelf.Filter
import com.inkslate.core.DocumentShelf.Item
import com.inkslate.core.DocumentShelf.Query
import com.inkslate.core.DocumentShelf.Sort
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Sorting, narrowing and grouping the documents on Home. */
class DocumentShelfTest {

    private val root = File("/lib/Classwork")
    private val roots = listOf(root)
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun item(path: String, ageDays: Int, annotated: Boolean = false, size: Long = 1000) =
        Item(File(root, path), now - ageDays * day, size, annotated)

    private val items = listOf(
        item("PHYS 161/Sheet 10.pdf", 1, annotated = true),
        item("PHYS 161/Sheet 2.pdf", 3),
        item("MATH222/Quiz.png", 20, annotated = true),
        item("Loose 222.pdf", 0),
    )

    @Test
    fun `names sort the way people count`() {
        val names = DocumentShelf.apply(items, Query(sort = Sort.NAME), roots, now).map { it.name }
        assertEquals(listOf("Loose 222.pdf", "Quiz.png", "Sheet 2.pdf", "Sheet 10.pdf"), names)
    }

    @Test
    fun `filters of one kind widen, of different kinds narrow`() {
        fun names(vararg f: Filter) =
            DocumentShelf.apply(items, Query(filters = f.toSet()), roots, now).map { it.name }.toSet()
        assertEquals(setOf("Sheet 10.pdf", "Quiz.png"), names(Filter.ANNOTATED))
        assertEquals(items.map { it.name }.toSet(), names(Filter.ANNOTATED, Filter.UNTOUCHED))
        assertEquals(setOf("Sheet 10.pdf"), names(Filter.ANNOTATED, Filter.PDFS))
        assertEquals(setOf("Loose 222.pdf"), names(Filter.UNFILED))
    }

    @Test
    fun `within a folder and typed words narrow too`() {
        val inPhys = DocumentShelf.apply(items, Query(within = File(root, "PHYS 161")), roots, now)
        assertEquals(2, inPhys.size)
        val found = DocumentShelf.apply(items, Query(text = "phys 10"), roots, now)
        assertEquals(listOf("Sheet 10.pdf"), found.map { it.name })
    }

    @Test
    fun `grouped by class, a loose document goes under its own number`() {
        val sorted = DocumentShelf.apply(items, Query(sort = Sort.CLASS), roots, now)
        val groups = DocumentShelf.group(sorted, Sort.CLASS, roots, now).associate { g -> g.title to g.items.map { it.name } }
        assertEquals(listOf("Loose 222.pdf"), groups["222"])
        assertEquals(listOf("Quiz.png"), groups["MATH222"])
        assertEquals(2, groups["PHYS 161"]!!.size)
    }

    @Test
    fun `grouped by folder, the folder is named from the top of the library`() {
        val nested = Item(File(root, "PHYS 161/Homework/HW1.pdf"), now, 1, false)
        assertEquals("PHYS 161 / Homework", DocumentShelf.folderLabel(nested.file, roots))
        assertEquals("Classwork", DocumentShelf.folderLabel(File(root, "a.pdf"), roots))
    }
}
