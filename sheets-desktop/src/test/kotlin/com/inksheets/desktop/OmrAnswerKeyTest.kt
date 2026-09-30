package com.inksheets.desktop

import com.inksheets.core.omr.Ink
import com.inksheets.core.omr.Note
import com.inksheets.core.omr.Recognizer
import com.inksheets.core.omr.Rest
import com.inksheets.desktop.AnswerKey.Kind
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The music reader marked against answer keys taken from the library's own PDFs ([AnswerKey]):
 * of the noteheads printed, how many it found at the right pitch, and of the kind printed
 * (filled, half, whole); of those it found, how many are printed; the same for rests. Real
 * accuracy on real parts, not whether the bars happen to add up. Pictures of what it missed and
 * what it made up go to -Dinksheets.shots. -Dinksheets.omr=key, -Dinksheets.omr.songs=how many.
 */
class OmrAnswerKeyTest {
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")

    /** A head read: where, its pitch's step and staff, what kind. */
    private data class Read(val x: Float, val y: Float, val kind: Kind, val staff: Int, val bar: Int = -1, val dots: Int = 0, val confidence: Float = 1f)

    class Tally {
        var keyHeads = 0; var found = 0; var kindRight = 0; var readHeads = 0; var readRight = 0
        val confusion = HashMap<String, Int>()
        var valued = 0; var valueRight = 0
        val valueMisses = ArrayList<Triple<Pair<Float, Float>, Int, Int>>()
        val barCauses = HashMap<String, Int>()
        val confidence = HashMap<String, Int>()
        var sureBarsChecked = 0; var sureWrong = 0
        val sureWrongCauses = HashMap<String, Int>()
        var bars = 0; var sureBars = 0
        var keyRests = 0; var restsFound = 0; var restKindRight = 0; var readRests = 0; var readRestsRight = 0
        fun add(o: Tally) {
            o.confusion.forEach { (k, v) -> confusion[k] = (confusion[k] ?: 0) + v }
            o.barCauses.forEach { (k, v) -> barCauses.merge(k, v, Int::plus) }
            o.confidence.forEach { (k, v) -> confidence.merge(k, v, Int::plus) }
            sureBarsChecked += o.sureBarsChecked; sureWrong += o.sureWrong
            o.sureWrongCauses.forEach { (k, v) -> sureWrongCauses.merge(k, v, Int::plus) }
            valued += o.valued; valueRight += o.valueRight
            bars += o.bars; sureBars += o.sureBars
            keyHeads += o.keyHeads; found += o.found; kindRight += o.kindRight; readHeads += o.readHeads; readRight += o.readRight
            keyRests += o.keyRests; restsFound += o.restsFound; restKindRight += o.restKindRight; readRests += o.readRests; readRestsRight += o.readRestsRight
        }
        private fun pct(a: Int, b: Int) = if (b == 0) "-" else "%.1f%%".format(100.0 * a / b)
        override fun toString() = "heads found ${pct(found, keyHeads)} ($found/$keyHeads), kind right ${pct(kindRight, found)}, " +
            "real ${pct(readRight, readHeads)} ($readRight/$readHeads); rests found ${pct(restsFound, keyRests)} ($restsFound/$keyRests), " +
            "kind right ${pct(restKindRight, restsFound)}, real ${pct(readRestsRight, readRests)} ($readRestsRight/$readRests); " +
            "note values right ${pct(valueRight, valued)} ($valueRight/$valued); bars adding up ${pct(sureBars, bars)} ($sureBars/$bars)"
    }

    private val heads = setOf(Kind.HEAD_BLACK, Kind.HEAD_HALF, Kind.HEAD_WHOLE)
    private val rests = setOf(Kind.REST_1, Kind.REST_2, Kind.REST_4, Kind.REST_8, Kind.REST_16)

