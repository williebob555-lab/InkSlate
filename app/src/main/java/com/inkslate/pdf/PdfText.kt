package com.inkslate.pdf

import android.graphics.RectF
import android.util.LruCache
import com.inkslate.data.EventLog
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File

/** One word on a page, with where it sits in display coordinates. */
data class TextRun(val text: String, val rect: RectF)

/** A search result: the page, the matched span, and where to draw attention. */
data class SearchHit(
    val page: Int,
    val snippet: String,
    val rects: List<RectF>
)

/**
 * Extracts text and its geometry from a PDF.
 *
 * Two features depend on this: finding a term in a long textbook, and making the highlighter
 * follow the actual lines of text instead of the accuracy of your hand.
 *
 * Coordinates come from [TextPosition.getXDirAdj]/[TextPosition.getYDirAdj], which are already
 * top-left origin and already account for page rotation - the same space the ink uses, so no
 * conversion is needed anywhere else.
 */
object PdfText {

    /** Words per page, cached. Extraction is expensive enough to be worth never repeating. */
    private val cache = object : LruCache<String, List<TextRun>>(120) {}

    private fun key(file: File, page: Int) = "${file.absolutePath}|${file.lastModified()}|$page"

    fun wordsOn(file: File, page: Int): List<TextRun> {
        cache.get(key(file, page))?.let { return it }
        val runs = runCatching {
            PDDocument.load(file).use { doc -> extract(doc, page) }
        }.onFailure {
            EventLog.warn("text", "${file.name} page ${page + 1}: ${it.message}")
        }.getOrDefault(emptyList())
        cache.put(key(file, page), runs)
        return runs
    }

    /** Extract from an already-open document, for bulk work like search. */
    private fun extract(doc: PDDocument, page: Int): List<TextRun> {
        if (page !in 0 until doc.numberOfPages) return emptyList()
        val stripper = WordStripper()
        stripper.startPage = page + 1
        stripper.endPage = page + 1
        stripper.getText(doc)     // side effect: fills stripper.words
        return stripper.words
    }

    /**
     * Search the whole document.
     *
     * Results are delivered through [onHit] as they are found rather than returned at the end,
     * because on a thousand-page book the first useful result arrives long before the last page
     * is read, and waiting for completion would make the feature feel broken.
     */
    suspend fun search(
        file: File,
        query: String,
        pageCount: Int,
        onProgress: suspend (page: Int) -> Unit,
        onHit: suspend (SearchHit) -> Unit
    ) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return

        runCatching {
            PDDocument.load(file).use { doc ->
                for (page in 0 until minOf(pageCount, doc.numberOfPages)) {
                    onProgress(page)
                    val words = cache.get(key(file, page)) ?: extract(doc, page).also {
                        cache.put(key(file, page), it)
                    }
                    if (words.isEmpty()) continue
                    findIn(words, needle, page).forEach { onHit(it) }
                }
            }
        }.onFailure { EventLog.warn("search", "${file.name}: ${it.message}") }
    }

    /**
     * Locate a phrase within one page's words.
     *
     * The page is rebuilt as a single string with an offset table back to the words, so a phrase
     * spanning several words - which is most of what anyone searches for - is matched as one
     * thing rather than word by word.
     */
    private fun findIn(words: List<TextRun>, needle: String, page: Int): List<SearchHit> {
        val builder = StringBuilder()
        val wordStart = IntArray(words.size)
        words.forEachIndexed { i, w ->
            wordStart[i] = builder.length
            builder.append(w.text.lowercase())
            builder.append(' ')
        }
        val haystack = builder.toString()

        val hits = ArrayList<SearchHit>()
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) break
            from = at + needle.length

            val firstWord = wordStart.indexOfLast { it <= at }.coerceAtLeast(0)
            val lastWord = wordStart.indexOfLast { it < at + needle.length }.coerceAtLeast(firstWord)

            val rects = (firstWord..lastWord).mapNotNull { words.getOrNull(it)?.rect }
            if (rects.isEmpty()) continue

            val snippetFrom = maxOf(0, firstWord - 4)
            val snippetTo = minOf(words.size - 1, lastWord + 5)
            val snippet = (snippetFrom..snippetTo)
                .mapNotNull { words.getOrNull(it)?.text }
                .joinToString(" ")

            hits.add(SearchHit(page, snippet, mergeByLine(rects)))
            if (hits.size > 40) break     // one page rarely needs more than this
        }
        return hits
    }

    /** Merge word rectangles that sit on the same line into one span. */
    fun mergeByLine(rects: List<RectF>): List<RectF> {
        if (rects.size <= 1) return rects
        val sorted = rects.sortedWith(compareBy({ it.top }, { it.left }))
        val out = ArrayList<RectF>()
        var current = RectF(sorted.first())
        for (r in sorted.drop(1)) {
            val sameLine = kotlin.math.abs(r.centerY() - current.centerY()) < current.height() * 0.7f
            val adjacent = r.left - current.right < current.height() * 1.5f
            if (sameLine && adjacent) current.union(r) else { out.add(current); current = RectF(r) }
        }
        out.add(current)
        return out
    }

    /**
     * Words whose rectangles are crossed by a freehand path, for snapping a highlighter to text.
     */
    fun wordsUnderPath(
        file: File,
        page: Int,
        points: List<Pair<Float, Float>>,
        tolerance: Float
    ): List<RectF> {
        if (points.isEmpty()) return emptyList()
        val words = wordsOn(file, page)
        if (words.isEmpty()) return emptyList()

        val touched = words.filter { w ->
            val probe = RectF(w.rect)
            probe.inset(-tolerance, -tolerance)
            points.any { (x, y) -> probe.contains(x, y) }
        }
        return mergeByLine(touched.map { it.rect })
    }

    fun clearCache() = cache.evictAll()

    /**
     * Collects words with their bounding boxes.
     *
     * [PDFTextStripper] hands over characters in runs; grouping them into words here keeps the
     * rest of the app working with something meaningful rather than per-glyph rectangles.
     */
    private class WordStripper : PDFTextStripper() {

        val words = ArrayList<TextRun>()
        private val pending = StringBuilder()
        private var box: RectF? = null

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            for (tp in textPositions) {
                val ch = tp.unicode ?: continue
                if (ch.isBlank()) { flush(); continue }

                val r = RectF(
                    tp.xDirAdj,
                    tp.yDirAdj - tp.heightDir,
                    tp.xDirAdj + tp.widthDirAdj,
                    tp.yDirAdj
                )
                pending.append(ch)
                box = box?.apply { union(r) } ?: RectF(r)
            }
            flush()
        }

        private fun flush() {
            val b = box
            val t = pending.toString()
            if (b != null && t.isNotBlank()) words.add(TextRun(t, RectF(b)))
            pending.setLength(0)
            box = null
        }
    }
}
