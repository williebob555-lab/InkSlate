package com.inksheets.core.omr

import org.junit.Assert.assertTrue
import org.junit.Test

class BarChoicesTripletTest {
    private fun note(step: Int, duration: Duration, x: Float) = Note(
        listOf(step), listOf(Pitch.fromDiatonic(Clef.TREBLE.at(step), 0)), duration, x)

    private fun measure(duration: Duration) = Measure(
        1, 0, 0, Box(0, 0, 120, 40), 10f, Clef.TREBLE, Key(0), TimeSig(1, 4),
        listOf(note(4, duration, 10f), note(3, duration, 40f), note(2, duration, 70f)))

    @Test
    fun `triplet missing offers only adding a triplet`() {
        val offered = BarChoices.of(measure(Duration(8)), focus = BarChoices.FOCUS["Triplet missing"])
        assertTrue(offered.isNotEmpty())
        assertTrue(offered.all { choice -> choice.kinds.all { it == "triplet-add" } })
        assertTrue(offered.all { choice -> choice.events.all { it.duration.actual == 3 && it.duration.normal == 2 } })
    }

    @Test
    fun `triplet invented offers only removing the read triplet`() {
        val offered = BarChoices.of(measure(Duration(8, actual = 3, normal = 2)), focus = BarChoices.FOCUS["Triplet invented"])
        assertTrue(offered.isNotEmpty())
        assertTrue(offered.all { choice -> choice.kinds.all { it == "triplet-remove" } })
        assertTrue(offered.all { choice -> choice.events.none { it.duration.tuplet } })
    }
}
