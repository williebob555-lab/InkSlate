package com.inksheets.desktop.review

import com.inksheets.core.RemoteClient
import com.inksheets.core.RemoteHost
import com.inksheets.core.RemoteLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** The Remote wire (host + client) on loopback: speed, many remotes, bad input, dead links. */
class T4RemoteCoreTest {

    private fun host(key: String = "K7Q2PX", port: Int = T4.freePort(), silentMs: Long = RemoteHost.SILENT_FOR_MS, pingMs: Long = RemoteHost.PING_EVERY_MS): Pair<RemoteHost, Int> {
        val h = RemoteHost("T4 host", key, port, silentMs, pingMs)
        assertTrue(h.start())
        return h to port
    }

    private fun target(port: Int, key: String = "K7Q2PX") = RemoteLink.Target("T4 host", listOf("127.0.0.1"), port, key)

    @Test
    fun `press to command and press to tick latency on loopback`() {
        val (h, port) = host()
        val sentAt = ConcurrentHashMap<Int, Long>()
        val arrived = ConcurrentHashMap<Int, Long>()
        val got = ConcurrentHashMap<Int, Long>()
        h.onCommand = { c -> arrived[c.seq] = System.nanoTime() }
        val connected = java.util.concurrent.CountDownLatch(1)
        val c = RemoteClient("T4 remote", onLine = { line -> if (line is RemoteLink.Line.Got) got[line.seq] = System.nanoTime() })
        c.onConnected = { if (it) connected.countDown() }
        try {
            c.start(target(port))
            assertTrue(connected.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val cmd = RemoteLink.Command(action = "NEXT_PAGE")
            repeat(300) {
                val t = System.nanoTime()
                val n = c.send(cmd)
                sentAt[n] = t
                Thread.sleep(15)
            }
            T4.waitFor(2000) { got.size >= 300 }
            val toHost = sentAt.keys.mapNotNull { k -> arrived[k]?.let { (it - sentAt[k]!!) / 1_000_000 } }
            val toTick = sentAt.keys.mapNotNull { k -> got[k]?.let { (it - sentAt[k]!!) / 1_000_000 } }
            T4.log("LATENCY remote press -> host command: " + T4.summary("cmd", toHost))
            T4.log("LATENCY remote press -> tick back:   " + T4.summary("tick", toTick))
            assertEquals(300, toHost.size)
        } finally { c.stop(); h.stop() }
    }

    @Test
    fun `a dozen remotes at once all get the state, and every press is counted`() {
        val (h, port) = host()
        val cmds = AtomicInteger()
        h.onCommand = { cmds.incrementAndGet() }
        val clients = (1..12).map { i ->
            val seen = AtomicLong()
            RemoteClient("remote $i", java.util.UUID.randomUUID().toString(), onLine = { if (it is RemoteLink.Line.Shows) seen.set(System.nanoTime()) }) to seen
        }
        try {
            clients.forEach { (c, _) -> c.start(target(port)) }
            assertTrue("12 remotes connect", T4.waitFor(6000) { h.remotes == 12 })
            val t0 = System.nanoTime()
            h.show(RemoteLink.State(title = "Fight Song", page = 3, pages = 4))
            T4.waitFor(3000) { clients.all { (_, s) -> s.get() > 0 } }
            val lat = clients.map { (_, s) -> (s.get() - t0) / 1_000_000 }
            T4.log("12 REMOTES state fan-out: " + T4.summary("state", lat))
            clients.forEach { (c, _) -> repeat(10) { c.send(RemoteLink.Command(action = "NEXT_PAGE")) } }
            assertTrue(T4.waitFor(3000) { cmds.get() == 120 })
        } finally { clients.forEach { it.first.stop() }; h.stop() }
    }

    @Test
    fun `wrong key is turned away and tried again at speed - no throttle`() {
        val (h, port) = host()
        try {
            var tried = 0
            val t0 = System.currentTimeMillis()
            var turnedAway = 0
            while (System.currentTimeMillis() - t0 < 3000) {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", port), 1000)
                    s.soTimeout = 1000
                    s.getOutputStream().write((RemoteLink.encode(RemoteLink.Hello(name = "x", key = "AAAAA$tried", id = "z")) + "\n").toByteArray())
                    val line = s.getInputStream().bufferedReader().readLine()
                    if (line != null && line.contains("refused")) turnedAway++
                }
                tried++
            }
            T4.log("BRUTE-FORCE wrong keys: $tried tries in 3 s ($turnedAway refused) - no delay or lockout; 6 chars of 32 = 1.07e9 keys")
            assertTrue(tried > 20)
        } finally { h.stop() }
    }

