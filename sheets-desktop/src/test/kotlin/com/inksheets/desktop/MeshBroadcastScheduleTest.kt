package com.inksheets.desktop

import com.inksheets.ui.meshBroadcastSchedule
import org.junit.Assert.*
import org.junit.Test

class MeshBroadcastScheduleTest {
    @Test fun longMessageQueueCannotStarveHeartbeatAndPreservesEveryPayload() {
        val heartbeat = byteArrayOf(99)
        for (count in listOf(0, 1, 3, 4, 15, 60, 151)) {
            val payload = List(count) { byteArrayOf(it.toByte()) }
            val schedule = meshBroadcastSchedule(heartbeat, payload)
            assertEquals(payload, schedule.filter { it !== heartbeat })
            for (offset in schedule.indices) {
                assertTrue("count=$count offset=$offset", (0..3).any { schedule[(offset + it) % schedule.size] === heartbeat })
            }
        }
    }

    @Test fun discoveryWithoutHeartbeatKeepsPayloadAndIdleLeaderStillAdvertises() {
        val payload = listOf(byteArrayOf(1), byteArrayOf(2))
        assertEquals(payload, meshBroadcastSchedule(null, payload))
        val heartbeat = byteArrayOf(3)
        assertEquals(listOf(heartbeat), meshBroadcastSchedule(heartbeat, emptyList()))
    }
}
