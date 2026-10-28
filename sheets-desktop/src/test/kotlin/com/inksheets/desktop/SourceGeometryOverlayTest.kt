package com.inksheets.desktop

import com.inkslate.core.PageMark
import com.inksheets.core.omr.*
import com.inksheets.ui.ScoreTools
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.hypot

/** Quantitative placement checks exercise the final page polygons, not just intermediate anchors. */
class SourceGeometryOverlayTest {
    private data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val x get() = (left + right) / 2
        val y get() = (top + bottom) / 2
        val width get() = right - left
        val height get() = bottom - top
    }
    private fun bounds(contours: List<FloatArray>): Bounds {
        val xs = contours.flatMap { it.indices.step(2).map { i -> it[i] } }
        val ys = contours.flatMap { it.indices.step(2).map { i -> it[i + 1] } }
        return Bounds(xs.min(), ys.min(), xs.max(), ys.max())
    }
    private fun heads(measure: Measure, note: Note, scale: Float): List<Bounds> {
        val isolated = note.copy(accidentals = emptyMap(), duration = note.duration.copy(dots = 0), articulations = emptyList(), tie = false, beam = 0)
        val m = measure.copy(events = listOf(isolated), showsClef = false, showsKey = false, showsTime = false,
            directions = emptyList(), sourceSymbols = emptyList())
        return ScoreTools.engraved(m, scale, 0xFF000000.toInt()).filter { it.kind == PageMark.Kind.FILL }
            .take(note.steps.size).map { bounds(it.contours) }
    }

    @Test fun `final glyph polygons preserve observed head bounds through nonuniform staff warp and page scale`() {
        val source = SourceHead(3, SourcePoint(88f, 120f), 16f, 11f)
        val note = Note(listOf(3), listOf(Pitch(1, 5)), Duration(4), 80f,
            geometry = NoteGeometry(listOf(source), duration = Duration(4)))
        val m = Measure(1, 0, 0, Box(20, 100, 220, 148), 12f, Clef.TREBLE, Key(0), TimeSig(4, 4), listOf(note),
            lines = listOf(100f, 111f, 124f, 133f, 148f, 110f, 121f, 136f, 145f, 161f))
        val first = heads(m, note, 1f).single()
        assertEquals(source.center.x, first.x, .1f)
        assertEquals(source.center.y, first.y, .8f)
        assertEquals(source.width, first.width, .1f)
        assertEquals(source.height, first.height, 1f)
        for (scale in listOf(.5f, 1.75f, 3f)) {
            val scaled = heads(m, note, scale).single()
            assertEquals(first.left * scale, scaled.left, .001f)
            assertEquals(first.top * scale, scaled.top, .001f)
            assertEquals(first.right * scale, scaled.right, .001f)
            assertEquals(first.bottom * scale, scaled.bottom, .001f)
        }
        // A manually corrected pitch must leave the observed horizontal place but move onto
        // the requested pitch of this same warped staff, rather than resurrecting source y.
        val corrected = note.copy(steps = listOf(6), pitches = listOf(Pitch(5, 4)))
        val correctedBounds = heads(m, corrected, 1f).single()
        assertEquals(source.center.x, correctedBounds.x, .1f)
        val correctedSymbol = Engraver.aligned(m.copy(events = listOf(corrected))).marks
            .filterIsInstance<Engraver.Symbol>().first { it.name == "noteheadBlack" }
        assertEquals("Corrected pitch anchor lies on the requested staff line", 3f, correctedSymbol.y, .001f)
        // Unequal spaces above/below a staff line make a warped outline's bounding-box midpoint
        // asymmetric around its pitch anchor. Check the anchor exactly and bound that visual error.
        assertEquals(m.yAt(3f, source.center.x), correctedBounds.y, m.space * .15f)
        assertTrue(correctedBounds.y > first.y + 12f)
    }

    @Test fun `changed rest pitch overrides its old source glyph position`() {
        val glyph = SourceGlyph("restQuarter", SourcePoint(70f, 105f), 10f, 30f, step = 2)
        val rest = Rest(Duration(4), 70f, step = 2, geometry = glyph)
        val m = Measure(1, 0, 0, Box(20, 100, 220, 140), 10f, Clef.TREBLE, Key(0), TimeSig(4, 4), listOf(rest))
        fun symbols(r: Rest) = Engraver.aligned(m.copy(events = listOf(r))).marks.filterIsInstance<Engraver.Symbol>()
        val original = symbols(rest).first { it.name == "restQuarter" }
        val corrected = symbols(rest.copy(step = 6)).first { it.name == "restQuarter" }
        assertEquals(3f, corrected.y, .001f)
        assertTrue("Rest correction must not retain stale observed y", abs(corrected.y - original.y) > .5f)
    }

    @Test fun `real PDF observed heads align with final polygons and produce original baseline current crops`() {
        val library = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")
        val candidates = listOf("Imported/PEP BAND/Music/Diva/Diva- Trumpet 1.pdf", "MobileSheets/Chester.pdf",
            "Imported/PEP BAND/Music/Maryland School Songs/Maryland School Songs - Trombone 1.pdf")
            .map { File(library, it) }.filter { it.isFile }
        assumeTrue("Real PDF fixtures are installed", candidates.isNotEmpty())
        val out = File("build/source-alignment").apply { mkdirs() }
        val report = ArrayList<String>()
        for (file in candidates) Loader.loadPDF(file).use { document ->
            val renderer = PDFRenderer(document)
            val first = renderer.renderImageWithDPI(0, 100f, ImageType.RGB)
            fun ink(image: BufferedImage): Ink {
                val pixels = IntArray(image.width * image.height)
                image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
                return Ink.fromArgb(image.width, image.height, pixels)
            }
            val staffSpace = Recognizer().metrics(ink(first))?.second ?: return@use
            val image = renderer.renderImageWithDPI(0, 100f * 18f / staffSpace, ImageType.RGB)
            val source = PdfPrinted.read(document, 0)
            val reading = Recognizer().read(ink(image), 0, printed = source)
            val originalHeads = source?.scaled(image.width)?.heads.orEmpty().mapNotNull { symbol ->
                symbol.bounds.takeIf { it.size == 4 }?.let { Bounds(it[0], it[1], it[2], it[3]) }
            }
            val observed = reading.measures.flatMap { m -> m.events.filterIsInstance<Note>().map { m to it } }
                .filter { it.second.geometry?.heads?.size == it.second.steps.size && it.second.steps.isNotEmpty() }
            assertTrue("${file.name}: observed geometry captured", observed.size >= 8)
            val errors = ArrayList<Float>()
            val widths = ArrayList<Float>()
            val pdfErrors = ArrayList<Float>()
            for ((measure, note) in observed) {
                val rendered = heads(measure, note, 1f)
                note.steps.forEachIndexed { index, step ->
                    val head = note.geometry!!.heads.firstOrNull { it.step == step } ?: return@forEachIndexed
                    val drawn = rendered[index]
                    errors += hypot(drawn.x - head.center.x, drawn.y - head.center.y) / measure.space
                    widths += abs(drawn.width - head.width) / measure.space
                    originalHeads.minByOrNull { hypot(it.x - head.center.x, it.y - head.center.y) }
                        ?.takeIf { hypot(it.x - head.center.x, it.y - head.center.y) < measure.space * .4f }
                        ?.let { original -> pdfErrors += hypot(drawn.x - original.x, drawn.y - original.y) / measure.space }
                }
            }
            errors.sort(); widths.sort()
            val p95 = errors[(errors.size - 1) * 95 / 100]
            val width95 = widths[(widths.size - 1) * 95 / 100]
            report += "${file.name}: ${errors.size} heads; center p95=$p95 staff spaces; width p95=$width95"
            assertTrue("${file.name}: center p95 $p95 staff spaces", p95 < .15f)
            assertTrue("${file.name}: width p95 $width95 staff spaces", width95 < .03f)
            if (originalHeads.isNotEmpty()) {
                assertTrue("${file.name}: compare actual PDF outline bounds independently of captured model", pdfErrors.size >= 8)
                pdfErrors.sort()
                val pdf95 = pdfErrors[(pdfErrors.size - 1) * 95 / 100]
                report += "${file.name}: ${pdfErrors.size} matches to actual PDF font outlines; center p95=$pdf95 staff spaces"
                assertTrue("${file.name}: actual PDF outline center p95 $pdf95 staff spaces", pdf95 < .15f)
            }
            crop(image, reading.measures, out, file.nameWithoutExtension)
        }
        File(out, "report.txt").writeText(report.joinToString("\n"))
        report.forEach(::println)
    }

    private fun crop(original: BufferedImage, measures: List<Measure>, out: File, name: String) {
        val staff = measures.groupBy { it.staff }.values.firstOrNull { row -> row.sumOf { it.events.size } >= 8 } ?: return
        val space = staff.first().space
        val x = (staff.minOf { it.box.left } - space).toInt().coerceAtLeast(0)
        val y = (staff.minOf { it.box.top } - space * 4).toInt().coerceAtLeast(0)
        val width = ((staff.maxOf { it.box.right } + space).toInt().coerceAtMost(original.width) - x).coerceAtLeast(1)
        val height = ((staff.maxOf { it.box.bottom } + space * 4).toInt().coerceAtMost(original.height) - y).coerceAtLeast(1)
        val sheet = BufferedImage(width, (height + 24) * 3, BufferedImage.TYPE_INT_RGB)
        val graphics = sheet.createGraphics()
        graphics.color = Color.WHITE; graphics.fillRect(0, 0, sheet.width, sheet.height)
        val labels = listOf("Original print", "Baseline: source anchors removed (blue)", "Current: observed source anchors (blue)")
        for (row in 0..2) {
            val top = row * (height + 24)
            graphics.color = Color.BLACK; graphics.drawString(labels[row], 6, top + 17)
            graphics.drawImage(original.getSubimage(x, y, width, height), 0, top + 24, null)
            if (row == 0) continue
            val layer = sheet.createGraphics()
            layer.translate(-x, top + 24 - y)
            layer.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            for (measure in staff) {
                val selected = if (row == 2) measure else measure.copy(sourceSymbols = emptyList(), events = measure.events.map { event -> when (event) {
                    is Note -> event.copy(geometry = null)
                    is Rest -> event.copy(geometry = null)
                } })
                for (mark in ScoreTools.engraved(selected, 1f, 0xA02266EE.toInt())) {
                    layer.color = Color(mark.color, true)
                    if (mark.kind == PageMark.Kind.LINE) {
                        layer.stroke = BasicStroke(mark.width)
                        mark.contours.forEach { p -> for (i in 2 until p.size step 2) layer.draw(java.awt.geom.Line2D.Float(p[i - 2], p[i - 1], p[i], p[i + 1])) }
                    } else layer.fill(Path2D.Float(Path2D.WIND_EVEN_ODD).apply { mark.contours.forEach { p ->
                        if (p.size >= 6) { moveTo(p[0], p[1]); for (i in 2 until p.size step 2) lineTo(p[i], p[i + 1]); closePath() }
                    } })
                }
            }
            layer.dispose()
        }
        graphics.dispose()
        ImageIO.write(sheet, "png", File(out, "$name-original-baseline-current.png"))
    }
}
