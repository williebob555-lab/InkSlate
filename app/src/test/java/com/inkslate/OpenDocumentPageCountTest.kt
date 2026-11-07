package com.inkslate

import android.graphics.Bitmap
import android.graphics.RectF
import com.inkslate.data.InkDocument
import com.inkslate.data.OpenDocument
import com.inkslate.pdf.PageDim
import com.inkslate.pdf.PageSource
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class OpenDocumentPageCountTest {
    @Test
    fun `page count is captured once instead of locking the renderer during turns`() {
        var calls = 0
        val source = object : PageSource {
            override val pageCount: Int get() { calls++; return 40 }
            override val kind = "pdf"
            override fun pageDim(index: Int) = PageDim(612f, 792f)
            override fun renderPage(index: Int, targetWidthPx: Int): Bitmap? = null
            override fun renderRegion(index: Int, region: RectF, targetWidthPx: Int): Bitmap? = null
            override fun close() = Unit
        }
        val ink = InkDocument.create("method.pdf", "pdf", 40, 1L, "fingerprint")
        val document = OpenDocument(File("method.pdf"), source, ink, "test")

        assertEquals(1, calls)
        repeat(20) { assertEquals(40, document.pageCount) }
        assertEquals("turn-time reads must not return to PageSource", 1, calls)
    }
}
