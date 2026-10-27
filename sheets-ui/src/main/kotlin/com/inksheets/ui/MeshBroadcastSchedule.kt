package com.inksheets.ui

/** At 250ms per frame, a heartbeat at least every fourth slot preserves health during messages. */
internal fun meshBroadcastSchedule(heartbeat: ByteArray?, payload: List<ByteArray>): List<ByteArray> = when {
    heartbeat == null -> payload
    payload.isEmpty() -> listOf(heartbeat)
    else -> payload.chunked(3).flatMap { listOf(heartbeat) + it }
}
