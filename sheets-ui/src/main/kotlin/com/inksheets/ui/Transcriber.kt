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
    private const val READER = 8

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
    private fun idOf(file: File): String = synchronized(ids) {
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

    private fun readNow(state: SheetsState, file: File, only: Set<Int>? = null): Score? {
        val peek = state.platform.peek(file) ?: return null
        peek.use {
            val dir = folder(state, file)
            // What was read before (here or elsewhere): a whole read carries on where it left off.
            val before = cached(state, file)
            val todo = (only ?: (0 until peek.pageCount).filter { before == null || !before.hasRead(it) }.toSet()).filter { it in 0 until peek.pageCount }.sorted()
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
            state.platform.log("Read ${file.name}${if (only != null) " pages ${todo.map { it + 1 }}" else ""}: ${measures.size} bars, ${measures.count { it.sure }} sure, in ${(System.currentTimeMillis() - t0) / 1000}s")
            return score
        }
    }

    /** Page [page] drawn [width] wide: its grey levels, and in black and white. */
    private fun inkOf(peek: PagePeek, page: Int, width: Int): Pair<IntArray, Ink>? {
        val img = peek.render(page, width) ?: return null
        val px = IntArray(img.width * img.height)
        img.readPixels(px)
        return com.inksheets.core.omr.Strips.grey(px) to Ink.fromArgb(img.width, img.height, px)
    }
}
