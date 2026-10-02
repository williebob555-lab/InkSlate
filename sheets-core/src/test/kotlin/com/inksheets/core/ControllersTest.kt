package com.inksheets.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllersTest {
    private val pod = "POD Go"

    @Test
    fun `MIDI messages come in as notes, control changes and program changes, on channels 1 to 16`() {
        assertEquals(ControlEvent(pod, ControlEvent.CC, 1, 71, 127), ControlEvent.fromMidi(pod, 0xB0, 71, 127))
        assertEquals(ControlEvent(pod, ControlEvent.NOTE, 16, 60, 100), ControlEvent.fromMidi(pod, 0x9F, 60, 100))
        assertEquals(0, ControlEvent.fromMidi(pod, 0x80, 60, 64)!!.value)
        assertEquals(ControlEvent(pod, ControlEvent.PROGRAM, 2, 5, 5), ControlEvent.fromMidi(pod, 0xC1, 5, 0))
        assertNull(ControlEvent.fromMidi(pod, 0xE0, 0, 64))   // pitch bend: nothing to bind
    }

    @Test
    fun `raw bytes - running status, clock and sysex among them - give the same events`() {
        val bytes = byteArrayOf(0xF8.toByte(), 0xB0.toByte(), 71, 127, 72, 0, 0xF0.toByte(), 1, 2, 0xF7.toByte(), 0xC0.toByte(), 3)
        val events = ControlEvent.fromMidiBytes(pod, bytes, 0, bytes.size)
        assertEquals(listOf(71 to 127, 72 to 0, 3 to 3), events.map { it.number to it.value })
        assertEquals(listOf(ControlEvent.CC, ControlEvent.CC, ControlEvent.PROGRAM), events.map { it.kind })
    }

    @Test
    fun `a momentary switch fires when pressed, a one-message switch at every message, a pedal every move`() {
        val next = RemoteButton.action("NEXT_PAGE")
        val sw = ControlRef(pod, ControlEvent.CC, 1, 71)
        val momentary = ControlBinding(sw, next)
        val toggle = ControlBinding(ControlRef(pod, ControlEvent.CC, 1, 72), next, everyMessage = true)
        val pedal = ControlBinding(ControlRef("", ControlEvent.CC, 1, 1), RemoteButton(RemoteButton.TEMPO_SET), continuous = true)
        val all = listOf(momentary, toggle, pedal)
        assertEquals(listOf(momentary), Controllers.firing(all, ControlEvent(pod, ControlEvent.CC, 1, 71, 127)))
        assertTrue(Controllers.firing(all, ControlEvent(pod, ControlEvent.CC, 1, 71, 0)).isEmpty())
        assertEquals(listOf(toggle), Controllers.firing(all, ControlEvent(pod, ControlEvent.CC, 1, 72, 0)))
        // A binding for any device ("") takes the pedal from whichever sends it.
        assertEquals(listOf(pedal), Controllers.firing(all, ControlEvent("Other", ControlEvent.CC, 1, 1, 30)))
        assertTrue(Controllers.firing(all, ControlEvent(pod, ControlEvent.CC, 2, 71, 127)).isEmpty())
    }

    @Test
    fun `a pedal sweeps its action's range end to end`() {
        val tempo = ControlBinding(ControlRef(pod, ControlEvent.CC, 1, 1), RemoteButton(RemoteButton.TEMPO_SET), continuous = true)
        assertEquals(40.0, Controllers.valueOf(tempo, 0))
        assertEquals(240.0, Controllers.valueOf(tempo, 127))
        assertEquals(141.0, Controllers.valueOf(tempo, 64))   // 64 is just past halfway
        val volume = tempo.copy(action = RemoteButton(RemoteButton.AUDIO_VOLUME_SET))
        assertEquals(100.0, Controllers.valueOf(volume, 127))
        assertNull(Controllers.valueOf(tempo.copy(action = RemoteButton.action("NEXT_PAGE")), 64))
    }

    @Test
    fun `bindings are kept and read back`() {
        val list = listOf(
            ControlBinding(ControlRef(pod, ControlEvent.CC, 1, 71), RemoteButton.action("NEXT_PAGE"), everyMessage = true),
            ControlBinding(ControlRef(pod, ControlEvent.CC, 1, 1), RemoteButton(RemoteButton.AUDIO_SPEED_SET), continuous = true)
        )
        assertEquals(list, Controllers.decode(Controllers.encode(list)))
        assertEquals(emptyList<ControlBinding>(), Controllers.decode("not json"))
        assertEquals(emptyList<ControlBinding>(), Controllers.decode(null))
    }
}
