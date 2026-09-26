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

    @Test
    fun `part numbers are read however they are printed, and a score is a score`() {
        for ((label, chair) in listOf("Trumpet in B♭ 1" to 1, "Trumpet in Bb 2" to 2, "2nd Trumpet" to 2, "Tpt. II" to 2, "Horn in F 3" to 3, "Tuba" to null)) {
            assertEquals(label, chair, ImportPlan.chairOf(label))
        }
        // Read from the page with no number there, the file name's number stands.
        assertEquals(2, ImportPlan.readPart("x/Bills-Trumpet_in_Bb_2.pdf", textOf = { "Bills\nTrumpet in B♭" }).chair)
        val score = listOf(
            "Bills", "Piccolo in C", "Clarinet in Bb", "Alto Saxophone", "Tenor Saxophone",
            "Trumpet in Bb 1", "Trombone 1", "Sousaphone"
        ).joinToString("\n")
        assertEquals("score", ImportPlan.readPart("x/Bills.pdf", textOf = { score }).instrument)
        // Export-style names come together as one song.
        val plan = BulkImport.plan("Band", listOf("Bills/bills-Trumpet_in_Bb_1.pdf", "Bills/bills-Trumpet_in_Bb_2.pdf", "Bills/bills-Mellophone.pdf", "Bills/bills.pdf"))
        assertEquals(1, plan.songs.size)
    }
}
