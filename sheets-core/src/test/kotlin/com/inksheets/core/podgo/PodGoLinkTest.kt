package com.inksheets.core.podgo

import com.inksheets.core.ControlEvent
import org.junit.Test
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class PodGoLinkTest {
    /** A pretend unit: answers each handshake, then has front-panel news to tell. */
    private class FakeUnit : PodGoLink.Wire {
        val sent = ArrayList<ByteArray>()
        val toSend = ArrayDeque<ByteArray>()
        override fun send(bytes: ByteArray): Boolean {
            sent += bytes
            val f = PodGoLink.decodeFrames(bytes).single()
            // Each opening answered on its channel, as the unit does.
            if (f.flags == PodGoLink.FLAG_HANDSHAKE) toSend += PodGoLink.encodeFrame(PodGoLink.FLAG_DATA, f.src, f.dst, PodGoLink.encodeHeader(0, PodGoLink.HELLO, 0x1000))
            return true
        }
        override fun recv(timeoutMs: Int): ByteArray? = toSend.removeFirstOrNull()
    }

    private fun stream(service: Int, body: ByteArray): ByteArray {
        val b = ByteArray(8 + body.size)
        b[2] = service.toByte(); b[3] = (service shr 8).toByte()
        b[4] = body.size.toByte(); b[5] = (body.size shr 8).toByte()
        body.copyInto(b, 8)
        return b
    }

    @Test
    fun `the first frame is the editor's own opening`() {
        val unit = FakeUnit()
        PodGoLink(unit).start()
        // As HX Edit sends it after claiming interface 0 (TonePush's capture).
        val hello = byteArrayOf(0x0c, 0x00, 0x00, 0x28, 0x01, 0x10, 0xef.toByte(), 0x03, 0x00, 0x00, 0x00, 0x02, 0x00, 0x01, 0x00, 0x21, 0x00, 0x10, 0x00, 0x00)
        assertArrayEquals(hello, unit.sent.first())
        // Every channel opened: control twice (services 5 then 2), events, data.
        val openings = unit.sent.map { PodGoLink.decodeFrames(it).single() }.filter { it.flags == PodGoLink.FLAG_HANDSHAKE }
        assertEquals(listOf(0x1001, 0x1001, 0x1002, 0x1080), openings.map { it.dst })
    }

    @Test
    fun `a front-panel change comes through as a control, acknowledged`() {
        val unit = FakeUnit()
        val link = PodGoLink(unit)
        assertTrue(link.start())
        // A footswitch going dark - {105: 41, 106: {70: 1, 63: false}} - on the events channel.
        val body = byteArrayOf(0x82.toByte(), 105, 41, 106, 0x82.toByte(), 70, 1, 63, 0xC2.toByte())
        val payload = PodGoLink.encodeHeader(5, PodGoLink.DATA, 0x1000) + stream(4, body)
        unit.toSend += PodGoLink.encodeFrame(PodGoLink.FLAG_DATA, 0x03f0, 0x1002, payload)
        unit.sent.clear()
        val got = ArrayList<ControlEvent>()
        link.pump(300) { m -> PodGoEvents.toControl(m)?.let { got += it } }
        assertEquals(listOf(ControlEvent(PodGoEvents.DEVICE, ControlEvent.CC, 5, 1, 0)), got)
        // Acknowledged: the bytes taken in counted from 0x1000.
        val ack = unit.sent.map { PodGoLink.decodeFrames(it).single() }.first { it.dst == 0x1002 && (it.payload[3].toInt() == PodGoLink.ACK) }
        val acked = java.nio.ByteBuffer.wrap(ack.payload, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        assertEquals(0x1000 + 8 + body.size, acked)
    }

    @Test
    fun `closing says goodbye on every channel`() {
        val unit = FakeUnit()
        val link = PodGoLink(unit)
        link.start()
        unit.sent.clear()
        link.close()
        val byes = unit.sent.map { PodGoLink.decodeFrames(it).single() }.filter { it.payload[3].toInt() == PodGoLink.HELLO }
        assertEquals(setOf(0x1001, 0x1002, 0x1080), byes.map { it.dst }.toSet())
    }

    /** A notification as the unit sends it: {105: event, 106: {82, 68, 121: routing, 106: what}}. */
    private fun note(event: Long, what: Map<Long, Any?>) =
        PodGoLink.Message(0x1002, 4, mapOf(105L to event, 106L to mapOf(82L to 0L, 68L to 5L, 121L to 17L, 106L to what)))

    @Test
    fun `what the unit said comes through as one control a press`() {
        val usb = PodGoEvents.DEVICE
        fun raw(event: Long, what: Map<Long, Any?>) = PodGoLink.Message(0x1002, 4, mapOf(105L to event, 106L to what))
        // FS1 in stomp mode, as logged: the footswitch lit, then its block on, then the block selected - one control.
        val press = listOf(raw(41, mapOf(70L to 0L, 63L to true, 66L to 3277055L)), note(49, mapOf(98L to 9L, 59L to true)), note(39, mapOf(98L to 9L, 26L to 0L)))
        assertEquals(listOf(ControlEvent(usb, ControlEvent.CC, 5, 0, 127)), press.mapNotNull { PodGoEvents.toControl(it) })
        // The toe switch: the pedal switched over, the active pedal, the toe's footswitch, two blocks - one control.
        val toe = listOf(raw(40, mapOf(91L to 514L)), note(22, mapOf(118L to 124L, 119L to 2L)), raw(41, mapOf(70L to 8L, 63L to false, 66L to 196619L)),
            note(49, mapOf(98L to 2L, 59L to false)), note(49, mapOf(98L to 1L, 59L to false)))
        assertEquals(listOf(ControlEvent(usb, ControlEvent.CC, 5, 8, 0)), toe.mapNotNull { PodGoEvents.toControl(it) })
        // A preset chosen, then loaded: one.
        assertEquals(listOf(ControlEvent(usb, ControlEvent.PROGRAM, 1, 2, 2)),
            listOf(note(8, mapOf(107L to 1L, 108L to 2L)), note(4, mapOf(107L to 1L, 108L to 2L))).mapNotNull { PodGoEvents.toControl(it) })
        // The pedal: 0-1 across, or a whole number as itself; the volume knob.
        assertEquals(ControlEvent(usb, ControlEvent.CC, 2, 8, 64), PodGoEvents.toControl(note(30, mapOf(98L to 1L, 28L to 0L, 119L to 0.5))))
        assertEquals(ControlEvent(usb, ControlEvent.CC, 2, 34, 19), PodGoEvents.toControl(note(30, mapOf(98L to 3L, 28L to 10L, 119L to 19L))))
        assertEquals(ControlEvent(usb, ControlEvent.CC, 3, 7, 127), PodGoEvents.toControl(note(22, mapOf(118L to 159L, 119L to 1.0))))
        // Not worked out: through as its own, to be put on a spot or left alone; a reply to our asking, not.
        assertEquals(ControlEvent(usb, ControlEvent.CC, 16, 77, 127), PodGoEvents.toControl(note(77, emptyMap())))
        assertEquals(null, PodGoEvents.toControl(note(20, emptyMap())))
        // The same unit's MIDI port is left to the USB link.
        assertTrue(PodGoEvents.sameUnit("POD Go") && PodGoEvents.sameUnit("Line 6 POD Go MIDI") && !PodGoEvents.sameUnit(usb) && !PodGoEvents.sameUnit("FCB1010"))
    }

    @Test
    fun `a spot set by an earlier version - a block, the pedal switched over - is known as no longer heard`() {
        val usb = PodGoEvents.DEVICE
        assertTrue(PodGoEvents.retired(com.inksheets.core.ControlRef(usb, ControlEvent.CC, 1, 4)))
        assertTrue(PodGoEvents.retired(com.inksheets.core.ControlRef(usb, ControlEvent.CC, 16, 49)))
        assertTrue(!PodGoEvents.retired(com.inksheets.core.ControlRef(usb, ControlEvent.CC, 5, 3)))
        assertTrue(!PodGoEvents.retired(com.inksheets.core.ControlRef("FCB1010", ControlEvent.CC, 1, 4)))
    }
}
