package com.inkslate.desktop

import com.inkslate.core.PageArranger
import com.inkslate.core.PageCrop
import com.inkslate.core.PageExtent
import com.inkslate.core.PageLayout
import org.junit.Assert.*
import org.junit.Test

class PageCropCoordinatesTest {
    @Test fun croppedPagesKeepPrintAndInkAlignedInEveryArrangement() {
        val crop = PageCrop(0.1f, 0.2f, 0.9f, 0.8f).box(600f, 800f)!!
        for (layout in PageLayout.entries) {
            val extents = listOf(PageExtent(600f, 800f), PageExtent(crop.width, crop.height))
            val (x, y) = PageArranger.arrange(extents, layout, 1)[1]
            val slot = PageSlot(1, crop.width, crop.height, x, y, crop.left, crop.top)
            // Printed point and an annotation written at that same point use the same transform.
            val fullPageInkX = 420f; val fullPageInkY = 560f
            val display = slot.fromInk(fullPageInkX, fullPageInkY)
            assertEquals(x + 360f, display.x, 0.0001f)
            assertEquals(y + 400f, display.y, 0.0001f)
            val newStroke = slot.toInk(display.x, display.y)
            assertEquals(fullPageInkX, newStroke.x, 0.0001f)
            assertEquals(fullPageInkY, newStroke.y, 0.0001f)
            assertTrue(slot.contains(display.x, display.y))
            // Resetting exposes the original point, without editing its stored coordinates.
            val reset = PageSlot(1, 600f, 800f, 0f, 0f)
            assertEquals(fullPageInkX, reset.fromInk(newStroke.x, newStroke.y).x, 0.0001f)
            assertEquals(fullPageInkY, reset.fromInk(newStroke.x, newStroke.y).y, 0.0001f)
        }
    }
}
