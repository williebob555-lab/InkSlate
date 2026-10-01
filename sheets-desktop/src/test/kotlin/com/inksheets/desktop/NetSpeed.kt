package com.inksheets.desktop

import com.inksheets.core.omr.Ink
import com.inksheets.core.omr.Learned
import com.inksheets.core.omr.Net
import com.inksheets.core.omr.Recognizer
import com.inksheets.core.omr.Strips
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * How long the trained reader takes over a real page, on this machine, by stage - against the
 * phone's budget (10 s a page at most, all told). -Dinksheets.bench.net=weights [-Dinksheets.omr.file=pdf]
 */
class NetSpeed {
    @Test
    fun `time a page`() {
        val weights = System.getProperty("inksheets.bench.net") ?: return assumeTrue(false)
        val file = File(System.getProperty("inksheets.omr.file") ?: (System.getenv("USERPROFILE") + "/Music/Sheet Music/InkSheets/MobileSheets/Chester.pdf"))
        val net = File(weights).inputStream().use { Net.load(it) }
        val (_, dpi) = OmrRealPagesTest().renderAt(file, 0)!!
        val img = Loader.loadPDF(file).use { PDFRenderer(it).renderImageWithDPI(0, dpi, ImageType.RGB) }
        val px = IntArray(img.width * img.height); img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
        val ink = Ink.fromArgb(img.width, img.height, px)
        val grey = Strips.grey(px)
        repeat(3) { round ->
            val t0 = System.nanoTime()
            val rec = Recognizer()
            val (t, space) = rec.metrics(ink)!!
            val staves = rec.staves(ink, t, space)
            val t1 = System.nanoTime()
            var cut = 0L; var run = 0L; var cols = 0
            for (s in staves) {
                val a = System.nanoTime(); val strip = Strips.cut(grey, img.width, img.height, s); val b = System.nanoTime()
                net.run(strip); val c = System.nanoTime()
                cut += b - a; run += c - b; cols += strip.width
            }
            val t2 = System.nanoTime()
            val p = Learned.symbols(grey, img.width, img.height, staves, net)
            val t3 = System.nanoTime()
            val r = Recognizer().read(ink, 0, grey = grey, net = net)
            val t4 = System.nanoTime()
            println("SPEED round $round: ${img.width}x${img.height}, ${staves.size} staves, strip columns $cols; staves ${(t1 - t0) / 1_000_000} ms, " +
                "cut ${cut / 1_000_000} ms, net ${run / 1_000_000} ms, symbols ${(t3 - t2) / 1_000_000} ms (${p.symbols.size}), whole read ${(t4 - t3) / 1_000_000} ms (${r.measures.size} bars, ${r.measures.count { it.sure }} sure)")
        }
    }
}
