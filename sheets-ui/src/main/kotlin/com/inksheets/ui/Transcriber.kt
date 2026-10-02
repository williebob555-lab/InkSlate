package com.inksheets.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.inksheets.core.omr.Ink
import com.inksheets.core.omr.Measure
import com.inksheets.core.omr.Net
import com.inksheets.core.omr.Recognizer
import com.inksheets.core.omr.Score
import com.inksheets.core.omr.Scores
import java.io.File

/**
 * Reading a part's notes off its pages (experimental): each page drawn so a staff space is
 * about 18 pixels - where the reader does best - read, and the whole kept on this device, so a
 * part is read once and not every time it is wanted.
 */
internal object Transcriber {
    /** What it is doing: "Reading page 2 of 4..." - null when idle. */
    var busy by mutableStateOf<String?>(null)
        private set

    /** The part read last, for the notes panel. */
    var shown by mutableStateOf<Pair<File, Score>?>(null)

    /** Bumped whenever the reader reads better: what was read before is read again. */
    private const val READER = 12

    /**
     * Where [file]'s reading is kept: a page to a file, in the library's own `.inksheets/readings`
     * (so it travels to the other devices with the library, and two devices reading different pages
     * of one part add up), in a folder named by the file's contents (the same file is the same
     * wherever it sits). Off the library: this device's own folder.
     */
    private fun folder(state: SheetsState, file: File): File {
        val root = state.root?.takeIf { file.absolutePath.startsWith(it.absolutePath) }
        val base = if (root != null) File(root, ".inksheets/readings") else File(state.platform.localFolder, "readings")
        return File(base, "r$READER-${idOf(file)}")
    }

    private val ids = HashMap<String, String>()

    /** [file]'s contents in a word: its size and a checksum of every byte. */
    internal fun idOf(file: File): String = synchronized(ids) {
        ids.getOrPut("${file.absolutePath}|${file.length()}|${file.lastModified()}") {
            val crc = java.util.zip.CRC32()
            runCatching { file.inputStream().buffered().use { input -> val buf = ByteArray(1 shl 16); while (true) { val n = input.read(buf); if (n < 0) break; crc.update(buf, 0, n) } } }
            "${java.lang.Long.toHexString(file.length())}-${java.lang.Long.toHexString(crc.value)}"
        }
    }

    private fun pageFile(dir: File, page: Int) = File(dir, "p${page + 1}.json")

    /** What was read and when it was looked for: the folder looked at again only every few seconds (another device may add pages). */
    private class Known(val score: Score?, val at: Long, val signature: String)
    private val known = HashMap<String, Known>()

    /** [file]'s notes as far as they have been read - here or on another device; null if none of it has. */
    fun cached(state: SheetsState, file: File): Score? {
        val dir = folder(state, file)
        val now = System.currentTimeMillis()
        synchronized(known) { known[dir.path]?.let { if (now - it.at < 3000) return it.score } }
        val pages = dir.listFiles { f -> f.name.startsWith("p") && f.name.endsWith(".json") }.orEmpty()
        val signature = pages.sortedBy { it.name }.joinToString { "${it.name}:${it.length()}:${it.lastModified()}" }
        synchronized(known) { known[dir.path]?.takeIf { it.signature == signature }?.let { known[dir.path] = Known(it.score, now, signature); return it.score } }
        val parts = pages.mapNotNull { f -> runCatching { Scores.decode(f.readText()) }.getOrNull() }
        val score = if (parts.isEmpty()) null else {
            val count = parts.maxOf { it.pages }
            val read = parts.flatMap { it.readPages.orEmpty() }.distinct().sorted()
            val widths = List(count) { i -> parts.firstNotNullOfOrNull { it.pageWidths.getOrNull(i)?.takeIf { w -> w > 0 } } ?: 0 }
            Score(parts.flatMap { it.measures }.sortedWith(compareBy({ it.page }, { it.staff }, { it.box.left })), count, widths, read.takeIf { it.size < count })
        }
        synchronized(known) { known[dir.path] = Known(score, now, signature) }
        return score
    }

