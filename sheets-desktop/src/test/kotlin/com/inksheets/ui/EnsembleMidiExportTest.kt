package com.inksheets.ui

import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.core.omr.Box
import com.inksheets.core.omr.Clef
import com.inksheets.core.omr.Duration
import com.inksheets.core.omr.Key
import com.inksheets.core.omr.Measure
import com.inksheets.core.omr.Note
import com.inksheets.core.omr.Pitch
import com.inksheets.core.omr.Score
import com.inksheets.core.omr.TimeSig
import org.junit.Assert.assertEquals
import org.junit.Test

class EnsembleMidiExportTest {
    private fun score(step: Int) = Score(listOf(Measure(1, 0, 0, Box(0, 0, 100, 50), 10f,
        Clef.TREBLE, Key(0), TimeSig(4, 4), listOf(Note(listOf(0), listOf(Pitch(step, 4)), Duration(4), 0f)))), 1)

    @Test fun `ensemble document keeps complete parts in song order and excludes a full score`() {
        val song = Song("piece", "Piece", parts = listOf(
            Part("flute", "flute.pdf", instrument = "flute", label = "Flute"),
            Part("score", "score.pdf", instrument = "score", label = "Full Score"),
            Part("clarinet", "clarinet.pdf", instrument = "clarinet:2", label = "Clarinet II"),
            Part("trumpet", "trumpet.pdf", instrument = "trumpet", label = "Trumpet")
        ))
        val available = mapOf("trumpet" to score(0), "flute" to score(1))
        val (document, missing) = ensembleDocument(song) { available[it.id] }
        assertEquals(listOf("flute", "trumpet"), document.parts.map { it.id })
        assertEquals(listOf("Flute", "Trumpet"), document.parts.map { it.label })
        assertEquals(1, missing)
    }
}
