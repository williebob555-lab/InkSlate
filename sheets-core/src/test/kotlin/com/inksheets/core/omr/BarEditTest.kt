package com.inksheets.core.omr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BarEditTest {
    private val bar = Measure(1, 0, 0, Box(0, 0, 1, 1), 10f, Clef.TREBLE, Key(-1), TimeSig(4, 4), emptyList())
    private fun note(step: Int, base: Int, x: Float) = Note(listOf(step), listOf(Pitch.fromDiatonic(Clef.TREBLE.at(step), 0)), Duration(base), x)

    @Test
    fun `a bar put right by hand`() {
        // Read as two halves and an eighth: 4.5 beats in a 4/4 bar.
        var events: List<Event> = listOf(note(4, 2, 0f), note(3, 2, 10f), note(2, 8, 20f))
        assertEquals(4.5, BarEdit.quarters(events), 1e-9)

        // The last one out: it comes to its time.
        events = BarEdit.delete(events, 2)
        assertEquals(4.0, BarEdit.quarters(events), 1e-9)

        // The second (C5) a step lower: B4, which in one flat is B flat, the key's.
        events = BarEdit.step(bar, events, 1, 1)
        val second = events[1] as Note
        assertEquals(listOf(4), second.steps)
        assertEquals("Bb4", second.pitches[0].toString())

        // A dotted quarter and an eighth in place of the first half: still four beats.
        events = BarEdit.length(events, 0, 4)
        events = BarEdit.dot(events, 0)
        events = BarEdit.addAfter(bar, events, 0)
        events = BarEdit.length(events, 1, 8)
        assertEquals(listOf(4, 8, 2), events.map { it.duration.base })
        assertEquals(4.0, BarEdit.quarters(events), 1e-9)
        assertTrue("added between the two it was put between", events[0].x < events[1].x && events[1].x < events[2].x)

        // A note made a rest keeps its length; made a note again it sits on the middle line.
        events = BarEdit.restOrNote(bar, events, 2)
        assertTrue(events[2] is Rest)
        events = BarEdit.restOrNote(bar, events, 2)
        assertEquals(listOf(4), (events[2] as Note).steps)
        assertEquals(4.0, BarEdit.quarters(events), 1e-9)
    }

    @Test
    fun `three as a triplet and back`() {
        val three: List<Event> = listOf(note(4, 8, 0f), note(3, 8, 5f), note(2, 8, 10f))
        val t = BarEdit.triplet(three, 0)
        assertEquals(1.0, BarEdit.quarters(t), 1e-9)
        assertEquals(1.5, BarEdit.quarters(BarEdit.triplet(t, 0)), 1e-9)
    }

    @Test
    fun `accidentals written by hand carry on through the bar, and a short bar filled out with rests`() {
        // Two B's in one flat: both B flat by the key.
        var events: List<Event> = BarEdit.step(bar, listOf(note(4, 4, 0f), note(4, 4, 10f)), 0, 0)
        assertEquals(listOf("Bb4", "Bb4"), events.map { (it as Note).pitches[0].toString() })
        // A natural before the first: both B natural (it carries on in the bar).
        events = BarEdit.accidental(bar, events, 0, 0)
        assertEquals(0, BarEdit.accidentalOf(events, 0))
        assertEquals(listOf("B4", "B4"), events.map { (it as Note).pitches[0].toString() })
        // Taken off again: the key's flat back.
        events = BarEdit.accidental(bar, events, 0, null)
        assertEquals(listOf("Bb4", "Bb4"), events.map { (it as Note).pitches[0].toString() })
        // Two quarters of four: filled with a half rest.
        events = BarEdit.fillWithRests(bar, events)
        assertEquals(4.0, BarEdit.quarters(events), 1e-9)
        assertTrue(events.last() is Rest && events.last().duration.base == 2)
    }
}
