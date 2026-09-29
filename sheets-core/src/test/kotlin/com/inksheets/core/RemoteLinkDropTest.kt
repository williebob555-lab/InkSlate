package com.inksheets.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A remote over a network that is not the loopback of a test: connections that open but carry
 * nothing back, and connections the network loses without a word. Reported as "connected, but no
 * button does anything, and the device counts more and more remotes". Each test writes a timeline.
 */
class RemoteLinkDropTest {

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private val started = System.currentTimeMillis()
    private val timeline = Collections.synchronizedList(ArrayList<String>())
    private fun note(who: String, what: String) {
        val line = "%6d ms  %-6s %s".format(System.currentTimeMillis() - started, who, what)
        timeline += line
        println(line)
    }

    /**
     * Between remote and device: carries what the remote sends; what the device sends back is
     * carried only while [back] says so, and swallowed otherwise. [frozen] stops both ways without
     * closing anything - a link the network lost.
     */
    private inner class Middle(target: Int) {
        val server = ServerSocket(0)
        val port get() = server.localPort
        @Volatile var back = true
        @Volatile var frozen = false
        val links = Collections.synchronizedList(ArrayList<Pair<Socket, Socket>>())

        init {
            Thread({
                while (!server.isClosed) {
                    val remote = runCatching { server.accept() }.getOrNull() ?: break
                    val device = Socket().apply { connect(InetSocketAddress("127.0.0.1", target), 2000) }
                    val link = remote to device
                    links += link
                    pump(remote, device) { !frozen && link in links }
                    pump(device, remote) { back && !frozen && link in links }
                }
            }, "middle").apply { isDaemon = true; start() }
        }

        /** Stops carrying the links open now - without closing them - and carries new ones. */
        fun loseOpenLinks() {
            links.clear()
        }

        private fun pump(from: Socket, to: Socket, carry: () -> Boolean) = Thread({
            runCatching {
                val input = from.getInputStream()
                val output = to.getOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (carry()) { output.write(buf, 0, n); output.flush() }
                }
            }
            // Closing is passed on only while the link is carried; a lost link just goes quiet.
            if (carry()) runCatching { to.shutdownOutput() }
        }, "middle-pump").apply { isDaemon = true; start() }

        fun close() { runCatching { server.close() } }
    }

    private fun host(port: Int) = RemoteHost("Stand", "K7Q2PX", port, silentMs = 1_500, pingEveryMs = 300).apply {
        onLog = { note("device", it) }
        onRemotes = { note("device", "remotes: $it") }
    }

    private fun remote(lines: LinkedBlockingQueue<RemoteLink.Line>, connected: MutableList<Boolean>) =
        RemoteClient("Phone", "phone-1", silentMs = 1_200, pingEveryMs = 300, ownAddresses = { emptyList() }) { lines += it }.apply {
            onLog = { note("remote", it) }
            onConnected = { on -> connected += on; note("remote", if (on) "shows connected" else "shows not connected") }
        }

    @Test
    fun `a network that carries nothing back is never shown as connected, and never counted twice`() {
        val port = freePort()
        val host = host(port)
        assertTrue(host.start())
        host.show(RemoteLink.State(title = "Take On Me", page = 1, pages = 4))
        val middle = Middle(port).apply { back = false }
        val lines = LinkedBlockingQueue<RemoteLink.Line>()
        val connected = Collections.synchronizedList(ArrayList<Boolean>())
        val remote = remote(lines, connected)
        try {
            remote.start(RemoteLink.Target("Stand", listOf("127.0.0.1"), middle.port, "K7Q2PX"))
            var most = 0
            val until = System.currentTimeMillis() + 6_000
            while (System.currentTimeMillis() < until) {
                most = maxOf(most, host.remotes)
                Thread.sleep(50)
            }
            note("test", "most remotes counted at once: $most; connections made: ${middle.links.size}")
            assertTrue("reconnected several times: ${middle.links.size}", middle.links.size >= 2)
            assertEquals("one remote, counted once", 1, most)
            assertFalse("never heard from the device, so never connected", connected.contains(true))
        } finally {
            remote.stop(); middle.close(); host.stop()
        }
    }

    @Test
    fun `a link the network loses is let go, the remote comes back on its own, and is counted once`() {
        val port = freePort()
        val host = host(port)
        val commands = LinkedBlockingQueue<RemoteLink.Command>()
        host.onCommand = { commands += it }
        assertTrue(host.start())
        host.show(RemoteLink.State(title = "Take On Me", page = 1, pages = 4))
        val middle = Middle(port)
        val lines = LinkedBlockingQueue<RemoteLink.Line>()
        val connected = Collections.synchronizedList(ArrayList<Boolean>())
        val remote = remote(lines, connected)
        try {
            remote.start(RemoteLink.Target("Stand", listOf("127.0.0.1"), middle.port, "K7Q2PX"))
            waitFor { connected.lastOrNull() == true && host.remotes == 1 }

            // A press is answered, by its number.
            var seq = 0
            waitFor { remote.send(RemoteLink.Command(action = "NEXT_PAGE")).also { seq = it } != 0 }
            assertEquals("NEXT_PAGE", commands.poll(3, TimeUnit.SECONDS)!!.action)
            assertTrue(generateSequence { lines.poll(3, TimeUnit.SECONDS) }.any { it is RemoteLink.Line.Got && it.seq == seq })

            note("test", "the network loses the link")
            middle.loseOpenLinks()
            var most = 0
            val until = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < until) {
                most = maxOf(most, host.remotes)
                Thread.sleep(50)
            }
            note("test", "most remotes counted at once: $most; connections made: ${middle.links.size}")
            assertEquals(1, most)
            waitFor { connected.lastOrNull() == true && host.remotes == 1 }
            waitFor { remote.send(RemoteLink.Command(action = "PREVIOUS_PAGE")) != 0 }
            assertEquals("PREVIOUS_PAGE", generateSequence { commands.poll(3, TimeUnit.SECONDS) }.first().action)
        } finally {
            remote.stop(); middle.close(); host.stop()
        }
    }

    @Test
    fun `an address that goes nowhere does not hold up the one that answers`() {
        val port = freePort()
        val host = host(port)
        assertTrue(host.start())
        host.show(RemoteLink.State(title = "Take On Me"))
        val lines = LinkedBlockingQueue<RemoteLink.Line>()
        val connected = Collections.synchronizedList(ArrayList<Boolean>())
        val remote = remote(lines, connected)
        try {
            // 10.255.255.1 is not routed anywhere: a connection to it hangs until the timeout.
            val t0 = System.currentTimeMillis()
            remote.start(RemoteLink.Target("Stand", listOf("10.255.255.1", "127.0.0.1"), port, "K7Q2PX"))
            waitFor { connected.lastOrNull() == true }
            val took = System.currentTimeMillis() - t0
            note("test", "connected in $took ms")
            assertTrue("took $took ms", took < 2_000)
        } finally {
            remote.stop(); host.stop()
        }
    }

    private fun waitFor(ms: Long = 5000, check: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        var ok = check()
        while (!ok && System.currentTimeMillis() < until) { Thread.sleep(20); ok = check() }
        assertTrue("timed out\n" + timeline.joinToString("\n"), ok)
    }
}
