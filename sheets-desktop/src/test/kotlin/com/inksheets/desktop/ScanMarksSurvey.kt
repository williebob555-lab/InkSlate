package com.inksheets.desktop

import com.inksheets.core.omr.Ink
import com.inksheets.core.omr.Net
import com.inksheets.core.omr.Note
import com.inksheets.core.omr.Recognizer
import com.inksheets.core.omr.Strips
import org.apache.pdfbox.Loader
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * How many marks the reader finds on the library's scanned (image-only) parts, with the geometry reading
 * of scans ([com.inksheets.core.omr.ScanMarks]) and without (-Dinksheets.omr.noscanmarks): a light read-only
 * pass over a sample, one page each. Run with -Dinksheets.survey=scanmarks[,N sample size].
 */
class ScanMarksSurvey {
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")

    private fun isScan(f: File): Boolean = runCatching {
        Loader.loadPDF(f).use { d -> d.numberOfPages > 0 && d.getPage(0).resources.let { r -> r.fontNames.none() && r.xObjectNames.any() } }
    }.getOrDefault(false)

    private fun marks(file: File, page: Int, geometry: Boolean): IntArray {
        if (geometry) System.clearProperty("inksheets.omr.noscanmarks") else System.setProperty("inksheets.omr.noscanmarks", "1")
        val source = com.inkslate.desktop.DesktopSources.open(file, detached = true) ?: return IntArray(5)
        fun draw(width: Int): Pair<IntArray, Ink> {
            val img = source.render(page, width)!!
            val px = IntArray(img.width * img.height); img.readPixels(px)
            return Strips.grey(px) to Ink.fromArgb(img.width, img.height, px)
        }
        val space = Recognizer().metrics(draw(1600).second)?.second
        val width = if (space == null || space <= 0f) 1600 else (1600 * 18f / space).toInt().coerceIn(1000, 5000)
        val (grey, ink) = draw(width)
        val r = Recognizer().read(ink, page, 1, Recognizer.Carry(), null, grey = grey, net = Net.shipped)
        val d = r.measures.flatMap { it.directions }.distinct()
        return intArrayOf(r.measures.sumOf { m -> m.events.count { it is Note && "staccato" in it.articulations } },
            d.count { it.kind == "slur" }, d.count { it.kind == "dynamic" }, d.count { it.kind == "cresc" || it.kind == "dim" }, r.measures.size)
    }

    @Test
    fun `marks found on scans`() {
        val spec = System.getProperty("inksheets.survey") ?: return
        assumeTrue(spec.startsWith("scanmarks"))
        val want = spec.substringAfter(",", "10").toInt()
        val files = music.walkTopDown().filter { it.isFile && it.extension.equals("pdf", true) && !it.path.contains(".inksheets") }.toList()
            .sortedBy { java.util.zip.CRC32().apply { update(it.path.toByteArray()) }.value }
        var n = 0
        val before = IntArray(5); val after = IntArray(5)
        for (f in files) {
            if (n >= want) break
            if (!isScan(f)) continue
            val a = runCatching { marks(f, 0, false) }.getOrNull() ?: continue
            val b = runCatching { marks(f, 0, true) }.getOrNull() ?: continue
            n++
            for (i in 0..4) { before[i] += a[i]; after[i] += b[i] }
            println("SURVEY ${f.name}: staccato ${a[0]}->${b[0]} slurs ${a[1]}->${b[1]} dynamics ${a[2]}->${b[2]} hairpins ${a[3]}->${b[3]} (bars ${a[4]})")
        }
        System.clearProperty("inksheets.omr.noscanmarks")
        println("SURVEY TOTAL over $n scans: staccato ${before[0]}->${after[0]} slurs ${before[1]}->${after[1]} dynamics ${before[2]}->${after[2]} hairpins ${before[3]}->${after[3]} (bars ${before[4]})")
    }
}
