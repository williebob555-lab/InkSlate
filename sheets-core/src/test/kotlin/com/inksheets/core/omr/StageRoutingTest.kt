package com.inksheets.core.omr

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class StageRoutingTest {
    private val tolerance = 1e-9

    @Test fun `routes use 3d distance delay signed response gain and pan`() {
        val scene = StageScene(
            room = StageRoom(speedOfSound = 100.0, nearDistance = 1.0),
            performers = listOf(StagePerformer("flute", y = -3.0, height = 4.0, gain = 2.0)),
            microphones = listOf(StageMicrophone("room", height = 0.0, pattern = MicrophonePattern.FIGURE_EIGHT,
                gain = 0.5, outputBus = "ambient", pan = 0.0))
        )
        val route = StageRouting.build(scene, 1000).single()
        assertEquals("flute", route.partId)
        assertEquals("room", route.microphoneId)
        assertEquals("ambient", route.outputBus)
        assertEquals(5.0, route.distanceMeters, tolerance)
        assertEquals(50, route.delaySamples)
        assertEquals(-1.0, route.microphoneResponse, tolerance)
        assertEquals(-0.2, route.gain, tolerance)
        assertEquals(1.0 / sqrt(2.0), route.panLeft, tolerance)
        assertEquals(1.0 / sqrt(2.0), route.panRight, tolerance)
    }

    @Test fun `duplicate part performers and microphones preserve deterministic scene order`() {
        val scene = StageScene(
            performers = listOf(StagePerformer("part", x = 1.0), StagePerformer("part", x = 2.0)),
            microphones = listOf(StageMicrophone("m", pan = -1.0), StageMicrophone("m", pan = 1.0))
        )
        val routes = StageRouting.build(scene, 48000)
        assertEquals(listOf(0 to 0, 0 to 1, 1 to 0, 1 to 1), routes.map { it.performerIndex to it.microphoneIndex })
        assertEquals(routes, StageRouting.build(scene, 48000))
        assertEquals(1.0, routes[0].panLeft, tolerance)
        assertEquals(0.0, routes[0].panRight, tolerance)
        assertEquals(0.0, routes[1].panLeft, tolerance)
        assertEquals(1.0, routes[1].panRight, tolerance)
        try {
            @Suppress("UNCHECKED_CAST")
            (routes as MutableList<StageRoute>).clear()
            fail("Routes must be immutable")
        } catch (_: UnsupportedOperationException) { }
    }

    @Test fun `no microphones produces no routes`() {
        assertTrue(StageRouting.build(StageScene(performers = listOf(StagePerformer("part"))), 48000).isEmpty())
    }

    @Test fun `coincident source uses near clamp and on-axis response`() {
        val route = StageRouting.build(StageScene(
            performers = listOf(StagePerformer("part", gain = 2.0)),
            microphones = listOf(StageMicrophone("m", pattern = MicrophonePattern.CARDIOID, gain = 0.5))
        ), 48000).single()
        assertEquals(0, route.delaySamples)
        assertEquals(1.0, route.microphoneResponse, tolerance)
        assertEquals(1.0, route.gain, tolerance)
    }

    @Test fun `invalid sample rate is rejected even without routes`() {
        try {
            StageRouting.build(StageScene(), 0)
            fail("Expected invalid sample rate")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun `performer and microphone mute and solo are applied before routing`() {
        val scene = StageScene(
            performers = listOf(
                StagePerformer("flute", solo = true),
                StagePerformer("oboe"),
                StagePerformer("horn", muted = true, solo = true)
            ),
            microphones = listOf(
                StageMicrophone("main", solo = true),
                StageMicrophone("room"),
                StageMicrophone("off", muted = true, solo = true)
            )
        )
        val routes = StageRouting.build(scene, 48_000)
        assertEquals(listOf("flute" to "main"), routes.map { it.partId to it.microphoneId })
    }
}
