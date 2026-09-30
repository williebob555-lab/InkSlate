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

    private fun key(file: File) = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
    private fun stored(state: SheetsState, file: File) =
        File(File(state.platform.localFolder, "scores"), Integer.toHexString(key(file).hashCode()) + ".json")

    /** [file]'s notes if they have been read on this device (and the file is unchanged since). */
    fun cached(state: SheetsState, file: File): Score? = synchronized(cache) {
        cache[key(file)] ?: runCatching { stored(state, file).takeIf { it.isFile }?.readText()?.let(Scores::decode) }.getOrNull()
            ?.also { cache[key(file)] = it }
    }

    /** Read [file] (off the UI thread); [onDone] on the UI thread with the notes, or null. */
    fun read(state: SheetsState, file: File, onDone: (Score?) -> Unit) {
        if (busy != null) return
        busy = "Getting ready..."
        Thread({
            val score = runCatching { readNow(state, file) }
                .onFailure { state.platform.log("Reading ${file.name} failed: ${it.message}") }.getOrNull()
            state.platform.onMain {
                busy = null
                if (score != null) shown = file to score
                onDone(score)
            }
        }, "transcribe").apply { isDaemon = true; start() }
    }

    private fun readNow(state: SheetsState, file: File): Score? {
        val peek = state.platform.peek(file) ?: return null
        peek.use {
            val carry = Recognizer.Carry()
            val measures = ArrayList<Measure>()
            val widths = ArrayList<Int>()
            var number = 1
            val t0 = System.currentTimeMillis()
            for (p in 0 until peek.pageCount) {
                state.platform.onMain { busy = "Reading page ${p + 1} of ${peek.pageCount}..." }
                val first = inkOf(peek, p, 1600) ?: continue
                val space = Recognizer().metrics(first)?.second
                val width = if (space == null || space <= 0f) 1600 else (1600 * 18f / space).toInt().coerceIn(1000, 5000)
                val ink = if (width == 1600) first else inkOf(peek, p, width) ?: continue
                val reading = Recognizer().read(ink, p, number, carry)
                measures += reading.measures
                widths += width
                reading.measures.lastOrNull()?.let { number = it.number + it.bars }
            }
            val score = Score(measures, peek.pageCount, widths)
            state.platform.log("Read ${file.name}: ${measures.size} bars, ${measures.count { it.sure }} sure, in ${(System.currentTimeMillis() - t0) / 1000}s")
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
