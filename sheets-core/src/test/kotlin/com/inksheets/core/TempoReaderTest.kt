package com.inksheets.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TempoReaderTest {

    @Test
    fun `a metronome mark is the tempo, in the forms fonts print it`() {
        assertEquals(120, TempoReader.read("Liberty Bell\nTrombone 1\n♩ = 120")?.bpm)
        assertEquals(96, TempoReader.read("Fight Song\nq = 96\nTrumpet 2")?.bpm)
        assertEquals(132, TempoReader.read("Rock tune\n  J=132")?.bpm)
        assertEquals(88, TempoReader.read("Anthem\nM.M. 88")?.bpm)
        assertEquals(140, TempoReader.read("Pep Tune\n140 bpm")?.bpm)
    }

    @Test
    fun `a tempo word gives its range and the middle of it, and a number beside it wins`() {
        val allegro = TempoReader.read("Fanfare and Allegro\nAllegro con brio\nTrombone")!!
        assertEquals("Allegro", allegro.mark)
        assertEquals(120..156, allegro.range)
        assertEquals(138, allegro.bpm)
        assertEquals("Allegretto", TempoReader.read("Title\nAllegretto")?.mark)
        val both = TempoReader.read("Title\nModerato ♩ = 112")!!
        assertEquals(112, both.bpm)
        assertEquals("Moderato", both.mark)
        assertEquals("Moderately", TempoReader.read("Hey Baby\nModerately, with a groove")?.mark)
    }

    @Test
    fun `a title with a tempo word in it, or a number that is not a tempo, is not read as one`() {
        assertNull(TempoReader.read("The Swing of Things at the Old Mill by the River Tonight\nTrumpet 1 in Bb"))
        assertNull(TempoReader.read("76 Trombones\nThe Music Man\nTrombone 2"))
        assertNull(TempoReader.read("Symphony No. 125\nOpus 11"))
    }
}
