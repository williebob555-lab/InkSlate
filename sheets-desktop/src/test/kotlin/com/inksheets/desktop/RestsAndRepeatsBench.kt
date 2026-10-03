package com.inksheets.desktop

import com.inksheets.core.omr.Ink
import com.inksheets.core.omr.Measure
import com.inksheets.core.omr.Recognizer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Multi-bar rests (how many bars, from the figure over them) and bar-repeat signs, read off scans:
 * the library's parts that state their notes give the truth (read from the PDF itself), and the same
 * pages scanned (as the reading benchmark scans them) are read as a scan is. Each truth rest or repeat
 * is matched to the scan's bar where it lies. -Dinksheets.restbench=1 [-Dinksheets.bench.n=200]
 */
class RestsAndRepeatsBench {
    /** Bar [m] and a bar's width either side, cut from the scanned page into -Dinksheets.restbench.crops (when given). */
    private fun crop(grey: IntArray, w: Int, h: Int, m: Measure, name: String) {
        val dir = System.getProperty("inksheets.restbench.crops") ?: return
        java.io.File(dir).mkdirs()
        val x0 = (m.box.left - m.space * 4).toInt().coerceAtLeast(0); val x1 = (m.box.right + m.space * 4).toInt().coerceAtMost(w - 1)
        val y0 = (m.box.top - m.space * 5).toInt().coerceAtLeast(0); val y1 = (m.box.bottom + m.space * 3).toInt().coerceAtMost(h - 1)
        if (x1 <= x0 || y1 <= y0) return
        val img = java.awt.image.BufferedImage(x1 - x0, y1 - y0, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in y0 until y1) for (x in x0 until x1) { val g = grey[y * w + x] and 0xFF; img.setRGB(x - x0, y - y0, (g shl 16) or (g shl 8) or g) }
        javax.imageio.ImageIO.write(img, "png", java.io.File(dir, "$name.png"))
    }

    @Test
    fun `rests and repeats on scans`() {
        assumeTrue(System.getProperty("inksheets.restbench") != null)
        val bench = ReadingBenchmark()
        val n = (System.getProperty("inksheets.bench.n") ?: "200").toInt()
        val net = com.inksheets.core.omr.Net.shipped!!
        val only = System.getProperty("inksheets.restbench.only")
        val parts = if (only != null) bench.corpus().filter { it.name.contains(only, true) } else bench.corpus().take(n)
        var rests = 0; var restsRight = 0; var restsSure = 0; var restsSureRight = 0
        var repeats = 0; var repeatsFound = 0; var repeatsInvented = 0
        val wrong = java.util.Collections.synchronizedList(ArrayList<String>())
        val pool = Executors.newFixedThreadPool(4)
        val results = parts.map { f -> pool.submit<IntArray> {
            val c = IntArray(7)
            for (p in 0 until 4) runCatching {
                val (ink, dpi) = OmrRealPagesTest().renderAt(f, p) ?: return@runCatching
                val truth = Recognizer().read(ink, p, 1, Recognizer.Carry(), PdfPrinted.read(f, p) ?: return@runCatching).measures
                if (truth.none { it.bars > 1 || it.repeatsBar }) return@runCatching
                val grey = bench.scannedGrey(ink, 0.0, (f.name + p).hashCode().toLong())
                val scan = Recognizer().apply { traceRests = only != null }.read(Ink.fromGrey(ink.width, ink.height, grey), p, 1, Recognizer.Carry(), null, grey = grey, net = net).measures
                fun at(m: Measure) = scan.filter { it.staff == m.staff || abs(it.box.top - m.box.top) < m.space * 3 }
                    .maxByOrNull { minOf(it.box.right, m.box.right) - maxOf(it.box.left, m.box.left) }
                    ?.takeIf { minOf(it.box.right, m.box.right) - maxOf(it.box.left, m.box.left) > m.box.width * 0.6f }
                for (m in truth) {
                    if (m.bars > 1) {
                        c[0]++
                        val s = at(m)
                        if (s != null && s.bars == m.bars) c[1]++ else {
                            wrong += "REST ${f.name} p${p + 1} bar ${m.number}: ${m.bars} bars, read ${s?.bars ?: "-"}${if (s?.sure == true) " (sure)" else ""} ${s?.doubts ?: ""} truth staff ${m.staff} box ${m.box} scan staff ${s?.staff} box ${s?.box}"
                            crop(grey, ink.width, ink.height, m, "rest-${f.nameWithoutExtension.take(16)}-p${p + 1}-b${m.number}")
                        }
                        if (s?.sure == true) { c[2]++; if (s.bars == m.bars) c[3]++ }
                    }
                    if (m.repeatsBar) { c[4]++; val s = at(m); if (s?.repeatsBar == true) c[5]++ else {
                        wrong += "REPEAT ${f.name} p${p + 1} bar ${m.number}: read ${s?.events?.size ?: "-"} events${s?.doubts?.let { " $it" } ?: ""}"
                        crop(grey, ink.width, ink.height, m, "repeat-${f.nameWithoutExtension.take(16)}-p${p + 1}-b${m.number}")
                    } }
                }
                for (s in scan) if (s.repeatsBar && truth.none { t -> t.repeatsBar && abs(t.box.left - s.box.left) < t.space * 2 && abs(t.box.top - s.box.top) < t.space * 3 }) { c[6]++; wrong += "REPEAT INVENTED ${f.name} p${p + 1} bar ${s.number}"; crop(grey, ink.width, ink.height, s, "invented-${f.nameWithoutExtension.take(16)}-p${p + 1}-b${s.number}") }
            }
            c
        } }.map { it.get() }
        pool.shutdown()
        for (c in results) { rests += c[0]; restsRight += c[1]; restsSure += c[2]; restsSureRight += c[3]; repeats += c[4]; repeatsFound += c[5]; repeatsInvented += c[6] }
        wrong.take(40).forEach { println("  $it") }
        println("RESTS: $rests multi-bar rests, count right $restsRight; read sure $restsSure, of those right $restsSureRight")
        println("REPEATS: $repeats bar-repeat signs, found $repeatsFound; read where none is $repeatsInvented")
    }
}
