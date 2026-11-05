package com.inksheets.core.omr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StagePresetsTest {
    private val score = Score(emptyList(), 1)

    @Test fun `concert band layout is deterministic ordered and omits duplicate full score`() {
        val document = ScoreDocument(listOf(
            ScorePart("perc", listOf(score), instrument = "percussion"),
            ScorePart("flute", listOf(score), instrument = "flute", chair = 2),
            ScorePart("score", listOf(score), instrument = "score"),
            ScorePart("tuba", listOf(score), instrument = "tuba"),
            ScorePart("clarinet", listOf(score), instrument = "clarinet"),
            ScorePart("mystery", listOf(score), instrument = null)
        ))

        val first = StagePresets.concertBand(document)
        assertEquals(first, StagePresets.concertBand(document))
        assertEquals(listOf("flute", "clarinet", "mystery", "tuba", "perc"),
            first.performers.map { it.partId })
        assertTrue(first.performers.zipWithNext().all { (a, b) -> a.y <= b.y })
        assertEquals(listOf("main-left", "main-right"), first.microphones.map { it.id })
        assertEquals(listOf(-0.65, 0.65), first.microphones.map { it.pan })
    }

    @Test fun `layout remains inside a small room and all players face the podium`() {
        val document = ScoreDocument((1..12).map {
            ScorePart("clarinet-$it", listOf(score), instrument = "clarinet", chair = it)
        })
        val room = StageRoom(width = 6.0, depth = 7.0)
        val scene = StagePresets.concertBand(document, room)

        assertTrue(scene.performers.all { it.x in -room.width / 2..room.width / 2 })
        assertTrue(scene.performers.all { it.y in 0.0..room.depth })
        assertTrue(scene.performers.first().facing > 0.0)
        assertTrue(scene.performers.last().facing < 0.0)
    }
}
