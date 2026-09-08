package com.inkslate.pdf

import com.inkslate.data.EventLog
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
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
 * Reads the table of contents embedded in a PDF.
 *
 * Most textbooks carry a full chapter tree, and using it is the difference between navigating a
 * thousand-page book and scrolling through one. Everything here is defensive: outlines are
 * frequently malformed, and a broken entry should cost that one row, not the whole contents list.
 */
object PdfOutline {

    private const val MAX_ENTRIES = 4000
    private const val MAX_DEPTH = 6

    fun read(file: File): List<OutlineEntry> = runCatching {
        val started = System.currentTimeMillis()
        PDDocument.load(file).use { doc ->
            val root = doc.documentCatalog?.documentOutline
            if (root == null) {
                EventLog.info("outline", "${file.name}: no embedded table of contents")
                return emptyList()
            }
            val out = ArrayList<OutlineEntry>()
            walk(doc, root.firstChild, 0, out)
            EventLog.info(
                "outline",
                "${file.name}: ${out.size} entries in ${System.currentTimeMillis() - started}ms"
            )
            out
        }
    }.onFailure {
        EventLog.warn("outline", "${file.name}: could not read contents (${it.message})")
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

            val title = runCatching { item.title }.getOrNull()?.trim().orEmpty()
            if (title.isNotEmpty()) {
                out.add(OutlineEntry(title, resolvePage(doc, item), depth))
            }
            runCatching { walk(doc, item.firstChild, depth + 1, out) }
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
        // direct destination
        runCatching {
            (item.destination as? PDPageDestination)?.let { dest ->
                dest.retrievePageNumber().takeIf { it >= 0 }?.let { return it }
                dest.page?.let { page ->
                    val idx = doc.pages.indexOf(page)
                    if (idx >= 0) return idx
                }
            }
        }
        // destination behind a GoTo action
        runCatching {
            (item.action as? PDActionGoTo)?.destination?.let { d ->
                (d as? PDPageDestination)?.let { dest ->
                    dest.retrievePageNumber().takeIf { it >= 0 }?.let { return it }
                    dest.page?.let { page ->
                        val idx = doc.pages.indexOf(page)
                        if (idx >= 0) return idx
                    }
                }
            }
        }
        // named destination resolved through the catalog
        runCatching {
            item.findDestinationPage(doc)?.let { page ->
                val idx = doc.pages.indexOf(page)
                if (idx >= 0) return idx
            }
        }
        return -1
    }
}
