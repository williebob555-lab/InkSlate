package com.inkslate.core

import kotlin.math.abs

/** One word on a page, with where it sits in display coordinates. */
data class TextRun(val text: String, val box: Box)

/** A search result: the page, the matched span, and where to draw attention. */
data class SearchHit(val page: Int, val snippet: String, val boxes: List<Box>)

/**
 * Finding a phrase among a page's words, and turning the match into something to draw.
 *
 * Pulling the text out of a PDF is each platform's own job - the two builds use different PDFBox
 * versions with different APIs - but what counts as a match, which words a phrase spans and how
 * those become highlight rectangles is arithmetic, and it lives here so that searching the same
 * textbook on the tablet and on the laptop finds the same things in the same places.
 */
object TextSearch {

    /** One page rarely needs more than this many results to be useful. */
    private const val MAX_HITS_PER_PAGE = 40

    /**
     * Locate a phrase within one page's words.
     *
     * The page is rebuilt as a single string with an offset table back to the words, so a phrase
     * spanning several words - which is most of what anyone searches for - is matched as one
     * thing rather than word by word.
     */
    fun findIn(words: List<TextRun>, needle: String, page: Int): List<SearchHit> {
        if (needle.isEmpty() || words.isEmpty()) return emptyList()

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

            val boxes = (firstWord..lastWord).mapNotNull { words.getOrNull(it)?.box }
            if (boxes.isEmpty()) continue

            val snippetFrom = maxOf(0, firstWord - 4)
            val snippetTo = minOf(words.size - 1, lastWord + 5)
            val snippet = (snippetFrom..snippetTo)
                .mapNotNull { words.getOrNull(it)?.text }
                .joinToString(" ")

            hits.add(SearchHit(page, snippet, mergeByLine(boxes)))
            if (hits.size > MAX_HITS_PER_PAGE) break
        }
        return hits
    }

    /**
     * Merge word rectangles that sit on the same line into one span.
     *
     * A highlight drawn as one bar per word looks like a row of separate marks; drawn as one bar
     * per line it looks like a highlighter went across it, which is what it is meant to be.
     */
    fun mergeByLine(boxes: List<Box>): List<Box> {
        if (boxes.size <= 1) return boxes
        val sorted = boxes.sortedWith(compareBy({ it.top }, { it.left }))
        val out = ArrayList<Box>()
        var current = sorted.first()
        for (r in sorted.drop(1)) {
            val sameLine = abs(r.centerY - current.centerY) < current.height * 0.7f
            val adjacent = r.left - current.right < current.height * 1.5f
            if (sameLine && adjacent) current = current.union(r) else {
                out.add(current)
                current = r
            }
        }
        out.add(current)
        return out
    }

    /**
     * Words whose rectangles a freehand path crosses, for snapping a highlighter to text.
     *
     * The tolerance widens each word rather than the path, because a word is the thing being
     * aimed at: a stroke that clips the top of a line should take the whole line, and a stroke
     * that passes between two lines should take neither.
     */
    fun wordsUnderPath(
        words: List<TextRun>,
        points: List<Pair<Float, Float>>,
        tolerance: Float
    ): List<Box> {
        if (points.isEmpty() || words.isEmpty()) return emptyList()
        val touched = words.filter { w ->
            val probe = w.box.expanded(tolerance)
            points.any { (x, y) -> probe.contains(x, y) }
        }
        return mergeByLine(touched.map { it.box })
    }
}
