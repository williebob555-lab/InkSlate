package com.inksheets.core.omr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiRestAuditTest {
    private val sp = 16f
    private fun measure() = Measure(1, 0, 0, Box(10, 45, 150, 110), sp, Clef.TREBLE, Key(0),
        TimeSig(4, 4), listOf(Rest(Duration(1), 65f)),
        lines = listOf(50f, 66f, 82f, 98f, 114f, 50f, 66f, 82f, 98f, 114f))

    @Test fun `thick middle bar without a count is not enough`() {
        val ink = Ink(180, 140)
        for (y in 78..86) for (x in 48..112) ink[x, y] = true
        for (y in 66..98) ink[48, y] = true
        assertFalse(MultiRestAudit.inspect(measure(), ink).suspicious)
    }

    @Test fun `thick middle bar with a printed count is challenged`() {
        val ink = Ink(180, 140)
        for (y in 78..86) for (x in 48..112) ink[x, y] = true
        for (y in 66..98) ink[48, y] = true
        Fill.polygons(ink, MusicGlyphs["timeSig5"].polygons(sp, 72f, 22f))
        assertTrue(MultiRestAudit.inspect(measure(), ink).suspicious)
    }

    @Test fun `beam with an internal stem is not a multi rest`() {
        val ink = Ink(180, 140)
        for (y in 78..86) for (x in 48..112) ink[x, y] = true
        for (x in listOf(48, 80, 112)) for (y in 66..98) ink[x, y] = true
        Fill.polygons(ink, MusicGlyphs["timeSig5"].polygons(sp, 72f, 22f))
        assertFalse(MultiRestAudit.inspect(measure(), ink).suspicious)
    }

    @Test fun `compact cap and block multi rest is accepted`() {
        val ink = Ink(180, 140)
        for (y in 78..86) for (x in 70..86) ink[x, y] = true
        for (y in 66..98) ink[70, y] = true
        Fill.polygons(ink, MusicGlyphs["timeSig3"].polygons(sp, 70f, 22f))
        assertTrue(MultiRestAudit.inspect(measure(), ink).suspicious)
    }
}
