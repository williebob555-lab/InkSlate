package com.inksheets.desktop

import com.inkslate.core.PageMark
import com.inksheets.core.omr.Ink
import com.inksheets.core.omr.Recognizer
import com.inksheets.core.omr.Score
import com.inksheets.ui.ScoreTools
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.io.File
import javax.imageio.ImageIO

/**
 * The music tools' marks on a real part, drawn as the editors draw them - the clean reading over
 * the print, a selection, bars cleaned up - to see that they sit where the music is.
 * Pictures to -Dinksheets.shots.
 */
class ScoreToolsShots {
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")

    @Test
    fun `the reading sits on the print`() {
        val file = File(music, "Imported/PEP BAND/Music/Sweet Caroline/SweetC - Trumpet 1.pdf")
        assumeTrue(file.isFile)
        // Read as the app reads: the page drawn so a staff space is about 18 pixels.
        val (ink, _) = OmrRealPagesTest().renderAt(file, 0)!!
        val reading = Recognizer().read(ink, 0)
        val score = Score(reading.measures, 1, listOf(ink.width))
        val path = file.absolutePath
        ScoreTools.scoreSource = { if (it == path) score else null }
        try {
            val (wPts, hPts) = Loader.loadPDF(file).use { d -> d.getPage(0).mediaBox.let { it.width to it.height } }
            ScoreTools.showUnderlay(true)
            ScoreTools.select(5..7)
            ScoreTools.cleanUp(path, listOf(10, 11))
            val marks = ScoreTools.marks(path, 0, wPts, hPts).orEmpty()
            println("${marks.size} marks for ${reading.measures.size} bars")
            assertTrue(marks.size > reading.measures.size * 5)
            // Every mark on the page.
            assertTrue(marks.all { m -> m.contours.all { c -> c.indices.all { i -> if (i % 2 == 0) c[i] in -5f..wPts + 5 else c[i] in -5f..hPts + 5 } } })
            val shots = System.getProperty("inksheets.shots") ?: return
            val k = 2f
            val img = Loader.loadPDF(file).use { PDFRenderer(it).renderImageWithDPI(0, 72f * k, ImageType.RGB) }
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.scale(k.toDouble(), k.toDouble())
            for (m in marks) {
                g.color = Color(m.color, true)
                when (m.kind) {
                    PageMark.Kind.FILL -> g.fill(Path2D.Float(Path2D.WIND_EVEN_ODD).apply {
                        for (c in m.contours) { if (c.size < 6) continue; moveTo(c[0], c[1]); var i = 2; while (i + 1 < c.size) { lineTo(c[i], c[i + 1]); i += 2 }; closePath() }
                    })
                    PageMark.Kind.LINE -> { g.stroke = BasicStroke(m.width); for (c in m.contours) { var i = 2; while (i + 1 < c.size) { g.draw(java.awt.geom.Line2D.Float(c[i - 2], c[i - 1], c[i], c[i + 1])); i += 2 } } }
                }
            }
            g.dispose()
            File(shots).mkdirs()
            ImageIO.write(img, "png", File(shots, "tools-underlay.png"))
        } finally {
            ScoreTools.scoreSource = null
            ScoreTools.showUnderlay(false)
            ScoreTools.select(null)
        }
    }

