package com.inksheets.core.podgo

import java.io.ByteArrayOutputStream

/**
 * Talking to a Line 6 POD Go over USB the way its editor does - its footswitches and expression
 * pedal are not sent as MIDI, but the unit tells an editor that is listening what changes on its
 * front panel. This is that listening: the editor's channel (USB interface 0, bulk endpoints 0x01
 * out and 0x81 in) opened, kept open, and every message the unit sends handed on.
 *
 * The protocol is Line 6's HX family's, as worked out from USB captures by the TonePush project
 * (github.com/crmne/tonepush, MIT licence); the POD Go is of that family (product 0x4247 among the
 * HX units' 0x4245-0x4253). Only listening is done here - nothing is asked of the unit, nothing in
 * it changed.
 *
 * Every transfer is one or more frames: an 8-byte header - payload length (3 bytes, little-endian),
 * flags (0x18 data, 0x28 a channel's opening), the destination's node and the source's (2 bytes
 * each, little-endian) - then the payload, padded to 4 bytes. A payload starts with a channel
 * header - sequence number and message type (2 bytes each, big-endian), and how many bytes have
 * been taken in (4 bytes, little-endian, counted from 0x1000) - and what follows is a byte stream:
 * messages of [originator 2][service 2][length 4] and a MessagePack body.
 */
class PodGoLink(private val wire: Wire, private val log: (String) -> Unit = {}) {

    /** The USB transfers: [send] one, [recv] the next (null when nothing came in [timeoutMs]). */
    interface Wire {
        fun send(bytes: ByteArray): Boolean
        fun recv(timeoutMs: Int): ByteArray?
    }

    /** One message from the unit: on which channel, for which service, and its body. */
    data class Message(val channel: Int, val service: Int, val body: Any?) {
        /** A notification's event number ({105: event, 106: args}), or null. */
        val event: Int? get() = ((body as? Map<*, *>)?.get(105L) as? Long)?.toInt()
        val args: Any? get() = (body as? Map<*, *>)?.get(106L)
    }

    private class Channel(val device: Int, val host: Int, val services: List<Int>) {
        var seq = 0
        var rx = 0L
        var acked = 0L
        val stream = ByteArrayOutputStream()
        fun ack(): Int = (ACK_BASE + rx).toInt()
    }

    private val channels = listOf(
        Channel(0x1001, 0x03ef, listOf(5, 2)),   // control
        Channel(0x1002, 0x03f0, listOf(4)),      // events: what changes on the front panel
        Channel(0x1080, 0x03ed, listOf(6))       // presets and settings
    )

    @Volatile var open = false
        private set

    /** The handshake, once: each channel opened for its services. False when the unit does not answer. */
    fun start(): Boolean {
        drain()
        var heard = false
        for (ch in channels) {
            for ((n, service) in ch.services.withIndex()) {
                if (n > 0) {
                    // A channel's second service: the first closed (a bare type-2 message), then opened afresh.
                    write(ch, header(ch, HELLO))
                    ch.acked = ch.rx
                    heard = readFor(REPLY_MS) || heard
                }
                val carried = if (n == 0) 0L else ch.rx
                ch.seq = 0; ch.rx = 0; ch.acked = 0; ch.stream.reset()
                val hello = frame(ch, encodeHeader(0, HELLO, HELLO_FIELD) + HELLO_TAIL, FLAG_HANDSHAKE)
                if (!wire.send(hello)) { log("POD Go: the handshake could not be sent"); return false }
                heard = readFor(REPLY_MS) || heard
                takeMessages(ch)
                ch.rx = carried
                // (The editor's numbering goes 0, then 2: the unit stops answering at 1.)
                ch.seq = 2
                sendStream(ch, service, MsgPack.encodeUInt(service.toLong()))
                heard = readFor(REPLY_MS) || heard
                takeMessages(ch)
                ackChannel(ch)
            }
        }
        open = heard
        log(if (heard) "POD Go: listening" else "POD Go: no answer to the handshake")
        return heard
    }