    @Test
    fun `hundreds of idle connections do not starve a real remote`() {
        val (h, port) = host()
        val idle = ArrayList<Socket>()
        try {
            val before = T4.threads()
            repeat(400) { runCatching { idle += Socket().apply { connect(InetSocketAddress("127.0.0.1", port), 1000) } } }
            Thread.sleep(300)
            val during = T4.threads()
            T4.log("IDLE FLOOD: 400 unauthenticated connections opened -> threads $before -> $during (2 per connection, no cap)")
            val cmds = AtomicInteger()
            h.onCommand = { cmds.incrementAndGet() }
            val c = RemoteClient("real remote", onLine = {})
            val ok = java.util.concurrent.CountDownLatch(1)
            c.onConnected = { if (it) ok.countDown() }
            val t0 = System.currentTimeMillis()
            c.start(target(port))
            val connected = ok.await(5, java.util.concurrent.TimeUnit.SECONDS)
            T4.log("IDLE FLOOD: real remote connected=$connected in ${System.currentTimeMillis() - t0} ms under 400 idle sockets")
            assertTrue(connected)
            c.stop()
        } finally { idle.forEach { runCatching { it.close() } }; h.stop() }
    }

    @Test
    fun `a line that never ends is read into memory without limit`() {
        val (h, port) = host()
        try {
            val before = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
            val s = Socket("127.0.0.1", port)
            val out = s.getOutputStream()
            val chunk = ByteArray(1 shl 20) { 'a'.code.toByte() }
            var sent = 0
            val t0 = System.currentTimeMillis()
            // 96 MB of one line, no newline, no hello.
            repeat(96) { runCatching { out.write(chunk); sent++ } }
            out.flush()
            Thread.sleep(500)
            val after = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
            T4.log("ENDLESS LINE: sent ${sent} MB in ${System.currentTimeMillis() - t0} ms; heap in use ${before / 1_000_000} MB -> ${after / 1_000_000} MB (accepted; host drops it only after 10 s)")
            s.close()
        } finally { h.stop() }
    }

    /** A TCP relay that can be told to swallow everything: a Wi-Fi that stops carrying. */
    private class Relay(val to: Int) {
        val server = ServerSocket(0)
        @Volatile var blackhole = false
        val port get() = server.localPort
        private val pairs = CopyOnWriteArrayList<Socket>()
        init {
            Thread {
                while (!server.isClosed) {
                    val a = runCatching { server.accept() }.getOrNull() ?: break
                    val b = Socket("127.0.0.1", to)
                    pairs += a; pairs += b
                    fun pump(i: InputStream, o: OutputStream) = Thread {
                        val buf = ByteArray(8192)
                        runCatching { while (true) { val n = i.read(buf); if (n < 0) break; if (!blackhole) { o.write(buf, 0, n); o.flush() } } }
                    }.apply { isDaemon = true; start() }
                    pump(a.getInputStream(), b.getOutputStream()); pump(b.getInputStream(), a.getOutputStream())
                }
            }.apply { isDaemon = true; start() }
        }
        fun close() { runCatching { server.close() }; pairs.forEach { runCatching { it.close() } } }
    }

    @Test
    fun `a link that goes silent is noticed and a press during it is not answered`() {
        val (h, hostPort) = host()
        val relay = Relay(hostPort)
        val ticks = ConcurrentHashMap<Int, Long>()
        val up = AtomicLong(); val down = AtomicLong(); val upAgain = AtomicLong()
        val cmds = AtomicInteger()
        h.onCommand = { cmds.incrementAndGet() }
        val c = RemoteClient("flaky", onLine = { if (it is RemoteLink.Line.Got) ticks[it.seq] = System.nanoTime() })
        c.onConnected = { on ->
            val now = System.currentTimeMillis()
            if (on) { if (up.get() == 0L) up.set(now) else upAgain.set(now) } else down.set(now)
        }
        try {
            c.start(target(relay.port))
            assertTrue(T4.waitFor(5000) { up.get() > 0 })
            relay.blackhole = true
            val cut = System.currentTimeMillis()
            // Press five times into the dead link: each is written without error.
            val seqs = (1..5).map { c.send(RemoteLink.Command(action = "NEXT_PAGE")) }
            Thread.sleep(2600)
            T4.log("DEAD LINK: presses into a swallowing link return seq ${seqs}; ticks back after 2.6 s: ${ticks.size}; host got ${cmds.get()} commands")
            // Heal: new connections go through.
            val ok = T4.waitFor(15000) { down.get() > 0 }
            T4.log("DEAD LINK: remote noticed silence after ${down.get() - cut} ms (limit ${RemoteClient.SILENT_FOR_MS} ms)")
            relay.blackhole = false
            val back = T4.waitFor(15000) { upAgain.get() > 0 }
            T4.log("DEAD LINK: reconnected ${if (back) "${upAgain.get() - cut} ms after the cut" else "NEVER within 15 s"}")
            assertTrue(ok)
        } finally { c.stop(); h.stop(); relay.close() }
    }

