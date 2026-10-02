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
import java.util.concurrent.Executors
import java.util.zip.CRC32
import kotlin.math.abs

/**
 * The standard the music reader is held to. Parts whose PDFs write their notes as music-font
 * characters are read, and every bar is marked against what the PDF itself says is printed:
 *
 * - a bar is CORRECT when every printed note is read at its pitch, with its value, dots and
 *   accidental, every printed rest with its value, and nothing is read that is not printed;
 * - a bar is VERIFIABLE when the PDF says all of that for certain (a note whose stem or flag the
 *   PDF does not show plainly, or a tuplet, leaves its bar unverifiable - not counted either way);
 * - TRUST is how many of the bars the reader calls sure are correct: it must stay at 99% or more.
 *
 * The goal (user, 2026-09-30): a whole part read with at most two bars left to ask about - each
 * offered as three readings to pick from - and every other bar right. So per part: bars FLAGGED
 * (not sure), and how many parts need two or fewer.
 *
 * Songs are split once, by name, into a DEV set (tuned on) and a TEST set (never tuned on, only
 * reported). Each run is written to sheets-desktop/bench/<mode>-<set>-last.tsv and compared, part by
 * part, with <mode>-<set>.tsv (the baseline, written with -Dinksheets.bench.save=1).
 *
 * -Dinksheets.bench=dev|test, .n=parts (20 dev / 60 test), .scan=1 (as scanned), .pages=6 (most),
 * .shots=dir (pictures of wrong bars, to check the checker), .printed=1 (read as the app reads a PDF
 * that states its notes: the reader given them, see [com.inksheets.core.omr.Printed]).
 */
class ReadingBenchmark {
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")
    private val results = File("bench")

    // ---- the corpus ---------------------------------------------------------------------------

    /** Which song a part belongs to: its folder under a band's Music, or its name before " - ". */
    internal fun song(f: File): String {
        val parts = f.relativeTo(music).path.replace('\\', '/').split('/')
        val i = parts.indexOf("Music")
        val name = if (i >= 0 && i + 2 < parts.size) parts[i + 1] else f.nameWithoutExtension.substringBefore(" - ").substringBefore("- ")
        return name.lowercase().replace(Regex("[^a-z0-9]"), "")
    }

    private fun crc(s: String) = CRC32().apply { update(s.toByteArray()) }.value

    /** Dev: a third of the songs, fixed by name. */
    internal fun isDev(f: File) = crc(song(f)) % 3 == 0L

    /** Every part the PDF says the notes of, one copy of each, in a fixed order. Cached by file. */
    internal fun corpus(): List<File> {
        val cache = File("build/bench-corpus.tsv")
        val known = if (cache.isFile) cache.readLines().associate { l -> l.substringBefore('\t') to l.substringAfter('\t') } else emptyMap()
        val lines = ArrayList<String>()
        val out = ArrayList<Pair<File, Long>>()
        val files = music.walkTopDown().filter { it.isFile && it.extension.equals("pdf", true) && !it.name.contains("score", true) && !it.path.contains(".inksheets") }.toList()
        for (f in files) {
            val id = "${f.absolutePath}|${f.length()}|${f.lastModified()}"
            val v = known[id] ?: run {
                val readable = runCatching { AnswerKey.read(f, 0, 72f) != null }.getOrDefault(false)
                val sum = if (readable) CRC32().apply { update(f.readBytes()) }.value else 0L
                "$readable\t$sum"
            }
            lines += "$id\t$v"
            if (v.startsWith("true")) out += f to v.substringAfter('\t').toLong()
        }
        cache.parentFile.mkdirs(); cache.writeText(lines.joinToString("\n"))
        return out.distinctBy { it.second }.map { it.first }.sortedBy { crc(it.relativeTo(music).path.lowercase().replace('\\', '/')) }
    }

    // ---- marking one page ---------------------------------------------------------------------

    private var whyShown = 0
    /** Kinds of change the right readings needed (see -Dinksheets.bench.calibrate). */
    private val calibration = HashMap<String, Int>()
    /** How often another look at a page reads a bar asked about right (see -Dinksheets.bench.looks). */
    private val looks = HashMap<String, Int>()
    private val barShots = java.util.Collections.synchronizedList(ArrayList<BufferedImage>())
    private val barsFrom = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private fun barCrop(ink: Ink, b: com.inksheets.core.omr.Box, sp: Float, caption: String): BufferedImage {
        val x0 = b.left - (sp * 2).toInt(); val y0 = b.top - (sp * 5).toInt()
        val w = b.width + (sp * 4).toInt(); val h = b.height + (sp * 10).toInt()
        val img = BufferedImage(w / 2, h / 2 + 14, BufferedImage.TYPE_INT_RGB)
        for (yy in 0 until h / 2) for (xx in 0 until w / 2) img.setRGB(xx, yy, if (ink[x0 + xx * 2, y0 + yy * 2] || ink[x0 + xx * 2 + 1, y0 + yy * 2]) 0 else 0xFFFFFF)
        val g = img.createGraphics()
        g.color = Color.WHITE; g.fillRect(0, h / 2, w / 2, 14)
        g.color = Color.RED; g.font = g.font.deriveFont(10f); g.drawString(caption, 2, h / 2 + 11)
        g.dispose()
        return img
    }

    class Bar(val sure: Boolean, val verifiable: Boolean, val causes: Set<String>, val doubts: List<String> = emptyList()) {
        val correct get() = verifiable && causes.isEmpty()
    }

    /**
     * [bars] marked; [outside] heads in no bar read; [choices]: of the bars asked about that were read
     * wrong (and can be checked), how many; with the right reading among the three offered; among
     * the next three (asked again); of those asked about though read right, how many; with that
     * reading offered first.
     */
    class PageResult(val bars: List<Bar>, val outside: Int, val shots: List<BufferedImage>, val choices: IntArray = IntArray(5))

    private data class ReadHead(val x: Float, val y: Float, val kind: Kind, val bar: Int, val dots: Int, val value: Int?, val alter: Int?, val chord: Boolean = false)

    private val heads = setOf(Kind.HEAD_BLACK, Kind.HEAD_HALF, Kind.HEAD_WHOLE)
    private val rests = setOf(Kind.REST_1, Kind.REST_2, Kind.REST_4, Kind.REST_8, Kind.REST_16)
    private val flags = setOf(Kind.FLAG_8, Kind.FLAG_16, Kind.FLAG_32)