    /**
     * Reads for [ms], handing on each message - acknowledging what came in, and every second
     * telling the unit the link is alive (it drops a quiet one).
     */
    fun pump(ms: Int, onMessage: (Message) -> Unit) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            read(100)
            for (ch in channels) for (m in takeMessages(ch)) onMessage(m)
            for (ch in channels) if (ch.rx != ch.acked) ackChannel(ch)
            val now = System.currentTimeMillis()
            if (now - lastKeepAlive >= 1000) { lastKeepAlive = now; for (ch in channels) { write(ch, header(ch, KEEPALIVE)); ch.acked = ch.rx } }
        }
    }

    private var lastKeepAlive = 0L

    /** The link closed as the editor closes it - a bare type-2 message on each channel - so the next opens cleanly. */
    fun close() {
        if (!open) return
        open = false
        for (ch in channels) { write(ch, header(ch, HELLO)); ch.acked = ch.rx }
        readFor(300)
        log("POD Go: closed")
    }

    // ---- frames -----------------------------------------------------------------------------

    private fun header(ch: Channel, type: Int): ByteArray {
        val seq = ch.seq; ch.seq = (ch.seq + 1) and 0xFFFF
        return encodeHeader(seq, type, ch.ack())
    }

    private fun write(ch: Channel, payload: ByteArray) = wire.send(frame(ch, payload, FLAG_DATA))

    private fun frame(ch: Channel, payload: ByteArray, flags: Int): ByteArray = encodeFrame(flags, ch.device, ch.host, payload)

    private fun ackChannel(ch: Channel) {
        write(ch, header(ch, ACK))
        ch.acked = ch.rx
    }

    private fun sendStream(ch: Channel, service: Int, body: ByteArray) {
        val stream = ByteArray(8 + body.size)
        le16(stream, 0, 1); le16(stream, 2, service); le32(stream, 4, body.size)
        body.copyInto(stream, 8)
        var at = 0
        while (at < stream.size) {
            val chunk = stream.copyOfRange(at, minOf(stream.size, at + CHUNK))
            write(ch, header(ch, DATA) + chunk)
            ch.acked = ch.rx
            at += CHUNK
        }
    }

    /** Whatever the unit queued before we came (a link not closed) read and let go - three seconds at most. */
    private fun drain() {
        val giveUp = System.currentTimeMillis() + 3000
        var quiet = 0
        while (quiet < 3 && System.currentTimeMillis() < giveUp) if (wire.recv(150) == null) quiet++ else quiet = 0
    }

    private fun readFor(ms: Int): Boolean {
        val until = System.currentTimeMillis() + ms
        var any = false
        while (System.currentTimeMillis() < until) if (read(minOf(100, ms))) any = true else if (any) break
        return any
    }

    private fun read(timeoutMs: Int): Boolean {
        val data = wire.recv(timeoutMs) ?: return false
        if (data.isEmpty()) return true
        for (f in decodeFrames(data)) route(f)
        return true
    }

    private fun route(f: Frame) {
        if (f.payload.size < 8) return
        val type = ((f.payload[2].toInt() and 0xFF) shl 8) or (f.payload[3].toInt() and 0xFF)
        val ch = channels.firstOrNull { it.device == f.src } ?: return
        if (type and DATA != 0 && f.payload.size > 8) {
            ch.rx += f.payload.size - 8
            ch.stream.write(f.payload, 8, f.payload.size - 8)
        }
    }

    /** The whole messages come in on [ch], taken from its stream. */
    private fun takeMessages(ch: Channel): List<Message> {
        val bytes = ch.stream.toByteArray()
        val out = ArrayList<Message>()
        var at = 0
        while (bytes.size - at >= 8) {
            val service = (bytes[at + 2].toInt() and 0xFF) or ((bytes[at + 3].toInt() and 0xFF) shl 8)
            val len = (bytes[at + 4].toLong() and 0xFF) or ((bytes[at + 5].toLong() and 0xFF) shl 8) or
                ((bytes[at + 6].toLong() and 0xFF) shl 16) or ((bytes[at + 7].toLong() and 0xFF) shl 24)
            if (len > MAX_MESSAGE) { log("POD Go: a message of $len bytes - the stream lost its place"); at = bytes.size; break }
            if (bytes.size - at - 8 < len) break
            val body = runCatching { MsgPack.decode(bytes, at + 8, (at + 8 + len).toInt()) }.getOrElse { log("POD Go: a message not read (${it.message})"); null }
            out += Message(ch.device, service, body)
            at += 8 + len.toInt()
        }
        ch.stream.reset()
        if (at < bytes.size) ch.stream.write(bytes, at, bytes.size - at)
        return out
    }

    class Frame(val flags: Int, val dst: Int, val src: Int, val payload: ByteArray)

    companion object {
        const val VENDOR = 0x0E41
        const val POD_GO = 0x4247
        const val FLAG_DATA = 0x18
        const val FLAG_HANDSHAKE = 0x28
        const val ACK = 0x08
        const val DATA = 0x04
        const val HELLO = 0x02
        const val KEEPALIVE = 0x10
        const val ACK_BASE = 0x1000L
        const val HELLO_FIELD = 0x21000100
        val HELLO_TAIL = byteArrayOf(0x00, 0x10, 0x00, 0x00)
        const val REPLY_MS = 800
        const val CHUNK = 256
        const val MAX_MESSAGE = 16L * 1024 * 1024

        fun encodeHeader(seq: Int, type: Int, ack: Int): ByteArray {
            val b = ByteArray(8)
            b[0] = (seq shr 8).toByte(); b[1] = seq.toByte()
            b[2] = (type shr 8).toByte(); b[3] = type.toByte()
            le32(b, 4, ack)
            return b
        }

        fun encodeFrame(flags: Int, dst: Int, src: Int, payload: ByteArray): ByteArray {
            val total = (8 + payload.size + 3) / 4 * 4
            val out = ByteArray(total)
            out[0] = payload.size.toByte(); out[1] = (payload.size shr 8).toByte(); out[2] = (payload.size shr 16).toByte()
            out[3] = flags.toByte()
            le16(out, 4, dst); le16(out, 6, src)
            payload.copyInto(out, 8)
            return out
        }

        /** Every frame one transfer carries (the unit puts several in one at times). */
        fun decodeFrames(buf: ByteArray): List<Frame> {
            val out = ArrayList<Frame>()
            var at = 0
            while (buf.size - at >= 8) {
                val len = (buf[at].toInt() and 0xFF) or ((buf[at + 1].toInt() and 0xFF) shl 8) or ((buf[at + 2].toInt() and 0xFF) shl 16)
                if (at + 8 + len > buf.size) break
                out += Frame(buf[at + 3].toInt() and 0xFF, (buf[at + 4].toInt() and 0xFF) or ((buf[at + 5].toInt() and 0xFF) shl 8),
                    (buf[at + 6].toInt() and 0xFF) or ((buf[at + 7].toInt() and 0xFF) shl 8), buf.copyOfRange(at + 8, at + 8 + len))
                at += (8 + len + 3) / 4 * 4
            }
            return out
        }

        private fun le16(b: ByteArray, at: Int, v: Int) { b[at] = v.toByte(); b[at + 1] = (v shr 8).toByte() }
        private fun le32(b: ByteArray, at: Int, v: Int) { for (k in 0..3) b[at + k] = (v shr (8 * k)).toByte() }
    }
}

