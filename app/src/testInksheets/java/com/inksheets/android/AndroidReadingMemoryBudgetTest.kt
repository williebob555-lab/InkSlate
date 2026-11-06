package com.inksheets.android

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidReadingMemoryBudgetTest {
    @Test fun `music recognition reserves heap and shrinks with current pressure`() {
        val mb = 1024L * 1024L
        assertEquals(8_388_608, AndroidSheetsPlatform.readingPixelBudget(512 * mb, 512 * mb))
        assertEquals(6_391_320, AndroidSheetsPlatform.readingPixelBudget(512 * mb, 256 * mb))
        assertEquals(0, AndroidSheetsPlatform.readingPixelBudget(512 * mb, 160 * mb))
        assertEquals(4_194_304, AndroidSheetsPlatform.readingPixelBudget(256 * mb, 256 * mb))
    }
}
