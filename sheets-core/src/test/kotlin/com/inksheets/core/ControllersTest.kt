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

    @Test
    fun `listening for a spot - what came first is offered first, a unit's own chatter last`() {
        val usb = "POD Go (USB)"
        val tempo = ControlRef(usb, ControlEvent.CC, 4, 16)
        val listener = ControlListener(chatter = setOf(tempo))
        // A footswitch in preset mode: the preset chosen, the preset loaded, its tempo - tempo was talking before.
        listener.hear(ControlEvent(usb, ControlEvent.CC, 4, 16, 120), 0)
        listener.hear(ControlEvent(usb, ControlEvent.PROGRAM, 1, 2, 2), 10)
        listener.hear(ControlEvent(usb, ControlEvent.CC, 16, 4, 127), 20)
        listener.hear(ControlEvent(usb, ControlEvent.PROGRAM, 1, 2, 2), 900)
        assertEquals(listOf(ControlRef(usb, ControlEvent.PROGRAM, 1, 2), ControlRef(usb, ControlEvent.CC, 16, 4), tempo), listener.candidates)
        assertEquals(2, listener.count(ControlRef(usb, ControlEvent.PROGRAM, 1, 2)))
    }

    @Test
    fun `a switch sending on then off at a press is momentary - one sending on, then off at the next press, is not`() {
        val listener = ControlListener()
        listener.hear(ControlEvent(pod, ControlEvent.CC, 1, 80, 127), 0)
        listener.hear(ControlEvent(pod, ControlEvent.CC, 1, 80, 0), 150)
        listener.hear(ControlEvent(pod, ControlEvent.CC, 1, 81, 127), 0)
        listener.hear(ControlEvent(pod, ControlEvent.CC, 1, 81, 0), 2500)
        assertTrue(listener.momentary(ControlRef(pod, ControlEvent.CC, 1, 80)))
        assertTrue(!listener.momentary(ControlRef(pod, ControlEvent.CC, 1, 81)))
    }

    @Test
    fun `an action given to a spot - a pedal sweeps, a block switch fires at every message, a momentary one when pressed`() {
        val pedal = SpotControl(ControlRef(pod, ControlEvent.CC, 2, 8))
        val tempo = Controllers.bindingFor(pedal, RemoteButton(RemoteButton.TEMPO_SET))
        assertTrue(tempo.continuous && !tempo.everyMessage)
        val block = Controllers.bindingFor(SpotControl(ControlRef(pod, ControlEvent.CC, 1, 1)), RemoteButton.action("NEXT_PAGE"))
        assertTrue(!block.continuous && block.everyMessage)
        val momentary = Controllers.bindingFor(SpotControl(ControlRef(pod, ControlEvent.CC, 1, 80), momentary = true), RemoteButton.action("NEXT_PAGE"))
        assertTrue(!momentary.continuous && !momentary.everyMessage)
        val preset = Controllers.bindingFor(SpotControl(ControlRef(pod, ControlEvent.PROGRAM, 1, 2)), RemoteButton.action("NEXT_PAGE"))
        assertTrue(!preset.continuous && !preset.everyMessage)
        assertEquals(listOf(preset), Controllers.firing(listOf(preset), ControlEvent(pod, ControlEvent.PROGRAM, 1, 2, 2)))
    }

    @Test
    fun `spots are kept and read back`() {
        val spots = mapOf("fs1" to SpotControl(ControlRef(pod, ControlEvent.PROGRAM, 1, 2)), "exp" to SpotControl(ControlRef(pod, ControlEvent.CC, 2, 8), momentary = false))
        assertEquals(spots, Controllers.decodeSpots(Controllers.encodeSpots(spots)))
        assertEquals(emptyMap<String, SpotControl>(), Controllers.decodeSpots("[]"))
    }

    @Test
    fun `a toggle can do it one way only - pressed twice, done once`() {
        val toe = ControlRef(pod, ControlEvent.CC, 5, 8)
        val lit = ControlBinding(toe, RemoteButton.action("NEXT_PAGE"))
        val dark = lit.copy(whenOff = true)
        val on = ControlEvent(pod, ControlEvent.CC, 5, 8, 127)
        val off = ControlEvent(pod, ControlEvent.CC, 5, 8, 0)
        assertEquals(listOf(lit), Controllers.firing(listOf(lit, dark), on))
        assertEquals(listOf(dark), Controllers.firing(listOf(lit, dark), off))
    }

    @Test
    fun `a sweep has no value of its own - a tempo set by a switch is not one`() {
        assertTrue(Controllers.isSweep(RemoteButton(RemoteButton.TEMPO_SET)))
        assertTrue(!Controllers.isSweep(RemoteButton(RemoteButton.TEMPO_SET, value = 120.0)))
        val fixed = Controllers.bindingFor(SpotControl(ControlRef(pod, ControlEvent.CC, 5, 1)), RemoteButton(RemoteButton.TEMPO_SET, value = 120.0))
        assertTrue(!fixed.continuous && fixed.everyMessage)
    }
}
