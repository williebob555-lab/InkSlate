package com.inksheets.desktop

import com.inksheets.core.omr.Ink
import com.inksheets.core.omr.Recognizer
import com.inksheets.core.omr.Strips
import com.inksheets.desktop.AnswerKey.Kind
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * Training data for the trained reader, from the library's own parts that state their notes: every
 * staff cut out as a [com.inksheets.core.omr.Strip] (a PNG), and what is printed on it - each head
 * with its value and dots, each rest, accidental and note's dot, at its place in the strip - one
 * JSON line a strip in labels.jsonl. Scans of the library (no labels) are cut too, into scans/,
 * for looking at and for learning from later. -Dinksheets.strips=<dir> [-Dinksheets.strips.pages=4]
 *
 * Classes: 0 black head, 1 half head, 2 whole head, 3 whole rest, 4 half rest, 5 quarter rest,
 * 6 eighth rest, 7 sixteenth rest, 8 note's dot, 9 sharp, 10 flat, 11 natural. A head also carries
 * its beams-or-flags count (0-3, -1 not shown plainly) and dots; a cue or grace head is marked
 * "ignore" (not taught either way).
 */
class StripExport {
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")
    private val heads = setOf(Kind.HEAD_BLACK, Kind.HEAD_HALF, Kind.HEAD_WHOLE)
    private val flags = setOf(Kind.FLAG_8, Kind.FLAG_16, Kind.FLAG_32)

    private fun cls(k: Kind): Int? = when (k) {
        Kind.HEAD_BLACK -> 0; Kind.HEAD_HALF -> 1; Kind.HEAD_WHOLE -> 2
        Kind.REST_1 -> 3; Kind.REST_2 -> 4; Kind.REST_4 -> 5; Kind.REST_8 -> 6; Kind.REST_16 -> 7
        Kind.SHARP -> 9; Kind.FLAT -> 10; Kind.NATURAL -> 11
        else -> null
    }

    /** Music-font characters that are surely no head, rest, dot or accidental: articulations, time digits, dynamics - taught as nothing. */
    private fun known(code: Int) = code in "->^~0123456789Cc".map { it.code } || code in 0xE4A0..0xE4BF || code in 0xE520..0xE54F || code == 0x0192 || code == 0x2018

    /** [file]'s page [index] drawn so a space is about 18 pixels: its grey levels, width and height, and the dpi. */
    private fun render(file: File, index: Int): Triple<BufferedImage, Float, Ink>? {
        Loader.loadPDF(file).use { doc ->
            if (index >= doc.numberOfPages) return null
            val r = PDFRenderer(doc)
            fun ink(img: BufferedImage): Ink {
                val px = IntArray(img.width * img.height); img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
                return Ink.fromArgb(img.width, img.height, px)
            }
            val first = r.renderImageWithDPI(index, 100f, ImageType.RGB)
            val m = Recognizer().metrics(ink(first)) ?: return null
            val dpi = 100f * 18f / m.second
            val img = r.renderImageWithDPI(index, dpi, ImageType.RGB)
            return Triple(img, dpi, ink(img))
        }
    }

    private fun save(strip: com.inksheets.core.omr.Strip, out: File) {
        val img = BufferedImage(strip.width, Strips.H, BufferedImage.TYPE_BYTE_GRAY)
        val raster = img.raster
        for (r in 0 until Strips.H) for (c in 0 until strip.width) raster.setSample(c, r, 0, (strip[c, r] * 255).toInt().coerceIn(0, 255))
        ImageIO.write(img, "png", out)
    }

