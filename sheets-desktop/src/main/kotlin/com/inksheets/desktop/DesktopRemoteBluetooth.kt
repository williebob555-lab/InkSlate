package com.inksheets.desktop

import com.inkslate.desktop.EventLog
import com.inksheets.core.RemoteBluetooth
import com.inksheets.core.RemotePipe
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * A remote over classic Bluetooth (RFCOMM) on a computer - the way round a Wi-Fi that lets a
 * connection open and then carries nothing (eduroam). Java has no Bluetooth of its own, so the
 * system's sockets are used directly: Winsock's on Windows, BlueZ's on Linux.
 */
object DesktopRemoteBluetooth {
    fun forThisSystem(): RemoteBluetooth? = runCatching {
        val os = System.getProperty("os.name").lowercase()
        when {
            os.contains("win") -> WindowsBluetooth()
            os.contains("linux") -> LinuxBluetooth()
            else -> null
        }
    }.onFailure { EventLog.info("sheets", "Remote: no Bluetooth here - ${it.message}") }.getOrNull()

    /** "AA:BB:CC:DD:EE:FF" as a number, first byte highest. */
    internal fun parse(address: String): Long =
        address.split(':').fold(0L) { acc, part -> (acc shl 8) or part.toLong(16) }

    internal fun format(address: Long): String =
        (5 downTo 0).joinToString(":") { "%02X".format((address shr (it * 8)) and 0xFF) }
}

/** A connected socket, read and written through [recv] and [send]. */
private class NativePipe(
    override val address: String,
    private val recv: (ByteArray, Int) -> Int,
    private val send: (ByteArray, Int) -> Int,
    private val shut: () -> Unit
) : RemotePipe {
    @Volatile private var closed = false

    override val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) return -1
            val buf = ByteArray(len.coerceAtMost(4096))
            val n = recv(buf, buf.size)
            if (n <= 0) return -1
            System.arraycopy(buf, 0, b, off, n)
            return n
        }
    }

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            var at = 0
            while (at < len) {
                if (closed) throw java.io.IOException("closed")
                val chunk = b.copyOfRange(off + at, off + len)
                val n = send(chunk, chunk.size)
                if (n <= 0) throw java.io.IOException("Bluetooth send failed")
                at += n
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        shut()
    }
}

// ---- Windows ---------------------------------------------------------------------------------

@Suppress("FunctionName")
private interface Ws2 : Library {
    fun WSAStartup(version: Short, data: Pointer): Int
    fun socket(af: Int, type: Int, protocol: Int): Pointer?
    fun bind(s: Pointer, addr: ByteArray, len: Int): Int
    fun listen(s: Pointer, backlog: Int): Int
    fun accept(s: Pointer, addr: ByteArray?, len: IntByReference?): Pointer?
    fun connect(s: Pointer, addr: ByteArray, len: Int): Int
    fun getsockname(s: Pointer, addr: ByteArray, len: IntByReference): Int
    fun recv(s: Pointer, buf: ByteArray, len: Int, flags: Int): Int
    fun send(s: Pointer, buf: ByteArray, len: Int, flags: Int): Int
    fun closesocket(s: Pointer): Int
    fun shutdown(s: Pointer, how: Int): Int
    fun WSAGetLastError(): Int
    fun WSASetServiceW(query: WsaQuerySet, op: Int, flags: Int): Int
}

@Suppress("FunctionName")
private interface BthApi : Library {
    fun BluetoothFindFirstRadio(params: Pointer, radio: com.sun.jna.ptr.PointerByReference): Pointer?
    fun BluetoothGetRadioInfo(radio: Pointer, info: Pointer): Int
    fun BluetoothFindRadioClose(find: Pointer): Boolean
}

/** SOCKET_ADDRESS */
@Structure.FieldOrder("lpSockaddr", "iSockaddrLength")
open class SocketAddress : Structure() {
    @JvmField var lpSockaddr: Pointer? = null
    @JvmField var iSockaddrLength: Int = 0
}

/** CSADDR_INFO */
@Structure.FieldOrder("LocalAddr", "RemoteAddr", "iSocketType", "iProtocol")
class CsAddrInfo : Structure() {
    @JvmField var LocalAddr = SocketAddress()
    @JvmField var RemoteAddr = SocketAddress()
    @JvmField var iSocketType: Int = 0
    @JvmField var iProtocol: Int = 0
}

