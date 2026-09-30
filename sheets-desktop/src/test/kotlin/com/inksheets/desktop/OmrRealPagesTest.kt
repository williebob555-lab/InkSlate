package com.inksheets.desktop

import com.inksheets.core.omr.Event
import com.inksheets.core.omr.Ink
import com.inksheets.core.omr.Note
import com.inksheets.core.omr.Recognizer
import com.inksheets.core.omr.Rest
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max

/**
 * The music reader on real parts from the music folder: each page drawn so a staff space is about
 * 18 pixels, read, and drawn over for looking at (-Dinksheets.shots). Run by hand:
 * -Dinksheets.omr=real.
 */
class OmrRealPagesTest {
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")

    /** [file]'s page [index], drawn so a staff space is about [space] pixels. */
    fun render(file: File, index: Int, space: Float = 18f): Ink? = renderAt(file, index, space)?.first

    /** [render], and the dots per inch it was drawn at. */
    fun renderAt(file: File, index: Int, space: Float = 18f): Pair<Ink, Float>? {
        Loader.loadPDF(file).use { doc ->
            if (index >= doc.numberOfPages) return null
            val r = PDFRenderer(doc)
            fun at(dpi: Float): Ink {
                val img = r.renderImageWithDPI(index, dpi, ImageType.RGB)
                val px = IntArray(img.width * img.height)
                img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
                return Ink.fromArgb(img.width, img.height, px)
            }
            val first = at(100f)
            val m = Recognizer().metrics(first) ?: return first to 100f
            val dpi = 100f * space / m.second
            System.getProperty("inksheets.shots")?.takeIf { System.getProperty("inksheets.omr") == "one" }?.let { dir ->
                ImageIO.write(r.renderImageWithDPI(index, dpi, ImageType.RGB), "png", File(dir, "grey-${file.nameWithoutExtension}-p${index + 1}.png"))
            }
            return at(dpi) to dpi
        }
    }

    private fun describe(e: Event) = when (e) {
        is Note -> e.pitches.joinToString("+") + "/" + e.duration.base + ".".repeat(e.duration.dots)
        is Rest -> "r/" + e.duration.base
    }

    fun overlay(ink: Ink, reading: Recognizer.PageReading, out: File) {
        val img = BufferedImage(ink.width, ink.height, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, ink.width, ink.height, ink.argb(), 0, ink.width)
        val g = img.createGraphics()
        g.stroke = BasicStroke(2f)
        g.font = g.font.deriveFont(11f)
        for ((si, s) in reading.staves.withIndex()) {
            g.color = Color(0, 160, 255)
            g.drawRect(s.left, s.top, s.right - s.left, s.bottom - s.top)
            g.color = Color.RED
            for (b in reading.barlines[si]) g.drawLine(b, s.top - 8, b, s.bottom + 8)
        }
        for (m in reading.measures) {
            g.color = if (m.sure) Color(0, 150, 0) else Color(220, 110, 0)
            g.drawString("${m.number}", m.box.left + 3, m.box.top - 4)
            var row = 0
            val staff = reading.staves[m.staff]
            for (e in m.events) {
                g.drawString(describe(e), e.x.toInt(), m.box.bottom + (m.space * 2.5f).toInt() + 12 * (row++ % 2))
                // Each head where it was read: a circle round it.
                if (e is Note) for (st in e.steps) {
                    val y = staff.y(st, e.x.toInt()).toInt()
                    g.drawOval(e.x.toInt() - 2, y - (m.space * 0.6f).toInt(), (m.space * 1.5f).toInt(), (m.space * 1.2f).toInt())
                }
                if (e is Rest) g.drawRect(e.x.toInt(), staff.y(2, e.x.toInt()).toInt(), (m.space).toInt(), (m.space * 2).toInt())
            }
        }
        g.dispose()
        out.parentFile.mkdirs()
        ImageIO.write(img, "png", out)
    }

    private fun read(relative: String, pages: Int = 1) {
        val file = File(music, relative)
        assumeTrue("no $relative", file.isFile)
        val shots = System.getProperty("inksheets.shots")
        for (p in 0 until pages) {
            val ink = runCatching { render(file, p) }.onFailure { println("${file.name}: cannot open - ${it.message}") }.getOrNull() ?: break
            val t0 = System.nanoTime()
            val reading = Recognizer().read(ink, p)
            val ms = (System.nanoTime() - t0) / 1_000_000
            val sure = reading.measures.count { it.sure }
            println("${file.name} p${p + 1}: ${ink.width}x${ink.height}, space ${"%.1f".format(reading.space)}, line ${reading.thickness}, " +
                "${reading.staves.size} staves, ${reading.measures.size} measures, $sure sure, ${reading.measures.sumOf { it.events.size }} events, $ms ms")
            reading.measures.filter { !it.sure }.forEach { println("   m${it.number}: ${it.doubts} ${it.events.map(::describe)}") }
            if (shots != null) {
                overlay(ink, reading, File(shots, "real-${file.nameWithoutExtension}-p${p + 1}.png"))
                val raw = BufferedImage(ink.width, ink.height, BufferedImage.TYPE_INT_RGB)
                raw.setRGB(0, 0, ink.width, ink.height, ink.argb(), 0, ink.width)
                ImageIO.write(raw, "png", File(shots, "raw-${file.nameWithoutExtension}-p${p + 1}.png"))
            }
        }
    }

