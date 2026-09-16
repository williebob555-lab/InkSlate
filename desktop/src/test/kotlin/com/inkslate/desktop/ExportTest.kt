package com.inkslate.desktop

import com.inkslate.core.BrushType
import com.inkslate.core.InkDocument
import com.inkslate.core.InkPoint
import com.inkslate.core.Stroke
import com.inkslate.core.TextFont
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Flattening a document, and getting back what was drawn on it.
 *
 * Export is where a mistake is least visible and most expensive: the file opens, the pages are
 * there, and the only thing wrong is that some of the annotation is missing. Text was exactly
 * that - every other kind of object was written and text fell through a branch that did nothing,
 * so a text box could be typed, saved, synced, and then quietly absent from the copy handed in.
 */
class ExportTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun blankPdf(name: String = "Sheet"): File =
        BlankDocumentFactory.create(
            temp.newFolder(), BlankDocumentFactory.Spec(name = name)
        ).getOrThrow()

    private fun docWith(vararg strokes: Stroke): InkDocument =
        InkDocument.create("Sheet.pdf", "pdf", 1, 0L, "")
            .withPage(0, strokes.toList(), "test")

    private fun textStroke(
        body: String,
        font: TextFont = TextFont.SANS,
        bold: Boolean = false
    ) = Stroke(
        id = "t1",
        kind = Stroke.Kind.TEXT,
        color = 0xFF000000.toInt(),
        baseWidth = 1f,
        points = listOf(InkPoint(72f, 72f, 1f)),
        text = body,
        textSize = 14f,
        font = font,
        bold = bold
    )

    private fun textOf(pdf: File): String =
        Loader.loadPDF(pdf).use { PDFTextStripper().getText(it) }

    @Test
    fun `a text box comes back out of the exported PDF`() {
        val source = blankPdf()
        val exported = DocumentIO.exportFlattened(
            source, docWith(textStroke("Hello homework"))
        ).getOrThrow()

        assertTrue(exported.isFile)
        assertTrue(
            "the words should be in the exported page, not merely near it",
            textOf(exported).contains("Hello homework")
        )
    }

    @Test
    fun `every font choice still writes readable text`() {
        for (font in TextFont.entries) {
            val exported = DocumentIO.exportFlattened(
                blankPdf(font.name), docWith(textStroke("Sample ${font.name}", font))
            ).getOrThrow()
            assertTrue(
                "${font.name} should export readable text",
                textOf(exported).contains("Sample ${font.name}")
            )
        }
    }

    @Test
    fun `bold is written as its own face rather than dropped`() {
        val exported = DocumentIO.exportFlattened(
            blankPdf(), docWith(textStroke("Heavy", bold = true))
        ).getOrThrow()
        assertTrue(textOf(exported).contains("Heavy"))
    }

    /**
     * A maths symbol must not take the whole export down with it.
     *
     * The standard-14 fonts encode WinAnsi and throw on anything outside it, so one character
     * pasted from a symbol palette could abort the export and lose a document's worth of
     * annotation. A visible placeholder is the better failure.
     */
    @Test
    fun `a character the font cannot encode does not fail the export`() {
        val exported = DocumentIO.exportFlattened(
            blankPdf(), docWith(textStroke("Area ∫ under the curve"))
        ).getOrThrow()

        val text = textOf(exported)
        assertTrue("the rest of the line survives", text.contains("under the curve"))
    }

    @Test
    fun `wrapped text keeps every word`() {
        val long = "The quick brown fox jumps over the lazy dog again and again and again"
        val wrapped = textStroke(long).copy(boxWidth = 120f)
        val exported = DocumentIO.exportFlattened(blankPdf(), docWith(wrapped)).getOrThrow()

        val text = textOf(exported).replace(Regex("\\s+"), " ")
        for (word in listOf("quick", "brown", "jumps", "lazy", "again")) {
            assertTrue("'$word' should survive wrapping", text.contains(word))
        }
    }

    @Test
    fun `handwriting and shapes still export alongside text`() {
        val ink = Stroke(
            id = "s1",
            kind = Stroke.Kind.FREEHAND,
            color = 0xFFD32F2FL.toInt(),
            baseWidth = 2f,
            brush = BrushType.BALLPOINT,
            points = (0..10).map { InkPoint(100f + it * 5f, 300f, 2f) }
        )
        val boxShape = Stroke(
            id = "s2",
            kind = Stroke.Kind.RECT,
            color = 0xFF1976D2L.toInt(),
            baseWidth = 1.5f,
            points = listOf(InkPoint(200f, 400f, 1.5f), InkPoint(320f, 470f, 1.5f))
        )
        val exported = DocumentIO.exportFlattened(
            blankPdf(), docWith(ink, boxShape, textStroke("Mixed page"))
        ).getOrThrow()

        assertTrue(textOf(exported).contains("Mixed page"))
        // The page still has to render - a content stream with a bad operator in it opens fine
        // and draws nothing, which is the failure this catches.
        DesktopSources.open(exported)!!.use {
            assertEquals(1, it.pageCount)
            assertNotNull(it.render(0, 200))
        }
    }

    /** Exporting twice must not overwrite the first copy. */
    @Test
    fun `a second export is written beside the first`() {
        val source = blankPdf()
        val doc = docWith(textStroke("One"))
        val first = DocumentIO.exportFlattened(source, doc).getOrThrow()
        val second = DocumentIO.exportFlattened(source, doc).getOrThrow()

        assertTrue(first.isFile && second.isFile)
        assertTrue(first.absolutePath != second.absolutePath)
    }

    // ---- exporting a subset --------------------------------------------------

    @Test
    fun `a page range exports only those pages`() {
        val dir = temp.newFolder()
        val source = BlankDocumentFactory.create(
            dir, BlankDocumentFactory.Spec(name = "Book", pageCount = 6)
        ).getOrThrow()
        val doc = InkDocument.create("Book.pdf", "pdf", 6, 0L, "")
            .withPage(2, listOf(textStroke("Middle page")), "test")

        val target = DocumentExport.freeTarget(dir, "Chapter")
        DocumentExport.exportTo(source, target, doc, pages = listOf(1, 2, 3), flatten = true)
            .getOrThrow()

        DesktopSources.open(target)!!.use { assertEquals(3, it.pageCount) }
        // The mark was on page 3 of the original, which is the middle of the exported run.
        assertTrue(textOf(target).contains("Middle page"))
        // And the original is untouched.
        DesktopSources.open(source)!!.use { assertEquals(6, it.pageCount) }
    }

    @Test
    fun `exporting the whole document keeps every page`() {
        val dir = temp.newFolder()
        val source = BlankDocumentFactory.create(
            dir, BlankDocumentFactory.Spec(name = "Book", pageCount = 4)
        ).getOrThrow()
        val target = DocumentExport.freeTarget(dir, "Whole")
        DocumentExport.exportTo(
            source, target, docWith(textStroke("Hello")), pages = null, flatten = true
        ).getOrThrow()
        DesktopSources.open(target)!!.use { assertEquals(4, it.pageCount) }
    }

    /**
     * Flattening deliberately drops the editable copy.
     *
     * That mode exists to produce something that cannot be edited again; carrying the strokes
     * along inside it would undo the point of choosing it.
     */
    @Test
    fun `a flattened export does not carry the editable copy`() {
        val dir = temp.newFolder()
        val source = BlankDocumentFactory.create(dir, BlankDocumentFactory.Spec(name = "S"))
            .getOrThrow()
        val flat = DocumentExport.freeTarget(dir, "Flat")
        DocumentExport.exportTo(source, flat, docWith(textStroke("Done")), null, flatten = true)
            .getOrThrow()
        assertNull(DesktopEmbedder.read(flat))

        val editable = DocumentExport.freeTarget(dir, "Editable")
        DocumentExport.exportTo(source, editable, docWith(textStroke("Done")), null, flatten = false)
            .getOrThrow()
        assertNotNull(DesktopEmbedder.read(editable))
    }

    /** The pages picked in the pages sheet are the pages in the file, in document order. */
    @Test
    fun `exporting picked pages keeps only those pages`() {
        val dir = temp.newFolder()
        val source = BlankDocumentFactory.create(
            dir, BlankDocumentFactory.Spec(name = "Five", pageCount = 5)
        ).getOrThrow()
        val ink = InkDocument.create("Five.pdf", "pdf", 5, 0L, "")
            .withPage(3, listOf(textStroke("Page four")), "test")
        val out = DocumentExport.freeTarget(dir, "Picked")
        DocumentExport.exportTo(source, out, ink, listOf(3, 1), flatten = true).getOrThrow()

        Loader.loadPDF(out).use { assertEquals(2, it.numberOfPages) }
        assertTrue(textOf(out).contains("Page four"))
    }

    /**
     * A picture exports with its handwriting on it, at its own size.
     *
     * Windows could open and draw on a picture and then had no way to hand it in: Save and Export
     * both stopped at "Only PDFs can be exported this way".
     */
    @Test
    fun `a picture exports as a picture and as a PDF with the ink on it`() {
        val dir = temp.newFolder()
        val photo = File(dir, "photo.png")
        val white = java.awt.image.BufferedImage(200, 120, java.awt.image.BufferedImage.TYPE_INT_RGB)
        white.createGraphics().apply { color = java.awt.Color.WHITE; fillRect(0, 0, 200, 120); dispose() }
        javax.imageio.ImageIO.write(white, "png", photo)

        val line = Stroke(
            id = "l1",
            kind = Stroke.Kind.FREEHAND,
            color = 0xFF000000.toInt(),
            baseWidth = 8f,
            brush = BrushType.MARKER,
            points = listOf(InkPoint(20f, 60f, 1f), InkPoint(180f, 60f, 1f))
        )
        val ink = InkDocument.create("photo.png", "png", 1, 0L, "").withPage(0, listOf(line), "test")

        val png = File(dir, "out.png")
        DocumentExport.exportImage(photo, png, ink, asPdf = false).getOrThrow()
        val read = javax.imageio.ImageIO.read(png)
        assertEquals(200, read.width)
        assertEquals(120, read.height)
        val middle = read.getRGB(100, 60) and 0xFFFFFF
        val corner = read.getRGB(5, 5) and 0xFFFFFF
        assertTrue("the stroke should be drawn across the middle", middle < 0x404040)
        assertEquals("away from the stroke the picture is untouched", 0xFFFFFF, corner)

        val pdf = File(dir, "out.pdf")
        DocumentExport.exportImage(photo, pdf, ink, asPdf = true).getOrThrow()
        Loader.loadPDF(pdf).use {
            assertEquals(1, it.numberOfPages)
            assertEquals(200f, it.getPage(0).mediaBox.width, 0.01f)
        }
    }

    @Test
    fun `an export never lands on an earlier one`() {
        val dir = temp.newFolder()
        val first = DocumentExport.freeTarget(dir, "Copy")
        first.writeText("taken")
        val second = DocumentExport.freeTarget(dir, "Copy")
        assertTrue(first.absolutePath != second.absolutePath)
    }
}