    @Test
    fun `the host going away and coming back is found again by the remote`() {
        val port = T4.freePort()
        var h = RemoteHost("T4 host", "K7Q2PX", port).also { assertTrue(it.start()) }
        val up = AtomicInteger(); val down = AtomicInteger()
        val lastUp = AtomicLong(); val lastDown = AtomicLong()
        val c = RemoteClient("steady", onLine = {})
        c.onConnected = { on -> if (on) { up.incrementAndGet(); lastUp.set(System.currentTimeMillis()) } else { down.incrementAndGet(); lastDown.set(System.currentTimeMillis()) } }
        try {
            c.start(target(port))
            assertTrue(T4.waitFor(5000) { up.get() == 1 })
            h.stop()
            assertTrue(T4.waitFor(5000) { down.get() == 1 })
            val t = System.currentTimeMillis()
            h = RemoteHost("T4 host", "K7Q2PX", port).also { assertTrue(it.start()) }
            val ok = T4.waitFor(10000) { up.get() == 2 }
            T4.log("HOST RESTART: remote back ${if (ok) "${lastUp.get() - t} ms" else "NEVER"} after the host came back")
            assertTrue(ok)
        } finally { c.stop(); h.stop() }
    }

    @Test
    fun `rekey turns paired remotes away and they stay away - and a re-paired one gets in`() {
        val (h, port) = host()
        val refused = AtomicInteger()
        val c = RemoteClient("old pairing", onLine = { if (it is RemoteLink.Line.Refused) refused.incrementAndGet() })
        try {
            val up = AtomicInteger(); c.onConnected = { if (it) up.incrementAndGet() }
            c.start(target(port))
            assertTrue(T4.waitFor(5000) { up.get() == 1 })
            h.rekey("NEWKEY")
            assertTrue(T4.waitFor(4000) { refused.get() == 1 })
            Thread.sleep(2500)
            assertEquals("not tried again", 1, refused.get())
            val c2 = RemoteClient("new pairing", onLine = {})
            val up2 = AtomicInteger(); c2.onConnected = { if (it) up2.incrementAndGet() }
            c2.start(target(port, "newkey"))      // typed lowercase
            assertTrue(T4.waitFor(5000) { up2.get() == 1 })
            c2.stop()
        } finally { c.stop(); h.stop() }
    }

    @Test
    fun `the same remote id connecting twice counts once, two ids count twice`() {
        val (h, port) = host()
        try {
            val a1 = RemoteClient("A", "id-a", onLine = {}); val a2 = RemoteClient("A", "id-a", onLine = {}); val b = RemoteClient("B", "id-b", onLine = {})
            a1.start(target(port)); a2.start(target(port)); b.start(target(port))
            T4.waitFor(5000) { h.remotes == 2 }
            Thread.sleep(500)
            T4.log("REMOTE COUNT: ids a,a,b -> host counts ${h.remotes}")
            // Two remotes that fight over one id replace each other forever?
            val flips = AtomicInteger()
            val x = RemoteClient("A", "id-a", onLine = {})
            x.onConnected = { if (!it) flips.incrementAndGet() }
            x.start(target(port))
            Thread.sleep(6000)
            T4.log("SAME ID twice (a cloned remote id): connections dropped in 6 s = ${flips.get()} (a1,a2,x take turns knocking each other off)")
            listOf(a1, a2, b, x).forEach { it.stop() }
        } finally { h.stop() }
    }
}
