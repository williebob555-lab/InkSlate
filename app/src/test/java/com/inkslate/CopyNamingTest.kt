package com.inkslate

import com.inkslate.data.CopyNaming
import com.inkslate.data.SavePrefs
import com.inkslate.data.SaveSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Copy naming has one hard requirement: never hand back a name that already exists.
 * Getting this wrong silently destroys a previous attempt at an assignment.
 */
class CopyNamingTest {

    private val suffix = SaveSettings(copyNaming = CopyNaming.SUFFIX, copySuffix = "_annotated")

    @Test
    fun `suffix naming appends before the extension`() {
        val name = SavePrefs.copyTargetName("homework.pdf", suffix) { false }
        assertEquals("homework_annotated.pdf", name)
    }

    @Test
    fun `collisions get a number rather than overwriting`() {
        val existing = setOf("homework_annotated.pdf")
        val name = SavePrefs.copyTargetName("homework.pdf", suffix) { it in existing }
        assertEquals("homework_annotated (2).pdf", name)
    }

    @Test
    fun `repeated collisions keep counting up`() {
        val existing = setOf(
            "homework_annotated.pdf",
            "homework_annotated (2).pdf",
            "homework_annotated (3).pdf"
        )
        val name = SavePrefs.copyTargetName("homework.pdf", suffix) { it in existing }
        assertEquals("homework_annotated (4).pdf", name)
    }

    @Test
    fun `never returns a name that already exists`() {
        // the guarantee that matters: whatever is on disk, the result is free
        val existing = mutableSetOf<String>()
        repeat(30) {
            val name = SavePrefs.copyTargetName("lab.pdf", suffix) { it in existing }
            assertTrue("collided on attempt $it: $name", name !in existing)
            existing += name
        }
    }

    @Test
    fun `increment naming does not collide with itself`() {
        val settings = SaveSettings(copyNaming = CopyNaming.INCREMENT)
        val first = SavePrefs.copyTargetName("essay.pdf", settings) { false }
        val second = SavePrefs.copyTargetName("essay.pdf", settings) { it == first }
        assertNotEquals(first, second)
    }

    @Test
    fun `files without an extension are handled`() {
        val name = SavePrefs.copyTargetName("scan", suffix) { false }
        assertEquals("scan_annotated", name)
    }

    @Test
    fun `dotfiles are not mistaken for extensions`() {
        // ".gitignore" has no stem before the dot; treating it as one would produce "_annotated"
        val name = SavePrefs.copyTargetName(".hidden", suffix) { false }
        assertEquals(".hidden_annotated", name)
    }

    @Test
    fun `timestamp naming preserves the extension`() {
        val settings = SaveSettings(copyNaming = CopyNaming.TIMESTAMP)
        val name = SavePrefs.copyTargetName("quiz.pdf", settings) { false }
        assertTrue(name.startsWith("quiz_"))
        assertTrue(name.endsWith(".pdf"))
    }
}