    /**
     * Bars cleaned up on a scan (the wind ensemble's Chester) meet the print either side without a
     * step: the redrawn staff's lines at each end of a bar are where the scanned lines are just
     * outside it - measured, to a pixel and a half - and pictures of the joins, close up.
     */
    @Test
    fun `cleaned bars join the print on a scan`() {
        val file = File(music, "MobileSheets/Chester.pdf")
        assumeTrue(file.isFile)
        val (ink, _) = OmrRealPagesTest().renderAt(file, 0)!!
        val reading = Recognizer().read(ink, 0)
        val bars = reading.measures.filter { it.bars == 1 && it.lines.size == 10 }
        assumeTrue(bars.size > 6)
        // Where a printed staff line crosses near column [x] (looking [dir]ward, 10 to 16 columns on -
        // further out than the reader looked), near [expected]: in each column the middle of the
        // line-thin dark run nearest it, the median of those.
        fun printedLine(x: Int, dir: Int, expected: Float, sp: Float): Float? {
            val r = (sp * 0.35f).toInt()
            val mids = ArrayList<Float>()
            for (k in 10..16) {
                val c = x + dir * k
                var best: Float? = null
                var y = expected.toInt() - r
                while (y <= expected.toInt() + r) {
                    if (!ink[c, y]) { y++; continue }
                    var e = y
                    while (ink[c, e + 1] && e < expected + r + 3) e++
                    if (e - y + 1 <= maxOf(3, (sp * 0.28f).toInt())) { val mid = (y + e) / 2f; if (best == null || kotlin.math.abs(mid - expected) < kotlin.math.abs(best - expected)) best = mid }
                    y = e + 1
                }
                best?.let { mids += it }
            }
            return if (mids.size < 4) null else mids.sorted()[mids.size / 2]
        }
        var measured = 0; var worst = 0f
        val offs = ArrayList<Triple<Float, Int, String>>()
        // Only where the printed staff goes on past the bar's edge (not past a line's end or start).
        for (m in bars) for ((edge, x) in listOf(0 to m.box.left, 1 to m.box.right)) for (i in 0..4) {
            val staff = reading.staves[m.staff]
            val dir = if (edge == 0) -1 else 1
            if (x + dir * 16 < staff.left + 2 || x + dir * 16 > staff.right - 2) continue
            val at = if (edge == 0) m.box.left.toFloat() else m.box.right.toFloat()
            val drawn = m.lineAt(i, at)
            val printed = printedLine(x, dir, drawn, m.space) ?: continue
            measured++
            val off = kotlin.math.abs(printed - drawn)
            worst = maxOf(worst, off)
            offs += Triple(off, m.number, "${if (edge == 0) "left" else "right"} line $i: drawn ${"%.1f".format(drawn)}, printed ${"%.1f".format(printed)} at x $x (staff ${m.staff})")
        }
        val sorted = offs.map { it.first }.sorted()
        println("staff lines at bar ends: $measured measured, median ${"%.2f".format(sorted[sorted.size / 2])}, 95th ${"%.2f".format(sorted[sorted.size * 95 / 100])}, worst ${"%.2f".format(worst)} px off (space ${"%.1f".format(reading.space)} px); over 1.5: ${sorted.count { it > 1.5f }}")
        offs.sortedByDescending { it.first }.take(8).forEach { println("  OFF ${"%.1f".format(it.first)} m${it.second} ${it.third}") }
        assertTrue(measured > bars.size * 5)
        val shots = System.getProperty("inksheets.shots")
        // A tenth of a space at worst, and nearly all within a pixel: no step to see.
        val p95 = sorted[sorted.size * 95 / 100]
        if (shots == null) { assertTrue("worst ${worst}px", worst <= 2.0f); assertTrue("95th ${p95}px", p95 <= 1.0f); return }
        val score = Score(reading.measures, 1, listOf(ink.width))
        val path = file.absolutePath
        ScoreTools.scoreSource = { if (it == path) score else null }
        try {
            // The joins measured worst, and a few others.
            val chosen = (offs.sortedByDescending { it.first }.take(8).map { it.second } + bars.filterIndexed { i, _ -> i % 5 == 1 }.take(4).map { it.number }).distinct()
            ScoreTools.cleanUp(path, chosen)
            val (wPts, hPts) = Loader.loadPDF(file).use { d -> d.getPage(0).mediaBox.let { it.width to it.height } }
            val marks = ScoreTools.marks(path, 0, wPts, hPts).orEmpty()
            val k = 3f
            val img = Loader.loadPDF(file).use { PDFRenderer(it).renderImageWithDPI(0, 72f * k, ImageType.RGB) }
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.scale(k.toDouble(), k.toDouble())
            for (m in marks) {
                g.color = Color(m.color, true)
                when (m.kind) {
                    PageMark.Kind.FILL -> g.fill(Path2D.Float(Path2D.WIND_EVEN_ODD).apply {
                        for (c in m.contours) { if (c.size < 6) continue; moveTo(c[0], c[1]); var i = 2; while (i + 1 < c.size) { lineTo(c[i], c[i + 1]); i += 2 }; closePath() }
                    })
                    PageMark.Kind.LINE -> { g.stroke = BasicStroke(m.width); for (c in m.contours) { var i = 2; while (i + 1 < c.size) { g.draw(java.awt.geom.Line2D.Float(c[i - 2], c[i - 1], c[i], c[i + 1])); i += 2 } } }
                }
            }
            g.dispose()
            File(shots).mkdirs()
            ImageIO.write(img, "png", File(shots, "tools-clean-scan.png"))
            // The joins close up: each cleaned bar's left and right ends.
            val toPage = wPts * k / ink.width
            val crops = chosen.mapNotNull { n -> reading.measures.firstOrNull { it.number == n } }.flatMap { m ->
                listOf(m.box.left, m.box.right).map { x ->
                    val cx = (x * toPage).toInt(); val cy = ((m.box.top + m.box.bottom) / 2 * toPage).toInt(); val half = (m.space * toPage * 4).toInt()
                    img.getSubimage((cx - half).coerceIn(0, img.width - 2 * half), (cy - half).coerceIn(0, img.height - 2 * half), 2 * half, 2 * half)
                }
            }
            OmrAnswerKeyTest().sheet(crops, File(shots, "tools-clean-scan-joins.png"))
        } finally {
            ScoreTools.scoreSource = null
            ScoreTools.undoAllClean(path)
        }
        assertTrue("worst ${worst}px", worst <= 2.0f)
        assertTrue("95th ${p95}px", p95 <= 1.0f)
    }

