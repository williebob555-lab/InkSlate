package com.inksheets.desktop

import androidx.compose.runtime.MutableState
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.core.omr.Box
import com.inksheets.core.omr.Clef
import com.inksheets.core.omr.BarChoices
import com.inksheets.core.omr.Duration
import com.inksheets.core.omr.Event
import com.inksheets.core.omr.Key
import com.inksheets.core.omr.Measure
import com.inksheets.core.omr.Note
import com.inksheets.core.omr.Pitch
import com.inksheets.core.omr.Score
import com.inksheets.core.omr.TimeSig
import com.inksheets.ui.ScoreTools
import com.inksheets.ui.SheetsState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A bar put right in one part, put right in the same measure of the song's other parts: kept as
 * that part's own (carried) edits, taken back by Back, and given to a part read later.
 */
class CarryFixUiTest {
    @After
    fun reset() { ScoreTools.scoreSource = null; ScoreTools.endCheck() }

    private fun p(s: String): Pitch = Pitch("CDEFGAB".indexOf(s[0]), s.last().digitToInt(), s.count { it == '#' } - s.count { it == 'b' })
    private fun quarters(vararg pitches: String): List<Event> = pitches.mapIndexed { i, s ->
        val pi = p(s); Note(listOf(Clef.TREBLE.topLine - pi.diatonic), listOf(pi), Duration(4), i * 10f)
    }
    private fun bar(n: Int, key: Int, events: List<Event>, doubts: List<String> = emptyList()) =
        Measure(n, 0, 0, Box(0, 0, 100, 40), 8f, Clef.TREBLE, Key(key), TimeSig(4, 4), events, doubts = doubts)

    private val fluteRead = Score(listOf(
        bar(1, -2, quarters("Bb4", "C5", "D5", "Eb5")),
        bar(2, -2, quarters("F5", "D5", "D5", "Bb4"), listOf("unclear")),
        bar(3, -2, quarters("Bb4", "Bb4", "C5", "D5"))), 1, listOf(800))
    private val trumpetRead = Score(listOf(
        bar(1, 0, quarters("C5", "D5", "E5", "F5")),
        bar(2, 0, quarters("G5", "E5", "E5", "C5"), listOf("unclear")),
        bar(3, 0, quarters("C5", "C5", "D5", "E5"))), 1, listOf(800))
    private val fix = quarters("F5", "Eb5", "D5", "Bb4")

    private class Setup(val s: SheetsState, val flute: String, val trumpet: String)

    private fun setup(name: String): Setup {
        val lib = File("build/carry-$name").apply { deleteRecursively(); mkdirs() }
        listOf("flute.pdf", "trumpet.pdf").forEach { File(lib, it).writeText("%PDF-1.4\n% $it $name\n%%EOF\n") }
        val s = installInkSheets { it.setPref("sheets_library", lib.absolutePath) }()
        val until = System.currentTimeMillis() + 15_000
        while (s.root == null && System.currentTimeMillis() < until) Thread.sleep(20)
        assertNotNull("library opened", s.root)
        s.current = Song("s-$name", "Song", parts = listOf(
            Part("pf", "flute.pdf", instrument = "flute", label = "Flute"),
            Part("pt", "trumpet.pdf", instrument = "trumpet", label = "Trumpet")))
        val f = s.fileOf("flute.pdf")!!.absolutePath
        val t = s.fileOf("trumpet.pdf")!!.absolutePath
        ScoreTools.install(s)
        return Setup(s, f, t)
    }

    private fun SheetsState.showing(path: String) {
        val field = SheetsState::class.java.getDeclaredField("currentPath\$delegate").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") (field.get(this) as MutableState<String?>).value = path
    }

    private fun names(events: List<Event>) = events.filterIsInstance<Note>().map { it.pitches[0].toString() }

