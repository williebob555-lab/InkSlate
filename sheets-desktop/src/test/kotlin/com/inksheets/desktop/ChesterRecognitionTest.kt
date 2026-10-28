package com.inksheets.desktop

import com.inksheets.core.omr.*
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.ui.*
import com.inkslate.core.PageMark
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.imageio.ImageIO

/** Investigate the original print with the actual shipped scan reader, without library writes. */
class ChesterRecognitionTest {
    @get:Rule val tmp = TemporaryFolder()
    private class Stand(override val startFolder: File) : SheetsPlatform {
        private val prefs = mutableMapOf("sheets_library" to startFolder.absolutePath)
        override val deviceId = "chester-regression"
        override val deviceName = "Chester regression"
        override val localFolder = File(startFolder, "local")
        override fun pref(key: String) = prefs[key]
        override fun setPref(key: String, value: String?) { if (value == null) prefs.remove(key) else prefs[key] = value }
        override fun openPart(song: Song, part: Part, file: File) {}
        override fun pageText(file: File, page: Int): String? = null
        override val audioOut: AudioOut? = null
        override val microphone: Microphone? = null
        override fun onMain(block: () -> Unit) = block()
    }
    @Test fun `Chester first staff uses the same recognition path as the app`() {
        val file = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets/MobileSheets/Chester.pdf")
        assumeTrue(file.isFile)
        val net = Net.shipped
        assumeTrue("Shipped scan reader is available", net != null)
        val out = File("build/chester-reading").apply { mkdirs() }
        Loader.loadPDF(file).use { document ->
            val renderer = PDFRenderer(document)
            fun draw(dpi: Float): Pair<java.awt.image.BufferedImage, IntArray> {
                val image = renderer.renderImageWithDPI(0, dpi, ImageType.RGB)
                val pixels = IntArray(image.width * image.height)
                image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
                return image to pixels
            }
            val first = draw(100f)
            val space = Recognizer().metrics(Ink.fromArgb(first.first.width, first.first.height, first.second))!!.second
            val (image, pixels) = draw(100f * 18f / space)
            val ink = Ink.fromArgb(image.width, image.height, pixels)
            val grey = Strips.grey(pixels)
            val printed = PdfPrinted.read(document, 0)
            assertNull("Chester is a scan, so app must use the shipped network", printed)
            val classical = Recognizer().read(ink, 0)
            val actual = Recognizer().read(ink, 0, grey = grey, net = net)
            for ((name, reading) in listOf("classical" to classical, "production" to actual)) {
                File(out, "$name.json").writeText(Scores.encode(Score(reading.measures, 1, listOf(image.width))))
                val staff = reading.measures.filter { it.staff == 0 }
                println("CHESTER $name: ${reading.measures.size} bars; first staff ${staff.size}; dropped ${reading.dropped.size}")
                staff.forEach { m -> println("CHESTER $name bar${m.number} ${m.box}: " + m.events.joinToString { event -> when (event) {
                    is Note -> "note step=${event.steps} ${event.duration} x=${event.x} beam=${event.beam} stem=${event.stemUp} tip=${event.stemTip}"
                    is Rest -> "rest ${event.duration} step=${event.step} x=${event.x}"
                } }) }
                println("CHESTER $name dropped: " + reading.dropped.joinToString { (h, reason) -> "${h.kind} step=${h.step} x=${h.x} y=${h.y}: $reason" })
            }
            ImageIO.write(image, "png", File(out, "source-page.png"))
            val firstStaff = actual.measures.filter { it.staff == 0 }
            val cue = firstStaff.first { it.number == 5 }
            val beamedCue = firstStaff.first { it.number == 6 }
            for (bar in listOf(cue, beamedCue)) {
                assertTrue("Bar ${bar.number}: the Bsn. cue is not the player's melody", bar.events.all { it is Rest })
                assertEquals("Bar ${bar.number}: the player's whole rest fills the bar", 4.0, bar.events.sumOf { it.duration.quarters }, .001)
                assertTrue("Bar ${bar.number}: original cue contours must be retained", bar.kept.isNotEmpty())
            }
            assertTrue("Actual full-size whole note remains playable", firstStaff.first { it.number == 4 }.events.any { it is Note && it.duration.base == 1 })
            val copied = File(tmp.root, "Chester.pdf").also { file.copyTo(it) }
            val state = SheetsState(Stand(tmp.root)).apply { currentPath = copied.absolutePath; pageShown = 0 to 1 }
            val score = Score(actual.measures, 1, listOf(image.width))
            ScoreTools.install(state)
            ScoreTools.scoreSource = { if (it == copied.absolutePath) score else null }
            try {
                ScoreTools.cleanUp(copied.absolutePath, listOf(5, 6))
                val clean = java.awt.image.BufferedImage(image.width, image.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
                val g = clean.createGraphics()
                g.drawImage(image, 0, 0, null)
                for (mark in ScoreTools.marks(copied.absolutePath, 0, image.width.toFloat(), image.height.toFloat()).orEmpty()) {
                    g.color = java.awt.Color(mark.color, true)
                    if (mark.kind == PageMark.Kind.LINE) {
                        g.stroke = java.awt.BasicStroke(mark.width)
                        mark.contours.forEach { p -> for (i in 2 until p.size step 2) g.draw(java.awt.geom.Line2D.Float(p[i - 2], p[i - 1], p[i], p[i + 1])) }
                    } else g.fill(java.awt.geom.Path2D.Float(java.awt.geom.Path2D.WIND_EVEN_ODD).apply {
                        mark.contours.forEach { p -> if (p.size >= 6) { moveTo(p[0], p[1]); for (i in 2 until p.size step 2) lineTo(p[i], p[i + 1]); closePath() } }
                    })
                }
                g.dispose()
                ImageIO.write(clean, "png", File(out, "production-clean-cues.png"))
                // First half of bar5 contains the hollow bassoon cue head that looked missing
                // in the earlier template-only overlay. Avoid the staff lines in this sample.
                val x0 = (cue.box.left + cue.space).toInt()
                val x1 = (cue.box.left + cue.box.width * .4f).toInt()
                val y0 = (cue.box.top + cue.space * .15f).toInt()
                val y1 = (cue.box.top + cue.space * .85f).toInt()
                var sourceInk = 0; var retainedInk = 0
                for (y in y0..y1) for (x in x0..x1) if ((image.getRGB(x, y) and 255) < 100) {
                    sourceInk++
                    if ((clean.getRGB(x, y) and 255) < 130) retainedInk++
                }
                assertTrue("Cue head is present in original crop", sourceInk > 20)
                val retained = retainedInk.toFloat() / sourceInk
                println("CHESTER production cue half-note ink retained=$retained ($retainedInk/$sourceInk)")
                assertTrue("Clean must preserve printed cue half-note ink", retained > .85f)
                var beamInk = 0; var keptBeamInk = 0
                for (y in (beamedCue.box.top - beamedCue.space * 4).toInt()..(beamedCue.box.top - beamedCue.space * .3f).toInt())
                    for (x in (beamedCue.box.left + beamedCue.space * .5f).toInt()..(beamedCue.box.right - beamedCue.space * .5f).toInt())
                        if ((image.getRGB(x, y) and 255) < 100) {
                            beamInk++
                            if ((clean.getRGB(x, y) and 255) < 130) keptBeamInk++
                        }
                assertTrue("The cue's original stems/beam are in the source crop", beamInk > 50)
                val keptBeam = keptBeamInk.toFloat() / beamInk
                println("CHESTER production cue beam/stem ink retained=$keptBeam ($keptBeamInk/$beamInk)")
                assertTrue("Clean must keep cue stems and beam rather than reconstructing a flag", keptBeam > .85f)
            } finally { ScoreTools.undoAllClean(copied.absolutePath); ScoreTools.close(state); ScoreTools.scoreSource = null }
        }
    }
}
