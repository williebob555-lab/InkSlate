package com.inksheets.desktop

import com.inksheets.core.omr.Event
import com.inksheets.core.omr.Measure
import com.inksheets.core.omr.Note
import com.inksheets.core.omr.Recognizer
import com.inksheets.core.omr.Rest
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * The reader checked against itself on real music: each part of a song read, and the song's full
 * score read, and each part's bars compared with its line in the score. Where two readings of
 * the same music agree, both are very likely right; the agreement is a floor on how well it reads.
 * Run by hand: -Dinksheets.omr=cross (and -Dinksheets.omr.songs=N for how many songs, default 12).
 */
class OmrScoreCrossTest {
    private val band = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets/Imported/PEP BAND/Music")

    /** Every page of [file] read: its measures, staff by staff, with the staves grouped into systems. */
    private class Reading(val staves: List<List<Measure>>, val systemOf: List<Int>, val pages: List<com.inksheets.core.omr.Ink>)

    private var galleryCount = 0

    /** A disagreement, saved to look at: the part's bar above the score's, each with its reading. */
    private fun gallery(label: String, a: Measure, pa: Reading, b: Measure, pb: Reading) {
        val dir = shots ?: return
        if (galleryCount >= 60) return
        fun crop(m: Measure, r: Reading): java.awt.image.BufferedImage? {
            val ink = r.pages.getOrNull(m.page) ?: return null
            val pad = (m.space * 4).toInt()
            val x0 = (m.box.left - pad / 2).coerceAtLeast(0); val x1 = (m.box.right + pad / 2).coerceAtMost(ink.width)
            val y0 = (m.box.top - pad).coerceAtLeast(0); val y1 = (m.box.bottom + pad).coerceAtMost(ink.height)
            if (x1 <= x0 || y1 <= y0) return null
            val img = java.awt.image.BufferedImage(x1 - x0, y1 - y0, java.awt.image.BufferedImage.TYPE_INT_RGB)
            for (y in y0 until y1) for (x in x0 until x1) img.setRGB(x - x0, y - y0, if (ink[x, y]) 0 else 0xFFFFFF)
            return img
        }
        val top = crop(a, pa) ?: return
        val bottom = crop(b, pb) ?: return
        val w = maxOf(top.width, bottom.width, 700); val h = top.height + bottom.height + 60
        val out = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.color = java.awt.Color.WHITE; g.fillRect(0, 0, w, h)
        g.drawImage(top, 0, 0, null)
        g.color = java.awt.Color(200, 0, 0); g.drawString("part: " + describe(a) + " " + a.doubts, 4, top.height + 14)
        g.drawImage(bottom, 0, top.height + 20, null)
        g.color = java.awt.Color(0, 0, 200); g.drawString("score: " + describe(b) + " " + b.doubts, 4, top.height + bottom.height + 40)
        g.dispose()
        File(dir, "gallery").mkdirs()
        ImageIO.write(out, "png", File(dir, "gallery/${"%02d".format(galleryCount++)}-${label.replace(Regex("[^A-Za-z0-9 -]"), "")}.png"))
    }

    private val shots = System.getProperty("inksheets.shots")