/** WSAQUERYSETW */
@Structure.FieldOrder(
    "dwSize", "lpszServiceInstanceName", "lpServiceClassId", "lpVersion", "lpszComment", "dwNameSpace",
    "lpNSProviderId", "lpszContext", "dwNumberOfProtocols", "lpafpProtocols", "lpszQueryString",
    "dwNumberOfCsAddrs", "lpcsaBuffer", "dwOutputFlags", "lpBlob"
)
class WsaQuerySet : Structure() {
    @JvmField var dwSize: Int = 0
    @JvmField var lpszServiceInstanceName: WString? = null
    @JvmField var lpServiceClassId: Pointer? = null
    @JvmField var lpVersion: Pointer? = null
    @JvmField var lpszComment: WString? = null
    @JvmField var dwNameSpace: Int = 0
    @JvmField var lpNSProviderId: Pointer? = null
    @JvmField var lpszContext: WString? = null
    @JvmField var dwNumberOfProtocols: Int = 0
    @JvmField var lpafpProtocols: Pointer? = null
    @JvmField var lpszQueryString: WString? = null
    @JvmField var dwNumberOfCsAddrs: Int = 0
    @JvmField var lpcsaBuffer: Pointer? = null
    @JvmField var dwOutputFlags: Int = 0
    @JvmField var lpBlob: Pointer? = null
}

private class WindowsBluetooth : RemoteBluetooth {
    private val ws2: Ws2 = Native.load("ws2_32", Ws2::class.java)
    @Volatile private var server: Pointer? = null
    @Volatile private var registered: WsaQuerySet? = null
    /** Kept alive while the service is registered: Windows reads them. */
    private var keep: List<Any> = emptyList()

    init {
        ws2.WSAStartup(0x0202, Memory(512))
    }

    private fun invalid(p: Pointer?) = p == null || Pointer.nativeValue(p) == -1L

    /** SOCKADDR_BTH, packed: family, address, service GUID, port. */
    private fun sockaddr(address: Long, uuid: UUID?, port: Int): ByteArray {
        val b = java.nio.ByteBuffer.allocate(SOCKADDR_BTH).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.putShort(AF_BTH.toShort())
        b.putLong(address)
        b.put(guid(uuid))
        b.putInt(port)
        return b.array()
    }

    /** A GUID as Windows lays it out: the first three parts little-endian. */
    private fun guid(uuid: UUID?): ByteArray {
        val b = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        if (uuid == null) return b.array()
        val hi = uuid.mostSignificantBits
        b.putInt((hi ushr 32).toInt())
        b.putShort((hi ushr 16).toShort())
        b.putShort(hi.toShort())
        b.order(java.nio.ByteOrder.BIG_ENDIAN).putLong(uuid.leastSignificantBits)
        return b.array()
    }

    override fun ready(): Boolean = true

