package com.inksheets.core.omr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestAuditTest {
    private val space = 16f
    private val x = 45f
    private val y = 42f

    private fun measure(base: Int) = Measure(1, 0, 0, Box(0, 0, 140, 80), space, Clef.TREBLE,
        Key(0), TimeSig(4, 4), listOf(Rest(Duration(base), x, 4,
            SourceGlyph(if (base == 1) "restWhole" else "restHalf", SourcePoint(x, y), space, space, anchor = SourcePoint(x, y)))))

    @Test fun `independent rest template challenges a wrong kind`() {
        val ink = Ink(140, 90)
        Fill.polygons(ink, MusicGlyphs["restHalf"].polygons(space, x, y))
        val evidence = RestAudit.inspect(measure(1), ink)
        assertTrue(evidence.candidates.any { it.selectedBase == 1 && it.suggestedBase == 2 })
    }

    @Test fun `matching rest kind is not challenged`() {
        val ink = Ink(140, 90)
        Fill.polygons(ink, MusicGlyphs["restHalf"].polygons(space, x, y))
        assertFalse(RestAudit.inspect(measure(2), ink).suspicious)
    }
}