    private fun readAll(file: File, space: Float = 18f, picture: Boolean = false): Reading? = runCatching {
        Loader.loadPDF(file).use { doc ->
            val r = PDFRenderer(doc)
            val staves = ArrayList<List<Measure>>()
            val systemOf = ArrayList<Int>()
            val inks = ArrayList<com.inksheets.core.omr.Ink>()
            var system = 0
            for (p in 0 until doc.numberOfPages) {
                fun at(dpi: Float): com.inksheets.core.omr.Ink {
                    val img = r.renderImageWithDPI(p, dpi, ImageType.RGB)
                    val px = IntArray(img.width * img.height)
                    img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
                    return com.inksheets.core.omr.Ink.fromArgb(img.width, img.height, px)
                }
                val first = at(72f)
                val sp = Recognizer().metrics(first)?.second ?: continue
                val ink = at((72f * space / sp).coerceAtMost(400f))
                val reading = Recognizer().read(ink, p)
                while (inks.size < p) inks += com.inksheets.core.omr.Ink(1, 1)
                inks += ink
                if (picture && p == 0 && shots != null) OmrRealPagesTest().overlay(ink, reading, File(shots, "score-${file.nameWithoutExtension}-p1.png"))
                // Systems: the staves close together; a wider gap starts the next system.
                val tops = reading.staves.map { it.top }; val bottoms = reading.staves.map { it.bottom }
                val gaps = (1 until tops.size).map { (tops[it] - bottoms[it - 1]).toFloat() }
                val usual = gaps.sorted().getOrNull(gaps.size / 3) ?: 0f
                for ((si, _) in reading.staves.withIndex()) {
                    if (si > 0 && gaps[si - 1] > usual * 1.6f + reading.space) system++
                    if (si == 0 && p > 0) system++
                    staves += reading.measures.filter { it.staff == si }
                    systemOf += system
                }
            }
            Reading(staves, systemOf, inks)
        }
    }.onFailure { println("  cannot read ${file.name}: ${it.message}") }.getOrNull()

    /** A part's bars in order, multi-bar rests counted out as single empty bars, as a score writes them. */
    private fun bars(staves: List<List<Measure>>): List<Measure> = staves.flatten().flatMap { m ->
        if (m.bars > 1) List(m.bars) { m.copy(bars = 1) } else listOf(m)
    }

    /**
     * A part's line through the score: a full score leaves out instruments that rest, so the same
     * instrument is not the same staff in every system. System by system, the staff whose bars best
     * agree with the part's next bars. Returns the score's bars, one for each system's worth.
     */
    private fun line(part: List<Measure>, score: Reading): List<Measure> {
        val systems = score.staves.indices.groupBy { score.systemOf[it] }.toSortedMap().values.toList()
        val out = ArrayList<Measure>()
        var at = 0
        for (sys in systems) {
            if (at >= part.size) break
            val width = sys.maxOf { score.staves[it].size }
            if (width == 0) continue
            val ahead = part.subList(at, minOf(part.size, at + width))
            val best = sys.maxByOrNull { si ->
                val staff = score.staves[si]
                staff.indices.count { i -> i < ahead.size && sameRhythm(ahead[i], staff[i]) == 1.0 } + (if (staff.size == width) 0.1 else 0.0).toInt()
            } ?: continue
            out += score.staves[best]
            at += width
        }
        return out
    }