    override fun listen(take: (RemotePipe) -> Unit): RemoteBluetooth.Listening? {
        stopListening()
        val s = ws2.socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM)
        if (invalid(s)) { EventLog.info("sheets", "Remote: no Bluetooth socket (error ${ws2.WSAGetLastError()}) - is Bluetooth on?"); return null }
        s!!
        val addr = sockaddr(0, null, BT_PORT_ANY)
        if (ws2.bind(s, addr, addr.size) != 0 || ws2.listen(s, 4) != 0) {
            EventLog.info("sheets", "Remote: Bluetooth would not listen (error ${ws2.WSAGetLastError()})")
            ws2.closesocket(s); return null
        }
        val bound = ByteArray(SOCKADDR_BTH)
        val len = IntByReference(bound.size)
        ws2.getsockname(s, bound, len)
        val info = java.nio.ByteBuffer.wrap(bound).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val local = info.getLong(2)
        val channel = info.getInt(26)
        register(bound)
        server = s
        Thread({
            while (server == s) {
                val c = ws2.accept(s, null, null)
                if (invalid(c)) break
                c!!
                val peer = ByteArray(SOCKADDR_BTH)
                ws2.getsockname(c, peer, IntByReference(peer.size))
                runCatching { take(pipe(c, "bt:remote")) }.onFailure { ws2.closesocket(c) }
            }
        }, "remote-bt-accept").apply { isDaemon = true; start() }
        val address = (local.takeIf { it != 0L } ?: radioAddress())?.let(DesktopRemoteBluetooth::format)
        EventLog.info("sheets", "Remote: Bluetooth listening on channel $channel" + (address?.let { " at $it" } ?: ""))
        return RemoteBluetooth.Listening(address, null)
    }

    /** The first radio's address, from the Bluetooth API: BLUETOOTH_RADIO_INFO.address. */
    private fun radioAddress(): Long? = runCatching {
        val api = runCatching { Native.load("BluetoothApis", BthApi::class.java) }.getOrElse { Native.load("bthprops.cpl", BthApi::class.java) }
        val params = Memory(4).apply { setInt(0, 4) }
        val radio = com.sun.jna.ptr.PointerByReference()
        val find = api.BluetoothFindFirstRadio(params, radio) ?: return null
        val info = Memory(520).apply { clear(); setInt(0, 520) }
        val ok = api.BluetoothGetRadioInfo(radio.value, info) == 0
        api.BluetoothFindRadioClose(find)
        if (ok) info.getLong(8) else null
    }.getOrNull()

    /** The service put in this computer's Bluetooth list, so a phone finds it by [RemoteBluetooth.UUID]. */
    private fun register(bound: ByteArray) {
        runCatching {
            val sa = Memory(bound.size.toLong()).apply { write(0, bound, 0, bound.size) }
            val id = Memory(16).apply { write(0, guid(RemoteBluetooth.UUID), 0, 16) }
            val cs = CsAddrInfo().apply {
                LocalAddr.lpSockaddr = sa
                LocalAddr.iSockaddrLength = bound.size
                iSocketType = SOCK_STREAM
                iProtocol = BTHPROTO_RFCOMM
                write()
            }
            val q = WsaQuerySet().apply {
                dwSize = size()
                lpszServiceInstanceName = WString(RemoteBluetooth.NAME)
                lpServiceClassId = id
                dwNameSpace = NS_BTH
                dwNumberOfCsAddrs = 1
                lpcsaBuffer = cs.pointer
            }
            val r = ws2.WSASetServiceW(q, RNRSERVICE_REGISTER, 0)
            if (r != 0) EventLog.info("sheets", "Remote: Bluetooth service not listed (error ${ws2.WSAGetLastError()})")
            else { registered = q; keep = listOf(sa, id, cs, q) }
        }.onFailure { EventLog.info("sheets", "Remote: Bluetooth service not listed - ${it.message}") }
    }

    private fun pipe(s: Pointer, address: String) = NativePipe(
        address,
        recv = { buf, n -> ws2.recv(s, buf, n, 0) },
        send = { buf, n -> ws2.send(s, buf, n, 0) },
        shut = { ws2.shutdown(s, 2); ws2.closesocket(s) }
    )

    override fun stopListening() {
        registered?.let { q -> runCatching { ws2.WSASetServiceW(q, RNRSERVICE_DELETE, 0) } }
        registered = null
        keep = emptyList()
        val s = server ?: return
        server = null
        ws2.closesocket(s)
    }

    override fun connect(address: String, channel: Int?): RemotePipe? {
        val s = ws2.socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM)
        if (invalid(s)) return null
        s!!
        val to = sockaddr(DesktopRemoteBluetooth.parse(address), RemoteBluetooth.UUID, channel ?: 0)
        if (ws2.connect(s, to, to.size) != 0) {
            EventLog.info("sheets", "Remote: Bluetooth to $address failed (error ${ws2.WSAGetLastError()})")
            ws2.closesocket(s); return null
        }
        return pipe(s, "bt:$address")
    }

    /** Paired devices are not listed here: a computer is the device controlled, or given the code. */
    override fun paired(): List<Pair<String, String>> = emptyList()

    companion object {
        const val AF_BTH = 32
        const val SOCK_STREAM = 1
        const val BTHPROTO_RFCOMM = 3
        const val BT_PORT_ANY = -1
        const val SOCKADDR_BTH = 30
        const val NS_BTH = 16
        const val RNRSERVICE_REGISTER = 0
        const val RNRSERVICE_DELETE = 2
    }
}

// ---- Linux -----------------------------------------------------------------------------------