    /** Where samples for the symbol reader go (SymbolExport), when it wants them. */
    var symbolSink: ((List<String>) -> Unit)? = null

    /**
     * Samples for the symbol reader from one page: every rest and accidental the trained reader
     * finds, labelled with what is printed there (or nothing: a dynamic, a letter, a mark read as
     * one), and every one printed that it does not find. One line each: label, what the reader
     * took it for, how sure, its height on the staff, held, song, then the picture round it (20 by
     * 40, 2.6 spaces by 5, four-bit grey).
     */
    private fun symbolSamples(f: File, w: Int, h: Int, grey: IntArray, staves: List<com.inksheets.core.omr.Recognizer.Staff>, key: List<AnswerKey.Symbol>, sp: Float): List<String> {
        val found = com.inksheets.core.omr.Learned.symbols(grey, w, h, staves, net!!).symbols.filter { it.kind in com.inksheets.core.omr.SymbolReader.KINDS }
        val truth = key.filter { it.kind in symbolTruth }
        val song = song(f)
        val held = song in heldSet
        val out = ArrayList<String>()
        fun staffOf(y: Float, x: Float) = staves.minByOrNull { s -> kotlin.math.abs((s.lineY(0, x.toInt()) + s.lineY(4, x.toInt())) / 2 - y) }
        fun sample(label: String, netKind: String, conf: Float, cx: Float, cy: Float) {
            val s = staffOf(cy, cx) ?: return
            val top = s.lineY(0, cx.toInt()); val half = (s.lineY(4, cx.toInt()) - top) / 8f
            val step = (cy - top) / half
            if (step < -10 || step > 18) return
            val px = com.inksheets.core.omr.SymbolReader.crop(grey, w, h, cx, cy, sp)
            out += "$label	$netKind	${"%.3f".format(java.util.Locale.ROOT, conf)}	${"%.2f".format(java.util.Locale.ROOT, step)}	$held	$song	" + px.joinToString("") { Integer.toHexString((it * 15f).toInt().coerceIn(0, 15)) }
        }
        val matched = HashSet<AnswerKey.Symbol>()
        val pick = java.util.Random((f.name + h).hashCode().toLong())
        for (c in found) {
            val cx = c.x + c.width / 2; val cy = c.y
            val rest = c.kind.name.startsWith("REST")
            val head = c.kind.name.startsWith("HEAD")
            if (head) {
                // Heads by the thousand: every one that is nothing kept, a fifth of the rest.
                val t = truth.filter { k -> k !in matched && k.kind.name.startsWith("HEAD") && kotlin.math.abs(k.x + k.width / 2 - cx) <= sp * 0.7f && kotlin.math.abs(k.y - cy) <= sp * 0.35f }
                    .minByOrNull { k -> kotlin.math.abs(k.x + k.width / 2 - cx) + kotlin.math.abs(k.y - cy) }
                if (t != null) matched += t
                // (The reader's faint ones too - under its floor, what a bar's other readings may put back.)
                if (t == null || pick.nextFloat() < 0.2f) sample(t?.let { com.inksheets.core.omr.SymbolReader.labelOf(it.kind.name) } ?: "other",
                    com.inksheets.core.omr.SymbolReader.labelOf(c.kind.name), c.odds.getOrNull(7) ?: c.confidence, cx, cy)
                continue
            }
            val t = truth.filter { k -> k !in matched && !k.kind.name.startsWith("HEAD") && kotlin.math.abs(k.x + k.width / 2 - cx) <= sp * 0.8f && kotlin.math.abs(k.y - cy) <= sp * (if (rest) 1.6f else 0.6f) &&
                (k.kind.name.startsWith("REST")) == rest }.minByOrNull { k -> kotlin.math.abs(k.x + k.width / 2 - cx) + kotlin.math.abs(k.y - cy) }
            if (t != null) matched += t
            sample(t?.let { com.inksheets.core.omr.SymbolReader.labelOf(it.kind.name) } ?: "other", com.inksheets.core.omr.SymbolReader.labelOf(c.kind.name), c.confidence, cx, cy)
        }
        for (t in truth) if (t !in matched && (!t.kind.name.startsWith("HEAD") || pick.nextFloat() < 0.2f)) sample(com.inksheets.core.omr.SymbolReader.labelOf(t.kind.name), "none", 0f, t.x + t.width / 2, t.y)
        return out
    }

    private val symbolTruth = setOf(Kind.REST_1, Kind.REST_2, Kind.REST_4, Kind.REST_8, Kind.REST_16, Kind.FLAT, Kind.SHARP, Kind.NATURAL, Kind.HEAD_BLACK, Kind.HEAD_HALF, Kind.HEAD_WHOLE)
    private val heldSet: Set<String> by lazy { File("../train/held.txt").takeIf { it.isFile }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet() }