    /**
     * [part]'s own bars out of [whole] - the reading of the file it is in: its pages only (a part
     * in a band pack), numbered from 1; and how far its numbers were moved from the file's.
     */
    fun partOf(whole: Score, part: com.inksheets.core.Part?): Pair<Score, Int> {
        val first = part?.firstPage ?: return whole to 0
        val last = part.lastPage ?: Int.MAX_VALUE
        val bars = whole.measures.filter { it.page + 1 in first..last }
        val offset = (bars.firstOrNull()?.number ?: 1) - 1
        return whole.copy(measures = bars.map { it.copy(number = it.number - offset) }) to offset
    }

    /**
     * Read [file] (off the UI thread) - [again] even if it has been; [onDone] on the UI thread with
     * the notes, or null. With [pages] (0-based), only those - a page or two of a long book, for
     * the passage being worked on - added to whatever of the file has been read already.
     */
    fun read(state: SheetsState, file: File, again: Boolean = false, pages: Set<Int>? = null, onDone: (Score?) -> Unit) {
        if (busy != null) return
        if (again) {
            val dir = folder(state, file)
            if (pages == null) dir.listFiles().orEmpty().forEach { it.delete() } else pages.forEach { pageFile(dir, it).delete() }
            synchronized(known) { known.remove(dir.path) }
        }
        busy = "Getting ready..."
        Thread({
            val score = runCatching { readNow(state, file, pages) }
                .onFailure { state.platform.log("Reading ${file.name} failed: ${it.message}") }.getOrNull()
            state.platform.onMain {
                busy = null
                if (score != null) shown = file to score
                onDone(score)
            }
        }, "transcribe").apply { isDaemon = true; start() }
    }

    /** Left in a part's readings while every page of it is being read: a read cut off carries on ([resume]). */
    private fun wholeMark(dir: File) = File(dir, "whole")

    /**
     * Carry on reading [file] if a reading of every page was started and cut off part way (the
     * app closed) - from the page in front. The look at the folder is off the UI thread.
     */
    fun resume(state: SheetsState, file: File) {
        if (busy != null) return
        Thread({
            val dir = folder(state, file)
            if (!wholeMark(dir).isFile) return@Thread
            val c = cached(state, file)
            if (c != null && (0 until c.pages).all { c.hasRead(it) }) { wholeMark(dir).delete(); return@Thread }
            state.platform.onMain { if (busy == null && state.currentPath == file.absolutePath) read(state, file) { com.inkslate.core.Perform.marksChanged() } }
        }, "read-resume").apply { isDaemon = true; start() }
    }

