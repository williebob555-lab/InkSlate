package com.inksheets.core.omr

import org.junit.Assert.*
import org.junit.Test

class PartPerformanceTest {
    private fun bar(n: Int, pitch: Int, start: Boolean = false, end: Boolean = false) =
        Measure(n, 0, 0, Box(0, 0, 100, 40), 10f, Clef.TREBLE, Key(0), TimeSig(4, 4),
            listOf(Note(listOf(0), listOf(Pitch(pitch, 4)), Duration(1), 20f)),
            repeatStart = start, repeatEnd = end)
    private fun score(vararg bars: Measure) = Score(bars.toList(), 1)
    private fun make(document: ScoreDocument, selection: ScoreSelection = document.defaultSelection(), range: IntRange? = null) =
        PartPerformance.plan(document, 120.0, 1000, selection, range) { PartPerformance.Sound(Synth.PIANO) }

    @Test fun `parts and multiple staves retain identity on one timeline`() {
        val keys = ScorePart("keys", listOf(score(bar(1, 0), bar(2, 1)), score(bar(1, 2), bar(2, 3))))
        val horn = ScorePart("horn", listOf(score(bar(1, 4), bar(2, 5))), transposition = 2)
        val plan = make(ScoreDocument(listOf(keys, horn)))
        assertEquals(listOf("keys", "horn"), plan.parts.map { it.id })
        assertEquals(listOf(60, 64, 62, 65), plan.parts[0].tones.map { it.midi })
        assertEquals(listOf(65, 67), plan.parts[1].tones.map { it.midi })
        assertEquals(2, plan.barNumbers.size)
        assertArrayEquals(intArrayOf(1, 2), plan.barNumbers)
        assertEquals(plan.barStarts[0], plan.parts[0].tones[0].start)
        assertEquals(plan.barStarts[0], plan.parts[1].tones[0].start)
        assertEquals(plan.barStarts[1], plan.parts[0].tones[2].start)
        assertEquals(plan.barStarts[1], plan.parts[1].tones[1].start)
        assertTrue(plan.length > plan.barStarts.last())
        val again = make(ScoreDocument(listOf(keys, horn)))
        assertArrayEquals(plan.barStarts, again.barStarts)
        assertEquals(plan.length, again.length)
    }

    @Test fun `selection chooses alternative and resolver receives selected parts only`() {
        val document = ScoreDocument(listOf(
            ScorePart("normal", listOf(score(bar(1, 0)))),
            ScorePart("easy", listOf(score(bar(1, 1))), alternative = ScoreAlternative("melody")),
            ScorePart("hard", listOf(score(bar(1, 2))), alternative = ScoreAlternative("melody"))))
        val resolved = mutableListOf<String>()
        val plan = PartPerformance.plan(document, 100.0, 1000,
            ScoreSelection(emptySet(), mapOf("melody" to "hard"))) {
            resolved += it.id
            PartPerformance.Sound(Synth.PIANO)
        }
        assertEquals(listOf("hard"), resolved)
        assertEquals(listOf("hard"), plan.parts.map { it.id })
        assertEquals(64, plan.parts.single().tones.single().midi)
        assertThrows(IllegalArgumentException::class.java) {
            make(document, ScoreSelection(emptySet(), mapOf("melody" to "normal")))
        }
    }

    @Test fun `range includes every occurrence of written bars and resets timeline`() {
        val document = ScoreDocument(listOf(ScorePart("a", listOf(score(
            bar(1, 0, start = true), bar(2, 1, end = true), bar(3, 2))))))
        val plan = make(document, range = 2..3)
        assertArrayEquals(intArrayOf(2, 2, 3), plan.barNumbers)
        assertEquals(0L, plan.barStarts.first())
        assertEquals(listOf(62, 62, 64), plan.parts.single().tones.map { it.midi })
        assertEquals(plan.barStarts.toList(), plan.parts.single().tones.map { it.start })
    }

    @Test fun `canonical alignment joins parts whose printed bar numbers differ`() {
        val lead = ScorePart("lead", listOf(score(bar(1, 0), bar(2, 1))))
        val oldNumbering = ScorePart("old", listOf(score(bar(101, 2), bar(102, 3))))
        val document = ScoreDocument(
            listOf(lead, oldNumbering),
            listOf(CanonicalBar("opening"), CanonicalBar("answer")),
            listOf(
                MeasureAlignment("lead", 0, 0, listOf("opening"), method = AlignmentMethod.MANUAL),
                MeasureAlignment("lead", 0, 1, listOf("answer"), method = AlignmentMethod.MANUAL),
                MeasureAlignment("old", 0, 0, listOf("opening"), method = AlignmentMethod.MANUAL),
                MeasureAlignment("old", 0, 1, listOf("answer"), method = AlignmentMethod.MANUAL)
            )
        )
        val plan = make(document)
        assertArrayEquals(intArrayOf(1, 2), plan.barNumbers)
        assertEquals(plan.barStarts.toList(), plan.parts[1].tones.map { it.start })
        assertEquals(listOf(64, 65), plan.parts[1].tones.map { it.midi })
    }
}