    /** Notes whose value was read wrong, drawn. */
    private val valueList = ArrayList<BufferedImage>()
    private val valuesFrom = HashMap<String, Int>()
    private val cleanValueShots = ArrayList<BufferedImage>()
    private var dotShown = 0
    private var nothingShown = 0

    fun keyed(want: Int): List<File> {
        // -Dinksheets.omr.only=text: just the parts whose names have it in them.
        val only = System.getProperty("inksheets.omr.only")
        val all = music.walkTopDown().filter { it.isFile && it.extension.equals("pdf", true) && !it.name.contains("score", true) && (only == null || it.name.contains(only, true)) }.toList()
        return all.sortedBy { java.util.zip.CRC32().apply { update(it.relativeTo(music).path.lowercase().replace('\\', '/').toByteArray()) }.value }
            .asSequence().filter { f -> runCatching { AnswerKey.read(f, 0, 72f) != null }.getOrDefault(false) }.take(want).toList()
    }

    /** One page marked; misses and inventions drawn into [missed] and [invented]. */
    fun mark(ink: Ink, key: List<AnswerKey.Symbol>, reading: Recognizer.PageReading, label: String,
             missed: MutableList<BufferedImage>? = null, invented: MutableList<BufferedImage>? = null, missedRests: MutableList<BufferedImage>? = null, inventedRests: MutableList<BufferedImage>? = null,
             vectors: VectorKey.Page? = null, valueShots: MutableList<BufferedImage>? = valueList): Tally {
        val t = Tally()
        val sp = reading.space
        // Printed heads of full size only: cue and grace notes are smaller, and left to another day.
        val keyHeads = key.filter { it.kind in heads }
        val normal = keyHeads.map { it.width }.sorted().let { it[it.size / 2] }
        val printed = keyHeads.filter { it.width > normal * 0.8f && reading.staves.any { s -> it.x >= s.left - sp && it.x <= s.right && it.y > s.top - sp * 6 && it.y < s.bottom + sp * 6 } }
        val read = ArrayList<Read>()
        val readValues = ArrayList<Int?>()
        for ((mi, m) in reading.measures.withIndex()) {
            val s = reading.staves[m.staff]
            for (e in m.events) if (e is Note) for (st in e.steps) {
                val kind = when (e.duration.base) { 1 -> Kind.HEAD_WHOLE; 2 -> Kind.HEAD_HALF; else -> Kind.HEAD_BLACK }
                read += Read(e.x, s.y(st, e.x.toInt()), kind, m.staff, mi, e.duration.dots, e.confidence)
                readValues += e.duration.base.takeIf { !e.duration.tuplet }
            }
        }
        val taken = BooleanArray(read.size)
        // What is wrong in each bar that does not add up, by its index.
        val causes = HashMap<Int, MutableSet<String>>()
        fun barAt(x: Float, y: Float) = reading.measures.indexOfFirst { m -> x >= m.box.left - sp * 0.5f && x <= m.box.right && y > m.box.top - sp * 6 && y < m.box.bottom + sp * 6 }
        val keyDots = key.filter { it.kind == Kind.DOT }
        t.keyHeads = printed.size; t.readHeads = read.size
        t.bars = reading.measures.size; t.sureBars = reading.measures.count { it.sure }
        reading.measures.filter { !it.sure }.forEach { m -> m.doubts.forEach { d -> t.confusion.merge("doubt: " + d.replace(Regex("[0-9.]+"), "#"), 1, Int::plus) } }
        for (h in printed) {
            // The nearest head read at the same pitch - a quarter of a space either way - and about the same place.
            val i = read.indices.filter { !taken[it] && abs(read[it].y - h.y) <= sp * 0.26f && abs(read[it].x - h.x) <= sp * 1.3f }
                .minByOrNull { abs(read[it].x - h.x) }
            if (i == null) { barAt(h.x, h.y).takeIf { it >= 0 }?.let { causes.getOrPut(it) { HashSet() } += "missed head" }; missed?.let { if (it.size < 40) it += crop(ink, h.x, h.y, sp, "$label missed ${h.kind}") }; continue }
            taken[i] = true
            // A dot printed beside the head (right of it, its space or the one above), against the dots read.
            val dotted = keyDots.any { d -> d.x > h.x + h.width * 0.8f && d.x < h.x + h.width + sp * 1.6f && d.y > h.y - sp * 0.9f && d.y < h.y + sp * 0.4f }
            if (dotted != (read[i].dots > 0)) causes.getOrPut(read[i].bar) { HashSet() } += if (dotted) "dot missed" else "dot invented"
            if (!dotted && read[i].dots > 0 && System.getProperty("inksheets.omr.why") != null && dotShown++ < 12) {
                val near = keyDots.minByOrNull { d -> abs(d.x - h.x) + abs(d.y - h.y) }
                println("  dot invented $label x=${h.x.toInt()} y=${h.y.toInt()} w=${h.width.toInt()}: nearest key dot at ${near?.let { "${"%.1f".format((it.x - h.x) / sp)},${"%.1f".format((it.y - h.y) / sp)} sp" }}")
            }
            if (read[i].kind != h.kind) causes.getOrPut(read[i].bar) { HashSet() } += "head kind"
            t.found++
            if (read[i].kind == h.kind) t.kindRight++
            // Its value, where the printed stems and beams say: against the value read.
            if (vectors != null && h.kind == Kind.HEAD_BLACK) {
                val printedValue = VectorKey.valueOf(vectors, h.x, h.y, h.width, sp, key.filter { it.kind == Kind.FLAG_8 || it.kind == Kind.FLAG_16 || it.kind == Kind.FLAG_32 })
                val readValue = readValues[i]
                // A page whose flags of the kind read the key cannot see (drawn as shapes, or in a font
                // it does not know) says every such note is a quarter: those quarters are not counted.
                val flagsSeen = readValue == null || key.any { it.kind == when (readValue) { 8 -> Kind.FLAG_8; 16 -> Kind.FLAG_16; 32 -> Kind.FLAG_32; else -> it.kind } }
                if (printedValue != null && readValue != null && (printedValue != 4 || flagsSeen)) {
                    t.valued++; if (printedValue == readValue) t.valueRight++
                    else { causes.getOrPut(read[i].bar) { HashSet() } += "value"; t.confusion.merge("value $printedValue->$readValue", 1, Int::plus); t.valueMisses += Triple(read[i].x to read[i].y, printedValue, readValue); valueShots?.let { if (it.size < 40 && valuesFrom.merge(label, 1, Int::plus)!! <= 3) it += crop(ink, h.x, h.y, sp, "${label.take(10)} $printedValue as $readValue", tall = true) } }
                }
            }
            val c = "${h.kind}->${read[i].kind}"; t.confusion[c] = (t.confusion[c] ?: 0) + 1
        }
        t.readRight = taken.count { it }
        // Each head's match against whether it is real, by whether its bar adds up without doubts of length.
        for (i in read.indices) {
            val m = reading.measures.getOrNull(read[i].bar) ?: continue
            val adds = m.doubts.none { it.contains("beats found") || it.contains("triplets") }
            val band = when { read[i].confidence >= 0.75f -> "0.75+"; read[i].confidence >= 0.7f -> "0.70"; read[i].confidence >= 0.65f -> "0.65"; read[i].confidence >= 0.6f -> "0.60"; else -> "low" }
            t.confidence.merge("${if (adds) "adds" else "long/short"} $band ${if (taken[i]) "real" else "invented"}", 1, Int::plus)
        }
        read.indices.filter { !taken[it] }.forEach { i -> t.confusion.merge("invented ${read[i].kind}", 1, Int::plus); causes.getOrPut(read[i].bar) { HashSet() } += "invented head" }
        read.indices.filter { !taken[it] && read[it].kind != Kind.HEAD_BLACK }.forEach { i -> invented?.let { if (it.size < 40) it += crop(ink, read[i].x, read[i].y, sp, "$label invented ${read[i].kind}") } }
        // Rests: the same, a little looser (a rest's origin is not its middle).
        val keyRests = key.filter { it.kind in rests && reading.staves.any { s -> it.y > s.top - sp * 2 && it.y < s.bottom + sp * 2 && it.x >= s.left } }
        val readRests = reading.measures.flatMap { m -> m.events.filterIsInstance<Rest>().filter { m.bars <= 1 }.map { r -> Triple(r, m.staff, m) } }
        val restTaken = BooleanArray(readRests.size)
        t.keyRests = keyRests.size; t.readRests = readRests.size
        for (r in keyRests) {
            val i = readRests.indices.filter { !restTaken[it] && abs(readRests[it].first.x - r.x) <= sp * 1.5f && reading.staves[readRests[it].second].let { s -> r.y > s.top - sp * 3 && r.y < s.bottom + sp * 3 } }
                .minByOrNull { abs(readRests[it].first.x - r.x) } ?: run { barAt(r.x, r.y).takeIf { it >= 0 }?.let { causes.getOrPut(it) { HashSet() } += "missed rest" }; t.confusion.merge("missed ${r.kind}", 1, Int::plus); missedRests?.let { if (it.size < 40) it += crop(ink, r.x, r.y, sp, "$label missed ${r.kind}") }; null } ?: continue
            restTaken[i] = true
            t.restsFound++
            val want = when (r.kind) { Kind.REST_1 -> 1; Kind.REST_2 -> 2; Kind.REST_4 -> 4; Kind.REST_8 -> 8; else -> 16 }
            if (readRests[i].first.duration.base == want) t.restKindRight++
            else causes.getOrPut(reading.measures.indexOf(readRests[i].third)) { HashSet() } += "rest kind"

            val c = "${r.kind}->r${readRests[i].first.duration.base}"; t.confusion[c] = (t.confusion[c] ?: 0) + 1
        }
        t.readRestsRight = restTaken.count { it }
        readRests.indices.filter { !restTaken[it] }.forEach { i -> causes.getOrPut(reading.measures.indexOf(readRests[i].third)) { HashSet() } += "invented rest" }
        // Bars read as sure that the key shows something wrong in: what "sure" is worth.
        reading.measures.withIndex().filter { it.value.sure && it.value.bars <= 1 }.forEach { (mi, _) ->
            t.sureBarsChecked++
            causes[mi]?.let { c -> t.sureWrong++; t.sureWrongCauses.merge(c.sorted().joinToString("+"), 1, Int::plus) }
        }
        // Each bar that does not add up, by what went wrong in it (as far as the key shows).
        reading.measures.withIndex().filter { !it.value.sure && it.value.bars <= 1 }.forEach { (mi, _) ->
            val m0 = reading.measures[mi]
            val c = causes[mi]?.sorted()?.joinToString("+") ?: ("nothing the key shows: " + m0.doubts.joinToString("; ") { it.replace(Regex("[0-9.]+"), "#") } + (if (m0.time.beats == 1 && m0.time.beatType == 1) " (time 1/1)" else ""))
            if (c.startsWith("nothing") && System.getProperty("inksheets.omr.why") != null && nothingShown++ < 40) {
                val m = reading.measures[mi]
                println("  bar $label m${m.number} staff ${m.staff} x ${m.box.left}..${m.box.right} ${m.time}: ${m.doubts} | " + m.events.joinToString(" ") { e -> when (e) { is Note -> "n" + e.duration.base + ".".repeat(e.duration.dots) + (if (e.duration.tuplet) "t" else ""); is Rest -> "r" + e.duration.base + ".".repeat(e.duration.dots) } })
            }
            t.barCauses.merge(c, 1, Int::plus)
        }

        return t
    }

