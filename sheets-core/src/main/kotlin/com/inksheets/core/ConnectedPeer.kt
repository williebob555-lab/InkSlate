package com.inksheets.core

/** Snapshot of a peer supported by actual inbound protocol evidence, never listener readiness. */
data class ConnectedPeer(
    val id: String,
    val name: String,
    val transport: String,
    val status: ConnectionStatus,
    val lastHeard: Long
)

fun peerStatus(lastHeard: Long, now: Long, reconnectMs: Long = 4_000, disconnectMs: Long = 7_000): ConnectionStatus = when {
    lastHeard <= 0L -> ConnectionStatus.RECONNECTING
    now - lastHeard >= disconnectMs -> ConnectionStatus.DISCONNECTED
    now - lastHeard >= reconnectMs -> ConnectionStatus.RECONNECTING
    else -> ConnectionStatus.CONNECTED
}

/** A real stable identity unions transports; names alone must never collapse distinct players. */
fun mergeConnectedPeers(peers: List<ConnectedPeer>): List<ConnectedPeer> = peers.groupBy { it.id }.map { (_, routes) ->
    val newest = routes.maxBy { it.lastHeard }
    newest.copy(
        name = routes.maxBy { it.name.length }.name,
        transport = routes.map { it.transport }.distinct().sorted().joinToString(" + "),
        status = routes.minBy { it.status.ordinal }.status
    )
}.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
