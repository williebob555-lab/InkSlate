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
}
