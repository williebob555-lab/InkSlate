package com.inkslate.desktop

import com.inkslate.core.InkDocument
import com.inkslate.core.InkPoint
import com.inkslate.core.Links
import com.inkslate.core.SaveMode
import com.inkslate.core.SaveSettings
import com.inkslate.core.Stroke
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Text given a web address is a real link in the saved PDF, found again by any reader - and by InkSlate. */
class LinkTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun linked(link: String?) = InkDocument.create("Sheet.pdf", "pdf", 1, 0L, "").withPage(
        0,
        listOf(Stroke(
            id = "t1", kind = Stroke.Kind.TEXT, color = 0xFF000000.toInt(), baseWidth = 1f,
            points = listOf(InkPoint(72f, 100f, 1f)), text = "Course page", textSize = 14f, link = link,
            updatedUtc = System.nanoTime()
        )),
        "test"
    )

    private fun linksIn(pdf: File): List<String> = Loader.loadPDF(pdf).use { d ->
        d.getPage(0).annotations.filterIsInstance<PDAnnotationLink>().mapNotNull { (it.action as? PDActionURI)?.uri }
    }

    @Test
    fun `an address is typed however a person types it`() {
        assertEquals("https://example.com", Links.normalise("example.com"))
        assertEquals("https://example.com/a", Links.normalise(" https://example.com/a "))
        assertEquals("mailto:me@school.edu", Links.normalise("me@school.edu"))
        assertEquals(null, Links.normalise("  "))
        assertTrue(!Links.safe("javascript:alert(1)"))
        assertTrue(!Links.safe("file:///C:/Windows"))
    }

    @Test
    fun `a linked text box saves as a link, once, and goes when the link does`() {
        for (format in com.inkslate.core.InkFormat.entries) {
            val source = BlankDocumentFactory.create(temp.newFolder(), BlankDocumentFactory.Spec(name = format.name)).getOrThrow()
            val settings = SaveSettings(mode = SaveMode.OVERWRITE, inkFormat = format, backupOnOverwrite = false)
            DocumentExport.save(source, linked("https://example.com/course"), settings)
            assertEquals(format.name, listOf("https://example.com/course"), linksIn(source))
            // Saved again: still one, not two.
            DocumentExport.save(source, linked("https://example.com/course"), settings)
            assertEquals(format.name, listOf("https://example.com/course"), linksIn(source))
            // And InkSlate itself finds it, where the text is.
            val found = PdfSource(source).links(0)
            assertEquals(1, found.size)
            assertTrue("the link covers the text: $found", found.single().contains(80f, 108f))
            // The link taken off: gone from the file too.
            DocumentExport.save(source, linked(null), settings)
            assertEquals(format.name, emptyList<String>(), linksIn(source))
        }
    }
}
