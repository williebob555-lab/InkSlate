package com.inkslate.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A topic row whose columns are independent of every other row. */
@Serializable
data class NoteGridRow(
    @SerialName("height") val height: Float = 220f,
    @SerialName("columns") val columns: List<Float> = listOf(360f)
)

/**
 * Structure for the Note Grid canvas preset.
 *
 * Rows own their columns, so a three-column topic can sit above a two-column topic without the
 * false alignment constraints of a table. The lines are derived data; only these dimensions are
 * saved. [accommodate] grows the cell containing a new mark and describes how later ink must move.
 */
@Serializable
data class NoteGrid(
    @SerialName("rows") val rows: List<NoteGridRow> = listOf(NoteGridRow()),
    @SerialName("gap") val contentGap: Float = 28f,
    @SerialName("newColumnWidth") val newColumnWidth: Float = 360f,
    @SerialName("newRowHeight") val newRowHeight: Float = 220f
) {
    data class Reflow(
        val grid: NoteGrid,
        val row: Int,
        val cell: Int,
        val oldCellRight: Float,
        val oldRowTop: Float,
        val oldRowBottom: Float,
        val shiftRight: Float,
        val shiftDown: Float
    ) {
        fun shiftFor(x: Float, y: Float): Pair<Float, Float> {
            val dx = if (y >= oldRowTop && y < oldRowBottom && x >= oldCellRight) shiftRight else 0f
            val dy = if (y >= oldRowBottom) shiftDown else 0f
            return dx to dy
        }
    }

    data class Segment(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

    /** Commutative merge: neither device can make a row or cell smaller on the other. */
    fun mergeWith(other: NoteGrid): NoteGrid {
        val count = maxOf(rows.size, other.rows.size)
        return NoteGrid(List(count) { rowIndex ->
            val a = rows.getOrNull(rowIndex)
            val b = other.rows.getOrNull(rowIndex)
            when {
                a == null -> b!!
                b == null -> a
                else -> NoteGridRow(
                    height = maxOf(a.height, b.height),
                    columns = List(maxOf(a.columns.size, b.columns.size)) { column ->
                        maxOf(
                            a.columns.getOrElse(column) { 0f },
                            b.columns.getOrElse(column) { 0f }
                        )
                    }
                )
            }
        })
    }

    fun accommodate(anchorX: Float, anchorY: Float, content: Box): Reflow {
        val working = rows.ifEmpty { listOf(NoteGridRow(newRowHeight, listOf(newColumnWidth))) }
            .toMutableList()

        var totalHeight = working.sumOf { it.height.toDouble() }.toFloat()
        while (anchorY >= totalHeight) {
            working += NoteGridRow(newRowHeight, listOf(newColumnWidth))
            totalHeight += newRowHeight
        }
        var rowTop = 0f
        var rowIndex = 0
        while (rowIndex < working.lastIndex && anchorY >= rowTop + working[rowIndex].height) {
            rowTop += working[rowIndex].height
            rowIndex++
        }

        val row = working[rowIndex]
        val columns = row.columns.ifEmpty { listOf(newColumnWidth) }.toMutableList()
        var totalWidth = columns.sum()
        while (anchorX >= totalWidth) {
            columns += newColumnWidth
            totalWidth += newColumnWidth
        }
        var cellLeft = 0f
        var cellIndex = 0
        while (cellIndex < columns.lastIndex && anchorX >= cellLeft + columns[cellIndex]) {
            cellLeft += columns[cellIndex]
            cellIndex++
        }

        val oldRight = cellLeft + columns[cellIndex]
        val oldBottom = rowTop + row.height
        val growRight = (content.right + contentGap - oldRight).coerceAtLeast(0f)
        val growDown = (content.bottom + contentGap - oldBottom).coerceAtLeast(0f)
        columns[cellIndex] += growRight
        working[rowIndex] = row.copy(
            height = row.height + growDown,
            columns = columns
        )
        return Reflow(
            copy(rows = working), rowIndex, cellIndex, oldRight, rowTop, oldBottom,
            growRight, growDown
        )
    }

    /** Border segments intersecting a requested world rectangle. */
    fun segments(view: Box): List<Segment> {
        val out = ArrayList<Segment>()
        var top = 0f
        for (row in rows) {
            val bottom = top + row.height
            val width = row.columns.sum()
            if (bottom >= view.top && top <= view.bottom) {
                out += Segment(0f, top, width, top)
                out += Segment(0f, top, 0f, bottom)
                var x = 0f
                for (column in row.columns.dropLast(1)) {
                    x += column
                    out += Segment(x, top, x, bottom)
                }
                out += Segment(width, top, width, bottom)
                out += Segment(0f, bottom, width, bottom)
            }
            top = bottom
        }
        return out
    }
}
