package com.inksheets.desktop

import com.inksheets.core.omr.PlayOrder
import com.inksheets.core.omr.Score
import com.inksheets.core.omr.ScorePlayer
import com.inksheets.core.omr.Scores
import com.inksheets.core.omr.Synth
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * A book of studies played from one study's page on, as the app plays it - the real reading of
 * the Tyrrell book, as kept in the library - and the bars it says it is in, in turn: they only go
 * on (never back to the book's start), and the page followed is the bar's own.
 */
class BookPlaybackTest {
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")

    private fun reading(): Score? {
        val dir = File(music, ".inksheets/readings").listFiles().orEmpty().filter { it.name.startsWith("r12-43537d") }.firstOrNull() ?: return null
        val pages = dir.listFiles { f -> f.name.matches(Regex("p\\d+\\.json")) }.orEmpty().mapNotNull { Scores.decode(it.readText()) }
        if (pages.isEmpty()) return null
        return Score(pages.flatMap { it.measures }.sortedWith(compareBy({ it.page }, { it.staff }, { it.box.left })), pages.first().pages,
            List(pages.first().pages) { p -> pages.firstNotNullOfOrNull { it.pageWidths.getOrNull(p)?.takeIf { w -> w > 0 } } ?: 0 })
    }

    @Test
    fun `a study played from its page goes on through the book`() {
        val score = reading()
        assumeTrue("no reading of the Tyrrell book here", score != null)
        score!!
        val page = 5   // study 4
        val first = score.measures.first { it.page >= page }.number
        val last = score.measures.last().let { it.number + it.bars - 1 }
        for ((how, player) in listOf(
            "as shipped" to ScorePlayer(Synth(8000), PlayOrder.unrolled(score), first, last, 240.0, 0, Synth.PIANO),
            "from a bar" to ScorePlayer(Synth(8000), score, first, last, 240.0, 0, Synth.PIANO, order = PlayOrder.from(score, first)))) {
            val seen = ArrayList<Int>()
            val buf = FloatArray(800)
            var guard = 0
            while (!player.finished && guard++ < 60_000 && seen.size < 400) {
                player.fill(buf)
                if (seen.lastOrNull() != player.bar) seen += player.bar
            }
            val back = seen.zipWithNext().filter { (a, b) -> b < a }
            println("PLAY $how: from bar $first (page ${page + 1}): ${seen.take(80)}; went back ${back.take(5)}; pages ${seen.take(80).mapNotNull { n -> score.measures.firstOrNull { it.number == n }?.page?.plus(1) }.distinct()}")
            assertTrue("$how: never back", back.isEmpty())
        }
    }
}
