package com.inkslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Filing worksheets into class folders by the course number in their names. */
class ClassCodesTest {

    private val lib = File("/lib/Classwork")
    private fun f(vararg path: String) = File(lib, path.joinToString(File.separator))

    @Test
    fun `course numbers are read the ways courses get written`() {
        assertEquals(listOf(ClassCodes.Code("161", "PHYS")), ClassCodes.codesIn("PHYS161 Weekly Sheet 3.pdf"))
        assertEquals(listOf(ClassCodes.Code("222", "MATH")), ClassCodes.codesIn("Math 222 - Quiz.pdf"))
        assertEquals(listOf(ClassCodes.Code("222", null)), ClassCodes.codesIn("HW4_222.pdf"))
        assertEquals(listOf(ClassCodes.Code("161", "PHYS")), ClassCodes.codesIn("PHYS 161"))
    }

    @Test
    fun `years, dates and long numbers are not courses`() {
        assertTrue(ClassCodes.codesIn("Notes 2026.pdf").isEmpty())
        assertTrue(ClassCodes.codesIn("IMG_20261008_101500.jpg").isEmpty())
        assertTrue(ClassCodes.codesIn("Sheet 3 2026-10-08.pdf").isEmpty())
    }

    @Test
    fun `loose documents go into the folder with their number`() {
        val folders = listOf(f("PHYS 161"), f("MATH222"), f("PHYS 161", "Homework"))
        val docs = listOf(f("PHYS161 Sheet 3.pdf"), f("HW4_222.pdf"), f("Essay.pdf"))
        val s = ClassCodes.suggest(docs, folders)
        assertEquals(2, s.size)
        assertEquals(f("MATH222"), s.first { it.document.name == "HW4_222.pdf" }.target)
        assertEquals(f("PHYS 161"), s.first { it.document.name == "PHYS161 Sheet 3.pdf" }.target)
    }

    @Test
    fun `a document already inside its class is left alone`() {
        val folders = listOf(f("PHYS 161"), f("PHYS 161", "Homework"))
        val docs = listOf(f("PHYS 161", "Homework", "PHYS161 HW1.pdf"))
        assertTrue(ClassCodes.suggest(docs, folders).isEmpty())
    }

    @Test
    fun `the letters choose between two courses with one number`() {
        val folders = listOf(f("MATH 161"), f("PHYS 161"))
        val s = ClassCodes.suggest(listOf(f("phys_161 lab.pdf")), folders).single()
        assertEquals(f("PHYS 161"), s.target)

        // Without letters it cannot tell, and says what else it could be.
        val unsure = ClassCodes.suggest(listOf(f("161 lab.pdf")), folders).single()
        assertEquals(1, unsure.alternatives.size)
    }

    @Test
    fun `filed in the wrong class, it is offered the right one`() {
        val folders = listOf(f("MATH222"), f("PHYS 161"))
        val s = ClassCodes.suggest(listOf(f("MATH222", "PHYS161 Sheet.pdf")), folders).single()
        assertEquals(f("PHYS 161"), s.target)
    }
}
