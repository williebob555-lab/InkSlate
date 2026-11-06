package com.inksheets.core.omr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingConfidenceTest {
    private fun note() = Note(listOf(2), listOf(Pitch(0, 4)), Duration(4), 20f,
        odds = listOf(.98f, .01f, .01f, 0f, .99f, .01f, 0f, .95f))
    private fun measure(number: Int = 1, doubts: List<String> = emptyList()) = Measure(number, 0, 0,
        Box(0, 0, 100, 40), 10f, Clef.TREBLE, Key(0), TimeSig(4, 4), listOf(note()), doubts = doubts)

    @Test fun `stable audit raises ordering index without claiming verification`() {
        val m = measure()
        val evidence = ReadingEvidence.assess(m, List(4) { m.copy(box = Box(it, 0, 100 + it, 40)) })
        val confidence = ReadingConfidence.measure(m, evidence)
        assertEquals(ReadingConfidenceTier.STABLE, confidence.tier)
        assertTrue(confidence.index >= 80)
    }

    @Test fun `dot specialist demotes a unanimously repeated green mistake`() {
        val m = measure()
        val repeated = ReadingEvidence.assess(m, List(4) { m }).copy(dot = DotAuditEvidence(1, listOf(
            DotAuditCandidate(0, 2, 30f, 20f, 5f, 5f, .8f, 0f, .9f, 150f, .9f)
        )))
        val confidence = ReadingConfidence.measure(m, repeated)
        assertEquals(ReadingConfidenceTier.REVIEW, confidence.tier)
        assertTrue(ReadingConfidenceReason.POSSIBLE_MISSED_DOT in confidence.reasons)
    }

    @Test fun `doubt is urgent regardless of alternate agreement`() {
        val m = measure(doubts = listOf("3 beats found, 4 expected"))
        assertEquals(ReadingConfidenceTier.URGENT, ReadingConfidence.measure(m, ReadingEvidence.assess(m, List(4) { m })).tier)
    }

    @Test fun `weak head sends a green measure to review`() {
        val m = measure().copy(events = listOf(note().copy(odds = listOf(.98f, .01f, .01f, 0f, .99f, .01f, 0f, .8f))))
        val confidence = ReadingConfidence.measure(m, ReadingEvidence.assess(m, List(4) { m }))
        assertEquals(ReadingConfidenceTier.REVIEW, confidence.tier)
        assertTrue(ReadingConfidenceReason.WEAK_HEAD in confidence.reasons)
    }

    @Test fun `uncalibrated rest hint lowers priority index without forcing review`() {
        val m = measure()
        val restHint = RestAuditEvidence(1, listOf(RestAuditCandidate(0, 1, 2, .4f, .7f)))
        val confidence = ReadingConfidence.measure(m, ReadingEvidence.assess(m, List(4) { m }).copy(rest = restHint))
        assertEquals(ReadingConfidenceTier.STABLE, confidence.tier)
        assertTrue(ReadingConfidenceReason.REST_KIND_DISAGREEMENT in confidence.reasons)
        assertTrue(confidence.index < 85)
    }

    @Test fun `selective numbered multi rest pattern forces review`() {
        val m = measure()
        val hint = MultiRestAuditEvidence(listOf(MultiRestAuditCandidate(20, 60, 4, 8)))
        val confidence = ReadingConfidence.measure(m, ReadingEvidence.assess(m, List(4) { m }).copy(multiRest = hint))
        assertEquals(ReadingConfidenceTier.REVIEW, confidence.tier)
        assertTrue(ReadingConfidenceReason.POSSIBLE_MULTI_MEASURE_REST in confidence.reasons)
    }

    @Test fun `score ranking puts urgent work first`() {
        val stable = MeasureConfidence(1, 90, ReadingConfidenceTier.STABLE, emptySet())
        val urgent = MeasureConfidence(2, 15, ReadingConfidenceTier.URGENT, setOf(ReadingConfidenceReason.BASE_DOUBT))
        val ranked = ReadingConfidence.rank(mapOf("clean" to listOf(stable), "work" to listOf(stable, urgent)))
        assertEquals("work", ranked.first().first)
        assertEquals(1, ranked.first().second.urgent)
    }
}
