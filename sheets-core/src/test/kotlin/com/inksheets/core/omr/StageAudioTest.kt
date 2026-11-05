package com.inksheets.core.omr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StageAudioTest {
    private val tolerance = 1e-6f

    @Test fun `routes part stems through microphone buses pan gain and propagation delay`() {
        val scene = StageScene(
            room = StageRoom(speedOfSound = 100.0, nearDistance = 1.0),
            performers = listOf(StagePerformer("flute", x = 3.0), StagePerformer("horn", x = -1.0)),
            microphones = listOf(
                StageMicrophone("left", x = 0.0, outputBus = "main", pan = -1.0),
                StageMicrophone("right", x = 0.0, outputBus = "main", pan = 1.0, muted = true),
                StageMicrophone("room", x = 0.0, outputBus = "room", pan = 0.0)
            )
        )
        val rendered = StageAudio.render(mapOf(
            "flute" to floatArrayOf(1f),
            "horn" to floatArrayOf(.5f)
        ), scene, 1000, levelOutput = false)
        assertEquals(setOf("main", "room"), rendered.keys)
        val main = rendered.getValue("main")
        assertEquals(31, main.frames)
        assertEquals(.5f, main.left[10], tolerance)
        assertEquals(1f / 3f, main.left[30], tolerance)
        assertArrayEquals(FloatArray(main.frames), main.right, tolerance)
        val room = rendered.getValue("room")
        assertTrue(room.left[10] > 0f && room.right[10] > 0f)
        assertEquals(room.left[10], room.right[10], tolerance)
    }

    @Test fun `missing stems and scenes without active routes stay silent`() {
        val scene = StageScene(
            performers = listOf(StagePerformer("part")),
            microphones = listOf(StageMicrophone("m", muted = true))
        )
        assertTrue(StageAudio.render(mapOf("part" to floatArrayOf(1f)), scene, 48_000).isEmpty())
        assertTrue(StageAudio.render(emptyMap(), scene.copy(microphones = listOf(StageMicrophone("m"))), 48_000).isEmpty())
    }
}
