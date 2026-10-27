package com.inksheets.core

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket

class ConnectedPeerTest {
    @Test fun `host never lists a silent socket or a remote with the wrong pairing key`() {
        val host = RemoteHost("Stand", "RIGHT", pingEveryMs = 30)
        assertTrue(host.start(tcp = false))
        try {
            ServerSocket(0).use { wire ->
                Socket("127.0.0.1", wire.localPort).use { remote ->
                    host.take(SocketPipe(wire.accept()))
                    assertTrue(host.connectedDevices.isEmpty())
                    remote.getOutputStream().write((RemoteLink.encode(RemoteLink.Hello(name = "Wrong phone", key = "WRONG", id = "bad", v = 2)) + "\n").toByteArray())
                    remote.soTimeout = 1500
                    assertEquals(RemoteLink.Line.Refused, RemoteLink.read(remote.getInputStream().bufferedReader().readLine()))
                    assertTrue(host.connectedDevices.isEmpty())
                }
                Socket("127.0.0.1", wire.localPort).use { remote ->
                    host.take(SocketPipe(wire.accept()))
                    remote.getOutputStream().write((RemoteLink.encode(RemoteLink.Hello(name = "My phone", key = "RIGHT", id = "phone", v = 2)) + "\n").toByteArray())
                    val until = System.currentTimeMillis() + 1500
                    while (host.connectedDevices.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(10)
                    val peer = host.connectedDevices.single()
                    assertEquals("My phone", peer.name)
                    assertEquals("remote:phone", peer.id)
                    assertEquals(ConnectionStatus.CONNECTED, peer.status)
                    assertTrue(peer.lastHeard > 0)
                }
            }
        } finally { host.stop() }
    }

    @Test fun `freshness requires inbound evidence and expires without commands`() {
        assertEquals(ConnectionStatus.RECONNECTING, peerStatus(0, 100_000))
        assertEquals(ConnectionStatus.CONNECTED, peerStatus(1_000, 4_999))
        assertEquals(ConnectionStatus.RECONNECTING, peerStatus(1_000, 5_000))
        assertEquals(ConnectionStatus.DISCONNECTED, peerStatus(1_000, 8_000))
    }

    @Test fun `mixed transports deduplicate the same identity but preserve distinct same-name players`() {
        val same = "participant:${MeshFrames.participantOf("tablet-a")}"
        val wifi = ConnectedPeer(same, "Wilson's tablet", "Wi-Fi", ConnectionStatus.RECONNECTING, 1_000)
        val bluetooth = ConnectedPeer(same, "Wils", "Bluetooth", ConnectionStatus.CONNECTED, 5_000)
        val other = ConnectedPeer("participant:${MeshFrames.participantOf("tablet-b")}", "Wilson's tablet", "Bluetooth", ConnectionStatus.CONNECTED, 5_000)
        val result = mergeConnectedPeers(listOf(wifi, bluetooth, other))
        assertEquals(2, result.size)
        val joined = result.single { it.id == same }
        assertEquals("Wilson's tablet", joined.name)
        assertEquals("Bluetooth + Wi-Fi", joined.transport)
        assertEquals(ConnectionStatus.CONNECTED, joined.status)
        assertEquals(5_000L, joined.lastHeard)
        assertEquals(result, mergeConnectedPeers(listOf(other, bluetooth, wifi)))
    }

    @Test fun `presence names fit twenty bytes while legacy frames remain readable`() {
        val named = MeshFrames.Presence(321, 99, 15, 14, name = "🎼 tablet")
        val bytes = MeshFrames.encode(named)
        assertEquals(20, bytes.size)
        val decoded = MeshFrames.decode(bytes) as MeshFrames.Presence
        assertEquals("🎼", decoded.name)
        assertEquals(named.participant, decoded.participant)
        val legacy = MeshFrames.Presence(321, 99, 15, 14)
        assertEquals(16, MeshFrames.encode(legacy).size)
        assertEquals(legacy, MeshFrames.decode(MeshFrames.encode(legacy)))
    }
}
