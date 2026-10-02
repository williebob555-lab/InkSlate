package com.inksheets.desktop

import com.inksheets.core.omr.Event
import com.inksheets.core.omr.Ink
import com.inksheets.core.omr.Note
import com.inksheets.core.omr.Printed.Kind
import com.inksheets.core.omr.Recognizer
import com.inksheets.core.omr.Rest
import com.inksheets.core.omr.Strips
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * Teaching from the bars put right in Fix (the library's Training/Fixes), taken with a grain of salt:
 * the player's pick may be wrong too. Only a pick that adds up to its bar's time is taught, and only
 * where the reader saw every note and rest of it on the page (so where each is, is known) - its
 * values and dots taught from the pick, at half the weight of what the library's printed parts teach
 * (a [-4, from, to, 0.5] span); the rest of the strip not taught either way. Held-out songs left out.
 * Into <dir>/strips and <dir>/labels.jsonl. -Dinksheets.fixes=<dir> -Dinksheets.bench.net=<weights>
 */
class FixExport {
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")

    private class Fix(val file: String, val page: Int, val staff: Int, val width: Int, val box: List<Int>, val events: List<Event>, val at: Long, val adds: Boolean?)

    private fun render(file: File, index: Int): BufferedImage? = Loader.loadPDF(file).use { doc ->
        if (index >= doc.numberOfPages) return null
        val r = PDFRenderer(doc)
        fun ink(img: BufferedImage): Ink {
            val px = IntArray(img.width * img.height); img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
            return Ink.fromArgb(img.width, img.height, px)
        }
        val m = Recognizer().metrics(ink(r.renderImageWithDPI(index, 100f, ImageType.RGB))) ?: return null
        r.renderImageWithDPI(index, 100f * 18f / m.second, ImageType.RGB)
    }

    private fun save(strip: com.inksheets.core.omr.Strip, out: File) {
        val img = BufferedImage(strip.width, Strips.H, BufferedImage.TYPE_BYTE_GRAY)
        for (r in 0 until Strips.H) for (c in 0 until strip.width) img.raster.setSample(c, r, 0, (strip[c, r] * 255).toInt().coerceIn(0, 255))
        ImageIO.write(img, "png", out)
    }

    /** The fixes written down, the latest for each bar (a bar fixed again, the later one). */
    private fun fixes(): List<Fix> {
        val dir = File(music, "Training/Fixes")
        val all = ArrayList<Fix>()
        for (f in dir.listFiles { x -> x.name.endsWith(".jsonl") }.orEmpty()) for (line in f.readLines()) {
            if (line.isBlank()) continue
            val o = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            if (o["kind"] != null) continue   // "not one bar", a skip: nothing picked
            val events = o["events"] as? JsonObject ?: continue
            val evs = com.inksheets.core.omr.Scores.decodeFixes(events.toString()).values.firstOrNull() ?: continue
            all += Fix(o["file"]!!.jsonPrimitive.content, o["page"]!!.jsonPrimitive.intOrNull!!, o["staff"]!!.jsonPrimitive.intOrNull!!,
                o["width"]!!.jsonPrimitive.intOrNull!!, o["box"]!!.jsonArray.map { it.jsonPrimitive.intOrNull!! }, evs,
                o["at"]?.jsonPrimitive?.longOrNull ?: 0L, o["adds"]?.jsonPrimitive?.booleanOrNull)
        }
        return all.groupBy { Triple(it.file, it.page, it.box) }.values.map { same -> same.maxBy { it.at } }
    }

    @Test
    fun `cut the bars put right in Fix into strips`() {
        val out = File(System.getProperty("inksheets.fixes") ?: return assumeTrue(false))
        val net = File(System.getProperty("inksheets.bench.net")!!).inputStream().use { com.inksheets.core.omr.Net.load(it) }
        val dir = File(out, "strips").apply { mkdirs() }
        val bench = ReadingBenchmark()
        val held = File("../train/held.txt").readLines().map { it.trim() }.toSet()
        val labels = ArrayList<String>()
        val why = HashMap<String, Int>()
        fun no(reason: String) { why[reason] = (why[reason] ?: 0) + 1 }
        val all = fixes()
        for ((key, onPage) in all.groupBy { it.file to it.page }) {
            val file = File(music, key.first)
            if (!file.isFile) { onPage.forEach { no("file gone") }; continue }
            if (bench.song(file) in held) { onPage.forEach { no("held-out song") }; continue }
            val img = runCatching { render(file, key.second) }.getOrNull() ?: run { onPage.forEach { no("page not drawn") }; null } ?: continue
            val argb = IntArray(img.width * img.height); img.getRGB(0, 0, img.width, img.height, argb, 0, img.width)
            val ink = Ink.fromArgb(img.width, img.height, argb)
            val grey = Strips.grey(argb)
            val rec = Recognizer()
            val (t, space) = rec.metrics(ink) ?: continue
            val staves = rec.staves(ink, t, space)
            val found = com.inksheets.core.omr.Learned.symbols(grey, img.width, img.height, staves, net)
            val reading = Recognizer().read(ink, key.second, grey = grey, net = net)
            for (fix in onPage) {
                val k = img.width.toFloat() / fix.width
                val s = staves.getOrNull(fix.staff) ?: run { no("staff not found"); null } ?: continue
                val sp = s.space
                val l = fix.box[0] * k; val r = fix.box[2] * k
                // The bar as read here: the one on that staff sharing most of the fixed bar's width.
                val m = reading.measures.filter { it.staff == fix.staff && it.bars == 1 }.maxByOrNull { minOf(r, it.box.right.toFloat()) - maxOf(l, it.box.left.toFloat()) }
                    ?.takeIf { minOf(r, it.box.right.toFloat()) - maxOf(l, it.box.left.toFloat()) > (r - l) * 0.7f } ?: run { no("bar not found again"); null } ?: continue
                // A grain of salt: only a pick that adds up.
                val adds = abs(fix.events.sumOf { it.duration.quarters } - m.time.quarters) < 1e-6
                if (fix.adds == false || !adds) { no("does not add up"); continue }
                val strip = Strips.cut(grey, img.width, img.height, s)
                fun f1(v: Float) = "%.1f".format(java.util.Locale.ROOT, v)
                val inBar = found.symbols.filter { val cx = it.x + it.width / 2; cx >= m.box.left && cx < m.box.right && abs(it.y - s.y(4, cx.toInt())) < sp * 7 }
                val used = HashSet<com.inksheets.core.omr.Printed.Symbol>()
                val objs = ArrayList<String>()
                var lost = false
                for (e in fix.events) {
                    val x = e.x * k
                    when (e) {
                        is Note -> for (step in e.steps) {
                            val h = inBar.filter { it.kind in HEADS && it !in used && abs(it.x - x) < sp * 0.8f && abs(it.y - s.y(step, (it.x + it.width / 2).toInt())) < sp * 0.6f }
                                .minByOrNull { abs(it.x - x) } ?: run { lost = true; null } ?: continue
                            used += h
                            val c = when (e.duration.base) { 1 -> 2; 2 -> 1; else -> 0 }
                            val cx = h.x + h.width / 2
                            objs += "[$c,${f1(strip.column(cx))},${f1(strip.row(cx, h.y))},${e.duration.beams},${e.duration.dots}]"
                        }
                        is Rest -> {
                            val h = inBar.filter { it.kind in RESTS && it !in used && abs(it.x + it.width / 2 - x) < sp * 1.4f }
                                .minByOrNull { abs(it.x + it.width / 2 - x) } ?: run { lost = true; null } ?: continue
                            used += h
                            val c = when (e.duration.base) { 1 -> 3; 2 -> 4; 4 -> 5; 8 -> 6; else -> 7 }
                            val cx = h.x + h.width / 2
                            objs += "[$c,${f1(strip.column(cx))},${f1(strip.row(cx, h.y))}]"
                        }
                    }
                }
                // Where one is not seen on the page, where it is is not known: not taught.
                if (lost) { no("a note or rest not seen on the page"); continue }
                // Dots and accidentals as seen where as many are as the pick has; else not taught either way.
                val dotsWanted = fix.events.sumOf { e -> if (e.duration.dots == 0) 0 else if (e is Note) e.steps.size else 1 }
                val accWanted = fix.events.sumOf { e -> (e as? Note)?.accidentals?.size ?: 0 }
                val dots = inBar.filter { it.kind == Kind.DOT }
                val accs = inBar.filter { it.kind in ACCIDENTALS }
                for ((group, wanted) in listOf(dots to dotsWanted, accs to accWanted)) for (sym in group) {
                    val cx = sym.x + sym.width / 2
                    objs += if (group.size == wanted) "[${CLS.getValue(sym.kind)},${f1(strip.column(cx))},${f1(strip.row(cx, sym.y))}]"
                        else "[-1,${f1(strip.column(cx))},${f1(strip.row(cx, sym.y))}]"
                }
                // Heads and rests the pick says are not there: perhaps a cue - not taught either way.
                for (sym in inBar) if (sym !in used && (sym.kind in HEADS || sym.kind in RESTS)) { val cx = sym.x + sym.width / 2; objs += "[-1,${f1(strip.column(cx))},${f1(strip.row(cx, sym.y))}]" }
                // The bar taught at half weight; all else on the strip not at all.
                val a = strip.column(m.box.left.toFloat()); val b = strip.column(m.box.right.toFloat())
                objs += "[-3,0,${f1(a)}]"; objs += "[-3,${f1(b)},${strip.width}]"
                objs += "[-4,${f1(a)},${f1(b)},0.5]"
                val name = "fix${Integer.toHexString((fix.file + fix.page + fix.box).hashCode())}-p${fix.page}-s${fix.staff}.png"
                save(strip, File(dir, name))
                labels += "{\"file\":\"$name\",\"song\":\"${bench.song(file)}\",\"dev\":false,\"held\":false,\"fix\":true,\"w\":${strip.width},\"objs\":[${objs.joinToString(",")}]}"
                no("taught")
            }
        }
        File(out, "labels.jsonl").writeText(labels.joinToString("\n"))
        println("FIXES: ${all.size} bars picked; " + why.entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" })
    }

    private companion object {
        val HEADS = setOf(Kind.HEAD_BLACK, Kind.HEAD_HALF, Kind.HEAD_WHOLE)
        val RESTS = setOf(Kind.REST_1, Kind.REST_2, Kind.REST_4, Kind.REST_8, Kind.REST_16)
        val ACCIDENTALS = setOf(Kind.SHARP, Kind.FLAT, Kind.NATURAL)
        val CLS = mapOf(Kind.DOT to 8, Kind.SHARP to 9, Kind.FLAT to 10, Kind.NATURAL to 11)
    }
}
