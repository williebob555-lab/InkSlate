package com.inksheets.desktop

import com.inksheets.core.omr.Digits
import com.inksheets.core.omr.Ink
import com.inksheets.core.omr.Outline
import com.inksheets.core.omr.Printed
import com.inksheets.core.omr.Recognizer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Training data for the trained digit reader, from the library's parts that state their text: every
 * digit printed (a time signature's, from the page without its staff lines; a bar number's, a rest's
 * count, a tuplet's, from the page) cut out by its outline as the reader cuts it at run time
 * ([Digits.mask]), and - as "no digit" - the other shapes of a digit's size where the reader looks for
 * numbers (letters, dynamics, marks). Each page clean, as a scan, and as a scan drawn small (a phone's).
 * One line a shape in digits.tsv: label (0-9, 10 none), kind, look, song, held, height/space,
 * width/height, the mask's bits. -Dinksheets.digitexport=<dir> [-Dinksheets.digitexport.pages=3]
 */
class DigitExport {
    private val held: Set<String> by lazy { File("../train/held.txt").takeIf { it.isFile }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet() }

    private fun scanned(ink: Ink, seed: Long): Ink {
        val r = java.util.Random(seed)
        val grey = IntArray(ink.width * ink.height)
        for (y in 0 until ink.height) for (x in 0 until ink.width) {
            var dark = 0.0
            for (dy in -1..1) for (dx in -1..1) if (ink[x + dx, y + dy]) dark += if (dx == 0 && dy == 0) 0.4 else 0.1
            val paper = 220 + (x * 25 / ink.width) - (y * 10 / ink.height)
            grey[y * ink.width + x] = (paper - dark.coerceAtMost(1.0) * 210 + r.nextGaussian() * 14).toInt().coerceIn(0, 255)
        }
        return Ink.fromGrey(ink.width, ink.height, grey)
    }

    private class Shape(val l: Int, val t: Int, val r: Int, val b: Int, val n: Int) {
        val w get() = r - l + 1
        val h get() = b - t + 1
    }

    /** Every shape of [ink] with a pixel in the box, followed no further than [reach] outside it. */
    private fun shapes(ink: Ink, x0: Int, y0: Int, x1: Int, y1: Int, reach: Int): List<Shape> {
        val rx0 = max(0, x0 - reach); val ry0 = max(0, y0 - reach)
        val region = Outline.Region(rx0, ry0, min(ink.width, x1 + reach + 1) - rx0, min(ink.height, y1 + reach + 1) - ry0)
        val out = ArrayList<Shape>()
        for (y in max(0, y0)..min(ink.height - 1, y1)) for (x in max(0, x0)..min(ink.width - 1, x1)) {
            if (!ink[x, y] || !region.inside(x, y) || region.seen[region.index(x, y)]) continue
            val px = Outline.component(ink, x, y, region, 20_000)
            if (px.isEmpty()) continue
            var l = Int.MAX_VALUE; var r = Int.MIN_VALUE; var t = Int.MAX_VALUE; var b = Int.MIN_VALUE
            for (i in px.indices step 2) { l = min(l, px[i]); r = max(r, px[i]); t = min(t, px[i + 1]); b = max(b, px[i + 1]) }
            out += Shape(l, t, r, b, px.size / 2)
        }
        return out
    }

    private fun line(label: Int, kind: String, look: String, song: String, sp: Float, ink: Ink, s: Shape): String? {
        val m = Digits.mask(ink, s.l, s.t, s.r, s.b) ?: return null
        return "$label\t$kind\t$look\t$song\t${song in held}\t${"%.3f".format(java.util.Locale.ROOT, s.h / sp)}\t${"%.3f".format(java.util.Locale.ROOT, s.w.toFloat() / s.h)}\t" +
            m.joinToString("") { if (it) "1" else "0" }
    }

    private fun page(f: File, p: Int, song: String): List<String> {
        val printed0 = runCatching { com.inksheets.desktop.PdfPrinted.read(f, p) }.getOrNull() ?: return emptyList()
        val digits0 = printed0.symbols.filter { it.kind == Printed.Kind.TIME_DIGIT || it.kind == Printed.Kind.TEXT_DIGIT }
        if (digits0.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        for ((look, space) in listOf("clean" to 18f, "scan" to 18f, "small" to 11f)) {
            val (rendered, _) = OmrRealPagesTest().renderAt(f, p, space) ?: continue
            val ink = if (look == "clean") rendered else scanned(rendered, (f.name + p + look).hashCode().toLong())
            val rec = Recognizer()
            val (t, sp) = rec.metrics(ink) ?: continue
            val staves = rec.staves(ink, t, sp)
            if (staves.isEmpty()) continue
            val clean = rec.withoutLines(ink, staves, t)
            val printed = printed0.scaled(ink.width)
            val taken = HashSet<Long>()
            fun key(s: Shape, inStaff: Boolean) = (if (inStaff) 1L shl 62 else 0L) or (s.l.toLong() shl 32) or (s.t.toLong() shl 16) or s.r.toLong()
            for (d in printed.symbols) {
                if (d.kind != Printed.Kind.TIME_DIGIT && d.kind != Printed.Kind.TEXT_DIGIT || d.digit !in 0..9) continue
                val time = d.kind == Printed.Kind.TIME_DIGIT
                val src = if (time) clean else ink
                val cx0 = d.x + d.width * 0.15f; val cx1 = d.x + d.width * 0.85f
                // The biggest shape round the character's place whose middle is in its advance.
                val found = shapes(src, cx0.toInt(), (d.y - d.size * 0.6f).toInt(), cx1.toInt(), (d.y + d.size * 0.3f).toInt(), (d.size * 0.8f).toInt())
                    .filter { (it.l + it.r) / 2f in d.x..d.x + d.width }.maxByOrNull { it.n } ?: continue
                // Only a figure standing alone: one run into a stem or a beam is no sample of its shape.
                if (found.h < d.size * 0.3f || found.h > d.size * 0.9f || found.w > d.width * 1.4f + 2 || found.w > found.h * 1.3f) continue
                taken += key(found, time)
                line(d.digit, if (time) "time" else "text", look, song, sp, src, found)?.let { out += it }
            }
            // Shapes of a digit's size where numbers are looked for, that are none: over each staff, and its start.
            val rnd = java.util.Random((f.name + p + look).hashCode().toLong())
            for (s in staves) {
                for ((inStaff, box) in listOf(false to intArrayOf(s.left - (sp * 3).toInt(), s.y(-9, s.left).roundToInt(), s.right, s.y(-1, s.left).roundToInt()),
                    true to intArrayOf(s.left, s.top - (sp * 0.5f).toInt(), s.left + (sp * 14).toInt(), s.bottom + (sp * 0.5f).toInt()))) {
                    val src = if (inStaff) clean else ink
                    val cands = shapes(src, box[0], box[1], box[2], box[3], (sp * 3).toInt())
                        .filter { it.h >= sp * 0.5f && it.h <= sp * 3.2f && it.w <= it.h * 1.3f && key(it, inStaff) !in taken }
                        .filter { c -> printed.symbols.none { d -> (d.kind == Printed.Kind.TIME_DIGIT || d.kind == Printed.Kind.TEXT_DIGIT) && (c.l + c.r) / 2f in d.x - 2..d.x + d.width + 2 && (c.t + c.b) / 2f in d.y - d.size..d.y + d.size * 0.4f } }
                    for (c in cands.shuffled(rnd).take(if (inStaff) 3 else 6)) line(10, if (inStaff) "time" else "text", look, song, sp, src, c)?.let { out += it }
                }
            }
        }
        return out
    }

    @Test
    fun `cut the printed digits out`() {
        val dir = System.getProperty("inksheets.digitexport") ?: return assumeTrue(false)
        val pages = (System.getProperty("inksheets.digitexport.pages") ?: "3").toInt()
        val bench = ReadingBenchmark()
        val parts = bench.corpus()
        val lines = java.util.Collections.synchronizedList(ArrayList<String>())
        val pool = Executors.newFixedThreadPool(4)
        val t0 = System.currentTimeMillis()
        parts.map { f -> pool.submit { for (p in 0 until pages) runCatching { lines += page(f, p, bench.song(f)) }.onFailure { println("  failed ${f.name} p$p: ${it.message}") } } }.forEach { it.get() }
        pool.shutdown()
        File(dir).mkdirs()
        File(dir, "digits.tsv").writeText(lines.joinToString("\n"))
        println("DIGITS: ${parts.size} parts, ${lines.size} shapes in ${(System.currentTimeMillis() - t0) / 1000}s")
    }
}
