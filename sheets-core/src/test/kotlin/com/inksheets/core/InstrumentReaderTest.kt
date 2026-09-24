package com.inksheets.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Part names as they are actually printed, and what each must be read as. */
class InstrumentReaderTest {

    private fun read(text: String) = InstrumentReader.read(text)?.instrument?.id

    @Test
    fun `trombone parts in their usual spellings`() {
        assertEquals("trombone", read("Trombone 1"))
        assertEquals("trombone", read("2nd Trombone"))
        assertEquals("trombone", read("Tbn. II"))
        assertEquals("trombone", read("TROMBONE 3"))
        assertEquals("trombone", read("Posaune"))
    }

    @Test
    fun `bass trombone is not a trombone`() {
        assertEquals("bass-trombone", read("Bass Trombone"))
        assertEquals("bass-trombone", read("Bs. Tbn."))
    }

    @Test
    fun `baritone and euphonium by clef`() {
        assertEquals("baritone-bc", read("Baritone"))
        assertEquals("baritone-bc", read("Baritone B.C."))
        assertEquals("baritone-tc", read("Baritone T.C."))
        assertEquals("baritone-tc", read("Baritone Treble Clef"))
        assertEquals("baritone-tc", read("Bar. T.C."))
        assertEquals("euphonium", read("Euphonium"))
        assertEquals("euphonium-tc", read("Euph. T.C."))
        assertEquals("euphonium", read("Euphonium (B.C.)"))
    }

    @Test
    fun `the baritone saxophone is not the baritone horn`() {
        assertEquals("bari-sax", read("Baritone Saxophone"))
        assertEquals("bari-sax", read("Bari Sax"))
        assertEquals("bari-sax", read("E♭ Baritone Sax"))
    }

    @Test
    fun `bass guitar and the other basses`() {
        assertEquals("bass-guitar", read("Bass"))
        assertEquals("bass-guitar", read("Electric Bass"))
        assertEquals("bass-guitar", read("El. Bass"))
        assertEquals("bass-guitar", read("Bass Guitar"))
        assertEquals("string-bass", read("String Bass"))
        assertEquals("bass-clarinet", read("Bass Clarinet"))
        assertEquals("percussion", read("Bass Drum"))
    }

    @Test
    fun `the line naming the part wins over a title that happens to contain nothing`() {
        val header = """
            The Liberty Bell
            March
            John Philip Sousa
            Trombone 2
        """.trimIndent()
        assertEquals("trombone", read(header))
        assertEquals("Trombone 2", InstrumentReader.read(header)!!.label)
    }

    @Test
    fun `file names`() {
        assertEquals("euphonium", InstrumentReader.readFileName("Liberty_Bell-Euphonium.pdf")?.instrument?.id)
        assertEquals("bass-guitar", InstrumentReader.readFileName("September - Bass.pdf")?.instrument?.id)
        assertNull(InstrumentReader.readFileName("scan0042.pdf"))
    }

    @Test
    fun `parts are chosen by the profile's preference`() {
        val song = Song(
            id = "s", title = "Test",
            parts = listOf(
                Part(id = "tpt", file = "a.pdf", instrument = "trumpet"),
                Part(id = "bar", file = "b.pdf", instrument = "baritone-bc"),
                Part(id = "euph", file = "c.pdf", instrument = "euphonium")
            )
        )
        val baritone = Instruments.defaultProfiles.first { it.id == "baritone" }
        val bass = Instruments.defaultProfiles.first { it.id == "bass-guitar" }
        assertEquals("euph", PartChoice.partFor(song, baritone)!!.id)
        assertEquals(PartChoice.Fit.NO, PartChoice.fit(song, bass))
        val unread = song.copy(parts = song.parts + Part(id = "scan", file = "d.pdf"))
        assertEquals(PartChoice.Fit.UNKNOWN, PartChoice.fit(unread, bass))
        assertEquals("scan", PartChoice.partFor(unread, bass)!!.id)
    }
}
