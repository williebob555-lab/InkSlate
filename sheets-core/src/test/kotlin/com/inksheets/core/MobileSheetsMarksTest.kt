package com.inksheets.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MobileSheetsMarksTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun bytes(vararg d: Double): ByteArray {
        val b = ByteBuffer.allocate(d.size * 8).order(ByteOrder.LITTLE_ENDIAN)
        d.forEach { b.putDouble(it) }
        return b.array()
    }

    private val lift = 3.4028234663852886e38

    /** Rows shaped as a real MobileSheets database has them. */
    private val tables = MobileSheetsImport.Tables { table ->
        when (table) {
            "Files" -> listOf(mapOf("Id" to 1, "SongId" to 5, "Path" to "Drive/March.pdf", "PageOrder" to "3-4", "SourceFilePageCount" to 6))
            "AnnotationsBase" -> listOf(
                mapOf("Id" to 1, "SongId" to 5, "Page" to 1, "Type" to 1, "Opacity" to 100, "SourcePageWidth" to 600.0, "SourcePageHeight" to 800.0, "Layer" to 0),
                mapOf("Id" to 2, "SongId" to 5, "Page" to 0, "Type" to 6, "Opacity" to 100, "SourcePageWidth" to 600.0, "SourcePageHeight" to 800.0, "Layer" to 0),
                mapOf("Id" to 3, "SongId" to 5, "Page" to 0, "Type" to 0, "Opacity" to 100, "SourcePageWidth" to 600.0, "SourcePageHeight" to 800.0, "Layer" to 0)
            )
            "DrawAnnotations" -> listOf(
                mapOf("Id" to 1, "BaseId" to 1, "LineColor" to -16777216, "LineWidth" to 1.2, "DrawMode" to 0),
                mapOf("Id" to 2, "BaseId" to 2, "LineColor" to -65536, "LineWidth" to 0.6, "DrawMode" to 2)
            )
            "TextboxAnnotations" -> listOf(mapOf("Id" to 1, "BaseId" to 3, "TextColor" to -16777216, "Text" to "stay quiet", "FontSize" to 12.0))
            "AnnotationPoints" -> listOf(
                // Two pen-down runs: a header, three points, a lift, two points, a lift.
                mapOf("AnnotationId" to 1, "Points" to bytes(0.0, 14.0, 1.2, 60.0, 80.0, 66.0, 88.0, 72.0, 96.0, lift, lift, 300.0, 400.0, 306.0, 404.0, lift, lift)),
                mapOf("AnnotationId" to 2, "Points" to bytes(0.0, 400.0, 600.0, 400.0)),
                mapOf("AnnotationId" to 3, "Points" to bytes(120.0, 200.0, 180.0, 216.0, 124.0, 212.0))
            )
            else -> emptyList()
        }
    }

    @Test
    fun `pen strokes, lines and text land on the right page of the right file`() {
        val marks = MobileSheetsMarks.read(tables) { path -> "MobileSheets/" + path.substringAfterLast('/') }
        val list = marks.getValue("MobileSheets/March.pdf")
        val pens = list.filter { it.kind == ImportedMark.Kind.PEN }
        assertEquals(2, pens.size)
        // Song page 1 is the file's page 4 (order "3-4"): 0-based 3.
        assertEquals(3, pens[0].page)
        assertEquals(listOf(0.1f, 0.1f, 0.11f, 0.11f, 0.12f, 0.12f), pens[0].points.map { Math.round(it * 100) / 100f })
        assertEquals(1.2f / 600f, pens[0].width, 1e-6f)
        val line = list.single { it.kind == ImportedMark.Kind.LINE }
        assertEquals(2, line.page)
        assertEquals(-65536, line.color)
        val text = list.single { it.kind == ImportedMark.Kind.TEXT }
        assertEquals("stay quiet", text.text)
        assertEquals(0.2f, text.points[0], 1e-6f)
    }

    @Test
    fun `kept markings are read back, and keeping them again doubles nothing`() {
        val root = tmp.newFolder("lib")
        val marks = MobileSheetsMarks.read(tables) { "MobileSheets/March.pdf" }
        MobileSheetsMarks.save(root, marks)
        MobileSheetsMarks.save(root, marks)
        assertEquals(marks, MobileSheetsMarks.load(root))
    }

    @Test
    fun `page orders`() {
        assertEquals(listOf(1, 2, 3, 4), MobileSheetsMarks.pageOrder("1-4", 9))
        assertEquals(listOf(102, 103), MobileSheetsMarks.pageOrder("102-103", 200))
        assertEquals(listOf(2, 5, 6), MobileSheetsMarks.pageOrder("2, 5-6", 9))
        assertEquals(listOf(1, 2, 3), MobileSheetsMarks.pageOrder("", 3))
    }

    /** Point it at a real MobileSheets database with -Dinksheets.msdb=path/to/mobilesheets.db (desktop test). */
    @Test
    fun `pen runs split where the pen lifts`() {
        val runs = MobileSheetsMarks.strokes(MobileSheetsMarks.doubles(bytes(0.0, 6.0, 1.0, 1.0, 2.0, lift, lift, 3.0, 4.0)))
        assertEquals(listOf(listOf(1.0, 2.0), listOf(3.0, 4.0)), runs)
        assertTrue(MobileSheetsMarks.strokes(DoubleArray(0)).isEmpty())
    }
}