    private fun rhythm(e: Event) = (if (e is Rest) "r" else "n") + e.duration.base + ".".repeat(e.duration.dots)
    private fun lcs(a: List<String>, b: List<String>): Int {
        val t = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) t[i][j] = if (a[i] == b[j]) 1 + t[i + 1][j + 1] else maxOf(t[i + 1][j], t[i][j + 1])
        return t[0][0]
    }
    private fun sameRhythm(a: Measure, b: Measure): Double {
        val x = a.events.map(::rhythm); val y = b.events.map(::rhythm)
        if (x.isEmpty() && y.isEmpty()) return 1.0
        return lcs(x, y).toDouble() / maxOf(x.size, y.size)
    }

    /** The part's bars lined up with the line's (a bar may be missed or split on either side). */
    private fun align(part: List<Measure>, line: List<Measure>): List<Pair<Int, Int>> {
        val n = part.size; val m = line.size
        val cost = Array(n + 1) { DoubleArray(m + 1) { Double.MAX_VALUE / 4 } }
        val back = Array(n + 1) { IntArray(m + 1) }
        cost[0][0] = 0.0
        for (i in 0..n) for (j in 0..m) {
            if (i == 0 && j == 0) continue
            var best = Double.MAX_VALUE; var how = 0
            if (i > 0 && j > 0) { val c = cost[i - 1][j - 1] + (1 - sameRhythm(part[i - 1], line[j - 1])); if (c < best) { best = c; how = 0 } }
            if (i > 0) { val c = cost[i - 1][j] + 1.0; if (c < best) { best = c; how = 1 } }
            if (j > 0) { val c = cost[i][j - 1] + 1.0; if (c < best) { best = c; how = 2 } }
            cost[i][j] = best; back[i][j] = how
        }
        val pairs = ArrayList<Pair<Int, Int>>()
        var i = n; var j = m
        while (i > 0 || j > 0) {
            when (back[i][j]) { 0 -> { pairs += (i - 1) to (j - 1); i--; j-- }; 1 -> i--; else -> j-- }
        }
        return pairs.reversed()
    }

    private fun describe(m: Measure) = m.events.joinToString(" ") { e ->
        when (e) { is Note -> e.pitches.joinToString("+") + "/" + e.duration.base + ".".repeat(e.duration.dots); is Rest -> "r/" + e.duration.base }
    }

    @Test
    fun `parts agree with their scores`() {
        assumeTrue(System.getProperty("inksheets.omr") == "cross")
        val songs = (System.getProperty("inksheets.omr.songs") ?: "12").toInt()
        val folders = band.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }.orEmpty()
            .filter { d -> d.listFiles()?.any { it.name.contains("score", true) && it.extension.equals("pdf", true) } == true }
            .take(songs)
        var allBars = 0; var allRhythm = 0; var allPitchNotes = 0; var allPitchRight = 0; var parts = 0
        for (dir in folders) {
            val scoreFile = dir.listFiles()!!.first { it.name.contains("score", true) && it.extension.equals("pdf", true) && !it.name.contains("parts", true) }
            val t0 = System.currentTimeMillis()
            val score = readAll(scoreFile, picture = true) ?: continue
            val systems = score.systemOf.distinct().size
            println("${dir.name}: score ${score.staves.size} staves in $systems systems, ${score.staves.sumOf { it.size }} bars in all (${(System.currentTimeMillis() - t0) / 1000}s)")
            val partFiles = dir.listFiles()!!.filter { it.extension.equals("pdf", true) && !it.name.contains("score", true) }.sortedBy { it.name }
            for (pf in partFiles) {
                val part = readAll(pf) ?: continue
                val pb = bars(part.staves)
                if (pb.size < 4) continue
                val line = line(pb, score)
                val k = 0
                val pairs = align(pb, line)
                val same = pairs.count { (a, b) -> sameRhythm(pb[a], line[b]) == 1.0 }
                // Pitch: the score may be in concert pitch; the usual difference is the transposition.
                val diffs = ArrayList<Int>()
                for ((a, b) in pairs) if (sameRhythm(pb[a], line[b]) == 1.0) {
                    pb[a].events.zip(line[b].events).forEach { (x, y) -> if (x is Note && y is Note) diffs += x.pitches.last().midi - y.pitches.last().midi }
                }
                val offset = diffs.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 0
                val right = diffs.count { it == offset }
                parts++; allBars += pb.size; allRhythm += same; allPitchNotes += diffs.size; allPitchRight += right
                println("   ${pf.name}: ${pb.size} bars, line ${k + 1}; rhythm agrees in $same of ${pairs.size} bars lined up " +
                    "(${if (pairs.isEmpty()) 0 else same * 100 / pairs.size}%); pitch in $right of ${diffs.size} notes (${if (diffs.isEmpty()) 0 else right * 100 / diffs.size}%, offset $offset)")
                // A few disagreements, to look at.
                pairs.filter { (a, b) -> sameRhythm(pb[a], line[b]) < 1.0 }.take(3).forEach { (a, b) ->
                    println("      part bar ${pb[a].number} p${pb[a].page + 1} [${describe(pb[a])}] ${pb[a].doubts}  vs score [${describe(line[b])}] ${line[b].doubts}")
                }
                // Near misses are the useful ones to look at: the bars mostly agree.
                pairs.filter { (a, b) -> sameRhythm(pb[a], line[b]).let { it in 0.5..0.99 } }.take(2).forEach { (a, b) ->
                    gallery("${dir.name} ${pf.nameWithoutExtension} b${pb[a].number}", pb[a], part, line[b], score)
                }
            }
        }
        println("ALL: $parts parts, $allBars bars; rhythm agrees in ${if (allBars == 0) 0 else allRhythm * 100 / allBars}% of bars; " +
            "pitch in ${if (allPitchNotes == 0) 0 else allPitchRight * 100 / allPitchNotes}% of notes in agreeing bars")
    }
}