/** MessagePack, as much as the unit's messages use: maps, arrays, numbers, strings, raw bytes. */
object MsgPack {
    fun encodeUInt(v: Long): ByteArray = when {
        v < 0x80 -> byteArrayOf(v.toByte())
        v < 0x100 -> byteArrayOf(0xCC.toByte(), v.toByte())
        v < 0x10000 -> byteArrayOf(0xCD.toByte(), (v shr 8).toByte(), v.toByte())
        else -> byteArrayOf(0xCE.toByte(), (v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    }

    /** The value in [b] from [from] to [to]: Long, Double, Boolean, String, ByteArray, List, Map (keys as decoded), or null. */
    fun decode(b: ByteArray, from: Int, to: Int): Any? {
        val r = Reader(b, from, to)
        return r.value()
    }

    private class Reader(val b: ByteArray, var at: Int, val end: Int) {
        fun u8(): Int { require(at < end) { "message ends early" }; return b[at++].toInt() and 0xFF }
        fun be(n: Int): Long { var v = 0L; repeat(n) { v = (v shl 8) or u8().toLong() }; return v }
        fun bytes(n: Int): ByteArray { require(n in 0..(end - at)) { "message ends early" }; return b.copyOfRange(at, at + n).also { at += n } }
        fun value(): Any? {
            val t = u8()
            return when {
                t <= 0x7F -> t.toLong()
                t in 0x80..0x8F -> map(t and 0x0F)
                t in 0x90..0x9F -> list(t and 0x0F)
                t in 0xA0..0xBF -> String(bytes(t and 0x1F), Charsets.UTF_8)
                t >= 0xE0 -> (t - 256).toLong()
                else -> when (t) {
                    0xC0 -> null
                    0xC2 -> false
                    0xC3 -> true
                    0xC4 -> bytes(be(1).toInt()); 0xC5 -> bytes(be(2).toInt()); 0xC6 -> bytes(be(4).toInt())
                    0xCA -> java.lang.Float.intBitsToFloat(be(4).toInt()).toDouble()
                    0xCB -> java.lang.Double.longBitsToDouble(be(8))
                    0xCC -> be(1); 0xCD -> be(2); 0xCE -> be(4); 0xCF -> be(8)
                    0xD0 -> be(1).toByte().toLong(); 0xD1 -> be(2).toShort().toLong(); 0xD2 -> be(4).toInt().toLong(); 0xD3 -> be(8)
                    0xD9 -> String(bytes(be(1).toInt()), Charsets.UTF_8); 0xDA -> String(bytes(be(2).toInt()), Charsets.UTF_8); 0xDB -> String(bytes(be(4).toInt()), Charsets.UTF_8)
                    0xDC -> list(be(2).toInt()); 0xDD -> list(be(4).toInt())
                    0xDE -> map(be(2).toInt()); 0xDF -> map(be(4).toInt())
                    // Extension types: their bytes, unread.
                    0xD4 -> bytes(2); 0xD5 -> bytes(3); 0xD6 -> bytes(5); 0xD7 -> bytes(9); 0xD8 -> bytes(17)
                    0xC7 -> { val n = be(1).toInt(); bytes(n + 1) }
                    0xC8 -> { val n = be(2).toInt(); bytes(n + 1) }
                    0xC9 -> { val n = be(4).toInt(); bytes(n + 1) }
                    else -> throw IllegalArgumentException("type 0x${t.toString(16)}")
                }
            }
        }
        fun list(n: Int): List<Any?> = List(n) { value() }
        fun map(n: Int): Map<Any?, Any?> = LinkedHashMap<Any?, Any?>().also { m -> repeat(n) { m[value()] = value() } }
    }

    /** [v] as a short text, for the log. */
    fun show(v: Any?): String = when (v) {
        is Map<*, *> -> v.entries.joinToString(", ", "{", "}") { "${show(it.key)}: ${show(it.value)}" }
        is List<*> -> v.joinToString(", ", "[", "]") { show(it) }
        is ByteArray -> if (v.size <= 16) v.joinToString(" ", "<", ">") { "%02x".format(it) } else "<${v.size} bytes>"
        is String -> "\"$v\""
        else -> v.toString()
    }
}