    @Test
    fun `a fix is put right in the other part too, undone by Back`() {
        val x = setup("back")
        ScoreTools.scoreSource = { if (it == x.flute) fluteRead else if (it == x.trumpet) trumpetRead else null }
        x.s.showing(x.trumpet)
        assertEquals("one bar in doubt in the trumpet", 1, ScoreTools.redCount(x.s))
        x.s.showing(x.flute)
        // Fix: bar 2 up, the reading picked.
        ScoreTools.fixBar(x.s, 2)
        assertTrue(ScoreTools.checking)
        ScoreTools.pick(x.s, BarChoices.Choice(fix, listOf("by hand"), 0f))
        assertEquals(listOf("Trumpet"), ScoreTools.lastCarried)
        assertNull("no note in the part it was fixed in", ScoreTools.carriedNote(x.s, 2))
        x.s.showing(x.trumpet)
        val bar = ScoreTools.scoreHere(x.s)!!.measures[1]
        assertTrue("counts as put right", bar.sure)
        assertEquals(listOf("G5", "F5", "E5", "C5"), names(bar.events))
        assertEquals(0, ScoreTools.redCount(x.s))
        assertEquals("put right from Flute", ScoreTools.carriedNote(x.s, 2))
        // Back in the flute takes the carried copy back.
        x.s.showing(x.flute)
        ScoreTools.back(x.s)
        x.s.showing(x.trumpet)
        assertFalse("undone in the trumpet", ScoreTools.scoreHere(x.s)!!.measures[1].sure)
        assertEquals(1, ScoreTools.redCount(x.s))
        assertNull(ScoreTools.carriedNote(x.s, 2))
    }

    @Test
    fun `undoCarried takes the copies back and keeps them gone`() {
        val x = setup("undo")
        ScoreTools.scoreSource = { if (it == x.flute) fluteRead else if (it == x.trumpet) trumpetRead else null }
        x.s.showing(x.flute)
        ScoreTools.fix(x.flute, 2, fix)
        assertEquals(listOf("Trumpet"), ScoreTools.lastCarried)
        ScoreTools.undoCarried()
        x.s.showing(x.trumpet)
        assertFalse(ScoreTools.scoreHere(x.s)!!.measures[1].sure)
        // Looking again for fixes from the flute does not bring it back.
        assertEquals(0, ScoreTools.carryIn(x.s, force = true))
        assertFalse(ScoreTools.scoreHere(x.s)!!.measures[1].sure)
        // The trumpet's own fix still stands over anything carried.
        ScoreTools.fix(x.trumpet, 2, trumpetRead.measures[0].events)
        assertEquals(trumpetRead.measures[0].events, ScoreTools.scoreHere(x.s)!!.measures[1].events)
    }

    @Test
    fun `a part read later gets the fixes already kept in its sisters`() {
        val x = setup("later")
        var trumpetScore: Score? = null
        ScoreTools.scoreSource = { if (it == x.flute) fluteRead else if (it == x.trumpet) trumpetScore else null }
        x.s.showing(x.flute)
        ScoreTools.fix(x.flute, 2, fix)
        assertEquals("the trumpet is not read yet", emptyList<String>(), ScoreTools.lastCarried)
        trumpetScore = trumpetRead
        x.s.showing(x.trumpet)
        assertEquals(1, ScoreTools.carryIn(x.s, force = true))
        assertTrue(ScoreTools.scoreHere(x.s)!!.measures[1].sure)
        assertEquals("put right from Flute", ScoreTools.carriedNote(x.s, 2))
        // Once is enough.
        assertEquals(0, ScoreTools.carryIn(x.s, force = true))
    }

    @Test
    fun `a bar that only looks alike is left in doubt`() {
        val x = setup("alike")
        val different = Score(listOf(trumpetRead.measures[0], bar(2, 0, quarters("G5", "E5", "F5", "C5"), listOf("unclear")), trumpetRead.measures[2]), 1, listOf(800))
        ScoreTools.scoreSource = { if (it == x.flute) fluteRead else if (it == x.trumpet) different else null }
        x.s.showing(x.flute)
        ScoreTools.fix(x.flute, 2, fix)
        assertEquals(emptyList<String>(), ScoreTools.lastCarried)
        x.s.showing(x.trumpet)
        assertFalse(ScoreTools.scoreHere(x.s)!!.measures[1].sure)
    }
}
