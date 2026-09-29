package com.inksheets.core.omr

import org.junit.Assert.assertTrue
import org.junit.Test

class EngraverTest {
    @Test
    fun `engraves a page of music`() {
        val page = TestPages.page(1, staves = 3)
        TestPages.shot("engraved", page.ink)
        val inked = page.ink.bits.count { it }
        assertTrue("some ink: $inked", inked > page.ink.width * 10)
        // Every glyph loads and has an extent.
        for ((name, g) in MusicGlyphs.all) assertTrue(name, g.bounds[2] > g.bounds[0])
    }
}