    private fun readNow(state: SheetsState, file: File, only: Set<Int>? = null): Score? {
        val peek = state.platform.peek(file) ?: return null
        peek.use {
            val dir = folder(state, file)
            if (only == null) runCatching { dir.mkdirs(); wholeMark(dir).writeText("") }
            // What was read before (here or elsewhere): a whole read carries on where it left off.
            val before = cached(state, file)
            // A whole read starts at the page in front (a long book is opened at the study being
            // worked on) and goes on from there, coming back round to the pages before it last.
            val here = if (only == null && state.currentPath == file.absolutePath) state.pageShown.first.coerceIn(0, maxOf(0, peek.pageCount - 1)) else 0
            val todo = (only ?: (0 until peek.pageCount).filter { before == null || !before.hasRead(it) }.toSet()).filter { it in 0 until peek.pageCount }
                .sortedWith(compareBy({ it < here }, { it }))
            val carry = Recognizer.Carry()
            val measures = ArrayList<Measure>()
            // One width a page, whether read or not: a bar's box is on its page's scale.
            val widths = MutableList(peek.pageCount) { before?.pageWidths?.getOrNull(it) ?: 0 }
            var number = 1
            var last = -2
            val t0 = System.currentTimeMillis()
            for ((i, p) in todo.withIndex()) {
                state.platform.onMain { busy = if (only == null) "Reading page ${p + 1} of ${peek.pageCount}..." else "Reading page ${p + 1}${if (todo.size > 1) " (${i + 1} of ${todo.size})" else ""}..." }
                // A page following one just read, or read before, carries on from it: its clef, key,
                // time and bar numbers. One on its own takes its numbers from the print.
                if (p != last + 1) {
                    val prev = before?.measures?.filter { it.page == p - 1 }?.lastOrNull()
                    carry.alone = prev == null
                    if (prev != null) { carry.clef = prev.clef; carry.key = prev.key; carry.time = prev.time; number = prev.number + prev.bars }
                    else number = 1
                }
                last = p
                val pageStarted = System.currentTimeMillis()
                val first = inkOf(peek, p, 1600)?.second ?: continue
                val space = Recognizer().metrics(first)?.second
                val width = if (space == null || space <= 0f) 1600 else (1600 * 18f / space).toInt().coerceIn(1000, 5000)
                val (grey, ink) = inkOf(peek, p, width) ?: continue
                // A PDF that states its notes is read from them, exactly; a scan by the trained reader.
                val printed = runCatching { peek.printed(p) }.getOrNull()
                val reading = Recognizer().read(ink, p, number, carry, printed, grey = grey, net = if (printed == null) Net.shipped else null)
                measures += reading.measures
                // The width the page really came back at: a renderer may draw a small page narrower
                // than asked (a cap on how far it magnifies), and every bar is placed by it.
                widths[p] = ink.width
                reading.measures.lastOrNull()?.let { number = it.number + it.bars }
                pageTimes += System.currentTimeMillis() - pageStarted
                // Kept as soon as read: a reading stopped part way carries on from here, and the
                // pages read so far reach the other devices.
                runCatching {
                    dir.mkdirs()
                    val one = Score(reading.measures, peek.pageCount, List(peek.pageCount) { if (it == p) ink.width else 0 }, listOf(p))
                    val tmp = File(dir, "p${p + 1}.json.tmp"); tmp.writeText(Scores.encode(one))
                    val dest = pageFile(dir, p); dest.delete(); tmp.renameTo(dest)
                }
                synchronized(known) { known.remove(dir.path) }
            }
            val score = cached(state, file) ?: Score(measures, peek.pageCount, widths, todo)
            if (only == null) wholeMark(dir).delete()
            state.platform.log("Read ${file.name}${if (only != null) " pages ${todo.map { it + 1 }}" else ""}: ${measures.size} bars, ${measures.count { it.sure }} sure, in ${(System.currentTimeMillis() - t0) / 1000}s")
            return score
        }
    }

    /**
     * Bar [m] of [file] looked at again - its staff only, a little larger, smaller, higher and lower
     * ([deeper]: further still) - by the trained reader, off the UI thread: [onDone] on the UI thread
     * with each look's reading of the bar (none for a PDF that states its notes: read exactly already).
     */
    fun lookAgain(state: SheetsState, file: File, m: com.inksheets.core.omr.Measure, width: Int, deeper: Boolean,
                  /** Whether the look is still wanted when its turn comes (the user may have gone on). */
                  wanted: () -> Boolean = { true }, onDone: (List<com.inksheets.core.omr.Measure>) -> Unit) {
        looker.execute {
            // Gone on to another bar before this one's turn: not looked at at all.
            if (!wanted()) return@execute
            val found = runCatching {
                val peek = state.platform.peek(file) ?: return@runCatching emptyList()
                peek.use {
                    val net = Net.shipped
                    if (net == null || runCatching { peek.printed(m.page) }.getOrNull() != null) return@use emptyList()
                    val (grey, ink) = inkOf(peek, m.page, width) ?: return@use emptyList()
                    Recognizer().lookAgain(ink, grey, net, m, deeper)
                }
            }.onFailure { state.platform.log("Looking again at bar ${m.number} of ${file.name} failed: ${it.message}") }.getOrNull().orEmpty()
            state.platform.onMain { onDone(found) }
        }
    }

