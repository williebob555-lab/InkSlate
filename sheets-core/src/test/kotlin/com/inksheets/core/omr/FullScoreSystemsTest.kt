package com.inksheets.core.omr

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullScoreSystemsTest {
    private fun lane(id: String, top: Int, left: Int = 100, right: Int = 900,
                     page: Int = 0, confidence: Float = .95f) =
        ScoreStaffLane(id, page, Box(left, top, right, top + 40), confidence)

    @Test fun `repeated aligned staves form deterministic full score systems`() {
        val lanes = listOf(
            lane("a3", 120), lane("a1", 0), lane("b2", 320),
            lane("a2", 60), lane("b3", 380), lane("b1", 260)
        )
        val layout = ScoreSystemGrouper.group(lanes)

        assertEquals(listOf(listOf("a1", "a2", "a3"), listOf("b1", "b2", "b3")),
            layout.systems.map { it.staffLaneIds })
        assertEquals(listOf("page-0-system-0", "page-0-system-1"), layout.systems.map { it.id })
        assertEquals(Box(100, 0, 900, 160), layout.systems.first().bounds)
        assertFalse(layout.needsReview)
        assertTrue(layout.systems.all { it.confidence > .85f })
        assertEquals(layout, Json.decodeFromString<ScoreSystemLayout>(Json.encodeToString(layout)))
    }

    @Test fun `large gaps separate one staff systems and low confidence remains reviewable`() {
        val layout = ScoreSystemGrouper.group(listOf(
            lane("one", 0), lane("two", 180, confidence = .5f), lane("three", 360)
        ))
        assertEquals(listOf(1, 1, 1), layout.systems.map { it.staffLaneIds.size })
        assertTrue(layout.needsReview)
        assertEquals(setOf(ScoreSystemReviewReason.LOW_STAFF_CONFIDENCE), layout.systems[1].reviewReasons)
    }

    @Test fun `horizontal mismatch splits lanes and exposes the boundary for review`() {
        val layout = ScoreSystemGrouper.group(listOf(
            lane("wide", 0), lane("shifted", 55, left = 760, right = 1100)
        ))
        assertEquals(2, layout.systems.size)
        assertTrue(layout.systems.all { ScoreSystemReviewReason.HORIZONTAL_MISALIGNMENT in it.reviewReasons })
        assertTrue(layout.systems.all { it.needsReview })
    }

    @Test fun `minority staff count is marked rather than silently corrected`() {
        val layout = ScoreSystemGrouper.group(listOf(
            lane("a1", 0), lane("a2", 60), lane("b1", 260), lane("b2", 320), lane("c1", 520)
        ))
        assertEquals(listOf(2, 2, 1), layout.systems.map { it.staffLaneIds.size })
        assertTrue(ScoreSystemReviewReason.INCONSISTENT_STAFF_COUNT in layout.systems.last().reviewReasons)
        assertFalse(ScoreSystemReviewReason.INCONSISTENT_STAFF_COUNT in layout.systems.first().reviewReasons)
    }

    @Test fun `duplicate ids and invalid lanes are rejected`() {
        fun invalid(block: () -> Unit) {
            try { block(); throw AssertionError("Expected invalid score geometry") }
            catch (_: IllegalArgumentException) { }
        }
        invalid { ScoreStaffLane("", 0, Box(0, 0, 10, 10)) }
        invalid { ScoreStaffLane("x", 0, Box(0, 0, 0, 10)) }
        invalid { ScoreSystemGrouper.group(listOf(lane("x", 0), lane("x", 100))) }
    }
}