    @Test
    fun `reads one part`() {
        assumeTrue(System.getProperty("inksheets.omr") == "one")
        val f = File(System.getProperty("inksheets.omr.file")!!)
        read(f.relativeTo(music).path, 1)
        // Bars read differently with the page-adapting steps than without.
        val ink = render(f, 0) ?: return
        Recognizer().read(ink).measures.take(5).forEach { println("  first: m${it.number} bars ${it.bars} ${it.events.map(::describe)} ${it.doubts} x ${it.box.left}-${it.box.right}") }
        val old = Recognizer(adapt = false).read(ink).measures.associateBy { it.number }
        for (m in Recognizer().read(ink).measures) {
            val o = old[m.number] ?: continue
            if (o.sure != m.sure || o.events.map(::describe) != m.events.map(::describe))
                println("  m${m.number} was ${if (o.sure) "sure" else "unsure"} ${o.events.map(::describe)} ${o.doubts}\n" +
                    "         now ${if (m.sure) "sure" else "unsure"} ${m.events.map(::describe)} ${m.doubts}")
        }
    }

    /**
     * First pages of parts spread across the whole library (full scores left out), each read with
     * and without the page-adapting steps: bars that add up, in all and part by part, so a change
     * is measured on many engravers and scans rather than a few. -Dinksheets.omr=library,
     * -Dinksheets.omr.songs=how many parts (80).
     */
    @Test
    fun `reads across the library`() {
        assumeTrue(System.getProperty("inksheets.omr") == "library")
        val want = (System.getProperty("inksheets.omr.songs") ?: "80").toInt()
        val all = music.walkTopDown().filter { it.isFile && it.extension.equals("pdf", true) && !it.name.contains("score", true) }
            .toList()
        // The same parts each run, whatever is added to the library: chosen by a hash of their place in it.
        val picked = all.sortedBy { java.util.zip.CRC32().apply { update(it.relativeTo(music).path.lowercase().replace('\\', '/').toByteArray()) }.value }.take(want)
        val reasons = HashMap<String, Int>()
        var printedNumbers = 0; var agreeingNumbers = 0
        val numberMisses = ArrayList<String>()
        val examples = HashMap<String, MutableList<BufferedImage>>()
        var bars = 0; var sureOld = 0; var sureNew = 0; var parts = 0; var better = 0; var worse = 0
        for (f in picked) {
            val ink = runCatching { render(f, 0) }.getOrNull() ?: continue
            val old = Recognizer(adapt = false).read(ink)
            val new = Recognizer().read(ink)
            if (new.measures.isEmpty() && old.measures.isEmpty()) continue
            parts++
            bars += new.measures.size
            val a = old.measures.count { it.sure }; val b = new.measures.count { it.sure }
            sureOld += a; sureNew += b
            if (b > a) better++; if (b < a) worse++
            // Bar numbers printed at the start of a line: does the count agree?
            val clean = Recognizer().let { r -> r.withoutLines(ink, new.staves, new.thickness) }
            var printedHere = 0; var agreeHere = 0
            for ((si, st) in new.staves.withIndex()) {
                if (si == 0) continue
                val sp = st.space
                val printed = com.inksheets.core.omr.Digits.number(clean, st.left - sp.toInt(), st.left + (sp * 3).toInt(),
                    st.y(-8, st.left).toInt(), st.y(-1, st.left).toInt(), (sp * 0.6f).toInt(), (sp * 2.5f).toInt(), bottomFrom = st.y(-5, st.left).toInt())?.first ?: continue
                val first = new.measures.firstOrNull { it.staff == si }?.number ?: continue
                printedHere++
                if (printed == first) agreeHere++ else if (numberMisses.size < 30) numberMisses += "${f.nameWithoutExtension} staff ${si + 1}: printed $printed, counted $first"
            }
            printedNumbers += printedHere; agreeingNumbers += agreeHere
            println("${f.relativeTo(music).path}: ${old.measures.size} -> ${new.measures.size} bars, sure $a -> $b; bar numbers agree $agreeHere of $printedHere")
            // A few bars of each kind from each part, drawn for looking at.
            val perPart = HashMap<String, Int>()
            for (m in new.measures) {
                val kind = kindOf(m)
                if ((perPart[kind] ?: 0) >= 2 || (examples[kind]?.size ?: 0) >= 24) continue
                perPart[kind] = (perPart[kind] ?: 0) + 1
                examples.getOrPut(kind) { ArrayList() } += crop(ink, m, "${f.nameWithoutExtension} m${m.number}: ${m.events.joinToString(" ") { describe(it) }}")
            }
            for (m in new.measures) for (d in m.doubts) {
                val kind = when {
                    d.contains("beats found") -> {
                        val got = Regex("([0-9.]+) beats found, ([0-9.]+) expected").find(d)
                        val g = got?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0; val e = got?.groupValues?.get(2)?.toDoubleOrNull() ?: 0.0
                        when { g < e - 1.5 -> "far too few beats"; g < e -> "a little short"; g > e + 1.5 -> "far too many beats"; else -> "a little long" }
                    }
                    else -> d.replace(Regex("\\d+"), "#")
                }
                reasons[kind] = (reasons[kind] ?: 0) + 1
            }
        }
        System.getProperty("inksheets.shots")?.let { dir ->
            for ((kind, list) in examples) sheet(list, File(dir, "kind-" + kind.replace(Regex("[^a-z]+"), "-") + ".png"))
        }
        numberMisses.forEach { println("  numbers: $it") }
        println("BAR NUMBERS: $agreeingNumbers of $printedNumbers printed at line starts agree with the count")
        println("WHY: " + reasons.entries.sortedByDescending { it.value }.joinToString("; ") { "${it.key} ${it.value}" })
        println("ALL: $parts parts, $bars bars; sure ${sureOld * 100 / max(1, bars)}% -> ${sureNew * 100 / max(1, bars)}% ($sureOld -> $sureNew); better in $better, worse in $worse")
    }

