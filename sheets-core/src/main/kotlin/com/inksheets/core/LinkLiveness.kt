package com.inksheets.core

enum class ConnectionStatus { CONNECTED, RECONNECTING, DISCONNECTED }

/** Inbound evidence only. A generation prevents an old socket callback reviving a new session. */
class LinkLiveness(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val reconnectAfterMs: Long = 4_000,
    private val disconnectAfterMs: Long = 7_000
) {
    init { require(reconnectAfterMs > 0 && disconnectAfterMs >= reconnectAfterMs) }
    private var generation = 0L
    private var active = false
    private var began = 0L
    private var heard: Long? = null
    @Synchronized fun begin(): Long {
        generation++; active = true; began = now(); heard = null
        return generation
    }
    @Synchronized fun received(token: Long): Boolean {
        if (!active || token != generation) return false
        heard = now(); return true
    }
    @Synchronized fun lost(token: Long) {
        if (active && token == generation && heard != null) { began = heard!!; heard = null }
    }
    @Synchronized fun current(token: Long): Boolean = active && token == generation
    @Synchronized fun stop() { generation++; active = false; heard = null }
    @Synchronized fun status(): ConnectionStatus {
        if (!active) return ConnectionStatus.DISCONNECTED
        val age = (now() - (heard ?: began)).coerceAtLeast(0)
        return when {
            age >= disconnectAfterMs -> ConnectionStatus.DISCONNECTED
            heard == null || age >= reconnectAfterMs -> ConnectionStatus.RECONNECTING
            else -> ConnectionStatus.CONNECTED
        }
    }
}
