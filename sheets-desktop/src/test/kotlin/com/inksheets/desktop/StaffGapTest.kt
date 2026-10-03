package com.inksheets.desktop

import com.inksheets.core.omr.Ink
import com.inksheets.core.omr.Note
import com.inksheets.core.omr.Recognizer
import com.inksheets.core.omr.Strips
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Notes between two staves given to the right one: a high note over a staff (its ledger lines
 * climbing from it, its stem down into it) is that staff's, not a low note of the staff above.
 * Counted on the Tyrrell book's pages with many high notes - read as the app reads them - as how
 * many notes are read far under a trombone's staff (three ledger lines and more: none is printed).
 */
class StaffGapTest {
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets/MobileSheets")

    @Test
    fun `high notes stay with their own staff`() {
        val file = File(music, "Tyrrell-40-Progressive-Studies-for-Trombone.pdf")
        assumeTrue(file.isFile)
        val source = com.inkslate.desktop.DesktopSources.open(file, detached = true)!!
        var far = 0; var high = 0; var notes = 0
        for (p in listOf(10, 11, 13, 17)) {
            fun draw(width: Int): Pair<IntArray, Ink> {
                val img = source.render(p, width)!!
                val px = IntArray(img.width * img.height); img.readPixels(px)
                return Strips.grey(px) to Ink.fromArgb(img.width, img.height, px)
            }
            val space = Recognizer().metrics(draw(1600).second)?.second
            val width = if (space == null || space <= 0f) 1600 else (1600 * 18f / space).toInt().coerceIn(1000, 5000)
            val (grey, ink) = draw(width)
            val r = Recognizer().read(ink, p, 1, Recognizer.Carry(), null, grey = grey, net = com.inksheets.core.omr.Net.shipped)
            for (m in r.measures) for (e in m.events) if (e is Note) {
                notes++
                if (e.steps.any { it >= 13 }) { far++; println("  FAR p${p + 1} staff ${m.staff} bar ${m.number}: steps ${e.steps} stem ${if (e.stemUp == true) "up" else if (e.stemUp == false) "down" else "?"}") }
                if (e.steps.any { it <= -3 }) high++
            }
        }
        println("GAP: $notes notes, $far far under a staff, $high high over one")
        assertTrue("no note far under a trombone's staff", far <= 2)
    }
}
