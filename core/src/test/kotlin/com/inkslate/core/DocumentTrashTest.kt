package com.inkslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Deleting from Home is a trip to the trash, from which anything comes back for 30 days. */
class DocumentTrashTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a deleted document comes back to where it was`() {
        val root = temp.newFolder("Classwork")
        val doc = File(root, "PHYS 161/Sheet 3.pdf").apply { parentFile.mkdirs(); writeText("pdf") }
        val trash = DocumentTrash(root)
        val entry = trash.put(doc)
        assertFalse(doc.exists())
        assertEquals(listOf("Sheet 3.pdf"), trash.entries().map { it.name })

        val back = trash.restore(trash.entries().single())
        assertEquals(doc, back)
        assertEquals("pdf", back.readText())
        assertTrue(trash.entries().isEmpty())
        assertEquals("PHYS 161/Sheet 3.pdf".replace('/', File.separatorChar), entry.from)
    }

    @Test
    fun `its folder is made again, and a taken name is not overwritten`() {
        val root = temp.newFolder("Classwork")
        val doc = File(root, "Gone/Notes.pdf").apply { parentFile.mkdirs(); writeText("old") }
        val trash = DocumentTrash(root)
        trash.put(doc)
        doc.parentFile.deleteRecursively()
        assertEquals(doc, trash.restore(trash.entries().single()))

        trash.put(doc)
        doc.writeText("new")
        val back = trash.restore(trash.entries().single())
        assertEquals("Notes (restored).pdf", back.name)
        assertEquals("new", doc.readText())
    }

    @Test
    fun `folders go and come back whole`() {
        val root = temp.newFolder("Classwork")
        val folder = File(root, "MATH222").apply { mkdirs() }
        File(folder, "a.pdf").writeText("a")
        val trash = DocumentTrash(root)
        trash.put(folder)
        assertFalse(folder.exists())
        trash.restore(trash.entries().single())
        assertEquals("a", File(folder, "a.pdf").readText())
    }

    @Test
    fun `after thirty days it is gone for good`() {
        val root = temp.newFolder("Classwork")
        val trash = DocumentTrash(root)
        val day = 24L * 60 * 60 * 1000
        val now = 1_800_000_000_000L
        trash.put(File(root, "old.pdf").apply { writeText("x") }, now - 31 * day)
        trash.put(File(root, "new.pdf").apply { writeText("x") }, now - 2 * day)
        trash.purge(now)
        assertEquals(listOf("new.pdf"), trash.entries().map { it.name })
    }

    @Test
    fun `the trash itself is hidden from listings`() {
        assertTrue(DocumentTrash.DIR.startsWith("."))
    }
}
