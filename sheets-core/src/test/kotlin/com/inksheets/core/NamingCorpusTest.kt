package com.inksheets.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * File names as band music actually arrives - from a class website's downloads, a director's
 * scanner, notation software, a browser saving a second copy - and the song and instrument each
 * has to come out as, with nobody fixing anything by hand.
 */
class NamingCorpusTest {

    private val corpus = listOf(
        // file path                                           song                   instrument
        Triple("Liberty Bell - Trombone 2.pdf", "Liberty Bell", "trombone"),
        Triple("01 Liberty Bell - Trombone 2.pdf", "Liberty Bell", "trombone"),
        Triple("01. Liberty Bell_Tbn1.pdf", "Liberty Bell", "trombone"),
        Triple("Liberty_Bell_Trombone_2.pdf", "Liberty Bell", "trombone"),
        Triple("LibertyBell_Trombone1.pdf", "Liberty Bell", "trombone"),
        Triple("Liberty-Bell-Trombone-2.pdf", "Liberty Bell", "trombone"),
        Triple("Liberty Bell Trombone 2.pdf", "Liberty Bell", "trombone"),
        Triple("Liberty Bell - Trombone 2 (1).pdf", "Liberty Bell", "trombone"),
        Triple("LIBERTY BELL - TROMBONE 2.pdf", "LIBERTY BELL", "trombone"),
        Triple("Liberty Bell (Tbn. 2).pdf", "Liberty Bell", "trombone"),
        Triple("Liberty Bell - 2nd Trombone.pdf", "Liberty Bell", "trombone"),
        Triple("Liberty Bell - Trombone 1 & 2.pdf", "Liberty Bell", "trombone"),
        Triple("Liberty Bell - Trombones 1-2.pdf", "Liberty Bell", "trombone"),
        Triple("Liberty Bell - B. Tbn..pdf", "Liberty Bell", "bass-trombone"),
        Triple("Liberty Bell - Bass Trombone.pdf", "Liberty Bell", "bass-trombone"),
        Triple("Liberty Bell - Euphonium B.C..pdf", "Liberty Bell", "euphonium"),
        Triple("Liberty Bell - Euph TC.pdf", "Liberty Bell", "euphonium-tc"),
        Triple("Liberty Bell - Baritone T.C..pdf", "Liberty Bell", "baritone-tc"),
        Triple("Liberty Bell - Baritone B.C..pdf", "Liberty Bell", "baritone-bc"),
        Triple("Liberty Bell - Bb Trumpet 1.pdf", "Liberty Bell", "trumpet"),
        Triple("Liberty Bell - Horn in F 1.pdf", "Liberty Bell", "horn"),
        Triple("Liberty Bell - F Horn.pdf", "Liberty Bell", "horn"),
        Triple("Liberty Bell - Tuba.pdf", "Liberty Bell", "tuba"),
        Triple("Liberty Bell - Mallet Percussion.pdf", "Liberty Bell", "percussion"),
        Triple("Liberty Bell - Bells.pdf", "Liberty Bell", "percussion"),
        Triple("Liberty Bell - Timpani.pdf", "Liberty Bell", "percussion"),
        Triple("Liberty Bell - Percussion 1 (Snare, Bass Drum).pdf", "Liberty Bell", "percussion"),
        Triple("Liberty Bell - Full Score.pdf", "Liberty Bell", "score"),
        Triple("Liberty Bell - Soprano Sax.pdf", "Liberty Bell", "soprano-sax"),
        Triple("Liberty Bell - Eb Alto Sax 1.pdf", "Liberty Bell", "alto-sax"),
        Triple("Liberty Bell - Bari Sax.pdf", "Liberty Bell", "bari-sax"),
        Triple("Liberty Bell - Bb Clarinet 2.pdf", "Liberty Bell", "clarinet"),
        Triple("Liberty Bell - Bass Clarinet.pdf", "Liberty Bell", "bass-clarinet"),
        Triple("Liberty Bell - Electric Bass.pdf", "Liberty Bell", "bass-guitar"),
        Triple("Liberty Bell/Trombone 2.pdf", "Liberty Bell", "trombone"),
        Triple("Fall Concert/03 - Liberty Bell/Euphonium.pdf", "Liberty Bell", "euphonium"),
        Triple("Semper-Fidelis - Trombone 1.pdf", "Semper-Fidelis", "trombone"),
        Triple("76 Trombones - Trombone 1.pdf", "76 Trombones", "trombone"),
        Triple("9 to 5 - Trombone.pdf", "9 to 5", "trombone"),
        Triple("1812 Overture - Tbn 1.pdf", "1812 Overture", "trombone"),
        Triple("All About That Bass - Trombone 1.pdf", "All About That Bass", "trombone"),
        Triple("All About That Bass.pdf", "All About That Bass", "bass-guitar"),
        Triple("Seven Nation Army Bass 2.pdf", "Seven Nation Army", "bass-guitar"),
        Triple("Sleigh Ride Trombone.pdf", "Sleigh Ride", "trombone")
    )

    @Test
    fun `every name gives its song and instrument`() {
        val wrong = corpus.mapNotNull { (path, title, instrument) ->
            val gotTitle = ImportPlan.songTitle(path)
            val gotInstrument = ImportPlan.readPart(path).instrument
            if (gotTitle == title && gotInstrument == instrument) null
            else "$path -> \"$gotTitle\" / $gotInstrument (wanted \"$title\" / $instrument)"
        }
        assertEquals(wrong.joinToString("\n"), 0, wrong.size)
    }

    @Test
    fun `every spelling of one song's parts is one song`() {
        val liberty = corpus.filter { it.second.equals("Liberty Bell", ignoreCase = true) }.map { it.first }
        val plan = ImportPlan.plan(liberty)
        assertEquals(plan.joinToString { it.title }, 1, plan.size)
    }

    @Test
    fun `a flexible-band part serves every instrument printed on it`() {
        val part = ImportPlan.readPart("Liberty Bell - Part 4 (Trombone, Euphonium B.C., Bassoon).pdf")
        assertEquals("trombone", part.instrument)
        assertEquals(listOf("euphonium", "bassoon"), part.also)
        val song = Song("s", "Liberty Bell", parts = listOf(part.toPart()))
        val baritone = Instruments.defaultProfiles.first { it.id == "baritone" }
        assertEquals(PartChoice.Fit.YES, PartChoice.fit(song, baritone))
        assertEquals(part.file, PartChoice.partFor(song, baritone)?.file)
        // A bass trombone part is not also a trombone part.
        assertEquals(emptyList<String>(), ImportPlan.readPart("Liberty Bell - Bass Trombone.pdf").also)
    }
}