    /**
     * Bar [m] of [file] as printed, cut from its page drawn [width] wide (as it was read): the bar
     * and a space either side, three and a half over and under its staff - off the UI thread, [onDone] on it.
     */
    fun barPicture(state: SheetsState, file: File, m: com.inksheets.core.omr.Measure, width: Int,
                   wanted: () -> Boolean = { true }, onDone: (androidx.compose.ui.graphics.ImageBitmap?) -> Unit) {
        looker.execute {
            if (!wanted()) return@execute
            val picture = runCatching {
                state.platform.peek(file)?.use { peek ->
                    val page = peek.render(m.page, width) ?: return@use null
                    val sp = m.space
                    // A little of the bars either side, so a bar split or run together where it should not be shows as such.
                    val l = maxOf(0, (m.box.left - sp * 3).toInt()); val r = minOf(page.width, (m.box.right + sp * 3).toInt())
                    // As far up and down as the bar's ink goes - a note high on ledger lines, a slur over it -
                    // to the first clear gap, at most seven spaces from the staff.
                    val px = IntArray(page.width * page.height).also { page.readPixels(it) }
                    fun inkRow(y: Int): Boolean = y in 0 until page.height && (maxOf(0, m.box.left)..minOf(page.width - 1, m.box.right)).any { x ->
                        val c = px[y * page.width + x]; ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) < 3 * 140 }
                    fun reach(from: Int, step: Int): Int {
                        var y = from; var last = from; var gap = 0
                        while (kotlin.math.abs(y - from) < sp * 7 && gap < sp * 1.2f) { y += step; if (inkRow(y)) { last = y; gap = 0 } else gap++ }
                        return last
                    }
                    val t = maxOf(0, minOf(reach(m.box.top, -1), (m.box.top - sp * 2.5f).toInt()) - (sp * 0.6f).toInt())
                    val b = minOf(page.height, maxOf(reach(m.box.bottom, 1), (m.box.bottom + sp * 2.5f).toInt()) + (sp * 0.6f).toInt())
                    if (r - l < 4 || b - t < 4) return@use null
                    val out = androidx.compose.ui.graphics.ImageBitmap(r - l, b - t)
                    val canvas = androidx.compose.ui.graphics.Canvas(out)
                    canvas.drawImageRect(page,
                        androidx.compose.ui.unit.IntOffset(l, t), androidx.compose.ui.unit.IntSize(r - l, b - t),
                        androidx.compose.ui.unit.IntOffset.Zero, androidx.compose.ui.unit.IntSize(r - l, b - t), androidx.compose.ui.graphics.Paint())
                    // The bars either side dimmed: the bar asked about is what is left bright.
                    val dim = androidx.compose.ui.graphics.Paint().apply { color = androidx.compose.ui.graphics.Color(0xB0FFFFFF) }
                    val bl = (m.box.left - l).toFloat(); val br = (m.box.right - l).toFloat()
                    if (bl > 0) canvas.drawRect(0f, 0f, bl, (b - t).toFloat(), dim)
                    if (br < r - l) canvas.drawRect(br, 0f, (r - l).toFloat(), (b - t).toFloat(), dim)
                    out
                }
            }.getOrNull()
            state.platform.onMain { onDone(picture) }
        }
    }

    /**
     * What bar [m] of [file] really is - [events], as picked in Fix - written down for teaching the
     * trained reader: a line in the library's Training folder (which syncs, and is no song), one
     * file a device so two never write the same one.
     */
    fun recordFix(state: SheetsState, file: File, m: com.inksheets.core.omr.Measure, width: Int, events: List<com.inksheets.core.omr.Event>) {
        val root = state.root ?: return
        val rel = state.relative(file) ?: return
        looker.execute {
            runCatching {
                val dir = File(root, "${com.inksheets.core.LibraryScan.TRAINING}/Fixes").apply { mkdirs() }
                val line = buildString {
                    append("{\"file\":").append(kotlinx.serialization.json.JsonPrimitive(rel))
                    append(",\"id\":\"").append(idOf(file)).append('"')
                    append(",\"page\":").append(m.page).append(",\"staff\":").append(m.staff).append(",\"number\":").append(m.number)
                    append(",\"width\":").append(width)
                    append(",\"box\":[").append(m.box.left).append(',').append(m.box.top).append(',').append(m.box.right).append(',').append(m.box.bottom).append(']')
                    append(",\"events\":").append(com.inksheets.core.omr.Scores.encodeFixes(mapOf(m.number to events)))
                    append(",\"at\":").append(System.currentTimeMillis()).append('}')
                }
                File(dir, "fixes-${state.platform.deviceId}.jsonl").appendText(line + "\n")
            }.onFailure { state.platform.log("Keeping a fix for teaching failed: ${it.message}") }
        }
    }

    /** How long each page read on this device lately took, in ms (the last few dozen): shown so how fast it is can be seen. */
    val pageTimes: MutableList<Long> = java.util.Collections.synchronizedList(ArrayList())

    /** "About 2.4 s a page on this device", from the pages read lately; null before any. */
    fun speedSaid(): String? {
        val times = synchronized(pageTimes) { pageTimes.takeLast(30) }
        if (times.isEmpty()) return null
        return "About ${"%.1f".format(times.average() / 1000.0)} s a page on this device (${times.size} page${if (times.size == 1) "" else "s"}, slowest ${"%.1f".format(times.max() / 1000.0)} s)"
    }

    /**
     * Bar [m] of [file] is not one bar as read - its barlines found wrong (a stem taken for one, or one
     * missed) - written down beside the fixes, for finding and teaching barlines.
     */
    fun recordNotABar(state: SheetsState, file: File, m: com.inksheets.core.omr.Measure, width: Int) {
        val root = state.root ?: return
        val rel = state.relative(file) ?: return
        looker.execute {
            runCatching {
                val dir = File(root, "${com.inksheets.core.LibraryScan.TRAINING}/Fixes").apply { mkdirs() }
                val line = buildString {
                    append("{\"kind\":\"not one bar\",\"file\":").append(kotlinx.serialization.json.JsonPrimitive(rel))
                    append(",\"id\":\"").append(idOf(file)).append('"')
                    append(",\"page\":").append(m.page).append(",\"staff\":").append(m.staff).append(",\"number\":").append(m.number)
                    append(",\"width\":").append(width)
                    append(",\"box\":[").append(m.box.left).append(',').append(m.box.top).append(',').append(m.box.right).append(',').append(m.box.bottom).append(']')
                    append(",\"at\":").append(System.currentTimeMillis()).append('}')
                }
                File(dir, "fixes-${state.platform.deviceId}.jsonl").appendText(line + "\n")
            }.onFailure { state.platform.log("Keeping a barline mistake for teaching failed: ${it.message}") }
        }
    }

    /** One look at a time: a phone renders and reads one page at once, never a pile of them. */
    private val looker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "look-again").apply { isDaemon = true } }

    /** Page [page] drawn [width] wide: its grey levels, and in black and white. */
    private fun inkOf(peek: PagePeek, page: Int, width: Int): Pair<IntArray, Ink>? {
        val img = peek.render(page, width) ?: return null
        val px = IntArray(img.width * img.height)
        img.readPixels(px)
        return com.inksheets.core.omr.Strips.grey(px) to Ink.fromArgb(img.width, img.height, px)
    }
}
