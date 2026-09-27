package com.inkslate

import com.inkslate.core.InkPoint
import com.inkslate.core.Stroke
import com.inkslate.pdf.InkExporter
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tablet writes text given a web address as a real PDF link - once, however often it saves -
 * and the check that a page already carries its ink is not thrown by it.
 */
class LinkExportTest {

    private fun text(link: String?) = Stroke(
        id = "t1", kind = Stroke.Kind.TEXT, color = 0xFF000000.toInt(), baseWidth = 1f,
        points = listOf(InkPoint(72f, 100f, 1f)), text = "Course page", textSize = 14f, link = link, updatedUtc = 5L
    )

    private fun uris(page: PDPage): List<String> =
        ((page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray) ?: COSArray()).toList()
            .mapNotNull { (it as? COSDictionary) ?: (it as? com.tom_roush.pdfbox.cos.COSObject)?.getObject() as? COSDictionary }
            .filter { it.getNameAsString(COSName.SUBTYPE) == "Link" }
            .mapNotNull { (it.getDictionaryObject(COSName.A) as? COSDictionary)?.getString(COSName.getPDFName("URI")) }

    @Test
    fun `a linked text box is one link, and the page still reads as current`() {
        PDDocument().use { pdf ->
            val page = PDPage().also { pdf.addPage(it) }
            // Ink drawn as the tablet draws it, and the link written beside it - twice, as a
            // second save would. (Text itself needs Android's fonts, which the JVM lacks.)
            val mark = Stroke(id = "m", kind = Stroke.Kind.FREEHAND, color = 0xFF000000.toInt(), baseWidth = 2f,
                points = (0..5).map { InkPoint(100f + it * 5f, 300f, 2f) }, updatedUtc = 5L)
            InkExporter.annotatePageGrouped(pdf, page, listOf(mark), signature = 42L)
            repeat(2) { InkExporter.writeLinks(page, listOf(mark, text("https://example.com/course")), InkExporter.toUserFor(page)) }
            assertEquals(listOf("https://example.com/course"), uris(page))
            assertTrue("the link must not count as a second ink annotation", InkExporter.carriesCurrentInk(page, true, 42L))
            InkExporter.writeLinks(page, listOf(mark, text(null)), InkExporter.toUserFor(page))
            assertEquals(emptyList<String>(), uris(page))
        }
    }
}
