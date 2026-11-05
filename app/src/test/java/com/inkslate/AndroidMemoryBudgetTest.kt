package com.inkslate

import com.inkslate.pdf.PageCache
import com.inkslate.ui.editor.musicRenderWidthPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidMemoryBudgetTest {
    @Test fun `music pages use the screen's shorter side and stay bounded`() {
        assertEquals(1134,musicRenderWidthPx(1080f,2400f))
        assertEquals(1600,musicRenderWidthPx(2560f,1600f))
        assertEquals(720,musicRenderWidthPx(480f,800f))
    }

    @Test fun `disk page copies take only a small heap fraction`() {
        assertEquals(2L*1024*1024,PageCache.maxPixelCopyBytes(64L*1024*1024))
        assertEquals(4L*1024*1024,PageCache.maxPixelCopyBytes(128L*1024*1024))
        assertEquals(6L*1024*1024,PageCache.maxPixelCopyBytes(512L*1024*1024))
        assertTrue(PageCache.maxPixelCopyBytes(128L*1024*1024)<16L*1024*1024)
    }

}