    /** One labelled page: its strips written, their label lines returned. */
    private fun page(f: File, p: Int, bench: ReadingBenchmark, dir: File, held: Set<String>): List<String> {
        val (img, dpi, ink) = render(f, p) ?: return emptyList()
        val raw = AnswerKey.read(f, p, dpi) ?: return emptyList()
        val vectors = VectorKey.read(f, p, dpi) ?: return emptyList()
        val rec = Recognizer()
        val (t, space) = rec.metrics(ink) ?: return emptyList()
        val staves = rec.staves(ink, t, space)
        if (staves.isEmpty()) return emptyList()
        val sp = space
        // Some fonts' heads sit a little off their origin: moved by however far most sit off a line or space (as the benchmark does).
        val fracs = raw.filter { it.kind in heads }.mapNotNull { h ->
            val st = staves.firstOrNull { h.y > it.top - it.space && h.y < it.bottom + it.space && h.x >= it.left && h.x <= it.right } ?: return@mapNotNull null
            val exact = (h.y - st.lineY(0, h.x.toInt())) / ((st.lineY(4, h.x.toInt()) - st.lineY(0, h.x.toInt())) / 8f)
            exact - Math.round(exact)
        }.sorted()
        val shift = fracs.getOrNull(fracs.size / 2)?.takeIf { abs(it) > 0.1f }?.let { it * sp / 2 } ?: 0f
        val key = raw.map { if (it.kind in heads || it.kind == Kind.DOT) it.copy(y = it.y - shift) else it }
        val argb = IntArray(img.width * img.height); img.getRGB(0, 0, img.width, img.height, argb, 0, img.width)
        val grey = Strips.grey(argb)
        // As the benchmark marks: heads' sizes (cue notes smaller), dots beside heads, values from stems.
        val allHeads = key.filter { it.kind in heads }
        if (allHeads.isEmpty()) return emptyList()
        val normal = allHeads.map { it.size }.sorted().let { it[it.size / 2] }
        val dots = (key.filter { it.kind == Kind.DOT }.map { it.x to it.y } + vectors.dots.filter { it.size in sp * 0.25f..sp * 0.75f }.map { it.x to it.y })
            .filter { (dx, dy) -> allHeads.none { o -> dx in o.x..o.x + o.width && abs(dy - o.y) > sp * 0.3f && abs(dy - o.y) < sp * 3f } }
        val augDots = dots.filter { (dx, dy) -> allHeads.any { h -> dx > h.x + h.width * 0.8f && dx < h.x + h.width + sp * 2.2f && dy > h.y - sp * 0.9f && dy < h.y + sp * 0.4f } }
        val others = key.filter { it.kind == Kind.OTHER }
        val flagGlyphs = key.filter { it.kind in flags }
        fun beams(h: AnswerKey.Symbol): Int {
            if (h.kind != Kind.HEAD_BLACK) return 0
            val stem = VectorKey.stemOf(vectors, h.x, h.y, h.width, sp) ?: return -1
            val v = VectorKey.valueOf(vectors, h.x, h.y, h.width, sp, flagGlyphs) ?: return -1
            val end = if (abs(stem.y0 - h.y) > abs(stem.y1 - h.y)) stem.y0 else stem.y1
            if (v == 4 && others.any { o -> abs(o.x - stem.x) < sp * 1.2f && abs(o.y - end) < sp * 2.5f }) return -1
            return when (v) { 4 -> 0; 8 -> 1; 16 -> 2; 32 -> 3; else -> -1 }
        }
        fun dotsOf(h: AnswerKey.Symbol) = augDots.filter { (dx, dy) -> dx > h.x + h.width * 0.8f && dx < h.x + h.width + sp * 2.2f && dy > h.y - sp * 0.9f && dy < h.y + sp * 0.4f }
            .map { it.first }.sorted().fold(ArrayList<Float>()) { acc, x -> if (acc.isEmpty() || x - acc.last() > sp * 0.25f) acc += x; acc }.size.coerceAtMost(2)
        val lines = ArrayList<String>()
        val name = "${Integer.toHexString(f.absolutePath.hashCode())}-p$p"
        val dev = bench.isDev(f); val song = bench.song(f)
        for ((si, s) in staves.withIndex()) {
            val strip = Strips.cut(grey, img.width, img.height, s)
            val objs = ArrayList<String>()
            fun add(c: Int, x: Float, y: Float, extra: String = "") {
                val col = strip.column(x); val row = strip.row(x, y)
                if (col < 0 || col >= strip.width || row < 0 || row >= Strips.H) return
                objs += "[$c,${"%.1f".format(java.util.Locale.ROOT, col)},${"%.1f".format(java.util.Locale.ROOT, row)}$extra]"
            }
            for (sym in key) {
                // A music-font character not named here: not taught either way (it may be a head
                // in a form the key does not know), its code kept for naming later.
                if (sym.kind == Kind.OTHER && known(sym.text.codePointAt(0))) continue
                if (sym.kind == Kind.OTHER) { add(-1, sym.x + sym.width / 2, sym.y, ",\"U+%04X\"".format(sym.text.codePointAt(0))); continue }
                val c = cls(sym.kind) ?: continue
                val cx = sym.x + sym.width / 2
                if (sym.kind in heads) {
                    val cue = sym.size < normal * 0.85f
                    add(c, cx, sym.y, ",${if (cue) -2 else beams(sym)},${dotsOf(sym)}${if (cue) ",\"ignore\"" else ""}")
                } else add(c, cx, sym.y)
            }
            for ((dx, dy) in augDots) add(8, dx, dy)
            val file = "$name-s$si.png"
            save(strip, File(dir, file))
            lines += "{\"file\":\"$file\",\"song\":\"$song\",\"dev\":$dev,\"held\":${song in held},\"w\":${strip.width},\"objs\":[${objs.joinToString(",")}]}"
        }
        return lines
    }

