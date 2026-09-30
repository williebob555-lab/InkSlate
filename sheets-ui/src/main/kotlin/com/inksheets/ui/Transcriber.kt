package com.inksheets.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.inksheets.core.omr.Ink
import com.inksheets.core.omr.Measure
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

    private val cache = HashMap<String, Score>()

    /** Bumped whenever the reader reads better: what was read before is read again. */
    private const val READER = 4

    private fun key(file: File) = "${file.absolutePath}|${file.length()}|${file.lastModified()}|r$READER"
    private fun stored(state: SheetsState, file: File) =
        File(File(state.platform.localFolder, "scores"), Integer.toHexString(key(file).hashCode()) + ".json")

    /** [file]'s notes if they have been read on this device (and the file is unchanged since). */
    fun cached(state: SheetsState, file: File): Score? = synchronized(cache) {
        cache[key(file)] ?: runCatching { stored(state, file).takeIf { it.isFile }?.readText()?.let(Scores::decode) }.getOrNull()
            ?.also { cache[key(file)] = it }
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
        if (again) synchronized(cache) { cache.remove(key(file)); stored(state, file).delete() }
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
            // What of the file was read before, kept - unless the whole of it is being read now.
            val before = if (only == null) null else cached(state, file)
            val todo = (only ?: (0 until peek.pageCount).toSet()).filter { it in 0 until peek.pageCount }.sorted()
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
                val first = inkOf(peek, p, 1600) ?: continue
                val space = Recognizer().metrics(first)?.second
                val width = if (space == null || space <= 0f) 1600 else (1600 * 18f / space).toInt().coerceIn(1000, 5000)
                val ink = if (width == 1600) first else inkOf(peek, p, width) ?: continue
                // A PDF that states its notes is read from them, exactly; a scan from its picture.
                val printed = runCatching { peek.printed(p) }.getOrNull()
                val reading = Recognizer().read(ink, p, number, carry, printed)
                measures += reading.measures
                widths[p] = width
                reading.measures.lastOrNull()?.let { number = it.number + it.bars }
            }
            val read = if (only == null) null else ((before?.readPages ?: if (before != null) (0 until peek.pageCount).toList() else emptyList()) + todo).distinct().sorted()
            val kept = before?.measures?.filter { it.page !in todo }.orEmpty()
            val all = (kept + measures).sortedWith(compareBy({ it.page }, { it.staff }, { it.box.left }))
            val score = Score(all, peek.pageCount, widths, read?.takeIf { it.size < peek.pageCount })
            state.platform.log("Read ${file.name}${if (only != null) " pages ${todo.map { it + 1 }}" else ""}: ${measures.size} bars, ${measures.count { it.sure }} sure, in ${(System.currentTimeMillis() - t0) / 1000}s")
            synchronized(cache) { cache[key(file)] = score }
            runCatching { stored(state, file).apply { parentFile.mkdirs() }.writeText(Scores.encode(score)) }
            return score
        }
    }

    private fun inkOf(peek: PagePeek, page: Int, width: Int): Ink? {
        val img = peek.render(page, width) ?: return null
        val px = IntArray(img.width * img.height)
        img.readPixels(px)
        return Ink.fromArgb(img.width, img.height, px)
    }
}
