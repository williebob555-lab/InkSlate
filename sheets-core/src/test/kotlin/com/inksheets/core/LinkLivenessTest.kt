package com.inksheets.core

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException

class LinkLivenessTest {
    @Test fun `a failed Bluetooth greeting closes the unreadable pipe and retries`() {
        val attempts = CountDownLatch(2)
        val reads = AtomicInteger()
        val closes = AtomicInteger()
        val client = RemoteClient("Phone", silentMs = 200, pingEveryMs = 30, ownAddresses = { emptyList() }) {}
        client.bluetooth = object : RemoteBluetooth {
            override fun ready() = true
            override fun paired() = emptyList<Pair<String, String>>()
            override fun listen(take: (RemotePipe) -> Unit): RemoteBluetooth.Listening? = null
            override fun stopListening() {}
            override fun connect(address: String, channel: Int?): RemotePipe {
                attempts.countDown()
                val closed = CountDownLatch(1)
                return object : RemotePipe {
                    override val address = "bt:test"
                    override val input = object : InputStream() {
                        override fun read(): Int { reads.incrementAndGet(); closed.await(3, TimeUnit.SECONDS); return -1 }
                    }
                    override val output = object : OutputStream() { override fun write(b: Int) { throw IOException("radio write failed") } }
                    override fun close() { closes.incrementAndGet(); closed.countDown() }
                }
            }
        }
        try {
            client.start(RemoteLink.Target("Stand", emptyList(), key = "KEY", bt = "test"))
            assertTrue("failed greeting never retried", attempts.await(2, TimeUnit.SECONDS))
            assertEquals("failed output must not enter blocking input", 0, reads.get())
            assertTrue(closes.get() >= 1)
            assertNotEquals(ConnectionStatus.CONNECTED, client.connectionStatus)
        } finally { client.stop() }
    }

    @Test fun `Bluetooth host heartbeat and commands work while the TCP port is unavailable`() {
        ServerSocket(0).use { occupied ->
            val host = RemoteHost("Stand", "KEY", occupied.localPort, pingEveryMs = 30, silentMs = 500)
            assertTrue(host.start(tcp = false))
            val commands = LinkedBlockingQueue<RemoteLink.Command>()
            host.onCommand = { commands.offer(it) }
            try { ServerSocket(0).use { transport ->
                Socket("127.0.0.1", transport.localPort).use { peer ->
                    val accepted = transport.accept()
                    host.take(SocketPipe(accepted))
                    peer.soTimeout = 1500
                    val out = peer.getOutputStream().bufferedWriter()
                    val input = peer.getInputStream().bufferedReader()
                    out.write(RemoteLink.encode(RemoteLink.Hello(name = "Phone", key = "KEY", v = 2)) + "\n"); out.flush()
                    assertEquals(RemoteLink.Line.Ping, RemoteLink.read(input.readLine()))
                    out.write(RemoteLink.encode(RemoteLink.Command(action = "next")) + "\n"); out.flush()
                    assertEquals("next", commands.poll(1500, TimeUnit.MILLISECONDS)?.action)
                }
            } } finally { host.stop() }
            val leader = CompanionLeader("Director", occupied.localPort)
            try { assertTrue(leader.start(tcp = false)) } finally { leader.stop() }
        }
    }

    @Test fun `idle evidence ages out and an old generation cannot revive it`() {
        var time = 0L
        val live = LinkLiveness(now = { time })
        val old = live.begin()
        assertEquals(ConnectionStatus.RECONNECTING, live.status())
        assertTrue(live.received(old))
        assertEquals(ConnectionStatus.CONNECTED, live.status())
        time = 4_000
        assertEquals(ConnectionStatus.RECONNECTING, live.status())
        time = 7_000
        assertEquals(ConnectionStatus.DISCONNECTED, live.status())
        live.lost(old)
        assertEquals(ConnectionStatus.DISCONNECTED, live.status())
        val fresh = live.begin()
        assertFalse(live.received(old))
        assertEquals(ConnectionStatus.RECONNECTING, live.status())
        assertTrue(live.received(fresh))
        assertEquals(ConnectionStatus.CONNECTED, live.status())
        live.stop()
        assertFalse(live.received(fresh))
        assertEquals(ConnectionStatus.DISCONNECTED, live.status())
    }

    @Test fun `Bluetooth-only join links preserve the full-name session`() {
        val name = "A very long conductor name"
        val session = MeshFrames.sessionOf(name)
        val link = CompanionLink.joinLink(name, emptyList(), bluetoothSession = session)
        val parsed = CompanionLink.parseJoin(link)!!
        assertTrue(parsed.hosts.isEmpty())
        assertEquals(session, parsed.bluetoothSession)
        assertEquals(name, parsed.name)
        assertNull(CompanionLink.parseJoin(CompanionLink.joinLink(name, emptyList())))
    }

    @Test fun `Bluetooth heartbeat frames fit the radio and preserve identity`() {
        val beacon = MeshFrames.Beacon(65535, 0xffffffffL, "🎼 Long named conductor")
        val encoded = MeshFrames.encode(beacon)
        assertTrue(encoded.size <= MeshFrames.MAX_BYTES)
        val decoded = MeshFrames.decode(encoded) as MeshFrames.Beacon
        assertEquals(beacon.session, decoded.session)
        assertEquals(beacon.seq, decoded.seq)
        assertFalse(decoded.name.contains('\uFFFD'))
        val presence = MeshFrames.Presence(123, 0xffffffffL, 22, 18)
        assertEquals(presence, MeshFrames.decode(MeshFrames.encode(presence)))
        assertNull(MeshFrames.decode(MeshFrames.encode(presence.copy(hops = 7))))
    }
}