    @Test
    fun `cut the library into labelled strips`() {
        val out = System.getProperty("inksheets.strips") ?: return assumeTrue(false)
        val pages = (System.getProperty("inksheets.strips.pages") ?: "4").toInt()
        val limit = (System.getProperty("inksheets.strips.n") ?: "100000").toInt()
        val dir = File(out, "strips").apply { mkdirs() }
        val bench = ReadingBenchmark()
        val all = bench.corpus()
        // The benchmark's held-out parts' songs: never trained on, so the benchmark on them stays fair.
        val held = all.filter { !bench.isDev(it) }.take(60).map { bench.song(it) }.toSet()
        val parts = all.take(limit)
        val labels = java.util.Collections.synchronizedList(ArrayList<String>())
        val pool = Executors.newFixedThreadPool(4)
        val t0 = System.currentTimeMillis()
        parts.map { f -> pool.submit { for (p in 0 until pages) runCatching { labels += page(f, p, bench, dir, held) }.onFailure { println("  failed ${f.name} p$p: ${it.message}") } } }.forEach { it.get() }
        pool.shutdown()
        File(out, "labels.jsonl").writeText(labels.joinToString("\n"))
        println("STRIPS: ${parts.size} parts, ${labels.size} strips in ${(System.currentTimeMillis() - t0) / 1000}s")
    }

