package com.inksheets.core.omr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StageSceneEditingTest {
    private val score = Score(emptyList(), 1)

    @Test fun `codec round trips ignores additions and isolates corrupt or future data`() {
        val scene = StageScene(performers = listOf(StagePerformer("part")), microphones = listOf(StageMicrophone("main")))
        val encoded = StageSceneCodec.encode(scene)
        assertEquals(scene, StageSceneCodec.decodeOrNull(encoded))
        assertEquals(scene, StageSceneCodec.decodeOrNull(encoded.dropLast(1) + ",\"extra\":true}"))
        assertNull(StageSceneCodec.decodeOrNull("not json"))
        assertNull(StageSceneCodec.decodeOrNull(encoded.replace("\"version\":1", "\"version\":99")))
    }

    @Test fun `drag edits clamp to the room and do not mutate other elements`() {
        val scene = StageScene(
            room = StageRoom(width = 10.0, depth = 8.0),
            performers = listOf(StagePerformer("a"), StagePerformer("b", x = 1.0, y = 2.0)),
            microphones = listOf(StageMicrophone("m"))
        )
        val performer = StageSceneEditing.movePerformer(scene, 0, 99.0, -3.0)
        assertEquals(5.0, performer.performers[0].x, 0.0)
        assertEquals(0.0, performer.performers[0].y, 0.0)
        assertEquals(scene.performers[1], performer.performers[1])
        val microphone = StageSceneEditing.moveMicrophone(performer, 0, -99.0, 99.0)
        assertEquals(-5.0, microphone.microphones[0].x, 0.0)
        assertEquals(8.0, microphone.microphones[0].y, 0.0)
    }

    @Test fun `reconcile preserves known positions removes stale parts and seats new parts`() {
        val scene = StageScene(
            performers = listOf(StagePerformer("keep", x = 4.0), StagePerformer("gone", x = -4.0)),
            microphones = listOf(StageMicrophone("main"))
        )
        val document = ScoreDocument(listOf(
            ScorePart("keep", listOf(score), instrument = "flute"),
            ScorePart("new", listOf(score), instrument = "tuba"),
            ScorePart("full", listOf(score), instrument = "score")
        ))
        val reconciled = StageSceneEditing.reconcile(scene, document)
        assertEquals(listOf("keep", "new"), reconciled.performers.map { it.partId })
        assertEquals(4.0, reconciled.performers.first().x, 0.0)
        assertEquals(scene.microphones, reconciled.microphones)
    }
}
