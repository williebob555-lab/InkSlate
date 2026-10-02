package com.inksheets.desktop

import com.inksheets.core.omr.Ink
import com.inksheets.core.omr.Measure
import com.inksheets.core.omr.Note
import com.inksheets.core.omr.Recognizer
import com.inksheets.core.omr.Rest
import com.inksheets.core.omr.Strips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Bars of real scans (the wind ensemble's set) checked by eye against the print, read as the app
 * reads them: what the synthetic scans of the reading benchmark do not have - a cue printed small
 * over the part's rest, a natural, notes on the bottom line of a page printed a little uneven.
 * Skipped where the music folder is not on this machine.
 */
class RealScanTruthTest {
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets/MobileSheets")

    private fun page(name: String, p: Int): List<Measure> {
        val file = File(music, name)
        assumeTrue("no $name here", file.isFile)
        val source = com.inkslate.desktop.DesktopSources.open(file, detached = true)!!
        fun draw(width: Int): Pair<IntArray, Ink> {
            val img = source.render(p, width)!!
            val px = IntArray(img.width * img.height); img.readPixels(px)
            return Strips.grey(px) to Ink.fromArgb(img.width, img.height, px)
        }
        val space = Recognizer().metrics(draw(1600).second)?.second
        val width = if (space == null || space <= 0f) 1600 else (1600 * 18f / space).toInt().coerceIn(1000, 5000)
        val (grey, ink) = draw(width)
        return Recognizer().read(ink, p, 1, Recognizer.Carry(), null, grey = grey, net = com.inksheets.core.omr.Net.shipped).measures
    }

    private fun List<Measure>.bar(n: Int) = first { n >= it.number && n < it.number + it.bars }

    @Test
    fun `a cue printed small over the part's rest is the rest (Chester)`() {
        val bars = page("Chester.pdf", 0)
        for (n in listOf(1, 2, 3, 4, 5, 6, 7, 10, 12, 13)) {
            val e = bars.bar(n).events
            assertTrue("bar $n: ${e.map { it::class.simpleName + it.duration.base }}", e.size == 1 && e[0] is Rest && e[0].duration.base == 1)
        }
    }

    @Test
    fun `notes on the bottom line read on it, not a step under (Highwater)`() {
        val bars = page("Highwater Rising.pdf", 0)
        // Bass clef: the bottom line is G2, the space over it A2.
        for ((n, want) in listOf(34 to "G2", 35 to "G2", 36 to "A2", 37 to "G2", 38 to "G2", 39 to "G2")) {
            val pitches = bars.bar(n).events.filterIsInstance<Note>().flatMap { it.pitches }.map { it.toString() }
            assertTrue("bar $n: $pitches", pitches.isNotEmpty() && pitches.all { it == want })
        }
    }

    @Test
    fun `a natural is a natural, not a flat`() {
        val fanfare = page("Fanfare and Allegro.pdf", 0)
        assertEquals("Fanfare bar 43", listOf(0), fanfare.bar(43).events.filterIsInstance<Note>().flatMap { it.accidentals.values })
        val untitled = page("Untitled (p2-3).pdf", 0)
        val alters = untitled.bar(46).events.filterIsInstance<Note>().flatMap { it.accidentals.values }
        assertEquals("Untitled bar 46: $alters", 3, alters.count { it == 0 })
        assertEquals("Untitled bar 46: $alters", 3, alters.count { it == -1 })
    }
}