    /**
     * DeepScoresV2's pages (train/ds2.py made them: a PNG and its labels as a .tsv per page) cut into
     * strips as the library's are: the staves found as the app finds them, each symbol at its place
     * in its strip. Its own test pages kept out of teaching (song "ds2test", in held.txt).
     * -Dinksheets.ds2=<dir with img/> -Dinksheets.ds2.out=<dir>
     */
    @Test
    fun `cut DeepScores into strips`() {
        val src = System.getProperty("inksheets.ds2") ?: return assumeTrue(false)
        val out = File(System.getProperty("inksheets.ds2.out")!!)
        val dir = File(out, "strips").apply { mkdirs() }
        val pages = File(src, "img").listFiles { f -> f.name.endsWith(".png") }.orEmpty().sortedBy { it.name }
        val labels = java.util.Collections.synchronizedList(ArrayList<String>())
        val pool = Executors.newFixedThreadPool(3)
        val t0 = System.currentTimeMillis()
        pages.map { png -> pool.submit { runCatching {
            val tsv = File(png.path.removeSuffix(".png") + ".tsv").readLines()
            val split = tsv.first()
            val img = ImageIO.read(png)
            val argb = IntArray(img.width * img.height); img.getRGB(0, 0, img.width, img.height, argb, 0, img.width)
            val ink = Ink.fromArgb(img.width, img.height, argb)
            val grey = Strips.grey(argb)
            val rec = Recognizer()
            val (t, space) = rec.metrics(ink) ?: return@runCatching
            val staves = rec.staves(ink, t, space)
            val syms = tsv.drop(1).filter { it.isNotBlank() }.map { it.split('\t') }
            for ((si, s) in staves.withIndex()) {
                val strip = Strips.cut(grey, img.width, img.height, s)
                val objs = ArrayList<String>()
                fun f1(v: Float) = "%.1f".format(java.util.Locale.ROOT, v)
                for (r in syms) {
                    val x = r[1].toFloat(); val y = r[2].toFloat()
                    val col = strip.column(x); val row = strip.row(x, y)
                    if (col < 0 || col >= strip.width || row < 0 || row >= Strips.H) continue
                    objs += when {
                        r[0] == "-1" -> "[-1,${f1(col)},${f1(row)},\"odd\"]"
                        r.size >= 5 -> "[${r[0]},${f1(col)},${f1(row)},${r[3]},${r[4]}]"
                        else -> "[${r[0]},${f1(col)},${f1(row)}]"
                    }
                }
                val file = "d${png.nameWithoutExtension.hashCode().toUInt().toString(16)}-s$si.png"
                save(strip, File(dir, file))
                labels += "{\"file\":\"$file\",\"song\":\"${if (split == "test") "ds2test" else "ds2"}\",\"dev\":false,\"held\":${split == "test"},\"w\":${strip.width},\"objs\":[${objs.joinToString(",")}]}"
            }
        }.onFailure { println("  failed ${png.name}: ${it.message}") } } }.forEach { it.get() }
        pool.shutdown()
        File(out, "labels.jsonl").writeText(labels.joinToString("\n"))
        println("DS2: ${pages.size} pages, ${labels.size} strips in ${(System.currentTimeMillis() - t0) / 1000}s")
    }