    fun markPage(f: File, page: Int, scan: Boolean, shots: Boolean, usePrinted: Boolean = false): PageResult? {
        val (printed, dpi) = OmrRealPagesTest().renderAt(f, page) ?: return null
        val keyRaw = AnswerKey.read(f, page, dpi) ?: return null
        val warpKind = if (scan) System.getProperty("inksheets.bench.warp") else null
        val turn = if (!scan) 0.0 else if (warpKind == "tilt3") 3.0 else 0.6
        val warp = warpKind?.takeIf { it != "tilt3" }?.let { Warp(it, printed.width, printed.height) }
        val greyScan = if (scan) scannedGrey(printed, turn, (f.name + page).hashCode().toLong(), warp) else null
        val ink = if (greyScan == null) printed else Ink.fromGrey(printed.width, printed.height, greyScan)
        // The trained reader, given the page in grey: the scan's, or the print drawn again.
        val greyPage = if (net == null) null else greyScan ?: org.apache.pdfbox.Loader.loadPDF(f).use { d ->
            val img = org.apache.pdfbox.rendering.PDFRenderer(d).renderImageWithDPI(page, dpi, org.apache.pdfbox.rendering.ImageType.RGB)
            val px = IntArray(img.width * img.height); img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
            com.inksheets.core.omr.Strips.grey(px)
        }
        val a = Math.toRadians(-turn); val cx = printed.width / 2.0; val cy = printed.height / 2.0
        fun rx(x: Float, y: Float) = Math.cos(a) * (x - cx) + Math.sin(a) * (y - cy) + cx
        fun ry(x: Float, y: Float) = -Math.sin(a) * (x - cx) + Math.cos(a) * (y - cy) + cy
        // Where a point of the flat page lands on the scan: turned, then warped.
        fun tx(x: Float, y: Float) = (warp?.forward(rx(x, y), ry(x, y))?.first ?: rx(x, y)).toFloat()
        fun ty(x: Float, y: Float) = (warp?.forward(rx(x, y), ry(x, y))?.second ?: ry(x, y)).toFloat()
        val turned = if (!scan) keyRaw else keyRaw.map { s -> s.copy(x = tx(s.x, s.y), y = ty(s.x, s.y)) }
        val vectors = VectorKey.read(f, page, dpi)?.let { v ->
            if (!scan) v else VectorKey.Page(
                v.stems.map { st -> VectorKey.Stem((tx(st.x, st.y0) + tx(st.x, st.y1)) / 2, ty(st.x, st.y0), ty(st.x, st.y1)) },
                v.beams.map { b -> VectorKey.Beam(tx(b.x0, b.y0), ty(b.x0, b.y0), tx(b.x1, b.y1), ty(b.x1, b.y1), b.thick) },
                v.dots.map { d -> VectorKey.Dot(tx(d.x, d.y), ty(d.x, d.y), d.size) })
        } ?: return null
        // As the app reads a PDF that states its notes: with them (clean pages only - a scan has none).
        // -Dinksheets.bench.nolevel=1: a turned page read as it is, not turned upright first (to measure what that gives).
        val reading = Recognizer().read(ink, printed = if (usePrinted && !scan) PdfPrinted.read(f, page) else null, grey = greyPage, net = net,
            level = System.getProperty("inksheets.bench.nolevel") == null)
        if (reading.staves.isEmpty()) return null
        val sp = reading.space
        // Some fonts' heads sit a little off their origin: the key moved by however far most heads sit off a line or space.
        val fracs = turned.filter { it.kind in heads }.mapNotNull { h ->
            val s = reading.staves.firstOrNull { h.x >= it.left && h.x <= it.right && h.y > it.lineY(0, h.x.toInt()) - it.space && h.y < it.lineY(4, h.x.toInt()) + it.space } ?: return@mapNotNull null
            val exact = (h.y - s.lineY(0, h.x.toInt())) / ((s.lineY(4, h.x.toInt()) - s.lineY(0, h.x.toInt())) / 8f)
            exact - Math.round(exact)
        }.sorted()
        val shift = fracs.getOrNull(fracs.size / 2)?.takeIf { abs(it) > 0.1f }?.let { it * sp / 2 } ?: 0f
        val key = turned.map { if (it.kind in heads || it.kind == Kind.DOT) it.copy(y = it.y - shift) else it }
        symbolSink?.let { sink -> if (greyPage != null && net != null) sink(symbolSamples(f, ink.width, ink.height, greyPage, reading.staves, key, sp)) }

        // What was read.
        val read = ArrayList<ReadHead>()
        val readRests = ArrayList<Triple<Rest, Int, Int>>()   // rest, staff, bar
        for ((mi, m) in reading.measures.withIndex()) {
            if (m.bars > 1) continue
            val s = reading.staves[m.staff]
            for (e in m.events) when (e) {
                is Note -> for (st in e.steps) read += ReadHead(e.x, s.y(st, e.x.toInt()), when (e.duration.base) { 1 -> Kind.HEAD_WHOLE; 2 -> Kind.HEAD_HALF; else -> Kind.HEAD_BLACK },
                    mi, e.duration.dots, e.duration.base.takeIf { !e.duration.tuplet }, e.accidentals[st], e.steps.size > 1)
                is Rest -> readRests += Triple(e, m.staff, mi)
            }
        }
        val causes = HashMap<Int, MutableSet<String>>()
        val unverifiable = HashSet<Int>()
        fun cause(bar: Int, c: String) { if (bar >= 0) causes.getOrPut(bar) { HashSet() } += c }
        fun barAt(x: Float, y: Float) = reading.measures.indexOfFirst { m -> m.bars <= 1 && x >= m.box.left - sp * 0.5f && x <= m.box.right && y > m.box.top - sp * 6 && y < m.box.bottom + sp * 6 }
        var outside = 0

        // The printed heads (full size: cue and grace notes are another matter), their accidentals and dots.
        val keyHeads = key.filter { it.kind in heads }
        // Cue and grace notes - set smaller - take no time in the bar and are left out.
        val normal = keyHeads.map { it.size }.sorted().let { it[it.size / 2] }
        val printedHeads = keyHeads.filter { it.size >= normal * 0.85f && reading.staves.any { s -> it.x >= s.left - sp && it.x <= s.right && it.y > s.lineY(0, it.x.toInt()) - sp * 6 && it.y < s.lineY(4, it.x.toInt()) + sp * 6 } }
        val accidentalOf = HashMap<AnswerKey.Symbol, Int>()
        for (acc in key.filter { it.kind == Kind.FLAT || it.kind == Kind.SHARP || it.kind == Kind.NATURAL }) {
            val h = printedHeads.filter { h -> h.x - acc.x in sp * 0.3f..sp * 3f && abs(h.y - acc.y) <= sp * 0.3f && h !in accidentalOf }.minByOrNull { it.x - acc.x } ?: continue
            accidentalOf[h] = when (acc.kind) { Kind.FLAT -> -1; Kind.SHARP -> 1; else -> 0 }
        }
        // Dots written as characters, or drawn as small round shapes (their middles; a character's origin is its middle too).
        // A dot over or under a head (its middle within the head's width) is staccato, not a note's dot.
        val allHeads = key.filter { it.kind in heads }
        val dots = (key.filter { it.kind == Kind.DOT }.map { it.x to it.y } +
            vectors.dots.filter { it.size in sp * 0.25f..sp * 0.75f }.map { it.x to it.y })
            .filter { (dx, dy) -> allHeads.none { o -> dx in o.x..o.x + o.width && abs(dy - o.y) > sp * 0.3f && abs(dy - o.y) < sp * 3f } }
        val others = key.filter { it.kind == Kind.OTHER }
        val flagGlyphs = key.filter { it.kind in flags }
        /** How many dots are printed beside head [h] (0, 1, 2). */
        fun truthDots(h: AnswerKey.Symbol) = dots.filter { (dx, dy) -> dx > h.x + h.width * 0.8f && dx < h.x + h.width + sp * 2.2f && dy > h.y - sp * 0.9f && dy < h.y + sp * 0.4f }
            .map { it.first }.sorted().fold(ArrayList<Float>()) { acc, x -> if (acc.isEmpty() || x - acc.last() > sp * 0.25f) acc += x; acc }.size.coerceAtMost(2)
        /** A filled head's printed value (4, 8, 16, 32); null where the PDF does not show it plainly. */
        fun truthValue(h: AnswerKey.Symbol): Int? {
            val stem = VectorKey.stemOf(vectors, h.x, h.y, h.width, sp) ?: return null
            val v = VectorKey.valueOf(vectors, h.x, h.y, h.width, sp, flagGlyphs) ?: return null
            // Something the key cannot name at the stem's far end may be a flag it cannot see.
            val end = if (abs(stem.y0 - h.y) > abs(stem.y1 - h.y)) stem.y0 else stem.y1
            if (v == 4 && others.any { o -> abs(o.x - stem.x) < sp * 1.2f && abs(o.y - end) < sp * 2.5f }) return null
            return v
        }
        val taken = BooleanArray(read.size)
        val wrongAt = ArrayList<Pair<AnswerKey.Symbol, String>>()
        for (h in printedHeads) {
            // A chord is read at its leftmost head; a second's heads stand either side of the stem, a head apart.
            val i = read.indices.filter { !taken[it] && abs(read[it].y - h.y) <= sp * 0.26f && abs(read[it].x - h.x) <= sp * (if (read[it].chord) 2.3f else 1.3f) }.minByOrNull { abs(read[it].x - h.x) }
            if (i == null) { val b = barAt(h.x, h.y); if (b < 0) outside++ else { cause(b, "missed head"); wrongAt += h to "missed head" }; continue }
            taken[i] = true
            val r = read[i]
            if (r.kind != h.kind) { cause(r.bar, "head kind"); wrongAt += h to "head kind ${h.kind} read ${r.kind}"
                if (System.getProperty("inksheets.bench.why") != null) synchronized(this) {
                    if (whyShown++ < 12) println("  WHY kind ${f.nameWithoutExtension.take(20)} p$page x=${h.x.toInt()} y=${h.y.toInt()} w=${"%.1f".format(h.width)} ${h.kind} read ${r.kind}; stems near: " +
                        vectors.stems.filter { st -> abs(st.x - h.x) < sp * 3 && st.y1 > h.y - sp * 5 && st.y0 < h.y + sp * 5 }.joinToString { "x=${"%.1f".format(it.x - h.x)} y ${"%.1f".format((it.y0 - h.y) / sp)}..${"%.1f".format((it.y1 - h.y) / sp)} sp" })
                }
            }
            val dotCount = truthDots(h)
            val dotted = dotCount > 0
            if (dotCount != r.dots) { val c = if (dotCount > r.dots) "dot missed" else "dot invented"; cause(r.bar, c); wrongAt += h to c }
            if (!dotted && r.dots > 0 && System.getProperty("inksheets.bench.why") != null) synchronized(this) {
                if (whyShown++ < 20) println("  WHY dot ${f.nameWithoutExtension.take(16)} x=${h.x.toInt()}: near " + key.filter { it.x > h.x && it.x < h.x + h.width + sp * 2.5f && abs(it.y - h.y) < sp * 1.5f && it !== h }
                    .joinToString { "${it.kind}'${it.text}' (${"%.2f".format((it.x - h.x - h.width) / sp)}, ${"%.2f".format((it.y - h.y) / sp)})" })
            }
            val alter = accidentalOf[h]
            if (alter != null && alter != r.alter) { cause(r.bar, "accidental missed/wrong"); wrongAt += h to "accidental $alter read ${r.alter}" }
            if (alter == null && r.alter != null) { cause(r.bar, "accidental invented"); wrongAt += h to "accidental invented" }
            if (h.kind == Kind.HEAD_BLACK) {
                val printedValue = truthValue(h)
                when {
                    printedValue == null || r.value == null -> unverifiable += r.bar
                    printedValue != r.value -> { cause(r.bar, "value"); wrongAt += h to "value $printedValue read ${r.value}" }
                }
            }
        }
        read.indices.filter { !taken[it] }.forEach { cause(read[it].bar, "invented head") }
        // Rests.
        // (A staff's height where the rest is: a page turned has its staves aslant.)
        val printedRests = key.filter { it.kind in rests && reading.staves.any { s -> it.x >= s.left && it.y > s.lineY(0, it.x.toInt()) - sp * 2 && it.y < s.lineY(4, it.x.toInt()) + sp * 2 } }
        val restTaken = BooleanArray(readRests.size)
        for (r in printedRests) {
            val i = readRests.indices.filter { !restTaken[it] && abs(readRests[it].first.x - r.x) <= sp * 1.5f && reading.staves[readRests[it].second].let { s -> r.y > s.lineY(0, r.x.toInt()) - sp * 3 && r.y < s.lineY(4, r.x.toInt()) + sp * 3 } }
                .minByOrNull { abs(readRests[it].first.x - r.x) }
            if (i == null) {
                val b = barAt(r.x, r.y)
                // A whole-bar rest over a multi-bar rest's bar is that bar's, not missed.
                if (b >= 0) { cause(b, "missed rest"); wrongAt += r to "missed rest" }
                continue
            }
            restTaken[i] = true
            val want = when (r.kind) { Kind.REST_1 -> 1; Kind.REST_2 -> 2; Kind.REST_4 -> 4; Kind.REST_8 -> 8; else -> 16 }
            if (readRests[i].first.duration.base != want) { cause(readRests[i].third, "rest kind"); wrongAt += r to "rest ${r.kind} read ${readRests[i].first.duration.base}" }
        }
        readRests.indices.filter { !restTaken[it] }.forEach { cause(readRests[it].third, "invented rest") }

        if (System.getProperty("inksheets.bench.why") != null) synchronized(this) {
            val only = System.getProperty("inksheets.bench.only")
            for ((mi, m) in reading.measures.withIndex()) if (m.bars <= 1 && (!m.sure || mi !in unverifiable && causes[mi] != null) && (only == null || f.name.contains(only, true)) && whyShown++ < 100000)
            {
                val other = key.filter { it.kind == Kind.OTHER && it.x >= m.box.left && it.x < m.box.right && it.y > m.box.top - sp * 4 && it.y < m.box.bottom + sp * 4 }
                    .joinToString(" ") { "'${it.text}'U+%04X@%.1f".format(it.text.codePointAt(0), (it.y - m.box.top) / sp) }
                if (shots && m.sure && barShots.size < 60) barShots += barCrop(ink, m.box, sp, "SURE-WRONG ${f.nameWithoutExtension.take(16)} m${m.number} ${causes[mi]?.joinToString("+")}")
                else if (shots && barShots.size < 40 && barsFrom.merge(f.name, 1, Int::plus)!! <= (if (only != null) 20 else 3)) barShots += barCrop(ink, m.box, sp, "${f.nameWithoutExtension.take(16)} m${m.number} ${m.doubts.firstOrNull()?.take(22)}")
                if (m.sure && only != null) {
                    // Every head candidate in the bar: which the reading was made from.
                    val r = Recognizer(); r.read(ink)
                    val (t0, _) = r.metrics(ink)!!
                    val clean = r.withoutLines(ink, reading.staves, t0)
                    for (h in r.heads(clean, reading.staves[m.staff], m.box.left, m.box.right, ink))
                        println("    head x=${h.x} step=${h.step} ${h.kind} ${"%.2f".format(h.score)} lined=${h.lined} crowded=${h.crowded} weak=${h.weak}")
                }
                println("  ${if (m.sure) "SURE-WRONG" else "FLAGGED"} ${f.nameWithoutExtension.take(22)} p$page m${m.number} ${if (mi in unverifiable) "unverifiable" else causes[mi]?.joinToString("+") ?: "right"} st${m.staff} x${m.box.left}..${m.box.right} ${m.time.beats}/${m.time.beatType}${if (m.showsTime) "*" else ""}: ${m.doubts} | " +
                    m.events.joinToString(" ") { e -> when (e) { is Note -> "n" + e.duration.base + ".".repeat(e.duration.dots) + (if (e.steps.size > 1) "c" + e.steps else ""); is Rest -> "r" + e.duration.base + ".".repeat(e.duration.dots) } } + " | other: " + other)
            }
        }
        val bars = reading.measures.withIndex().filter { it.value.bars <= 1 }.map { (mi, m) -> Bar(m.sure, mi !in unverifiable, causes[mi] ?: emptySet(), m.doubts) }

        /**
         * Whether bar [mi] read as [events] is what is printed: true, false - or null where the PDF
         * does not say plainly enough (a value it does not show, a tuplet).
         */
        val truthHeads = HashMap<Int, List<AnswerKey.Symbol>>(); val truthRests = HashMap<Int, List<AnswerKey.Symbol>>()
        fun barRight(mi: Int, events: List<com.inksheets.core.omr.Event>): Boolean? {
            val m = reading.measures[mi]; val s = reading.staves[m.staff]
            val tHeads = truthHeads.getOrPut(mi) { printedHeads.filter { barAt(it.x, it.y) == mi } }
            val tRests = truthRests.getOrPut(mi) { printedRests.filter { barAt(it.x, it.y) == mi } }
            val cands = events.filterIsInstance<Note>().flatMap { e -> e.steps.map { st -> ReadHead(e.x, s.y(st, e.x.toInt()), when (e.duration.base) { 1 -> Kind.HEAD_WHOLE; 2 -> Kind.HEAD_HALF; else -> Kind.HEAD_BLACK },
                mi, e.duration.dots, e.duration.base.takeIf { !e.duration.tuplet }, e.accidentals[st], e.steps.size > 1) } }
            val used = BooleanArray(cands.size)
            var plain = true
            for (h in tHeads) {
                val i = cands.indices.filter { !used[it] && abs(cands[it].y - h.y) <= sp * 0.26f && abs(cands[it].x - h.x) <= sp * (if (cands[it].chord) 2.3f else 1.3f) }.minByOrNull { abs(cands[it].x - h.x) } ?: return false
                used[i] = true
                val r = cands[i]
                if (r.kind != h.kind || truthDots(h) != r.dots) return false
                val alter = accidentalOf[h]
                if (alter != r.alter) return false
                if (h.kind == Kind.HEAD_BLACK) { val v = truthValue(h); if (v == null || r.value == null) plain = false else if (v != r.value) return false }
            }
            if (used.any { !it }) return false
            val cr = events.filterIsInstance<Rest>()
            val usedR = BooleanArray(cr.size)
            for (r in tRests) {
                val i = cr.indices.filter { !usedR[it] && abs(cr[it].x - r.x) <= sp * 1.5f }.minByOrNull { abs(cr[it].x - r.x) } ?: return false
                usedR[i] = true
                val want = when (r.kind) { Kind.REST_1 -> 1; Kind.REST_2 -> 2; Kind.REST_4 -> 4; Kind.REST_8 -> 8; else -> 16 }
                if (cr[i].duration.base != want) return false
            }
            if (usedR.any { !it }) return false
            return if (plain) true else null
        }
        // -Dinksheets.bench.looks=1: the page read again a few more ways (each staff a little larger,
        // smaller, higher, lower), for how often one of them reads a bar asked about right.
        val looking = System.getProperty("inksheets.bench.looks") != null && net != null && greyPage != null
        // -Dinksheets.bench.features=<file>: each bar checked, what the reader knew of it and whether it was right (for fitting how sure to be).
        System.getProperty("inksheets.bench.features")?.let { out ->
            // With -Dinksheets.bench.featurelooks=1: the page read again four other ways, and how many of them read each bar the same.
            val others = if (System.getProperty("inksheets.bench.featurelooks") != null && net != null && greyPage != null)
                listOf(1.08f to 0f, 0.93f to 0f, 1f to 0.15f, 1f to -0.15f).map { (k, d) -> Recognizer().read(ink, grey = greyPage, net = net, look = k to d).measures } else emptyList()
            fun sameAs(a: List<com.inksheets.core.omr.Event>, b: List<com.inksheets.core.omr.Event>) = a.size == b.size && a.indices.all { i ->
                a[i]::class == b[i]::class && a[i].duration == b[i].duration && ((a[i] as? Note)?.steps == (b[i] as? Note)?.steps) }
            val lines = ArrayList<String>()
            for ((mi, m) in reading.measures.withIndex()) {
                if (m.bars > 1) continue
                val right = barRight(mi, m.events) ?: continue
                val notes = m.events.filterIsInstance<Note>()
                val odds = notes.map { it.odds }.filter { it.size == 8 }
                fun minOr1(f: (List<Float>) -> Float) = odds.minOfOrNull(f) ?: 1f
                fun meanOr1(f: (List<Float>) -> Float) = if (odds.isEmpty()) 1f else odds.map(f).average().toFloat()
                val adds = abs(m.quarters - m.time.quarters) < 1e-6
                lines += listOf(if (right) 1 else 0, if (m.sure) 1 else 0, if (adds) 1 else 0, notes.size, m.events.size - notes.size,
                    minOr1 { it[7] }, meanOr1 { it[7] }, minOr1 { it.subList(0, 4).max() }, meanOr1 { it.subList(0, 4).max() },
                    minOr1 { it.subList(4, 7).max() }, m.maybe.size, m.doubts.size, if (m.repeatsBar) 1 else 0,
                    notes.count { it.duration.tuplet }, notes.count { it.steps.size > 1 }, m.events.count { it.duration.dots > 0 },
                    others.count { o -> o.any { it.staff == m.staff && it.bars == 1 && abs(it.box.left - m.box.left) <= sp * 0.6f && abs(it.box.right - m.box.right) <= sp * 0.6f && sameAs(it.events, m.events) } }
                ).joinToString("\t") + "\t" + f.nameWithoutExtension.replace('\t', ' ') + "\t" + m.number
            }
            synchronized(ReadingBenchmark::class.java) { File(out).appendText(lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n")) }
        }
        // The bars asked about: the three readings offered, and three more when those are turned down.
        val choices = IntArray(5)
        val calibrate = System.getProperty("inksheets.bench.calibrate") != null
        for ((mi, m) in reading.measures.withIndex()) {
            if (m.sure || m.bars > 1) continue
            val now = barRight(mi, m.events) ?: continue
            // Which kinds of change the right reading needed (its cheapest way there), for fitting their costs.
            if (calibrate && !now) {
                val right = com.inksheets.core.omr.BarChoices.all(m).filter { it.changes.isNotEmpty() && barRight(mi, it.events) == true }.minByOrNull { it.kinds.size * 100 + it.cost }
                synchronized(calibration) {
                    calibration.merge("(bars)", 1, Int::plus)
                    if (right == null) calibration.merge("(none within two changes)", 1, Int::plus)
                    else right.kinds.forEach { calibration.merge(it, 1, Int::plus) }
                }
            }
            val t0 = System.nanoTime()
            val again = if (looking) Recognizer().lookAgain(ink, greyPage!!, net!!, m) else emptyList()
            if (looking) synchronized(looks) { looks.merge("ms looking", ((System.nanoTime() - t0) / 1_000_000).toInt(), Int::plus); looks.merge("bars looked at", 1, Int::plus) }
            // -Dinksheets.bench.overlay=1: a dozen readings laid over the bar as printed, the best lined up three offered.
            val overlay = System.getProperty("inksheets.bench.overlay")?.toFloatOrNull()
            val offered = if (overlay == null) com.inksheets.core.omr.BarChoices.of(m, 3, looked = again)
                else com.inksheets.core.omr.Overlay.rank(ink, m, com.inksheets.core.omr.BarChoices.of(m, 12, looked = again), overlay).take(3)
            if (!now && looking) {
                val rightIn = again.map { barRight(mi, it.events) == true }
                synchronized(looks) {
                    looks.merge("wrong", 1, Int::plus)
                    if (rightIn.any { it }) looks.merge("right in a look", 1, Int::plus)
                    if (again.any { it.sure && barRight(mi, it.events) == true }) looks.merge("right and sure in a look", 1, Int::plus)
                    rightIn.forEachIndexed { i, ok -> if (ok) looks.merge("look $i", 1, Int::plus) }
                    if (rightIn.any { it } || offered.any { barRight(mi, it.events) == true }) looks.merge("right in a look or the 3 offered", 1, Int::plus)
                }
            }
            if (!now) {
                choices[0]++
                if (offered.any { barRight(mi, it.events) == true }) choices[1]++
                else if (com.inksheets.core.omr.BarChoices.of(m, 3, rejected = offered.map { it.events }, deeper = true, looked = if (looking) Recognizer().lookAgain(ink, greyPage!!, net!!, m, deeper = true) + again else emptyList()).any { barRight(mi, it.events) == true }) choices[2]++
            } else {
                choices[3]++
                if (offered.firstOrNull()?.changes?.isEmpty() == true) choices[4]++
            }
        }
        // Pictures of what the checker called wrong in bars the reader called sure: to check the checker.
        if (System.getProperty("inksheets.bench.why") != null) synchronized(this) {
            wrongAt.filter { (s, _) -> barAt(s.x, s.y).let { b -> b >= 0 && reading.measures[b].sure } }.forEach { (s, why) -> println("  SURE-WRONG ${f.relativeTo(music).path} p$page x=${s.x.toInt()} y=${s.y.toInt()}: $why") }
        }
        val pics = if (!shots) emptyList() else wrongAt.filter { (s, _) -> barAt(s.x, s.y).let { b -> b >= 0 && reading.measures[b].sure } }.take(6).map { (s, why) -> crop(ink, s.x, s.y, sp, "${f.nameWithoutExtension.take(12)} $why") }
        return PageResult(bars, outside, pics, choices)
    }

    // ---- the run ------------------------------------------------------------------------------

    class PartResult(val name: String, val bars: Int, val verifiable: Int, val correct: Int, val sure: Int, val sureVerifiable: Int, val sureCorrect: Int, val outside: Int, val causes: Map<String, Int>, val choices: IntArray = IntArray(5)) {
        fun row() = "$name\t$bars\t$verifiable\t$correct\t$sure\t$sureVerifiable\t$sureCorrect\t$outside"
    }

    @Test
    fun `reading against the PDFs' own notes`() {
        val set = System.getProperty("inksheets.bench") ?: ""
        assumeTrue(set == "dev" || set == "test")
        val scan = System.getProperty("inksheets.bench.scan") != null
        val printed = System.getProperty("inksheets.bench.printed") != null
        val n = (System.getProperty("inksheets.bench.n") ?: if (set == "dev") "20" else "60").toInt()
        val pages = (System.getProperty("inksheets.bench.pages") ?: "6").toInt()
        val shotsDir = System.getProperty("inksheets.bench.shots")
        val started = System.currentTimeMillis()
        val only = System.getProperty("inksheets.bench.only")
        // -Dinksheets.bench.held=1: the trained reader's held-out songs (train/held.txt), whichever set they are in.
        val heldSongs = if (System.getProperty("inksheets.bench.held") != null) File("../train/held.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() else null
        val parts = (if (heldSongs != null) corpus().filter { song(it) in heldSongs } else corpus().filter { isDev(it) == (set == "dev") }).take(n).filter { only == null || it.name.contains(only, true) }
        val pool = Executors.newFixedThreadPool(4)
        val futures = parts.map { f ->
            pool.submit<Pair<PartResult, List<BufferedImage>>> {
                val bars = ArrayList<Bar>(); var outside = 0; val pics = ArrayList<BufferedImage>(); val ch = IntArray(5)
                for (p in 0 until pages) {
                    val r = runCatching { markPage(f, p, scan, shotsDir != null, printed) }.getOrNull() ?: continue
                    bars += r.bars; outside += r.outside; pics += r.shots; for (k in 0..4) ch[k] += r.choices[k]
                }
                val causes = HashMap<String, Int>()
                // Bars asked about though read right: what the reader doubted.
                bars.filter { !it.sure && it.correct }.forEach { b -> causes.merge("FLAGGED BUT RIGHT: " + b.doubts.joinToString("; ") { it.replace(Regex("[0-9.]+"), "#") }, 1, Int::plus) }
                bars.filter { !it.sure && !it.verifiable }.forEach { b -> causes.merge("FLAGGED, UNVERIFIABLE: " + b.doubts.joinToString("; ") { it.replace(Regex("[0-9.]+"), "#") }, 1, Int::plus) }
                bars.filter { it.verifiable && !it.correct }.forEach { b -> causes.merge(if (b.causes.size == 1) b.causes.first() else "several: " + b.causes.sorted().joinToString("+"), 1, Int::plus) }
                PartResult(f.relativeTo(music).path.replace('\\', '/'), bars.size, bars.count { it.verifiable }, bars.count { it.correct },
                    bars.count { it.sure }, bars.count { it.sure && it.verifiable }, bars.count { it.sure && it.correct }, outside, causes, ch) to pics
            }
        }
        val done = futures.map { it.get() }
        pool.shutdown()
        val all = done.map { it.first }
        fun pct(a: Int, b: Int) = if (b == 0) "-" else "%.1f%%".format(100.0 * a / b)
        val bars = all.sumOf { it.bars }; val ver = all.sumOf { it.verifiable }; val cor = all.sumOf { it.correct }
        val sure = all.sumOf { it.sure }; val sureVer = all.sumOf { it.sureVerifiable }; val sureCor = all.sumOf { it.sureCorrect }
        val mode = if (scan) "scan" else if (printed) "printed" else "clean"
        println("BENCH $mode $set: ${all.size} parts, $bars bars, verifiable ${pct(ver, bars)}; CORRECT ${pct(cor, ver)} ($cor/$ver); " +
            "SURE ${pct(sure, bars)}; TRUST ${pct(sureCor, sureVer)} ($sureCor/$sureVer); heads outside every bar ${all.sumOf { it.outside }}; ${(System.currentTimeMillis() - started) / 1000}s")
        val causes = HashMap<String, Int>(); all.forEach { p -> p.causes.forEach { (k, v) -> causes.merge(k, v, Int::plus) } }
        val flagged = all.map { it.bars - it.sure }.sorted()
        println("GOAL: parts with 2 or fewer bars flagged ${all.count { it.bars - it.sure <= 2 }}/${all.size}; flagged per part median ${flagged.getOrNull(flagged.size / 2)}, worst ${flagged.lastOrNull()}; " +
            "parts where every sure bar checked is correct ${all.count { it.sureCorrect == it.sureVerifiable }}/${all.size}")
        val ch = IntArray(5); all.forEach { p -> for (k in 0..4) ch[k] += p.choices[k] }
        println("CHOICES: of ${ch[0]} bars asked about and read wrong, the right reading among the 3 offered ${pct(ch[1], ch[0])} (${ch[1]}), " +
            "among the next 3 ${pct(ch[2], ch[0])} (${ch[2]}) - in 6: ${pct(ch[1] + ch[2], ch[0])}; of ${ch[3]} asked about though read right, offered first ${pct(ch[4], ch[3])}")
        if (calibration.isNotEmpty()) {
            val bars = calibration["(bars)"] ?: 0; val none = calibration["(none within two changes)"] ?: 0
            val kinds = calibration.filterKeys { !it.startsWith("(") }
            println("CALIBRATION: $bars bars read wrong; ${bars - none} have their right reading a change or two away; kinds needed: $kinds")
            println("COSTS: " + com.inksheets.core.omr.BarChoices.costsFrom(kinds, bars - none).entries.sortedBy { it.value }.joinToString { "\"${it.key}\" to ${"%.2f".format(java.util.Locale.ROOT, it.value)}f" })
        }
        if (looks.isNotEmpty()) println("LOOKS: " + looks.entries.sortedBy { it.key }.joinToString { "${it.key} ${it.value}" })
        println("WRONG BARS BY CAUSE: " + causes.entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" })
        // Against the baseline, part by part.
        results.mkdirs()
        val last = File(results, "$mode-$set-last.tsv"); val base = File(results, "$mode-$set.tsv")
        val header = "part\tbars\tverifiable\tcorrect\tsure\tsureVerifiable\tsureCorrect\toutside"
        last.writeText((listOf(header) + all.map { it.row() }).joinToString("\n") + "\n")
        if (base.isFile) {
            val before = base.readLines().drop(1).associate { l -> l.split('\t').let { it[0] to it.drop(1).map(String::toInt) } }
            val b = all.mapNotNull { p -> before[p.name]?.let { p to it } }
            val bVer = b.sumOf { it.second[1] }; val bCor = b.sumOf { it.second[2] }; val bSureVer = b.sumOf { it.second[4] }; val bSureCor = b.sumOf { it.second[5] }
            println("BASELINE (same ${b.size} parts): CORRECT ${pct(bCor, bVer)} -> ${pct(b.sumOf { it.first.correct }, b.sumOf { it.first.verifiable })}; " +
                "TRUST ${pct(bSureCor, bSureVer)} -> ${pct(b.sumOf { it.first.sureCorrect }, b.sumOf { it.first.sureVerifiable })}")
            b.filter { (p, o) -> p.correct < o[2] || p.sureCorrect * o[4] < o[5] * p.sureVerifiable - 0.02 * o[4] * p.sureVerifiable }.forEach { (p, o) ->
                println("  WORSE ${p.name}: correct ${o[2]} -> ${p.correct}, trust ${o[5]}/${o[4]} -> ${p.sureCorrect}/${p.sureVerifiable}")
            }
            b.filter { (p, o) -> p.correct > o[2] }.forEach { (p, o) -> println("  better ${p.name}: correct ${o[2]} -> ${p.correct}") }
        }
        if (System.getProperty("inksheets.bench.save") != null) { base.writeText(last.readText()); println("baseline saved: $base") }
        shotsDir?.let { sheet(done.flatMap { it.second }.take(60), File(it, "bench-$mode-$set-sure-wrong.png")); sheet(barShots.toList(), File(it, "bench-$mode-$set-right-but-long.png")) }
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** [ink] as a photocopy scanned: turned [degrees], its ink spread, blurred, lit unevenly, with noise. */
    /** The trained reader's weights (-Dinksheets.bench.net=path), or none. */
    init { System.getProperty("inksheets.bench.sure")?.toFloatOrNull()?.let { com.inksheets.core.omr.Learned.SURE = it } }

    private val net: com.inksheets.core.omr.Net? by lazy { System.getProperty("inksheets.bench.net")?.let { File(it).inputStream().use { s -> com.inksheets.core.omr.Net.load(s) } } }

    private fun scanned(ink: Ink, degrees: Double, seed: Long): Ink = Ink.fromGrey(ink.width, ink.height, scannedGrey(ink, degrees, seed))

    /**
     * A scan's distortion beyond a slight turn (-Dinksheets.bench.warp=): "keystone" - a phone's
     * photo, the bottom of the page 6% narrower than the top; "spine" - a book pressed on the glass,
     * its left edge in the gutter: the first fifth of the page squeezed sideways (by half at the
     * edge), its staves pinched toward the middle and sagging there; "tilt3" (a 3 degree turn, no
     * warp). [forward] takes a point of the page as printed to where it is on the scan, [inverse] back.
     */
    internal class Warp(val kind: String, w: Int, private val h: Int) {
        private val cx = w / 2.0; private val cy = h / 2.0
        private val band = w * 0.2
        private fun near(x: Double) = Math.exp(-x.coerceAtLeast(0.0) / band)
        private fun squeeze(x: Double) = x - 0.5 * band * (1 - near(x))
        fun forward(x: Double, y: Double): Pair<Double, Double> = when (kind) {
            "keystone" -> (cx + (x - cx) * (1 - 0.06 * y / h)) to y
            "spine" -> squeeze(x) to (cy + (y - cy) * (1 - 0.08 * near(x)) + 0.012 * h * near(x))
            else -> x to y
        }
        fun inverse(x: Double, y: Double): Pair<Double, Double> = when (kind) {
            "keystone" -> (cx + (x - cx) / (1 - 0.06 * y / h)) to y
            "spine" -> {
                // The squeeze undone by halving: it only grows with x.
                var lo = 0.0; var hi = x + band
                if (x <= 0) lo = x - band
                repeat(40) { val mid = (lo + hi) / 2; if (squeeze(mid) < x) lo = mid else hi = mid }
                val px = (lo + hi) / 2
                px to (cy + (y - 0.012 * h * near(px) - cy) / (1 - 0.08 * near(px)))
            }
            else -> x to y
        }
    }

    private fun scannedGrey(ink: Ink, degrees: Double, seed: Long, warp: Warp? = null): IntArray {
        val r = java.util.Random(seed)
        val a = Math.toRadians(degrees)
        val cos = Math.cos(a); val sin = Math.sin(a)
        val cx = ink.width / 2.0; val cy = ink.height / 2.0
        val grey = IntArray(ink.width * ink.height)
        for (y0 in 0 until ink.height) for (x0 in 0 until ink.width) {
            // The point of the (turned) page this pixel of the scan shows.
            val (x, y) = warp?.inverse(x0.toDouble(), y0.toDouble()) ?: (x0.toDouble() to y0.toDouble())
            var dark = 0.0
            for (dy in -1..1) for (dx in -1..1) {
                val sx = cos * (x + dx * 0.7 - cx) + sin * (y + dy * 0.7 - cy) + cx
                val sy = -sin * (x + dx * 0.7 - cx) + cos * (y + dy * 0.7 - cy) + cy
                if (ink[sx.toInt(), sy.toInt()]) dark += 1.0 / 7
            }
            val paper = 220 + (x0 * 25 / ink.width) - (y0 * 10 / ink.height)
            grey[y0 * ink.width + x0] = (paper - dark.coerceAtMost(1.0) * 210 + r.nextGaussian() * 14).toInt().coerceIn(0, 255)
        }
        return grey
    }

    private fun crop(ink: Ink, x: Float, y: Float, sp: Float, caption: String): BufferedImage {
        val w = (sp * 12).toInt(); val h = (sp * 11).toInt()
        val x0 = (x - sp * 5).toInt(); val y0 = (y - sp * 5.5f).toInt()
        val img = BufferedImage(w, h + 16, BufferedImage.TYPE_INT_RGB)
        for (yy in 0 until h) for (xx in 0 until w) img.setRGB(xx, yy, if (ink[x0 + xx, y0 + yy]) 0 else 0xFFFFFF)
        val g = img.createGraphics()
        g.color = Color.WHITE; g.fillRect(0, h, w, 16)
        g.color = Color.RED; g.drawOval((x - x0 - 3).toInt(), (y - y0 - sp * 0.8f).toInt(), (sp * 1.8f).toInt(), (sp * 1.6f).toInt())
        g.font = g.font.deriveFont(11f); g.drawString(caption.take(40), 2, h + 12)
        g.dispose()
        return img
    }

    private fun sheet(images: List<BufferedImage>, out: File) {
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
        javax.imageio.ImageIO.write(img, "png", out)
    }
}
