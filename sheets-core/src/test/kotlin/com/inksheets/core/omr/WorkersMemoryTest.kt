package com.inksheets.core.omr

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkersMemoryTest {
    private val mb = 1024L * 1024L

    @Test fun `android recognition concurrency follows heap size`() {
        assertEquals(1, Workers.threadCount(8, 256 * mb, android = true))
        assertEquals(1, Workers.threadCount(8, 512 * mb, android = true))
        assertEquals(2, Workers.threadCount(8, 1024 * mb, android = true))
        assertEquals(4, Workers.threadCount(8, 2048 * mb, android = true))
    }

    @Test fun `few cores never create an empty pool`() {
        assertEquals(1, Workers.threadCount(1, 1024 * mb, android = true))
        assertEquals(1, Workers.threadCount(1, 1024 * mb, android = false))
    }
}
