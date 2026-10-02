package com.inksheets.desktop

import com.inksheets.core.omr.Box
import com.inksheets.core.omr.Clef
import com.inksheets.core.omr.Duration
import com.inksheets.core.omr.Engraver
import com.inksheets.core.omr.Event
import com.inksheets.core.omr.Ink
import com.inksheets.core.omr.Key
import com.inksheets.core.omr.Measure
import com.inksheets.core.omr.Note
import com.inksheets.core.omr.Pitch
import com.inksheets.core.omr.TimeSig
import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Bars drawn the way they are printed (what Fix shows each reading as): a lone sixteenth's short
 * beam on the side the print puts it, tuplets with their figure. A picture to build/engraver-style.png.
 */
class EngraverStyleTest {
    private fun note(step: Int, base: Int, dots: Int = 0, beam: Int = 0, actual: Int = 1, normal: Int = 1) =
        Note(listOf(step), listOf(Pitch(0, 5)), Duration(base, dots, actual, normal), 0f, beam = beam)

    private fun bar(n: Int, vararg events: Event) =
        Measure(n, 0, 0, Box(0, 0, 100, 40), 10f, Clef.TREBLE, Key(0), TimeSig(4, 4), events.toList())

    /** Each short beam drawn alone: whether it runs back from its stem (left) or on (right). */
    private fun stubs(d: Engraver.Drawing): List<Boolean> = d.marks.filterIsInstance<Engraver.Slab>()
        .map { it.points[2] - it.points[0] }.filter { kotlin.math.abs(it) in 1.05f..1.35f }.map { it < 0 }

    @Test
    fun `a sixteenth's short beam points to the note it shares its eighth with`() {
        // Dotted eighth and sixteenth twice under one beam (as printed): both sixteenths' beams back.
        val swung = bar(1, note(6, 8, 1, 1), note(6, 16, 0, 1), note(6, 8, 1, 1), note(6, 16, 0, 1),
            note(6, 4), note(6, 4), note(6, 4))
        assertEquals(listOf(true, true), stubs(Engraver.line(listOf(swung))))
        // Sixteenth then dotted eighth, twice: both forward.
        val snapped = bar(2, note(6, 16, 0, 1), note(6, 8, 1, 1), note(6, 16, 0, 1), note(6, 8, 1, 1),
            note(6, 4), note(6, 4), note(6, 4))
        assertEquals(listOf(false, false), stubs(Engraver.line(listOf(snapped))))
        // A triplet of eighths beamed, and one of quarters (bracketed).
        val tuplets = bar(3, note(4, 8, actual = 3, normal = 2), note(5, 8, actual = 3, normal = 2), note(6, 8, actual = 3, normal = 2),
            note(6, 4, actual = 3, normal = 2), note(5, 4, actual = 3, normal = 2), note(4, 4, actual = 3, normal = 2), note(3, 4))
        val all = Engraver.line(listOf(swung, snapped, tuplets))
        // Two figures for the two triplets, small.
        assertEquals(2, all.marks.count { it is Engraver.Symbol && it.name == "timeSig3" && it.scale < 1f })

        val sp = 14f
        val (top, bottom) = all.extent
        val img = BufferedImage((all.width * sp + 20).toInt(), ((bottom - top) * sp + 20).toInt(), BufferedImage.TYPE_INT_ARGB)
        val ink = Ink(img.width, img.height)
        Engraver.paint(ink, all, sp, 10f, 10f - top * sp)
        img.setRGB(0, 0, ink.width, ink.height, ink.argb(), 0, ink.width)
        ImageIO.write(img, "png", File("build/engraver-style.png"))
    }
}
