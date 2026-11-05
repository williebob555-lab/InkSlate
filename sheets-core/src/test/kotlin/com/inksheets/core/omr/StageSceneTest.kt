package com.inksheets.core.omr

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sqrt

class StageSceneTest {
    private val tolerance = 1e-9

    @Test fun `scene defaults and populated scenes survive serialization`() {
        val empty = StageScene()
        assertEquals(empty, Json.decodeFromString<StageScene>("{}"))
        assertEquals(StagePoint(), empty.podium)
        assertTrue(empty.performers.isEmpty())
        val scene = StageScene(
            listener = StagePoint(1.0, -2.0),
            performers = listOf(StagePerformer("part-1", x = 2.0, playerCount = 3, spread = 1.5, height = 1.4)),
            microphones = listOf(StageMicrophone("mic-1", pattern = MicrophonePattern.CARDIOID, outputBus = "room", pan = -0.5, height = 2.0))
        )
        assertEquals(scene, Json.decodeFromString<StageScene>(Json.encodeToString(scene)))
    }

    @Test fun `inverse distance is bounded at the near distance`() {
        assertEquals(1.0, SpatialMath.distanceAttenuation(0.0), tolerance)
        assertEquals(1.0, SpatialMath.distanceAttenuation(0.5), tolerance)
        assertEquals(0.5, SpatialMath.distanceAttenuation(2.0), tolerance)
        assertEquals(0.5, SpatialMath.distanceAttenuation(4.0, 2.0), tolerance)
        assertEquals(5.0, SpatialMath.distance(0.0, 0.0, 3.0, 4.0), tolerance)
    }

    @Test fun `pan preserves power and clamps endpoints`() {
        for (pan in listOf(-2.0, -1.0, -0.5, 0.0, 0.5, 1.0, 2.0)) {
            val left = SpatialMath.panLeft(pan)
            val right = SpatialMath.panRight(pan)
            assertEquals(1.0, left * left + right * right, tolerance)
        }
        assertEquals(1.0 / sqrt(2.0), SpatialMath.panLeft(0.0), tolerance)
        assertEquals(1.0, SpatialMath.panLeft(-1.0), tolerance)
        assertEquals(0.0, SpatialMath.panLeft(1.0), tolerance)
        assertEquals(1.0, SpatialMath.panRight(1.0), tolerance)
    }

    @Test fun `microphone patterns reject and reverse the expected directions`() {
        assertEquals(1.0, SpatialMath.polarResponse(MicrophonePattern.OMNI, PI), tolerance)
        assertEquals(1.0, SpatialMath.polarResponse(MicrophonePattern.CARDIOID, 0.0), tolerance)
        assertEquals(0.5, SpatialMath.polarResponse(MicrophonePattern.CARDIOID, PI / 2), tolerance)
        assertEquals(0.0, SpatialMath.polarResponse(MicrophonePattern.CARDIOID, PI), tolerance)
        assertEquals(0.0, SpatialMath.polarResponse(MicrophonePattern.FIGURE_EIGHT, PI / 2), tolerance)
        assertEquals(-1.0, SpatialMath.polarResponse(MicrophonePattern.FIGURE_EIGHT, PI), tolerance)
        val mic = StageMicrophone("m", facing = PI / 2, pattern = MicrophonePattern.CARDIOID)
        assertEquals(1.0, SpatialMath.microphoneResponse(mic, 2.0, 0.0), tolerance)
        assertEquals(0.0, SpatialMath.microphoneResponse(mic, -2.0, 0.0), tolerance)
        assertEquals(1.0, SpatialMath.microphoneResponse(mic, 0.0, 0.0), tolerance)
    }

    @Test fun `delay follows distance and sample rate`() {
        assertEquals(0, SpatialMath.propagationDelaySamples(0.0, 48_000))
        assertEquals(480, SpatialMath.propagationDelaySamples(3.43, 48_000))
        assertEquals(441, SpatialMath.propagationDelaySamples(3.43, 44_100))
        val distance = SpatialMath.distance(0.0, 0.0, 0.0, 3.0, 4.0, 12.0)
        assertEquals(13.0, distance, tolerance)
        assertEquals(1819, SpatialMath.propagationDelaySamples(distance, 48_000))
    }

    @Test fun `identifiers and player counts are valid`() {
        fun invalid(block: () -> Unit) {
            try { block(); throw AssertionError("Expected invalid scene element") }
            catch (_: IllegalArgumentException) { }
        }
        invalid { StagePerformer(" ") }
        invalid { StagePerformer("part", playerCount = 0) }
        invalid { StagePerformer("part", playerCount = -1) }
        invalid { StageMicrophone("") }
        invalid { StageRoom(width = 0.0) }
        invalid { StageRoom(depth = Double.NaN) }
        invalid { StageRoom(speedOfSound = -1.0) }
        assertEquals(1.2, Json.decodeFromString<StagePerformer>("{\"partId\":\"part\"}").height, tolerance)
        assertEquals(1.2, Json.decodeFromString<StageMicrophone>("{\"id\":\"mic\"}").height, tolerance)
    }
}