    /** [ink] as a photocopy scanned: turned [degrees], its ink spread, blurred, lit unevenly, with noise. */
    private fun scanned(ink: Ink, degrees: Double, seed: Long): Ink {
        val r = java.util.Random(seed)
        val a = Math.toRadians(degrees)
        val cos = Math.cos(a); val sin = Math.sin(a)
        val cx = ink.width / 2.0; val cy = ink.height / 2.0
        val grey = IntArray(ink.width * ink.height)
        for (y in 0 until ink.height) for (x in 0 until ink.width) {
            var dark = 0.0
            for (dy in -1..1) for (dx in -1..1) {
                val sx = cos * (x + dx * 0.7 - cx) + sin * (y + dy * 0.7 - cy) + cx
                val sy = -sin * (x + dx * 0.7 - cx) + cos * (y + dy * 0.7 - cy) + cy
                if (ink[sx.toInt(), sy.toInt()]) dark += 1.0 / 7
            }
            val paper = 220 + (x * 25 / ink.width) - (y * 10 / ink.height)
            grey[y * ink.width + x] = (paper - dark.coerceAtMost(1.0) * 210 + r.nextGaussian() * 14).toInt().coerceIn(0, 255)
        }
        return Ink.fromGrey(ink.width, ink.height, grey)
    }