    /**
     * Learning from the library's own scans: each read by the trained reader (-Dinksheets.bench.net),
     * and what it found in bars it is sure of - they add up, every head likely - kept as their
     * labels; bars in doubt left out of teaching either way (a [-3, from, to] column span). Into
     * <dir>/strips and <dir>/labels.jsonl, as the labelled export. -Dinksheets.pseudo=<dir>
     */
    @Test
    fun `cut the library's scans into strips labelled by the reader`() {
        val out = System.getProperty("inksheets.pseudo") ?: return assumeTrue(false)
        val net = File(System.getProperty("inksheets.bench.net")!!).inputStream().use { com.inksheets.core.omr.Net.load(it) }
        val pages = (System.getProperty("inksheets.strips.pages") ?: "6").toInt()
        val dir = File(out, "strips").apply { mkdirs() }
        val bench = ReadingBenchmark()
        val labelled = bench.corpus().map { it.absolutePath }.toSet()
        val held = File("../train/held.txt").readLines().map { it.trim() }.toSet()
        val files = music.walkTopDown().filter { it.isFile && it.extension.equals("pdf", true) && !it.name.contains("score", true) && !it.path.contains(".inksheets") }
            .filter { it.absolutePath !in labelled && bench.song(it) !in held }.distinctBy { it.length() }.toList()
        val labels = java.util.Collections.synchronizedList(ArrayList<String>())
        var bars = 0; var sure = 0
        val pool = Executors.newFixedThreadPool(3)
        val t0 = System.currentTimeMillis()
        files.map { f -> pool.submit { for (p in 0 until pages) runCatching {
            val (img, _, ink) = render(f, p) ?: return@runCatching
            val argb = IntArray(img.width * img.height); img.getRGB(0, 0, img.width, img.height, argb, 0, img.width)
            val grey = Strips.grey(argb)
            val rec = Recognizer()
            val (t, space) = rec.metrics(ink) ?: return@runCatching
            val staves = rec.staves(ink, t, space)
            if (staves.isEmpty()) return@runCatching
            val found = com.inksheets.core.omr.Learned.symbols(grey, img.width, img.height, staves, net)
            val reading = Recognizer().read(ink, p, grey = grey, net = net)
            synchronized(this) { bars += reading.measures.size; sure += reading.measures.count { it.sure } }
            val name = "x${Integer.toHexString(f.absolutePath.hashCode())}-p$p"
            for ((si, s) in staves.withIndex()) {
                val mine = reading.measures.filter { it.staff == si && it.bars == 1 }
                if (mine.none { it.sure }) continue
                val strip = Strips.cut(grey, img.width, img.height, s)
                val objs = ArrayList<String>()
                fun f1(v: Float) = "%.1f".format(java.util.Locale.ROOT, v)
                for (m in mine) if (!m.sure) objs += "[-3,${f1(strip.column(m.box.left.toFloat()))},${f1(strip.column(m.box.right.toFloat()))}]"
                // Before the first bar and after the last (clef, key, time; a line's end): not taught either way.
                mine.minOfOrNull { it.box.left }?.let { objs += "[-3,0,${f1(strip.column(it.toFloat()))}]" }
                mine.maxOfOrNull { it.box.right }?.let { objs += "[-3,${f1(strip.column(it.toFloat()))},${strip.width}]" }
                for (sym in found.symbols) {
                    val m = mine.firstOrNull { sym.x + sym.width / 2 >= it.box.left && sym.x + sym.width / 2 < it.box.right && it.sure } ?: continue
                    val col = strip.column(sym.x + sym.width / 2); val row = strip.row(sym.x + sym.width / 2, sym.y)
                    if (row < 0 || row >= Strips.H || m.staff != si) continue
                    val c = when (sym.kind) {
                        com.inksheets.core.omr.Printed.Kind.HEAD_BLACK -> 0; com.inksheets.core.omr.Printed.Kind.HEAD_HALF -> 1; com.inksheets.core.omr.Printed.Kind.HEAD_WHOLE -> 2
                        com.inksheets.core.omr.Printed.Kind.REST_1 -> 3; com.inksheets.core.omr.Printed.Kind.REST_2 -> 4; com.inksheets.core.omr.Printed.Kind.REST_4 -> 5
                        com.inksheets.core.omr.Printed.Kind.REST_8 -> 6; com.inksheets.core.omr.Printed.Kind.REST_16 -> 7; com.inksheets.core.omr.Printed.Kind.DOT -> 8
                        com.inksheets.core.omr.Printed.Kind.SHARP -> 9; com.inksheets.core.omr.Printed.Kind.FLAT -> 10; com.inksheets.core.omr.Printed.Kind.NATURAL -> 11
                        else -> continue
                    }
                    objs += if (c <= 2) "[$c,${f1(col)},${f1(row)},${sym.beams},${sym.dots}]" else "[$c,${f1(col)},${f1(row)}]"
                }
                val file = "$name-s$si.png"
                save(strip, File(dir, file))
                labels += "{\"file\":\"$file\",\"song\":\"${bench.song(f)}\",\"dev\":false,\"held\":false,\"pseudo\":true,\"w\":${strip.width},\"objs\":[${objs.joinToString(",")}]}"
            }
        } } }.forEach { it.get() }
        pool.shutdown()
        File(out, "labels.jsonl").writeText(labels.joinToString("\n"))
        println("PSEUDO: ${files.size} files, $bars bars read, $sure sure; ${labels.size} strips in ${(System.currentTimeMillis() - t0) / 1000}s")
    }
}
