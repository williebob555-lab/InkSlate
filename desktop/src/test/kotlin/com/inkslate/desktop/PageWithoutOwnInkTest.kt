package com.inkslate.desktop

import androidx.compose.ui.graphics.toPixelMap
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSString
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationRubberStamp
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The picture of a page leaves out the handwriting this app saved into it.
 *
 * A saved document carries every mark twice - as annotations for other readers, and as the
 * editable copy drawn on top here. Rendering the annotations too put a flattened twin under each
 * mark, which showed as duplicated, stretched handwriting on a whiteboard that had grown.
 */
class PageWithoutOwnInkTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** A page with a black square drawn by an annotation, tagged as ours or not. */
    private fun pageWithSquare(tagged: Boolean): File {
        val file = File(temp.newFolder(), "page.pdf")
        PDDocument().use { pdf ->
            val page = PDPage(PDRectangle(200f, 200f))
            pdf.addPage(page)
            val rect = PDRectangle(50f, 50f, 100f, 100f)
            val ap = PDAppearanceStream(pdf).apply { bBox = rect }
            PDPageContentStream(pdf, ap).use { cs ->
                cs.setNonStrokingColor(0f, 0f, 0f)
                cs.addRect(50f, 50f, 100f, 100f)
                cs.fill()
            }
            val annot = PDAnnotationRubberStamp().apply {
                this.rectangle = rect
                appearance = PDAppearanceDictionary().apply { setNormalAppearance(ap) }
                if (tagged) {
                    cosObject.setItem(COSName.T, COSString("InkSlate"))
                    cosObject.setItem(COSName.getPDFName("InkSlateObject"), COSString("InkSlate"))
                }
            }
            page.annotations = listOf(annot)
            pdf.save(file)
        }
        return file
    }

    private fun centreIsDark(file: File): Boolean {
        val source = DesktopSources.open(file)!!
        source.use {
            val picture = it.render(0, 200)!!
            val pixels = picture.toPixelMap()
            val c = pixels[picture.width / 2, picture.height / 2]
            return (c.red + c.green + c.blue) / 3f < 0.5f
        }
    }

    @Test
    fun `handwriting this app saved into the page is not in the picture of it`() {
        assertTrue(
            "the page picture must not carry our own saved ink",
            !centreIsDark(pageWithSquare(tagged = true))
        )
    }

    @Test
    fun `anyone else's annotations still show`() {
        assertTrue(
            "an annotation someone else added is part of the page",
            centreIsDark(pageWithSquare(tagged = false))
        )
    }
}
