package com.inksheets.desktop

import com.inksheets.ui.Transcriber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

/**
 * A part's reading is filed by its music, not by its marks: handwriting saved into the PDF (an
 * update appended after its first end-of-file mark) must not make it a file to read again.
 */
class ReadingKeyTest {
    @Test
    fun `marking a pdf keeps its reading`() {
        val dir = File("build/reading-key").apply { deleteRecursively(); mkdirs() }
        val pdf = File(dir, "part.pdf")
        pdf.writeBytes("%PDF-1.7\n1 0 obj << >> endobj\ntrailer << >>\n%%EOF\n".toByteArray())
        val before = Transcriber.idOf(pdf)
        Thread.sleep(20)
        // What InkSlate's incremental save adds: new objects, a new trailer, another %%EOF.
        pdf.appendBytes("9 0 obj << /Ink true >> endobj\ntrailer << /Prev 9 >>\n%%EOF\n".toByteArray())
        pdf.setLastModified(System.currentTimeMillis() + 5_000)
        assertEquals(before, Transcriber.idOf(pdf))
        // Another edition of the music is another file.
        val other = File(dir, "other.pdf")
        other.writeBytes("%PDF-1.7\n1 0 obj << /Changed 1 >> endobj\ntrailer << >>\n%%EOF\n".toByteArray())
        assertNotEquals(before, Transcriber.idOf(other))
    }
}