    private fun crop(ink: Ink, x: Float, y: Float, sp: Float, caption: String, tall: Boolean = false): BufferedImage {
        val w = (sp * (if (tall) 10 else 8)).toInt(); val h = (sp * (if (tall) 13 else 7)).toInt()
        val x0 = (x - sp * (if (tall) 4 else 3)).toInt(); val y0 = (y - sp * (if (tall) 6.5f else 3.5f)).toInt()
        val img = BufferedImage(w, h + 16, BufferedImage.TYPE_INT_RGB)
        for (yy in 0 until h) for (xx in 0 until w) img.setRGB(xx, yy, if (ink[x0 + xx, y0 + yy]) 0 else 0xFFFFFF)
        val g = img.createGraphics()
        g.color = Color.WHITE; g.fillRect(0, h, w, 16)
        g.color = Color.RED; g.drawOval((x - x0 - 2).toInt(), (y - y0 - sp * 0.7f).toInt(), (sp * 1.6f).toInt(), (sp * 1.4f).toInt())
        g.font = g.font.deriveFont(10f); g.drawString(caption.takeLast(48), 2, h + 12)
        g.dispose()
        return img
    }

    fun sheet(images: List<BufferedImage>, out: File) {
        if (images.isEmpty()) return
        val cols = 5
        val w = images.maxOf { it.width }; val h = images.maxOf { it.height }
        val rows = (images.size + cols - 1) / cols
        val img = BufferedImage(cols * (w + 4), rows * (h + 4), BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(210, 210, 210); g.fillRect(0, 0, img.width, img.height)
        images.forEachIndexed { i, im -> g.drawImage(im, (i % cols) * (w + 4), (i / cols) * (h + 4), null) }
        g.dispose()
        out.parentFile.mkdirs()
        ImageIO.write(img, "png", out)
    }

    @Test
    fun `reads against the answer keys`() {
        assumeTrue(System.getProperty("inksheets.omr") == "key")
        val want = (System.getProperty("inksheets.omr.songs") ?: "40").toInt()
        val shots = System.getProperty("inksheets.shots")
        val all = Tally()
        val missed = ArrayList<BufferedImage>(); val invented = ArrayList<BufferedImage>()
        val offsets = ArrayList<Float>()
        val why = HashMap<String, Int>()
        val never = HashMap<String, Int>()
        var explained = 0
        val pitchShots = ArrayList<BufferedImage>()
        val neverShots = ArrayList<BufferedImage>()
        val restShots = ArrayList<BufferedImage>()
        val inventedRestShots = ArrayList<BufferedImage>()
        for (f in keyed(want)) {
            val (printed, dpi) = OmrRealPagesTest().renderAt(f, 0) ?: continue
            val keyRaw = AnswerKey.read(f, 0, dpi) ?: continue
            // As a scan, if asked: the page turned a little, its ink spread and blurred, the light uneven,
            // noise over it - and the key turned with it.
            val turn = if (System.getProperty("inksheets.omr.scan") != null) 0.6 else 0.0
            val ink = if (turn == 0.0) printed else scanned(printed, turn, f.name.hashCode().toLong())
            val a = Math.toRadians(-turn); val cx = printed.width / 2.0; val cy = printed.height / 2.0
            fun tx(x: Float, y: Float) = (Math.cos(a) * (x - cx) + Math.sin(a) * (y - cy) + cx).toFloat()
            fun ty(x: Float, y: Float) = (-Math.sin(a) * (x - cx) + Math.cos(a) * (y - cy) + cy).toFloat()
            val raw = if (turn == 0.0) keyRaw else keyRaw.map { s -> s.copy(x = tx(s.x, s.y), y = ty(s.x, s.y)) }
            // The printed stems and beams turned the same way.
            val vectors = VectorKey.read(f, 0, dpi)?.let { v ->
                if (turn == 0.0) v else VectorKey.Page(
                    v.stems.map { st -> VectorKey.Stem((tx(st.x, st.y0) + tx(st.x, st.y1)) / 2, ty(st.x, st.y0), ty(st.x, st.y1)) },
                    v.beams.map { b -> VectorKey.Beam(tx(b.x0, b.y0), ty(b.x0, b.y0), tx(b.x1, b.y1), ty(b.x1, b.y1), b.thick) })
            }
            val recognizer = Recognizer()
            val reading = recognizer.read(ink)
            if (reading.staves.isEmpty()) continue
            // Some fonts' heads sit a little off their origin: the page's key moved by however far
            // its heads on the staff sit, most of them, from the nearest line or space.
            val fracs = raw.filter { it.kind in heads }.mapNotNull { h ->
                val s = reading.staves.firstOrNull { h.y > it.top - it.space && h.y < it.bottom + it.space && h.x >= it.left && h.x <= it.right } ?: return@mapNotNull null
                val exact = (h.y - s.lineY(0, h.x.toInt())) / ((s.lineY(4, h.x.toInt()) - s.lineY(0, h.x.toInt())) / 8f)
                exact - Math.round(exact)
            }.sorted()
            val shift = fracs.getOrNull(fracs.size / 2)?.takeIf { abs(it) > 0.1f }?.let { it * reading.space / 2 } ?: 0f
            if (shift != 0f) println("  key moved ${"%.1f".format(shift)} px")
            val key = raw.map { it.copy(y = it.y - shift) }
            // Each printed eighth rest missed: how well the rest's shape fitted near it.
            if (System.getProperty("inksheets.omr.why") != null) {
                val (t0, _) = recognizer.metrics(ink)!!
                val clean = recognizer.withoutLines(ink, reading.staves, t0)
                val readRests = reading.measures.flatMap { m -> m.events.filterIsInstance<Rest>() }
                key.filter { it.kind == Kind.REST_8 }.filter { r -> readRests.none { abs(it.x - r.x) <= reading.space * 1.5f && abs(reading.staves.minBy { s -> abs((s.top + s.bottom) / 2f - r.y) }.let { (it.top + it.bottom) / 2f } - r.y) < reading.space * 4 } }.take(6).forEach { r ->
                    val s = reading.staves.minBy { abs((it.top + it.bottom) / 2f - r.y) }
                    println("  rest missed ${f.nameWithoutExtension.take(20)} x=${r.x.toInt()}: " + recognizer.explainRest(clean, s, r.x.roundToInt(), r.y.roundToInt()))
                }
            }            // Each printed head missed: never found as a head at all, or found and lost after.
            if (System.getProperty("inksheets.omr.why") != null) {
                val (t0, _) = recognizer.metrics(ink)!!
                val clean = recognizer.withoutLines(ink, reading.staves, t0)
                val t = mark(ink, key, reading, "", valueShots = null)
                val sp = reading.space
                val readHeads = reading.measures.flatMap { m -> m.events.filterIsInstance<Note>().flatMap { e -> e.steps.map { st -> e.x to reading.staves[m.staff].y(st, e.x.toInt()) } } }
                for (h in key.filter { it.kind in heads }) {
                    if (readHeads.any { (x, y) -> abs(y - h.y) <= sp * 0.26f && abs(x - h.x) <= sp * 1.3f }) continue
                    val si = reading.staves.indices.minByOrNull { abs((reading.staves[it].top + reading.staves[it].bottom) / 2 - h.y) } ?: continue
                    val s = reading.staves[si]
                    val cands = recognizer.heads(clean, s, (h.x - sp * 2).toInt(), (h.x + sp * 3).toInt(), ink)
                    val near = cands.filter { abs(it.y - h.y) <= sp * 0.3f && abs(it.x - h.x) <= sp * 1.3f }
                    val best = (-3..3).flatMap { dx -> (-2..2).map { dy -> recognizer.score(clean, "noteheadBlack", sp, (h.x + dx).toInt(), (h.y + dy).toInt()) } }.maxOrNull()
                    val lost = reading.dropped.firstOrNull { (d, _) -> abs(d.y - h.y) <= sp * 0.3f && abs(d.x - h.x) <= sp * 1.3f }?.second
                    val wrongPitch = readHeads.filter { (x, _) -> abs(x - h.x) <= sp * 1.3f }.minByOrNull { (_, y) -> abs(y - h.y) }?.let { (_, y) -> Math.round((y - h.y) / (sp / 2)) }
                    val stage = if (lost != null) "lost: $lost" else if (wrongPitch != null && abs(wrongPitch) <= 2) "read at the wrong pitch ($wrongPitch steps)" else if (near.isNotEmpty()) "found, lost later (${near.first().kind} ${"%.2f".format(near.first().score)})" else "never found (black score ${"%.2f".format(best)})"
                    val step = Math.round((h.y - s.lineY(0, h.x.toInt())) / (s.space / 2))
                    why.merge(stage.substringBefore(" ("), 1, Int::plus)
                    if (stage.startsWith("never") && h.kind != Kind.HEAD_BLACK && explained++ < 25) println("    hollow ${h.kind}: " + recognizer.explainHollow(clean, s, h.x.roundToInt(), h.y.roundToInt()))
                    if (stage.startsWith("never")) never.merge("${h.kind} " + when { step < -1 -> "above"; step > 9 -> "below"; else -> "on" }, 1, Int::plus)
                    if (stage.startsWith("never") && neverShots.size < 60) neverShots += crop(ink, h.x, h.y, sp, "${f.nameWithoutExtension.take(14)} ${h.kind} $stage")
                    if (stage.startsWith("read at the wrong") && pitchShots.size < 40) {
                        val img = crop(ink, h.x, h.y, sp, "${f.nameWithoutExtension.take(16)} $stage")
                        val g = img.createGraphics()
                        g.color = Color.BLUE
                        readHeads.filter { (x, _) -> abs(x - h.x) <= sp * 1.3f }.forEach { (x, y) ->
                            g.drawRect((x - (h.x - sp * 3)).toInt(), (y - (h.y - sp * 3.5f) - sp * 0.5f).toInt(), (sp * 1.3f).toInt(), sp.toInt())
                        }
                        g.dispose()
                        pitchShots += img
                    }
                    val exact = (h.y - s.lineY(0, h.x.toInt())) / ((s.lineY(4, h.x.toInt()) - s.lineY(0, h.x.toInt())) / 8f)
                    val readY = readHeads.filter { (x, _) -> abs(x - h.x) <= sp * 1.3f }.map { (_, y) -> "%.2f".format((y - s.lineY(0, h.x.toInt())) / ((s.lineY(4, h.x.toInt()) - s.lineY(0, h.x.toInt())) / 8f)) }
                    val traceOff = (0..4).map { l -> val y0 = s.lineY(l, h.x.toInt()).roundToInt(); (-6..6).filter { dy -> (h.x.toInt() - sp.toInt() * 2..h.x.toInt() - sp.toInt() * 2 + 6).all { ink[it, y0 + dy] } }.minByOrNull { abs(it) } }
                    println("  miss ${f.nameWithoutExtension.take(20)} trace$traceOff ${h.kind} x=${h.x.toInt()} step=$step (exactly ${"%.2f".format(exact)}; read at $readY) staff=$si: $stage")
                }
            }
            // How far the key's heads sit from the nearest staff position: whether its origin is a head's middle.
            val s0 = reading.staves.first()
            key.filter { it.kind == Kind.HEAD_BLACK && it.y > s0.top - s0.space && it.y < s0.bottom + s0.space }.take(20).forEach { h ->
                val step = ((h.y - s0.lineY(0, h.x.toInt())) / (s0.space / 2)).let { Math.round(it) }
                offsets += h.y - s0.y(step, h.x.toInt())
            }
            val t = mark(ink, key, reading, f.nameWithoutExtension.take(24), if (shots != null) missed else null, if (shots != null) invented else null, if (shots != null) restShots else null, if (shots != null) inventedRestShots else null,
                vectors)
            all.add(t)
            if (System.getProperty("inksheets.omr.why") != null) {
                val (t0, _) = recognizer.metrics(ink)!!
                val clean = recognizer.withoutLines(ink, reading.staves, t0)
                for ((h, printedValue, readValue) in t.valueMisses.take(6)) {
                    val (hx, hy) = h
                    val s = reading.staves.minBy { abs((it.top + it.bottom) / 2f - hy) }
                    val why = recognizer.explainValue(clean, s, hx.roundToInt(), hy.roundToInt())
                    println("  value ${f.nameWithoutExtension.take(20)} x=${hx.toInt()} y=${hy.toInt()} $printedValue as $readValue: $why")
                    if (cleanValueShots.size < 30) cleanValueShots += crop(clean, hx, hy, reading.space, "${f.nameWithoutExtension.take(8)} $printedValue as $readValue ${why.take(12)}", tall = true)
                }
            }
            val mism = reading.measures.flatMap { it.doubts }.filter { it.contains("beats found") }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3)
            println("${f.relativeTo(music).path}: $t | ${reading.measures.size} bars, time ${reading.measures.firstOrNull()?.time} | " + mism.joinToString { "${it.key} x${it.value}" })
        }
        println("OFFSETS: median ${offsets.sorted().getOrNull(offsets.size / 2)}")
        println("WHY MISSED: $why")
        println("NEVER FOUND: $never")
        println("ALL: $all")
        println("SURE BUT WRONG: ${all.sureWrong} of ${all.sureBarsChecked} sure bars: " + all.sureWrongCauses.entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" })
        println("HEAD CONFIDENCE: " + all.confidence.entries.sortedBy { it.key }.joinToString { "${it.key} ${it.value}" })
        println("BAR CAUSES: " + all.barCauses.entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" })
        println("CONFUSION: " + all.confusion.entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" })
        if (shots != null) { sheet(missed, File(shots, "key-missed.png")); sheet(invented, File(shots, "key-invented.png")); sheet(pitchShots, File(shots, "key-pitch.png")); sheet(neverShots, File(shots, "key-never.png")); sheet(restShots, File(shots, "key-rests.png")); sheet(inventedRestShots, File(shots, "key-rests-invented.png")); sheet(valueList, File(shots, "key-values.png")); sheet(cleanValueShots, File(shots, "key-values-clean.png")) }
    }
}
