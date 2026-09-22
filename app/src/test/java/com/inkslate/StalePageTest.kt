package com.inkslate

import com.inkslate.core.BrushType
import com.inkslate.core.InkDocument
import com.inkslate.core.InkFormat
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A partial save has to leave every page showing its ink, whatever it was told had changed.
 *
 * A real homework file went out with 341 marks on a page and one of them drawn: the tablet
 * rebuilt only the pages its record said had changed, the record said that page was current, and
 * the embedded copy - which is what the app itself draws from - hid it completely. Every other
 * reader, and every portal, got a blank page.
 */
class StalePageTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun blankPdf(pages: Int): File {
        val f = temp.newFile("sheet.pdf")
        PDDocument().use { pdf ->
            repeat(pages) { pdf.addPage(PDPage()) }
            pdf.save(f)
        }
        return f
    }

    private fun mark(id: String, y: Float) = Stroke(
        id = id,
        kind = Stroke.Kind.FREEHAND,
        color = 0xFF000000.toInt(),
        baseWidth = 2f,
        brush = BrushType.BALLPOINT,
        points = (0..10).map { InkPoint(100f + it * 5f, y, 2f) },
        updatedUtc = 1000L + id.hashCode().toLong().and(0xffff)
    )

    private fun ink(vararg pages: Pair<Int, List<Stroke>>): InkDocument =
        pages.fold(InkDocument.create("sheet.pdf", "pdf", 3, 0L, "")) { d, (p, s) ->
            d.withPage(p, s, "test")
        }

    private fun ourAnnotations(file: File, page: Int): List<COSDictionary> =
        PDDocument.load(file).use { pdf ->
            val annots = pdf.getPage(page).cosObject.getDictionaryObject(COSName.ANNOTS)
                as? COSArray ?: return@use emptyList()
            (0 until annots.size()).mapNotNull { annots.getObject(it) as? COSDictionary }
                .filter { it.getString(COSName.T) == "InkSlate" }
        }

    private fun signatureOn(file: File, page: Int): String? =
        ourAnnotations(file, page).singleOrNull()?.getString(COSName.getPDFName("InkSlateSig"))

    @Test
    fun `a page the record wrongly calls current is still redrawn`() {
        val doc = blankPdf(3)
        val first = ink(0 to listOf(mark("a", 100f)), 1 to listOf(mark("b", 100f)))
        InkExporter.exportPdf(doc, doc, first, InkFormat.ANNOTATIONS).getOrThrow()
        assertEquals(first.pageSignature(1).toString(), signatureOn(doc, 1))

        // Page 1 gains marks, and the save is told only page 0 changed.
        val second = first.withPage(1, listOf(mark("b", 100f), mark("c", 200f)), "test")
        InkExporter.exportPdf(
            doc, doc, second, InkFormat.ANNOTATIONS, rebuild = setOf(0)
        ).getOrThrow()

        assertEquals(
            "page 1's drawing should be of the ink it has now",
            second.pageSignature(1).toString(), signatureOn(doc, 1)
        )
    }

    @Test
    fun `a page whose ink was all erased loses its drawing even when not named`() {
        val doc = blankPdf(3)
        val first = ink(2 to listOf(mark("a", 100f)))
        InkExporter.exportPdf(doc, doc, first, InkFormat.ANNOTATIONS).getOrThrow()
        assertEquals(1, ourAnnotations(doc, 2).size)

        val erased = first.withPage(2, emptyList(), "test")
        InkExporter.exportPdf(
            doc, doc, erased, InkFormat.ANNOTATIONS, rebuild = emptySet()
        ).getOrThrow()
        assertTrue(ourAnnotations(doc, 2).isEmpty())
    }

    @Test
    fun `a flattened export does not keep the document's old drawing on top`() {
        val doc = blankPdf(3)
        val first = ink(0 to listOf(mark("a", 100f), mark("gone", 300f)))
        InkExporter.exportPdf(doc, doc, first, InkFormat.ANNOTATIONS).getOrThrow()

        // "gone" was erased; a flattened copy must not show it through the old annotation.
        val now = first.withPage(0, listOf(mark("a", 100f)), "test")
        val out = File(temp.root, "flat.pdf")
        InkExporter.exportPdf(doc, out, now, InkFormat.FLATTENED).getOrThrow()
        assertTrue(ourAnnotations(out, 0).isEmpty())

        val picked = File(temp.root, "picked.pdf")
        InkExporter.exportPdfPages(doc, picked, now.withPage(0, emptyList(), "t"), InkFormat.FLATTENED, listOf(0))
            .getOrThrow()
        assertTrue(ourAnnotations(picked, 0).isEmpty())
    }
}
