package com.inksheets.core.omr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayTest {
    private val sp = 16f
    private val bar = Measure(1, 0, 0, Box(100, 200, 100 + (sp * 16).toInt(), 200 + (sp * 4).toInt()), sp, Clef.TREBLE, Key(0), TimeSig(4, 4), emptyList())
    private fun note(step: Int, base: Int, x: Float) = Note(listOf(step), listOf(Pitch.fromDiatonic(Clef.TREBLE.at(step), 0)), Duration(base), x)

    /** The bar "printed": [events] drawn where it is, staff lines and all, as a page would show it. */
    private fun page(events: List<Event>): Ink {
        val ink = Ink(600, 500)
        val drawing = Engraver.aligned(bar.copy(events = events))
        val polys = ArrayList<FloatArray>()
        for (mark in drawing.marks) when (mark) {
            is Engraver.Symbol -> polys += MusicGlyphs[mark.name].polygons(sp, bar.box.left + mark.x * sp, bar.box.top + mark.y * sp)
            is Engraver.Stroke -> {
                val h = maxOf(0.6f, mark.w * sp / 2)
                val ax = bar.box.left + mark.x1 * sp; val ay = bar.box.top + mark.y1 * sp; val bx = bar.box.left + mark.x2 * sp; val by = bar.box.top + mark.y2 * sp
                val len = kotlin.math.hypot(bx - ax, by - ay).coerceAtLeast(1e-3f); val nx = -(by - ay) / len * h; val ny = (bx - ax) / len * h
                polys += floatArrayOf(ax + nx, ay + ny, bx + nx, by + ny, bx - nx, by - ny, ax - nx, ay - ny)
            }
            is Engraver.Slab -> polys += FloatArray(mark.points.size) { i -> if (i % 2 == 0) bar.box.left + mark.points[i] * sp else bar.box.top + mark.points[i] * sp }
        }
        Fill.polygons(ink, polys)
        return ink
    }

    @Test
    fun `the reading that lines up with the print ranks first`() {
        val printed = listOf(note(4, 4, 130f), note(3, 4, 170f), note(2, 2, 220f))
        val ink = page(printed)
        val right = Overlay.score(ink, bar, printed)
        val stepOff = Overlay.score(ink, bar, listOf(note(5, 4, 130f), note(3, 4, 170f), note(2, 2, 220f)))
        val hollow = Overlay.score(ink, bar, listOf(note(4, 2, 130f), note(3, 4, 170f), note(2, 4, 220f)))
        val missing = Overlay.score(ink, bar, listOf(note(4, 4, 130f), note(2, 2, 220f)))
        println("overlay: right $right, a step off $stepOff, filled/hollow swapped $hollow, a note missing $missing")
        assertTrue("a step off scores worse", right > stepOff)
        assertTrue("filled and hollow swapped scores worse", right > hollow)
        assertTrue("a note left out scores worse", right > missing)
        val ranked = Overlay.rankAll(ink, bar, listOf(
            BarChoices.Choice(listOf(note(5, 4, 130f), note(3, 4, 170f), note(2, 2, 220f)), listOf("off"), 0f),
            BarChoices.Choice(printed, listOf("right"), 0.5f)))
        assertEquals("right", ranked.first().changes.first())
    }
}
