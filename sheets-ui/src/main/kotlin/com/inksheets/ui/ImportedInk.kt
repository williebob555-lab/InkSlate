package com.inksheets.ui

import com.inkslate.core.InkPoint
import com.inkslate.core.Stroke
import com.inksheets.core.ImportedMark

/**
 * Markings brought across from another app, turned into strokes on pages of the given sizes.
 *
 * Every stroke is stamped as made at the beginning of time: an erase or an edit made here is
 * always newer, so it wins over the imported original whenever the two meet in a merge.
 */
internal object ImportedInk {

    fun strokes(marks: List<ImportedMark>, pageSize: (Int) -> Pair<Float, Float>?): List<Stroke> =
        marks.mapNotNull { m ->
            val (w, h) = pageSize(m.page) ?: return@mapNotNull null
            if (w <= 0f || h <= 0f) return@mapNotNull null
            val width = (m.width * w).coerceAtLeast(0.6f)
            val xy = m.points.chunked(2).filter { it.size == 2 }.map { (x, y) -> InkPoint(x * w, y * h, width) }
            if (xy.isEmpty()) return@mapNotNull null
            when (m.kind) {
                ImportedMark.Kind.TEXT -> {
                    val corner = xy.first()
                    val far = xy.getOrNull(1) ?: corner
                    Stroke(
                        id = m.id, kind = Stroke.Kind.TEXT, color = m.color, baseWidth = 1f,
                        points = listOf(InkPoint(corner.x, corner.y, 1f)),
                        opacity = m.opacity, text = m.text,
                        textSize = (m.textSize * w).coerceIn(4f, 200f),
                        boxWidth = (far.x - corner.x).coerceAtLeast(0f),
                        boxHeight = (far.y - corner.y).coerceAtLeast(0f),
                        pageIndex = m.page, updatedUtc = ORIGIN
                    )
                }
                ImportedMark.Kind.LINE -> if (xy.size < 2) null else Stroke(
                    id = m.id, kind = Stroke.Kind.LINE, color = m.color, baseWidth = width,
                    points = xy.take(2), opacity = m.opacity, pageIndex = m.page, updatedUtc = ORIGIN
                )
                ImportedMark.Kind.PEN -> Stroke(
                    id = m.id, kind = Stroke.Kind.FREEHAND, color = m.color, baseWidth = width,
                    points = if (xy.size == 1) xy + xy else xy,
                    opacity = m.opacity, pageIndex = m.page, updatedUtc = ORIGIN
                )
            }
        }

    /** Older than anything done here. */
    private const val ORIGIN = 1L
}

/** [ImportedInk] for tests in other modules. */
object ImportedInkForTests {
    fun strokes(marks: List<com.inksheets.core.ImportedMark>, pageSize: (Int) -> Pair<Float, Float>?) = ImportedInk.strokes(marks, pageSize)
}