private interface LibC : Library {
    fun socket(domain: Int, type: Int, protocol: Int): Int
    fun bind(fd: Int, addr: ByteArray, len: Int): Int
    fun listen(fd: Int, backlog: Int): Int
    fun accept(fd: Int, addr: ByteArray?, len: IntByReference?): Int
    fun connect(fd: Int, addr: ByteArray, len: Int): Int
    fun read(fd: Int, buf: ByteArray, len: Long): Long
    fun write(fd: Int, buf: ByteArray, len: Long): Long
    fun shutdown(fd: Int, how: Int): Int
    fun close(fd: Int): Int
}

/**
 * BlueZ's RFCOMM sockets. The service is not put in the device list (that needs BlueZ's D-Bus
 * profile manager), so it listens on a fixed channel, which the pairing code carries.
 */
private class LinuxBluetooth : RemoteBluetooth {
    private val c: LibC = Native.load("c", LibC::class.java)
    @Volatile private var server = -1

    /** sockaddr_rc: family, the address byte-reversed, channel. */
    private fun sockaddr(address: Long, channel: Int): ByteArray {
        val b = ByteArray(10)
        b[0] = AF_BLUETOOTH.toByte(); b[1] = 0
        for (i in 0 until 6) b[2 + i] = ((address shr (8 * i)) and 0xFF).toByte()
        b[8] = channel.toByte()
        return b
    }

    override fun ready(): Boolean = true

    override fun listen(take: (RemotePipe) -> Unit): RemoteBluetooth.Listening? {
        stopListening()
        val fd = c.socket(AF_BLUETOOTH, SOCK_STREAM, BTPROTO_RFCOMM)
        if (fd < 0) { EventLog.info("sheets", "Remote: no Bluetooth socket - is Bluetooth on?"); return null }
        val addr = sockaddr(0, RemoteBluetooth.CHANNEL)
        if (c.bind(fd, addr, addr.size) != 0 || c.listen(fd, 4) != 0) {
            EventLog.info("sheets", "Remote: Bluetooth channel ${RemoteBluetooth.CHANNEL} is taken")
            c.close(fd); return null
        }
        server = fd
        Thread({
            while (server == fd) {
                val s = c.accept(fd, null, null)
                if (s < 0) break
                runCatching { take(pipe(s, "bt:remote")) }.onFailure { c.close(s) }
            }
        }, "remote-bt-accept").apply { isDaemon = true; start() }
        return RemoteBluetooth.Listening(controllerAddress(), RemoteBluetooth.CHANNEL)
    }

    private fun pipe(fd: Int, address: String) = NativePipe(
        address,
        recv = { buf, n -> c.read(fd, buf, n.toLong()).toInt() },
        send = { buf, n -> c.write(fd, buf, n.toLong()).toInt() },
        shut = { c.shutdown(fd, 2); c.close(fd) }
    )

    /** This computer's Bluetooth address, as `bluetoothctl` gives it. */
    private fun controllerAddress(): String? = runCatching {
        val p = ProcessBuilder("bluetoothctl", "list").redirectErrorStream(true).start()
        if (!p.waitFor(2, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
        Regex("Controller (([0-9A-F]{2}:){5}[0-9A-F]{2})", RegexOption.IGNORE_CASE)
            .find(p.inputStream.bufferedReader().readText())?.groupValues?.get(1)?.uppercase()
    }.getOrNull()

    override fun stopListening() {
        val fd = server
        if (fd < 0) return
        server = -1
        c.shutdown(fd, 2)
        c.close(fd)
    }

    override fun connect(address: String, channel: Int?): RemotePipe? {
        val fd = c.socket(AF_BLUETOOTH, SOCK_STREAM, BTPROTO_RFCOMM)
        if (fd < 0) return null
        val to = sockaddr(DesktopRemoteBluetooth.parse(address), channel ?: RemoteBluetooth.CHANNEL)
        if (c.connect(fd, to, to.size) != 0) { c.close(fd); return null }
        return pipe(fd, "bt:$address")
    }

    override fun paired(): List<Pair<String, String>> = emptyList()

    companion object {
        const val AF_BLUETOOTH = 31
        const val SOCK_STREAM = 1
        const val BTPROTO_RFCOMM = 3
    }
}
