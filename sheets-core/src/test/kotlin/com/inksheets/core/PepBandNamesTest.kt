package com.inksheets.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Names from a real pep band folder, and what each part must be read as. */
class PepBandNamesTest {

    private fun part(name: String) = ImportPlan.readPart("Imported/PEP BAND/Music/X/$name")
    private fun seat(name: String) = part(name).let { "${it.instrument}${it.chair?.let { c -> ":$c" } ?: ""}" }

    @Test
    fun `drum line parts are the drum line's`() {
        assertEquals("drumline", seat("Barbie Girl - DL - Bass Drums.pdf"))
        assertEquals("drumline", seat("Barbie Girl - DL - Tenor Drums.pdf"))
        assertEquals("drumline", seat("Barbie Girl - DL - Cymbals.pdf"))
        assertEquals("drumline", seat("Dancing Queen - DL - Snare Drum.pdf"))
        assertEquals("drumline", seat("Ya Ya - DL Drum Set.pdf"))
        assertEquals("percussion", seat("Liberty Bell - Bass Drum.pdf"))
        assertEquals("percussion", seat("Liberty Bell - Cymbals.pdf"))
    }

    @Test
    fun `pans are steel pans`() {
        assertEquals("steel-pan", seat("Like Ah Boss - Pan Score.pdf"))
        assertEquals("steel-pan", seat("Like Ah Boss - Double Second.pdf"))
        assertEquals("steel-pan", seat("Raining Men - Double Tenor.pdf"))
    }

    @Test
    fun `a name that says its part beats the page`() {
        val score = "Alto Sax I\nTrumpet\nTrombone\nTuba\nFlute\nClarinet"
        assertEquals("score", ImportPlan.readPart("x/Score.pdf", textOf = { score }).instrument)
        assertEquals("score", ImportPlan.readPart("x/Pour Some Sugar - Score.pdf", textOf = { "Flute\nPiccolo" }).instrument)
        assertEquals("piccolo", ImportPlan.readPart("x/Pour Some Sugar - Piccolo.pdf", textOf = { "Flute" }).instrument)
        assertEquals(2, ImportPlan.readPart("x/Song - Trumpet.pdf", textOf = { "Trumpet 2" }).chair)
    }

    @Test
    fun `glued names and short ones`() {
        assertEquals("alto-sax", seat("neckalto.pdf"))
        assertEquals("trombone:1", seat("necktbn1.pdf"))
        assertEquals("trumpet:2", seat("necktpt2.pdf"))
        assertEquals("baritone-bc", seat("neckbtone.pdf"))
        assertEquals("mellophone", seat("neckmello.pdf"))
        assertEquals("tuba", seat("necktuba.pdf"))
        assertEquals("alto-sax:1", seat("In The Stone - Alto 1.pdf"))
        assertEquals("alto-sax:2", seat("Diva- Alto 2.pdf"))
        assertEquals("trumpet:1", seat("National Anthem - Cornet 1.pdf"))
        assertEquals("alto-sax:1", seat("Crab_Rave-Alto_Sax_1.pdf"))
        assertEquals("clarinet:1", seat("Crab_Rave-Bb_Clarinet_1.pdf"))
        assertEquals("trombone:3", seat("Barbie Girl - Trombone 3.pdf"))
        assertEquals("baritone-bc", seat("Hurricane Season - Baritone (B.C.).pdf"))
        assertEquals("piccolo", seat("SweetC - PiccoloFlute.pdf"))
    }

    @Test
    fun `with every instrument chosen, the score opens`() {
        val song = Song("s", "Song", parts = listOf(Part(id = "t", file = "t.pdf", instrument = "trombone"), Part(id = "sc", file = "s.pdf", instrument = "score")))
        assertEquals("sc", PartChoice.partFor(song, null)?.id)
    }
}
