package com.inksheets.core.omr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DotAuditTest {
    private fun measure(note: Note) = Measure(1, 0, 0, Box(10, 20, 110, 60), 10f,
        Clef.TREBLE, Key(0), TimeSig(4, 4), listOf(note), lines = listOf(20f, 30f, 40f, 50f, 60f, 20f, 30f, 40f, 50f, 60f))

    private fun page(): IntArray = IntArray(120 * 80) { 245 }

    @Test fun `finds compact dark dot beside undotted head`() {
        val note = Note(listOf(3), listOf(Pitch(0, 4)), Duration(4), 30f,
            geometry = NoteGeometry(heads = listOf(SourceHead(3, SourcePoint(35f, 35f), 10f, 7f))))
        val grey = page()
        for (y in 32..35) for (x in 43..46) grey[y * 120 + x] = 35
        val evidence = DotAudit.inspect(measure(note), grey, 120, 80)
        assertTrue(evidence.suspicious)
    }

    @Test fun `ignores an empty dot box`() {
        val note = Note(listOf(3), listOf(Pitch(0, 4)), Duration(4), 30f,
            geometry = NoteGeometry(heads = listOf(SourceHead(3, SourcePoint(35f, 35f), 10f, 7f))))
        assertFalse(DotAudit.inspect(measure(note), page(), 120, 80).suspicious)
    }

    @Test fun `local dot shape needs model corroboration when odds exist`() {
        val note = Note(listOf(3), listOf(Pitch(0, 4)), Duration(4), 30f,
            odds = listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 1f),
            geometry = NoteGeometry(heads = listOf(SourceHead(3, SourcePoint(35f, 35f), 10f, 7f))))
        val grey = page()
        for (y in 32..35) for (x in 43..46) grey[y * 120 + x] = 35
        assertFalse(DotAudit.inspect(measure(note), grey, 120, 80).suspicious)
    }

    @Test fun `does not audit notes already read with dots`() {
        val note = Note(listOf(3), listOf(Pitch(0, 4)), Duration(4, dots = 1), 30f,
            geometry = NoteGeometry(heads = listOf(SourceHead(3, SourcePoint(35f, 35f), 10f, 7f))))
        val grey = page()
        for (y in 32..35) for (x in 43..46) grey[y * 120 + x] = 35
        val evidence = DotAudit.inspect(measure(note), grey, 120, 80)
        assertTrue(evidence.notesChecked == 0 && evidence.candidates.isEmpty())
    }

    @Test fun `specialist evidence round trips independently of score readings`() {
        val evidence = listOf(MeasureSpecialistEvidence(12, DotAuditEvidence(3, listOf(
            DotAuditCandidate(1, 3, 42f, 24f, 5f, 5f, .7f, .1f, .8f, 130f, .82f)
        )), RestAuditEvidence(1, listOf(RestAuditCandidate(2, 1, 2, .4f, .7f))),
            MultiRestAuditEvidence(listOf(MultiRestAuditCandidate(20, 60, 4, 8)))))
        assertEquals(evidence, SpecialistEvidenceCodec.decode(SpecialistEvidenceCodec.encode(evidence)))
    }


    @Test fun `older specialist sidecar gains empty rest and multi rest evidence`() {
        val decoded = SpecialistEvidenceCodec.decode("""[{"number":4,"dot":{"notesChecked":0,"candidates":[]}}]""")
        assertEquals(RestAuditEvidence(0, emptyList()), decoded.single().rest)
        assertEquals(MultiRestAuditEvidence(emptyList()), decoded.single().multiRest)
    }
}
