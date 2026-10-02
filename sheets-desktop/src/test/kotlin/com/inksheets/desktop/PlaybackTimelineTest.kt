package com.inksheets.desktop

import com.inksheets.core.omr.Note
import com.inksheets.core.omr.PlayOrder
import com.inksheets.core.omr.Score
import com.inksheets.core.omr.ScorePlayer
import com.inksheets.core.omr.Scores
import com.inksheets.core.omr.Synth
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The music read off a real book (the app's own reading of the Tyrrell studies, as kept beside
 * the library) played as the app plays it: which bar it says it is at, a tenth of a second at a
 * time, against when each note sounds. -Dinksheets.playback=1
 */
class PlaybackTimelineTest {
    private val readings = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets/.inksheets/readings/r11-43537d-338f6293")

    @Test
    fun `the Tyrrell study played - bar shown against notes heard`() {
        assumeTrue(System.getProperty("inksheets.playback") != null && readings.isDirectory)
        val page = Scores.decode(File(readings, "p3.json").readText())!!
        val score = Score(page.measures, page.pages, page.pageWidths)
        val rate = 8000
        val synth = Synth(rate)
        val source = PlayOrder.unrolled(score)
        val p = ScorePlayer(synth, source, 1, 12, 90.0, 0, Synth.BRASS)
        val buf = FloatArray(rate / 10)
        val shown = ArrayList<Int>()
        while (!p.finished && shown.size < 600) { java.util.Arrays.fill(buf, 0f); p.fill(buf); shown += p.bar }
        println("PLAYBACK bars shown each 0.1 s: " + shown.joinToString(" "))
        // Where each bar's first note sounds by the score itself (its notes added up), at 90 bpm.
        var t = 0.0
        for (m in source.measures.filter { it.number in 1..12 }) {
            println("  bar ${m.number}: starts %.2f s by its notes, lasts %.2f beats (time %.2f), events ${m.events.size}, ties ${m.events.count { (it as? Note)?.tie == true }}".format(t, m.quarters, m.time.quarters))
            t += m.quarters * 60 / 90.0
        }
    }
}