    @Test
    fun `reads real parts`() {
        assumeTrue(System.getProperty("inksheets.omr") == "real")
        read("Imported/PEP BAND/Music/Sweet Caroline/SweetC - Trumpet 1.pdf", 2)
        read("Imported/PEP BAND/Music/September/September - Alto Sax 1.pdf")
        read("Imported/PEP BAND/Music/September/September - Tuba.pdf")
        music.resolve("MobileSheets").listFiles { f -> f.extension.equals("pdf", true) }?.sortedBy { it.name }?.take(3)?.forEach {
            read("MobileSheets/${it.name}")
        }
    }

    /** The one thing most wrong with [m]: what its bar is filed under for looking at. */
    private fun kindOf(m: com.inksheets.core.omr.Measure): String {
        if (m.sure) return "sure"
        val beats = m.doubts.firstOrNull { it.contains("beats found") }
        if (beats != null) {
            val got = Regex("([0-9.]+) beats found, ([0-9.]+) expected").find(beats)
            val g = got?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0; val e = got?.groupValues?.get(2)?.toDoubleOrNull() ?: 0.0
            return when { g < e - 1.5 -> "far too few"; g < e -> "a little short"; g > e + 1.5 -> "far too many"; else -> "a little long" }
        }
        if (m.doubts.any { it.contains("nothing read") }) return "nothing read"
        if (m.doubts.all { it.contains("unclear") }) return "only unclear"
        return "other"
    }

    /** [m]'s bar from the page with room round it, and [caption] under it. */
    private fun crop(ink: Ink, m: com.inksheets.core.omr.Measure, caption: String): BufferedImage {
        val sp = m.space
        val x0 = (m.box.left - sp).toInt().coerceAtLeast(0); val x1 = (m.box.right + sp).toInt().coerceAtMost(ink.width)
        val y0 = (m.box.top - sp * 5).toInt().coerceAtLeast(0); val y1 = (m.box.bottom + sp * 5).toInt().coerceAtMost(ink.height)
        val w = max(1, x1 - x0); val h = max(1, y1 - y0)
        val img = BufferedImage(max(w, 420), h + 30, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.WHITE; g.fillRect(0, 0, img.width, img.height)
        val argb = ink.argb()
        for (y in 0 until h) for (x in 0 until w) img.setRGB(x, y, argb[(y0 + y) * ink.width + x0 + x])
        g.color = Color(200, 60, 0); g.font = g.font.deriveFont(12f)
        caption.chunked(70).forEachIndexed { i, line -> if (i < 2) g.drawString(line, 3, h + 12 + 13 * i) }
        g.dispose()
        return img
    }

    /** [images] one under another, in two columns, into [out]. */
    private fun sheet(images: List<BufferedImage>, out: File) {
        if (images.isEmpty()) return
        val colW = images.maxOf { it.width }.coerceAtMost(900)
        val half = (images.size + 1) / 2
        val cols = listOf(images.take(half), images.drop(half))
        val height = cols.maxOf { c -> c.sumOf { it.height + 6 } }
        val img = BufferedImage(colW * 2 + 10, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(230, 230, 230); g.fillRect(0, 0, img.width, img.height)
        cols.forEachIndexed { c, list ->
            var y = 0
            for (im in list) { g.drawImage(im, c * (colW + 10), y, minOf(im.width, colW), im.height, null); y += im.height + 6 }
        }
        g.dispose()
        out.parentFile.mkdirs()
        ImageIO.write(img, "png", out)
    }
}