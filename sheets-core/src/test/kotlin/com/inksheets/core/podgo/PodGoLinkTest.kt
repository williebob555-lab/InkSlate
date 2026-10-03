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
        // {105: 39, 106: {82: 3, 68: 1, 121: 0.5}} on the events channel.
        val body = byteArrayOf(0x82.toByte(), 105, 39, 106, 0x83.toByte(), 82, 3, 68, 1, 121, 0xCB.toByte()) +
            java.nio.ByteBuffer.allocate(8).putDouble(0.5).array()
        val payload = PodGoLink.encodeHeader(5, PodGoLink.DATA, 0x1000) + stream(4, body)
        unit.toSend += PodGoLink.encodeFrame(PodGoLink.FLAG_DATA, 0x03f0, 0x1002, payload)
        unit.sent.clear()
        val got = ArrayList<ControlEvent>()
        link.pump(300) { m -> PodGoEvents.toControl(m)?.let { got += it } }
        assertEquals(listOf(ControlEvent(PodGoEvents.DEVICE, ControlEvent.CC, 4, 1, 64)), got)
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
}
