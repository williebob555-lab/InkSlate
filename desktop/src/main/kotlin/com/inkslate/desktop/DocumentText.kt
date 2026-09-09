package com.inkslate.desktop

import com.inkslate.core.Box
import com.inkslate.core.SearchHit
import com.inkslate.core.TextRun
import com.inkslate.core.TextSearch
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.io.File

/** One entry in a document's table of contents. */
data class OutlineEntry(
    val title: String,
    /** Zero-based page index, or -1 when the destination could not be resolved. */
    val pageIndex: Int,
    val depth: Int
) {
    val isResolvable: Boolean get() = pageIndex >= 0
}

/**
 * The text inside a PDF: its table of contents, its words, and where they sit.
 *
 * The Android `PdfOutline` and `PdfText` against full PDFBox. What counts as a match and how a
 * match becomes a highlight bar is `core/TextSearch`, shared - only pulling the characters out of
 * the file is written twice, because the two builds use PDFBox versions with different APIs.
 */
object DocumentText {

    private const val MAX_ENTRIES = 4000
    private const val MAX_DEPTH = 6

    // ---- the table of contents -----------------------------------------------

    /**
     * Reads the table of contents embedded in a PDF.
     *
     * Most textbooks carry a full chapter tree, and using it is the difference between navigating
     * a thousand-page book and scrolling through one. Everything here is defensive: outlines are
     * frequently malformed, and a broken entry should cost that one row, not the whole list.
     */
    fun outline(file: File): List<OutlineEntry> = runCatching {
        Loader.loadPDF(file).use { doc ->
            val root = doc.documentCatalog?.documentOutline ?: return emptyList()
            val out = ArrayList<OutlineEntry>()
            walk(doc, root.firstChild, 0, out)
            out
        }
    }.getOrDefault(emptyList())

    private fun walk(
        doc: PDDocument,
        first: PDOutlineItem?,
        depth: Int,
        out: MutableList<OutlineEntry>
    ) {
        if (depth > MAX_DEPTH) return
        var item = first
        // Guard against malformed outlines whose sibling chain loops back on itself, which would
        // otherwise spin forever on a file the user simply wanted to read.
        val seen = HashSet<Int>()
        while (item != null && out.size < MAX_ENTRIES) {
            if (!seen.add(System.identityHashCode(item.cosObject))) break
            val title = runCatching { item?.title }.getOrNull()?.trim().orEmpty()
            if (title.isNotEmpty()) out.add(OutlineEntry(title, resolvePage(doc, item!!), depth))
            runCatching { walk(doc, item?.firstChild, depth + 1, out) }
            item = runCatching { item?.nextSibling }.getOrNull()
        }
    }

    /**
     * Resolve an outline item to a page index.
     *
     * Entries point at their target in several different ways depending on the producer, so each
     * form is tried in turn rather than assuming the common one.
     */
    private fun resolvePage(doc: PDDocument, item: PDOutlineItem): Int {
        runCatching {
            (item.destination as? PDPageDestination)?.let { dest ->
                dest.retrievePageNumber().takeIf { it >= 0 }?.let { return it }
                dest.page?.let { page ->
                    val idx = doc.pages.indexOf(page)
                    if (idx >= 0) return idx
                }
            }
        }
        runCatching {
            ((item.action as? PDActionGoTo)?.destination as? PDPageDestination)?.let { dest ->
                dest.retrievePageNumber().takeIf { it >= 0 }?.let { return it }
                dest.page?.let { page ->
                    val idx = doc.pages.indexOf(page)
                    if (idx >= 0) return idx
                }
            }
        }
        runCatching {
            item.findDestinationPage(doc)?.let { page ->
                val idx = doc.pages.indexOf(page)
                if (idx >= 0) return idx
            }
        }
        return -1
    }

    // ---- words ---------------------------------------------------------------

    /** Words per page, cached. Extraction is expensive enough to be worth never repeating. */
    private val cache = object : LinkedHashMap<String, List<TextRun>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, List<TextRun>>) = size > 120
    }

    private fun key(file: File, page: Int) = "${file.absolutePath}|${file.lastModified()}|$page"

    fun wordsOn(file: File, page: Int): List<TextRun> {
        synchronized(cache) { cache[key(file, page)] }?.let { return it }
        val runs = runCatching {
            Loader.loadPDF(file).use { extract(it, page) }
        }.getOrDefault(emptyList())
        synchronized(cache) { cache[key(file, page)] = runs }
        return runs
    }

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
            Loader.loadPDF(file).use { doc ->
                for (page in 0 until minOf(pageCount, doc.numberOfPages)) {
                    onProgress(page)
                    val words = synchronized(cache) { cache[key(file, page)] }
                        ?: extract(doc, page).also {
                            synchronized(cache) { cache[key(file, page)] = it }
                        }
                    if (words.isEmpty()) continue
                    TextSearch.findIn(words, needle, page).forEach { onHit(it) }
                }
            }
        }
    }

    /** Words a freehand path crosses, for snapping a highlighter to the document's own lines. */
    fun wordsUnderPath(
        file: File,
        page: Int,
        points: List<Pair<Float, Float>>,
        tolerance: Float
    ): List<Box> = TextSearch.wordsUnderPath(wordsOn(file, page), points, tolerance)

    fun clearCache() = synchronized(cache) { cache.clear() }

    /**
     * Collects words with their bounding boxes.
     *
     * [PDFTextStripper] hands over characters in runs; grouping them into words here keeps the
     * rest of the app working with something meaningful rather than per-glyph rectangles.
     *
     * The coordinates come from the `DirAdj` accessors, which are already top-left origin and
     * already account for page rotation - the same space the ink uses, so nothing else has to
     * convert.
     */
    private class WordStripper : PDFTextStripper() {

        val words = ArrayList<TextRun>()
        private val pending = StringBuilder()
        private var box: Box? = null

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            for (tp in textPositions) {
                val ch = tp.unicode ?: continue
                if (ch.isBlank()) { flush(); continue }
                val r = Box(
                    tp.xDirAdj,
                    tp.yDirAdj - tp.heightDir,
                    tp.xDirAdj + tp.widthDirAdj,
                    tp.yDirAdj
                )
                pending.append(ch)
                box = box?.union(r) ?: r
            }
            flush()
        }

        private fun flush() {
            val b = box
            val t = pending.toString()
            if (b != null && t.isNotBlank()) words.add(TextRun(t, b))
            pending.setLength(0)
            box = null
        }
    }
}
