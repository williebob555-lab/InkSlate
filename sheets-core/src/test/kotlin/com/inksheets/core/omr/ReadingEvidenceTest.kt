package com.inksheets.core.omr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingEvidenceTest {
    private fun note(step: Int = 2, base: Int = 4, confidence: Float = 1f, dots: Int = 0, odds: List<Float> = emptyList()) = Note(
        listOf(step), listOf(Pitch.fromDiatonic(Clef.TREBLE.at(step), 0)), Duration(base, dots), 30f,
        confidence = confidence, odds = odds
    )

    private fun measure(
        number: Int = 1,
        page: Int = 0,
        staff: Int = 0,
        events: List<Event> = listOf(note()),
        doubts: List<String> = emptyList(),
        bars: Int = 1,
        time: TimeSig = TimeSig(4, 4)
    ) = Measure(number, page, staff, Box(10, 20, 100, 60), 10f, Clef.TREBLE, Key(0), time, events, doubts = doubts, bars = bars)

    @Test fun `unanimous semantic looks are stable despite geometry and certainty differences`() {
        val base = measure()
        val shifted = base.copy(box = Box(11, 19, 102, 61), events = listOf(note(confidence = .6f)), doubts = listOf("unclear note"))
        val evidence = ReadingEvidence.assess(base, listOf(shifted, base.copy(space = 11f)))
        assertEquals(ReadingStability.STABLE, evidence.stability)
        assertEquals(2, evidence.agreeingLooks)
        assertEquals(1, evidence.sureLooks)
        assertEquals(1, evidence.distinctReadings)
        assertFalse(evidence.hasCriticalDisagreement)
    }

    @Test fun `pitch duration inventory and signature disagreements remain distinct`() {
        val base = measure(events = listOf(note(), Rest(Duration(4), 60f)))
        val pitch = base.copy(events = listOf(note(step = 3), Rest(Duration(4), 60f)))
        val duration = base.copy(events = listOf(note(base = 8), Rest(Duration(4), 60f)))
        val inventoryAndSignature = base.copy(time = TimeSig(3, 4), events = listOf(note()))
        val evidence = ReadingEvidence.assess(base, listOf(pitch, duration, inventoryAndSignature))
        assertEquals(ReadingStability.UNSTABLE, evidence.stability)
        assertEquals(0, evidence.agreeingLooks)
        assertTrue(ReadingDisagreement.PITCH in evidence.disagreements)
        assertTrue(ReadingDisagreement.DURATION in evidence.disagreements)
        assertTrue(ReadingDisagreement.EVENT_INVENTORY in evidence.disagreements)
        assertTrue(ReadingDisagreement.SIGNATURE in evidence.disagreements)
    }

    @Test fun `trained reader margins describe the selected value and dot count`() {
        val odds = listOf(.7f, .1f, .1f, .1f, .2f, .7f, .1f, .96f)
        val evidence = ReadingEvidence.assess(measure(events = listOf(note(dots = 1, odds = odds))), emptyList())
        assertEquals(1, evidence.model.notesWithOdds)
        assertEquals(.96f, evidence.model.minHeadProbability!!, .0001f)
        assertEquals(.6f, evidence.model.minValueMargin!!, .0001f)
        assertEquals(.5f, evidence.model.minDotMargin!!, .0001f)
    }

    @Test fun `class margins retain head versus rest ambiguity`() {
        val n = note().copy(headVsRestMargin = .2f)
        val r = Rest(Duration(4), 60f, restVsHeadMargin = .15f, restKindMargin = .67f)
        val model = ReadingEvidence.modelEvidence(measure(events = listOf(n, r)))
        assertEquals(.2f, model.minHeadVsRestMargin!!, .0001f)
        assertEquals(.15f, model.minRestVsHeadMargin!!, .0001f)
        assertEquals(.67f, model.minRestKindMargin!!, .0001f)
    }

    @Test fun `compact symbol margins survive score persistence`() {
        val original = Score(listOf(measure(events = listOf(note().copy(headVsRestMargin = .25f),
            Rest(Duration(4), 60f, restVsHeadMargin = .35f, restKindMargin = .45f)))), 1)
        val decoded = Scores.decode(Scores.encode(original))!!
        assertEquals(.25f, (decoded.measures.single().events[0] as Note).headVsRestMargin!!, .0001f)
        assertEquals(.35f, (decoded.measures.single().events[1] as Rest).restVsHeadMargin!!, .0001f)
        assertEquals(.45f, (decoded.measures.single().events[1] as Rest).restKindMargin!!, .0001f)
    }

    @Test fun `event consensus separates unsupported base and recurring novel notes`() {
        val first = note(step = 2).copy(x = 20f)
        val second = note(step = 4).copy(x = 50f)
        val novel = note(step = 6).copy(x = 75f)
        val base = measure(events = listOf(first, second))
        val looks = listOf(
            base.copy(events = listOf(first, novel)),
            base.copy(events = listOf(first, novel)),
            base.copy(events = listOf(first, novel)),
            base
        )
        val evidence = ReadingEvidence.assess(base, looks).events
        assertEquals(listOf(1), evidence.weakBaseEventIndices)
        assertEquals(1, evidence.majorityNovelEvents.size)
        assertEquals(3, evidence.majorityNovelEvents.single().supportingLooks)
    }

    @Test fun `audit planner includes green measures and excludes fixes and multi bar rests`() {
        val green = measure(number = 1, page = 0, staff = 0)
        val red = measure(number = 2, page = 0, staff = 0, doubts = listOf("nothing read"))
        val fixed = measure(number = 3, page = 0, staff = 1)
        val multi = measure(number = 4, page = 1, staff = 0, bars = 4)
        val later = measure(number = 8, page = 1, staff = 1)
        val batches = ReadingAuditPlanner.batches(Score(listOf(red, multi, later, fixed, green), 2), setOf(3))
        assertEquals(listOf(0 to 0, 1 to 1), batches.map { it.page to it.staff })
        assertEquals(listOf(1, 2), batches[0].measures.map { it.number })
        assertEquals(listOf(8), batches[1].measures.map { it.number })
    }

    @Test fun `audit image variants are named and rethreshold independently`() {
        assertEquals(4, ReadingAuditVariants.all.size)
        assertEquals(4, ReadingAuditVariants.all.map { it.id }.distinct().size)
        val w = 40; val h = 20
        val grey = IntArray(w * h) { i ->
            val x = i % w
            when {
                x < 10 -> 45
                x < 20 -> 125
                x < 30 -> 175
                else -> 235
            }
        }
        val base = Ink.fromGrey(w, h, grey)
        val geometry = ReadingAuditVariants.ink(base, grey, ReadingAuditVariants.all[0])
        val clean = ReadingAuditVariants.ink(base, grey, ReadingAuditVariants.all[2])
        val cleaner = ReadingAuditVariants.ink(base, grey, ReadingAuditVariants.all[3])
        assertTrue(geometry === base)
        assertFalse(clean === base)
        assertFalse(cleaner === base)
        assertFalse(clean === cleaner)
        assertTrue(ReadingAuditVariants.all[2].k != ReadingAuditVariants.all[3].k)
        assertTrue(ReadingAuditVariants.all[2].window != ReadingAuditVariants.all[3].window)
    }
}
