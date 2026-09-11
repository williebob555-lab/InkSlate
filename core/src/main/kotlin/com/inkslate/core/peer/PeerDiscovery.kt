package com.inkslate.core.peer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Finding your other devices on the network you are both sitting on.
 *
 * The second way in, not the first. A configured address - a tailnet name, say - works from
 * anywhere and keeps working when the wifi changes; this only works when two devices share a
 * network segment, but it needs nothing typed in. Both end at the same place: a device tag, an
 * address, and a pairing code entered once.
 *
 * What goes out is deliberately dull: a name, a tag and a port. No document names, nothing about
 * what is open, and no secret - the announcement is a "here I am" that anyone on the network could
 * have worked out by looking at the port anyway. What it does *not* do is grant anything: a device
 * that has not been paired cannot push a single mark, however loudly it announces itself.
 */
class PeerDiscovery(
    private val selfTag: String,
    private val selfName: () -> String,
    private val servicePort: () -> Int,
    private val onSeen: (Announcement) -> Unit,
    private val log: (String, String) -> Unit = { _, _ -> }
) {

    @Serializable
    data class Announcement(
        @SerialName("tag") val tag: String,
        @SerialName("name") val name: String,
        @SerialName("port") val port: Int,
        @SerialName("protocol") val protocol: Int = PeerMessage.PROTOCOL,
        /** Filled in by the receiver from the packet, never by the sender. */
        @SerialName("address") val address: String = ""
    )

    @Volatile private var running = false
    private var socket: DatagramSocket? = null

    @Synchronized
    fun start() {
        if (running) return
        running = true
        val bound = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 1_000
                bind(InetSocketAddress(PORT))
            }
        }.getOrElse {
            log("warn", "Cannot look for devices on this network: ${it.message}")
            running = false
            return
        }
        socket = bound

        Thread({ listen(bound) }, "inkslate-discovery-listen").apply { isDaemon = true }.start()
        Thread({ announce(bound) }, "inkslate-discovery-say").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
    }

    private fun listen(socket: DatagramSocket) {
        val buffer = ByteArray(2048)
        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                return
            }
            val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
            val announcement = runCatching { json.decodeFromString<Announcement>(text) }.getOrNull()
                ?: continue
            // Our own broadcast comes back to us on most networks.
            if (announcement.tag == selfTag) continue
            if (announcement.protocol != PeerMessage.PROTOCOL) continue
            onSeen(
                announcement.copy(address = packet.address?.hostAddress.orEmpty())
            )
        }
    }

    private fun announce(socket: DatagramSocket) {
        while (running) {
            val payload = json.encodeToString(
                Announcement(selfTag, selfName(), servicePort())
            ).toByteArray(Charsets.UTF_8)

            // Sent to each interface's own broadcast address as well as the global one: a machine
            // with several networks - a laptop with wifi and a tunnel - otherwise announces itself
            // on whichever one the routing table happens to prefer, which is rarely the one the
            // tablet is on.
            for (address in broadcastAddresses()) {
                runCatching {
                    socket.send(DatagramPacket(payload, payload.size, address, PORT))
                }
            }
            runCatching { Thread.sleep(ANNOUNCE_MS) }
        }
    }

    private fun broadcastAddresses(): List<InetAddress> = buildList {
        runCatching { add(InetAddress.getByName("255.255.255.255")) }
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .mapNotNull { it.broadcast }
                .forEach { add(it) }
        }
    }

    companion object {
        /** One above the service port, so a firewall rule for the pair is an obvious range. */
        const val PORT = PeerService.DEFAULT_PORT + 1
        private const val ANNOUNCE_MS = 4_000L

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
