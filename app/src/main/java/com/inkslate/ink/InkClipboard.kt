package com.inkslate.ink

import android.graphics.RectF

/**
 * Process-wide clipboard for drawn objects.
 *
 * Lives outside any one document so a diagram can be lifted from one assignment and dropped into
 * another, which is most of the point: redrawing the same axes or the same circuit symbol every
 * week is exactly the busywork this app is supposed to remove.
 *
 * Contents survive page changes and document changes, but not app restarts - a persistent
 * clipboard would be surprising, and stale content is worse than none.
 */
object InkClipboard {

    private var contents: List<Stroke> = emptyList()

    /** Page size the objects were copied from, so a paste onto a smaller page can be scaled. */
    private var sourcePageWidth: Float = 0f
    private var sourcePageHeight: Float = 0f

    val isEmpty: Boolean get() = contents.isEmpty()
    val count: Int get() = contents.size

    fun put(strokes: List<Stroke>, pageWidth: Float, pageHeight: Float) {
        contents = strokes.map { it }        // Strokes are immutable data, so no deep copy needed
        sourcePageWidth = pageWidth
        sourcePageHeight = pageHeight
    }

    fun clear() {
        contents = emptyList()
    }

    /** Bounding box of the clipboard contents, in their original page coordinates. */
    fun bounds(): RectF? {
        if (contents.isEmpty()) return null
        val r = RectF(contents.first().bounds())
        contents.drop(1).forEach { r.union(it.bounds()) }
        return r
    }

    /**
     * Objects ready to drop onto a page of the given size, centred on ([atX], [atY]).
     *
     * If the copied material is wider or taller than the destination page it is scaled down to
     * fit, rather than pasting something partly off the paper where it cannot be saved.
     */
    fun paste(
        ids: com.inkslate.data.StrokeIdGen,
        atX: Float,
        atY: Float,
        pageWidth: Float,
        pageHeight: Float
    ): List<Stroke> {
        val src = bounds() ?: return emptyList()
        val now = System.currentTimeMillis()

        val fitScale = minOf(
            1f,
            (pageWidth * 0.9f) / src.width().coerceAtLeast(0.01f),
            (pageHeight * 0.9f) / src.height().coerceAtLeast(0.01f)
        )

        val m = android.graphics.Matrix().apply {
            postTranslate(-src.centerX(), -src.centerY())
            if (fitScale < 1f) postScale(fitScale, fitScale)
            postTranslate(atX, atY)
        }

        return contents.map { it.transformed(m, ids.next()).copy(updatedUtc = now) }
    }
}
