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
    private data class Read(val x: Float, val y: Float, val kind: Kind, val staff: Int)

    class Tally {
        var keyHeads = 0; var found = 0; var kindRight = 0; var readHeads = 0; var readRight = 0
        val confusion = HashMap<String, Int>()
        var keyRests = 0; var restsFound = 0; var restKindRight = 0; var readRests = 0; var readRestsRight = 0
        fun add(o: Tally) {
            o.confusion.forEach { (k, v) -> confusion[k] = (confusion[k] ?: 0) + v }
            keyHeads += o.keyHeads; found += o.found; kindRight += o.kindRight; readHeads += o.readHeads; readRight += o.readRight
            keyRests += o.keyRests; restsFound += o.restsFound; restKindRight += o.restKindRight; readRests += o.readRests; readRestsRight += o.readRestsRight
        }
        private fun pct(a: Int, b: Int) = if (b == 0) "-" else "%.1f%%".format(100.0 * a / b)
        override fun toString() = "heads found ${pct(found, keyHeads)} ($found/$keyHeads), kind right ${pct(kindRight, found)}, " +
            "real ${pct(readRight, readHeads)} ($readRight/$readHeads); rests found ${pct(restsFound, keyRests)} ($restsFound/$keyRests), " +
            "kind right ${pct(restKindRight, restsFound)}, real ${pct(readRestsRight, readRests)} ($readRestsRight/$readRests)"
    }

    private val heads = setOf(Kind.HEAD_BLACK, Kind.HEAD_HALF, Kind.HEAD_WHOLE)
    private val rests = setOf(Kind.REST_1, Kind.REST_2, Kind.REST_4, Kind.REST_8, Kind.REST_16)

    fun keyed(want: Int): List<File> {
        val all = music.walkTopDown().filter { it.isFile && it.extension.equals("pdf", true) && !it.name.contains("score", true) }.toList()
        return all.sortedBy { java.util.zip.CRC32().apply { update(it.relativeTo(music).path.lowercase().replace('\\', '/').toByteArray()) }.value }
            .asSequence().filter { f -> runCatching { AnswerKey.read(f, 0, 72f) != null }.getOrDefault(false) }.take(want).toList()
    }

    /** One page marked; misses and inventions drawn into [missed] and [invented]. */
    fun mark(ink: Ink, key: List<AnswerKey.Symbol>, reading: Recognizer.PageReading, label: String,
             missed: MutableList<BufferedImage>? = null, invented: MutableList<BufferedImage>? = null): Tally {
        val t = Tally()
        val sp = reading.space
        // Printed heads of full size only: cue and grace notes are smaller, and left to another day.
        val keyHeads = key.filter { it.kind in heads }
        val normal = keyHeads.map { it.width }.sorted().let { it[it.size / 2] }
        val printed = keyHeads.filter { it.width > normal * 0.8f && reading.staves.any { s -> it.x >= s.left - sp && it.x <= s.right && it.y > s.top - sp * 6 && it.y < s.bottom + sp * 6 } }
        val read = ArrayList<Read>()
        for (m in reading.measures) {
            val s = reading.staves[m.staff]
            for (e in m.events) if (e is Note) for (st in e.steps) {
                val kind = when (e.duration.base) { 1 -> Kind.HEAD_WHOLE; 2 -> Kind.HEAD_HALF; else -> Kind.HEAD_BLACK }
                read += Read(e.x, s.y(st, e.x.toInt()), kind, m.staff)
            }
        }
        val taken = BooleanArray(read.size)
        t.keyHeads = printed.size; t.readHeads = read.size
        for (h in printed) {
            // The nearest head read at the same pitch - a quarter of a space either way - and about the same place.
            val i = read.indices.filter { !taken[it] && abs(read[it].y - h.y) <= sp * 0.26f && abs(read[it].x - h.x) <= sp * 1.3f }
                .minByOrNull { abs(read[it].x - h.x) }
            if (i == null) { missed?.let { if (it.size < 40) it += crop(ink, h.x, h.y, sp, "$label missed ${h.kind}") }; continue }
            taken[i] = true
            t.found++
            if (read[i].kind == h.kind) t.kindRight++
            val c = "${h.kind}->${read[i].kind}"; t.confusion[c] = (t.confusion[c] ?: 0) + 1
        }
        t.readRight = taken.count { it }
        read.indices.filter { !taken[it] }.forEach { i -> invented?.let { if (it.size < 40) it += crop(ink, read[i].x, read[i].y, sp, "$label invented ${read[i].kind}") } }
        // Rests: the same, a little looser (a rest's origin is not its middle).
        val keyRests = key.filter { it.kind in rests && reading.staves.any { s -> it.y > s.top - sp * 2 && it.y < s.bottom + sp * 2 && it.x >= s.left } }
        val readRests = reading.measures.flatMap { m -> m.events.filterIsInstance<Rest>().filter { m.bars <= 1 }.map { r -> Triple(r, m.staff, m) } }
        val restTaken = BooleanArray(readRests.size)
        t.keyRests = keyRests.size; t.readRests = readRests.size
        for (r in keyRests) {
            val i = readRests.indices.filter { !restTaken[it] && abs(readRests[it].first.x - r.x) <= sp * 1.5f && reading.staves[readRests[it].second].let { s -> r.y > s.top - sp * 3 && r.y < s.bottom + sp * 3 } }
                .minByOrNull { abs(readRests[it].first.x - r.x) } ?: continue
            restTaken[i] = true
            t.restsFound++
            val want = when (r.kind) { Kind.REST_1 -> 1; Kind.REST_2 -> 2; Kind.REST_4 -> 4; Kind.REST_8 -> 8; else -> 16 }
            if (readRests[i].first.duration.base == want) t.restKindRight++
            val c = "${r.kind}->r${readRests[i].first.duration.base}"; t.confusion[c] = (t.confusion[c] ?: 0) + 1
        }
        t.readRestsRight = restTaken.count { it }
        return t
    }

    private fun crop(ink: Ink, x: Float, y: Float, sp: Float, caption: String): BufferedImage {
        val w = (sp * 8).toInt(); val h = (sp * 7).toInt()
        val x0 = (x - sp * 3).toInt(); val y0 = (y - sp * 3.5f).toInt()
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
        for (f in keyed(want)) {
            val (ink, dpi) = OmrRealPagesTest().renderAt(f, 0) ?: continue
            val raw = AnswerKey.read(f, 0, dpi) ?: continue
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
            // Each printed head missed: never found as a head at all, or found and lost after.
            if (System.getProperty("inksheets.omr.why") != null) {
                val (t0, _) = recognizer.metrics(ink)!!
                val clean = recognizer.withoutLines(ink, reading.staves, t0)
                val t = mark(ink, key, reading, "")
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
            val t = mark(ink, key, reading, f.nameWithoutExtension.take(24), if (shots != null) missed else null, if (shots != null) invented else null)
            all.add(t)
            println("${f.relativeTo(music).path}: $t")
        }
        println("OFFSETS: median ${offsets.sorted().getOrNull(offsets.size / 2)}")
        println("WHY MISSED: $why")
        println("NEVER FOUND: $never")
        println("ALL: $all")
        println("CONFUSION: " + all.confusion.entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" })
        if (shots != null) { sheet(missed, File(shots, "key-missed.png")); sheet(invented, File(shots, "key-invented.png")); sheet(pitchShots, File(shots, "key-pitch.png")); sheet(neverShots, File(shots, "key-never.png")) }
    }
}