    @Test
    fun `cue notes before an entry`() {
        val dir = File(music, "Imported/PEP BAND/Music/Sweet Caroline")
        val file = File(dir, "SweetC - Trumpet 1.pdf")
        assumeTrue(file.isFile)
        fun readOf(f: File): Score? {
            val (ink, _) = OmrRealPagesTest().renderAt(f, 0) ?: return null
            val r = Recognizer().read(ink, 0)
            return Score(r.measures, 1, listOf(ink.width))
        }
        val mine = readOf(file)!!
        val others = dir.listFiles { f -> f.extension.equals("pdf", true) && f.name != file.name && !f.name.contains("score", true) }!!.sortedBy { it.name }.take(8).mapNotNull { readOf(it) }
        val cues = ScoreTools.cueBars(mine, others)
        println("cues over bars ${cues.keys.map { mine.measures[it].number }} from ${others.size} parts: " + cues.entries.joinToString { (k, v) -> "${mine.measures[k].number}<-${v.map { it.number }}" })
        assertTrue(cues.isNotEmpty())
        val path = file.absolutePath
        ScoreTools.scoreSource = { if (it == path) mine else null }
        try {
            ScoreTools.showCuesFor(path, cues)
            val (wPts, hPts) = Loader.loadPDF(file).use { d -> d.getPage(0).mediaBox.let { it.width to it.height } }
            val marks = ScoreTools.marks(path, 0, wPts, hPts).orEmpty()
            val shots = System.getProperty("inksheets.shots") ?: return
            val k = 2f
            val img = Loader.loadPDF(file).use { PDFRenderer(it).renderImageWithDPI(0, 72f * k, ImageType.RGB) }
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.scale(k.toDouble(), k.toDouble())
            for (m in marks) {
                g.color = Color(m.color, true)
                when (m.kind) {
                    PageMark.Kind.FILL -> g.fill(Path2D.Float(Path2D.WIND_EVEN_ODD).apply {
                        for (c in m.contours) { if (c.size < 6) continue; moveTo(c[0], c[1]); var i = 2; while (i + 1 < c.size) { lineTo(c[i], c[i + 1]); i += 2 }; closePath() }
                    })
                    PageMark.Kind.LINE -> { g.stroke = BasicStroke(m.width); for (c in m.contours) { var i = 2; while (i + 1 < c.size) { g.draw(java.awt.geom.Line2D.Float(c[i - 2], c[i - 1], c[i], c[i + 1])); i += 2 } } }
                }
            }
            g.dispose()
            ImageIO.write(img, "png", File(shots, "tools-cues.png"))
        } finally {
            ScoreTools.scoreSource = null
            ScoreTools.showCuesFor(path, emptyMap())
        }
    }
}
