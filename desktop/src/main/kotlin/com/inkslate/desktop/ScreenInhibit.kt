package com.inkslate.desktop

import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.util.concurrent.Executors

/**
 * Keeping a Linux desktop's display awake, the way the desktop itself asks to be asked.
 *
 * KDE (and GNOME, and most others) answer `org.freedesktop.ScreenSaver.Inhibit` on the session
 * bus, and hold the inhibition for exactly as long as the connection that asked stays open. So
 * this opens its own connection, asks once, and closing the connection is the release - there is
 * no cookie to lose and nothing left behind if the program dies.
 *
 * The protocol is spoken directly rather than through a library: it is three short messages, and
 * a library for them would be the largest thing in the package after the runtime. Moving the
 * pointer, which is what Windows gets, is not an option here - under Wayland a synthetic pointer
 * event from a program is either ignored or answered with a "remote control" permission prompt.
 *
 * Every step fails soft: no bus, no screensaver service, a refusal - the display blanks as it
 * would have, and that is logged once.
 */
object ScreenInhibit {

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "screen-inhibit").apply { isDaemon = true } }

    @Volatile
    private var channel: SocketChannel? = null

    @Volatile
    private var refused = false

    /** Who wants the display kept on: each open document asks under its own name. */
    private val holders = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * [holder] wants the display kept on, or no longer does. The display stays on while anyone
     * does. Cheap to call repeatedly with the same answer; the work is off the UI thread.
     */
    fun hold(holder: String, on: Boolean) {
        if (on) holders.add(holder) else holders.remove(holder)
        val wanted = holders.isNotEmpty()
        // A desktop that said no once is not asked every two seconds; it is asked again the next
        // time something wants the display kept on after nothing did.
        if (!wanted) refused = false
        if (wanted && refused) return
        if (wanted == (channel != null)) return
        worker.execute {
            val now = holders.isNotEmpty()
            if (now && channel == null) {
                channel = runCatching { inhibit() }
                    .onSuccess { EventLog.info("screen", "Asked the desktop to keep the display on") }
                    .onFailure {
                        refused = true
                        EventLog.warn("screen", "Could not keep the display on: ${it.message}")
                    }
                    .getOrNull()
            } else if (!now) {
                channel?.let { runCatching { it.close() } }
                channel = null
            }
        }
    }

    /** Ask, and return the connection that holds the answer. Internal for the wire-format test. */
    internal fun inhibit(bus: String = busPath()): SocketChannel {
        val socket = SocketChannel.open(StandardProtocolFamily.UNIX)
        try {
            socket.connect(UnixDomainSocketAddress.of(bus))
            authenticate(socket)
            var serial = 1
            call(socket, serial++, "org.freedesktop.DBus", "/org/freedesktop/DBus",
                "org.freedesktop.DBus", "Hello", emptyList())
            expectReply(socket)
            call(socket, serial, "org.freedesktop.ScreenSaver", "/org/freedesktop/ScreenSaver",
                "org.freedesktop.ScreenSaver", "Inhibit", listOf("InkSlate", "A document is open"))
            expectReply(socket)
            return socket
        } catch (t: Throwable) {
            runCatching { socket.close() }
            throw t
        }
    }

    /** `unix:path=/run/user/1000/bus`, or the usual place when the variable is not set. */
    private fun busPath(): String {
        val address = System.getenv("DBUS_SESSION_BUS_ADDRESS").orEmpty()
        address.split(';').forEach { entry ->
            if (!entry.startsWith("unix:")) return@forEach
            entry.removePrefix("unix:").split(',').forEach { kv ->
                if (kv.startsWith("path=")) return kv.removePrefix("path=")
            }
        }
        return "/run/user/${uid()}/bus"
    }

    private fun uid(): String =
        File("/proc/self/status").readLines().first { it.startsWith("Uid:") }
            .split(Regex("\\s+"))[1]

    private fun authenticate(socket: SocketChannel) {
        val hexUid = uid().toByteArray().joinToString("") { "%02x".format(it) }
        write(socket, "\u0000AUTH EXTERNAL $hexUid\r\n".toByteArray())
        val answer = readLine(socket)
        check(answer.startsWith("OK")) { "the bus refused: $answer" }
        write(socket, "BEGIN\r\n".toByteArray())
    }

    // ---- the wire format -----------------------------------------------------

    /** A method call whose arguments are all strings, which is all this needs. */
    private fun call(
        socket: SocketChannel, serial: Int,
        destination: String, path: String, iface: String, member: String, args: List<String>
    ) {
        val body = Writer()
        args.forEach { body.string(it) }
        val bodyBytes = body.bytes()

        val m = Writer()
        m.byte('l'.code); m.byte(1); m.byte(0); m.byte(1)
        m.uint(bodyBytes.size); m.uint(serial)
        val fields = Writer().apply {
            field(1, "o", path)
            field(2, "s", iface)
            field(3, "s", member)
            field(6, "s", destination)
            if (args.isNotEmpty()) field(8, "g", "s".repeat(args.size))
        }.bytes()
        m.uint(fields.size)
        m.raw(fields)
        m.pad(8)
        m.raw(bodyBytes)
        write(socket, m.bytes())
    }

    /** Read messages until a reply or an error arrives; signals on the way are skipped. */
    private fun expectReply(socket: SocketChannel) {
        repeat(8) {
            val head = read(socket, 16).order(ByteOrder.LITTLE_ENDIAN)
            val big = head.get(0) == 'B'.code.toByte()
            head.order(if (big) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
            val type = head.get(1).toInt()
            val bodyLen = head.getInt(4)
            val fieldsLen = head.getInt(12)
            val headerEnd = 16 + fieldsLen
            val padded = (headerEnd + 7) / 8 * 8
            read(socket, padded - 16 + bodyLen)
            when (type) {
                2 -> return
                3 -> throw Refused()
            }
        }
        error("no reply from the desktop")
    }

    /** The bus understood the request and something said no - as opposed to a broken exchange. */
    internal class Refused : IllegalStateException("the desktop answered with an error")

    private class Writer {
        private val out = java.io.ByteArrayOutputStream()
        fun byte(b: Int) = out.write(b)
        fun raw(b: ByteArray) = out.write(b)
        fun pad(to: Int) { while (out.size() % to != 0) out.write(0) }
        fun uint(v: Int) { pad(4); out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()) }
        fun string(s: String) { val b = s.toByteArray(); uint(b.size); raw(b); byte(0) }
        fun signature(s: String) { byte(s.length); raw(s.toByteArray()); byte(0) }
        fun field(code: Int, type: String, value: String) {
            pad(8)
            byte(code)
            signature(type)
            if (type == "g") signature(value) else string(value)
        }
        fun bytes(): ByteArray = out.toByteArray()
    }

    private fun write(socket: SocketChannel, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) socket.write(buffer)
    }

    private fun read(socket: SocketChannel, n: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(n)
        while (buffer.hasRemaining()) check(socket.read(buffer) >= 0) { "the bus hung up" }
        return buffer.flip()
    }

    private fun readLine(socket: SocketChannel): String {
        val line = StringBuilder()
        val one = ByteBuffer.allocate(1)
        while (true) {
            one.clear()
            check(socket.read(one) > 0) { "the bus hung up" }
            val c = one.get(0).toInt().toChar()
            if (c == '\n') return line.toString().trimEnd('\r')
            line.append(c)
        }
    }
}
