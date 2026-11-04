package com.inksheets.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportPlanTest {

    @Test
    fun `titles lose their instrument`() {
        assertEquals("Liberty Bell", ImportPlan.titleOf("Liberty_Bell - Trombone 2.pdf"))
        assertEquals("Liberty Bell", ImportPlan.titleOf("Liberty Bell (Euphonium).pdf"))
        assertEquals("September", ImportPlan.titleOf("September - Bass.pdf"))
        assertEquals("Stars and Stripes Forever", ImportPlan.titleOf("Stars and Stripes Forever.pdf"))
        // A title that merely contains an instrument word keeps it.
        assertEquals("Trombone Institute of Technology", ImportPlan.titleOf("Trombone Institute of Technology.pdf"))
    }

    @Test
    fun `one song's parts are gathered, and printed text beats the file name`() {
        val plan = ImportPlan.plan(
            listOf(
                "Band/Liberty Bell - Trombone 2.pdf",
                "Band/Liberty Bell - Euphonium.pdf",
                "Band/Liberty Bell - scan.pdf",
                "Jazz/September - Bass.pdf"
            ),
            textOf = { if (it.endsWith("scan.pdf")) "The Liberty Bell\nBaritone T.C." else null }
        )
        assertEquals(listOf("Liberty Bell", "Liberty Bell - scan", "September"), plan.map { it.title })
        val liberty = plan[0]
        assertEquals(listOf("trombone", "euphonium"), liberty.parts.map { it.instrument })
        assertEquals(InstrumentSource.FILE_NAME, liberty.parts[0].source)
        assertEquals("baritone-tc", plan[1].parts.single().instrument)
        assertEquals(InstrumentSource.TEXT, plan[1].parts.single().source)
        assertEquals("bass-guitar", plan[2].parts.single().instrument)
    }

    @Test
    fun `files already in the library are not imported twice`() {
        val existing = listOf(Song("s", "Liberty Bell", parts = listOf(Part(file = "Band/a.pdf"))))
        val plan = ImportPlan.plan(listOf("Band/a.pdf", "Band/b.pdf"), existing = existing)
        assertEquals(listOf("Band/b.pdf"), plan.flatMap { s -> s.parts.map { it.file } })
    }

    @Test
    fun `a scan is recognised only when it has no text of its own`() {
        val asked = ArrayList<String>()
        val plan = ImportPlan.plan(
            listOf("a.pdf", "b.pdf"),
            textOf = { if (it == "a.pdf") "Trombone 1" else null },
            recognise = { asked += it; "Euph. T.C." }
        )
        assertEquals(listOf("b.pdf"), asked)
        val parts = plan.flatMap { it.parts }
        assertEquals(InstrumentSource.TEXT, parts[0].source)
        assertEquals("euphonium-tc", parts[1].instrument)
        assertEquals(InstrumentSource.OCR, parts[1].source)
    }

    @Test
    fun `a trailing instrument without a separator comes off only when asked`() {
        assertEquals("1812", ImportPlan.withoutTrailingInstrument("1812 Euph 2"))
        assertEquals("9 to 5", ImportPlan.withoutTrailingInstrument(ImportPlan.cleanTitle("9 to 5 - Trombones 1-2")))
        assertEquals("All About That", ImportPlan.withoutTrailingInstrument("All About That Bass"))
        assertEquals("All About That Bass", ImportPlan.cleanTitle("All About That Bass - Trombone 1"))
        assertEquals("Mr. Brightside", ImportPlan.cleanTitle("Mr. Brightside - Electric Bass"))
    }

    @Test fun `one combined PDF is split at each titled part page and keeps multi page ranges`() {
        val pages=mapOf(
            1 to "Piccolo\nGive Us This Day\nShort Symphony", 2 to "2 Piccolo\nII.",
            3 to "Piccolo\n92", 4 to "4 Piccolo\n210",
            5 to "Flute 1\nGive Us This Day\nShort Symphony", 6 to "2 Flute 1\n78",
            7 to "Flute 1 3\n50", 8 to "4 Flute 1\n115", 9 to "Flute 1 5\n214",
            10 to "Flute 2\nGive Us This Day\nShort Symphony", 11 to "2 Flute 2\n80",
            12 to "Flute 2 3\n60", 13 to "4 Flute 2\n185",
            14 to "Euphonium\nGive Us This Day\nShort Symphony", 15 to "2 Euphonium\nII.",
            16 to "Euphonium 3\n111", 17 to "4 Euphonium\n250"
        )
        val parts=ImportPlan.splitCombinedParts("Give Us This Day.pdf","Give Us This Day",17,{pages[it]})
        assertEquals(listOf("piccolo","flute","flute","euphonium"),parts.map {it.instrument})
        assertEquals(listOf(null,1,2,null),parts.map {it.chair})
        assertEquals(listOf(1 to 4,5 to 9,10 to 13,14 to 17),parts.map {it.firstPage to it.lastPage})
    }

    @Test fun `repeated title pages do not split one ordinary instrument part`() {
        val parts=ImportPlan.splitCombinedParts("Solo.pdf","Long Solo",4,{
            if(it==1 || it==3) "Long Solo\nTrombone" else "Trombone\ncontinued"
        })
        assertEquals(emptyList<ImportPlan.PlannedPart>(),parts)
    }
}
