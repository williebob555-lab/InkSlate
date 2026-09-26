package com.inksheets.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TaughtInstrumentsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun forget() = Instruments.use(emptyList())

    @Test
    fun `a mellophone part is read as one, spelled out or short`() {
        assertEquals("mellophone", ImportPlan.readPart("Fight Song - Mellophone 1.pdf").instrument)
        assertEquals("mellophone", ImportPlan.readPart("Fight Song - Mello. 2.pdf").instrument)
    }

    @Test
    fun `an instrument taught in the library is read on every device, and a built-in one learns names`() {
        val root = tmp.newFolder("music")
        val here = Library(LibraryLog(root, "tablet"))
        assertNull(ImportPlan.readPart("Anthem - Hurdy Gurdy.pdf").instrument)
        here.saveInstrument(Instrument("i-gurdy", "Hurdy-gurdy", listOf("Hurdy-gurdy", "Hurdy Gurdy", "H.G."), clef = "treble"))
        here.saveInstrument(Instrument("trombone", "Trombone", listOf("Sackbut")))

        // Another device, reading the same folder.
        val there = Library(LibraryLog(root, "laptop"))
        Instruments.use(there.instruments())
        assertEquals("i-gurdy", ImportPlan.readPart("Anthem - Hurdy Gurdy.pdf").instrument)
        assertEquals("i-gurdy", ImportPlan.readPart("Anthem - H.G..pdf").instrument)
        assertEquals("trombone", ImportPlan.readPart("Anthem - Sackbut 2.pdf").instrument)
        // The built-in one keeps its own names too.
        assertEquals("trombone", ImportPlan.readPart("Anthem - Trombone 1.pdf").instrument)

        there.deleteInstrument("i-gurdy")
        Instruments.use(there.instruments())
        assertNull(ImportPlan.readPart("Anthem - Hurdy Gurdy.pdf").instrument)
    }
}
