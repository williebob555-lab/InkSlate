package com.inksheets.android

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidReadingMemoryBudgetTest {
    @Test fun `music recognition reserves heap and shrinks with current pressure`() {
        val mb = 1024L * 1024L
        assertEquals(11_184_810, AndroidSheetsPlatform.readingPixelBudget(512 * mb, 512 * mb))
        assertEquals(8_947_848, AndroidSheetsPlatform.readingPixelBudget(512 * mb, 256 * mb))
        assertEquals(0, AndroidSheetsPlatform.readingPixelBudget(512 * mb, 160 * mb))
        assertEquals(5_592_405, AndroidSheetsPlatform.readingPixelBudget(256 * mb, 256 * mb))
    }
}
