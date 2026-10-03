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
        // A block turned off - {105: 49, 106: {82: 0, 68: 5, 121: 17, 106: {98: 1, 59: false}}} - on the events channel, as captured.
        val body = byteArrayOf(0x82.toByte(), 105, 49, 106, 0x84.toByte(), 82, 0, 68, 5, 121, 17, 106, 0x82.toByte(), 98, 1, 59, 0xC2.toByte())
        val payload = PodGoLink.encodeHeader(5, PodGoLink.DATA, 0x1000) + stream(4, body)
        unit.toSend += PodGoLink.encodeFrame(PodGoLink.FLAG_DATA, 0x03f0, 0x1002, payload)
        unit.sent.clear()
        val got = ArrayList<ControlEvent>()
        link.pump(300) { m -> PodGoEvents.toControl(m)?.let { got += it } }
        assertEquals(listOf(ControlEvent(PodGoEvents.DEVICE, ControlEvent.CC, 1, 1, 0)), got)
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
    fun `what the unit said in the capture comes through as controls`() {
        // A footswitch in preset mode: preset 2 chosen.
        assertEquals(ControlEvent(PodGoEvents.DEVICE, ControlEvent.PROGRAM, 1, 2, 2), PodGoEvents.toControl(note(8, mapOf(107L to 1L, 108L to 2L))))
        // The toe switch: block 1 on again.
        assertEquals(ControlEvent(PodGoEvents.DEVICE, ControlEvent.CC, 1, 1, 127), PodGoEvents.toControl(note(49, mapOf(98L to 1L, 59L to true))))
        // The pedal: block 1's first setting, halfway.
        assertEquals(ControlEvent(PodGoEvents.DEVICE, ControlEvent.CC, 2, 8, 64),
            PodGoEvents.toControl(note(30, mapOf(98L to 1L, 29L to true, 26L to 0L, 28L to 0L, 119L to 0.5))))
        // The volume knob, and which pedal is active.
        assertEquals(ControlEvent(PodGoEvents.DEVICE, ControlEvent.CC, 3, 7, 127), PodGoEvents.toControl(note(22, mapOf(118L to 159L, 119L to 1.0))))
        assertEquals(ControlEvent(PodGoEvents.DEVICE, ControlEvent.CC, 3, 124, 127), PodGoEvents.toControl(note(22, mapOf(118L to 124L, 119L to 2L))))
        // What follows a preset change - its tempo, the preset loaded - comes through as controls of
        // its own, for the player to put on a spot or leave alone; a reply to our own asking does not.
        assertEquals(ControlEvent(PodGoEvents.DEVICE, ControlEvent.CC, 4, 16, 120), PodGoEvents.toControl(note(22, mapOf(118L to 16L, 119L to 120.0))))
        assertEquals(ControlEvent(PodGoEvents.DEVICE, ControlEvent.CC, 16, 4, 127), PodGoEvents.toControl(note(4, mapOf(107L to 1L, 108L to 2L))))
        assertEquals(ControlEvent(PodGoEvents.DEVICE, ControlEvent.CC, 16, 34, 127), PodGoEvents.toControl(note(34, mapOf(74L to 1L, 98L to 2L))))
        assertEquals(null, PodGoEvents.toControl(note(20, emptyMap())))
    }
}
