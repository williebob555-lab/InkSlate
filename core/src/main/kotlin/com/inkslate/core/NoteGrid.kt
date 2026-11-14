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
 * saved. [fitContents] grows or shrinks sections around their content and describes how ink moves;
 * a pinned cell keeps a live stroke's origin steady until the gesture finishes.
 */
@Serializable
data class NoteGrid(
    @SerialName("rows") val rows: List<NoteGridRow> = listOf(NoteGridRow()),
    @SerialName("gap") val contentGap: Float = 28f,
    @SerialName("newColumnWidth") val newColumnWidth: Float = 360f,
    @SerialName("newRowHeight") val newRowHeight: Float = 220f,
    /** Committed layout revision; preview fits deliberately leave this unchanged. */
    @SerialName("modifiedAt") val modifiedAt: Long = 0L
) {
    /** Stable membership while dimensions and neighbouring content move. */
    data class Cell(val row: Int, val column: Int) {
        init { require(row >= 0 && column >= 0) }
    }

    data class Content(val cell: Cell, val bounds: Box)

    data class Fit(
        val grid: NoteGrid,
        private val before: NoteGrid,
        private val contentInsets: Map<Cell, Pair<Float, Float>>
    ) {
        /** Signed translation of a whole object; never remap its points by separate memberships. */
        fun shiftFor(cell: Cell): Pair<Float, Float> {
            val old = before.originOf(cell)
            val next = grid.originOf(cell)
            val inset = contentInsets[cell] ?: (0f to 0f)
            return next.first - old.first + inset.first to next.second - old.second + inset.second
        }

        fun shiftFor(x: Float, y: Float): Pair<Float, Float> = shiftFor(before.cellAt(x, y))

        fun remap(x: Float, y: Float, cell: Cell): Pair<Float, Float> {
            val shift = shiftFor(cell)
            return x + shift.first to y + shift.second
        }
    }

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

    /** Committed compaction wins over an older expanded grid; legacy documents still union. */
    fun mergeWith(other: NoteGrid): NoteGrid {
        if (modifiedAt != 0L || other.modifiedAt != 0L) {
            if (modifiedAt != other.modifiedAt) return if (modifiedAt > other.modifiedAt) this else other
            return if (layoutKey() >= other.layoutKey()) this else other
        }
        val count = maxOf(rows.size, other.rows.size)
        val base = if (layoutKey() >= other.layoutKey()) this else other
        return base.copy(rows = List(count) { rowIndex ->
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

    private fun layoutKey(): String = buildString {
        append(contentGap.toRawBits()).append('/').append(newColumnWidth.toRawBits())
            .append('/').append(newRowHeight.toRawBits())
        rows.forEach { row ->
            append(';').append(row.height.toRawBits()).append(':')
            row.columns.forEach { append(it.toRawBits()).append(',') }
        }
    }

    /** Resolve against this layout before a stroke or object begins moving. */
    fun cellAt(x: Float, y: Float): Cell {
        require(x.isFinite() && y.isFinite())
        requireValidDimensions()
        val working = rows.ifEmpty { listOf(NoteGridRow(newRowHeight, listOf(newColumnWidth))) }
        var top = 0f
        for ((index, row) in working.withIndex()) {
            if (y < top + row.height) {
                var left = 0f
                for ((column, width) in row.columns.withIndex()) {
                    if (x < left + width) return Cell(index, column)
                    left += width
                }
                return Cell(index, row.columns.size + ((x - left).coerceAtLeast(0f) / newColumnWidth).toInt())
            }
            top += row.height
        }
        return Cell(
            working.size + ((y - top).coerceAtLeast(0f) / newRowHeight).toInt(),
            (x.coerceAtLeast(0f) / newColumnWidth).toInt()
        )
    }

    fun originOf(cell: Cell): Pair<Float, Float> {
        var top = 0f
        for (row in 0 until cell.row) top += rows.getOrNull(row)?.height ?: newRowHeight
        val columns = rows.getOrNull(cell.row)?.columns.orEmpty()
        var left = 0f
        for (column in 0 until cell.column) left += columns.getOrNull(column) ?: newColumnWidth
        return left to top
    }

    /**
     * Fit every cell to all remaining content, including a currently unfinished stroke.
     * Membership is supplied from the old layout so an overflowing stroke never switches cells.
     * Empty space contracts to the preset minimum; explicit rows and columns remain present.
     *
     * During a gesture [pinnedCell] freezes its origin and prevents that cell shrinking. Later
     * cells and rows may move immediately, while the pen and camera stay in their coordinates.
     */
    fun fitContents(contents: List<Content>, pinnedCell: Cell? = null): Fit {
        requireValidDimensions()
        require(contents.all { entry ->
            val b = entry.bounds
            b.left.isFinite() && b.top.isFinite() && b.right.isFinite() && b.bottom.isFinite() &&
                b.right >= b.left && b.bottom >= b.top
        }) { "Note Grid content bounds must be finite and ordered." }
        val rowCount = maxOf(rows.size, (contents.maxOfOrNull { it.cell.row } ?: -1) + 1,
            (pinnedCell?.row ?: -1) + 1, 1)
        val before = copy(rows = List(rowCount) { row ->
            val original = rows.getOrNull(row) ?: NoteGridRow(newRowHeight, listOf(newColumnWidth))
            val count = maxOf(original.columns.size,
                (contents.filter { it.cell.row == row }.maxOfOrNull { it.cell.column } ?: -1) + 1,
                if (pinnedCell?.row == row) pinnedCell.column + 1 else 0, 1)
            original.copy(columns = List(count) { original.columns.getOrElse(it) { newColumnWidth } })
        })
        val byCell = contents.groupBy { it.cell }
        val insets = byCell.mapValues { (cell, entries) ->
            val origin = before.originOf(cell)
            // A temporary spill from the active cell must never move the pen under the hand.
            if (cell == pinnedCell) 0f to 0f else {
                val left = entries.minOf { it.bounds.left } - origin.first
                val top = entries.minOf { it.bounds.top } - origin.second
                (-left).coerceAtLeast(0f) to (-top).coerceAtLeast(0f)
            }
        }
        val fitted = before.rows.mapIndexed { rowIndex, row ->
            var height = newRowHeight
            val columns = row.columns.mapIndexed { columnIndex, oldWidth ->
                val cell = Cell(rowIndex, columnIndex)
                val origin = before.originOf(cell)
                val inset = insets[cell] ?: (0f to 0f)
                var width = newColumnWidth
                for (entry in byCell[cell].orEmpty()) {
                    width = maxOf(width, entry.bounds.right - origin.first + inset.first + contentGap)
                    height = maxOf(height, entry.bounds.bottom - origin.second + inset.second + contentGap)
                }
                when {
                    pinnedCell?.row == rowIndex && columnIndex < pinnedCell.column -> oldWidth
                    cell == pinnedCell -> maxOf(width, oldWidth)
                    else -> width
                }
            }
            height = when {
                pinnedCell != null && rowIndex < pinnedCell.row -> row.height
                pinnedCell?.row == rowIndex -> maxOf(height, row.height)
                else -> height
            }
            row.copy(height = height, columns = columns)
        }
        return Fit(copy(rows = fitted), before, insets)
    }

    private fun requireValidDimensions() {
        require(newColumnWidth.isFinite() && newColumnWidth > 0f &&
            newRowHeight.isFinite() && newRowHeight > 0f && contentGap.isFinite() && contentGap >= 0f)
        require(rows.all { row -> row.height.isFinite() && row.height > 0f &&
            row.columns.all { it.isFinite() && it > 0f } })
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

    /**
     * Separators between neighbouring sections, with every outside edge left open.
     *
     * A row boundary only spans the width shared by its two neighbours: extending it across a
     * wider row would put a top or bottom border on an otherwise open outer section. Each shared
     * edge is emitted once so it has the same weight on screen and in exported PDFs.
     */
    fun segments(view: Box): List<Segment> {
        val out = ArrayList<Segment>()
        fun separator(x0: Float, y0: Float, x1: Float, y1: Float) {
            if (x1 >= view.left && x0 <= view.right && y1 >= view.top && y0 <= view.bottom) {
                out += Segment(x0, y0, x1, y1)
            }
        }
        var top = 0f
        for ((index, row) in rows.withIndex()) {
            val bottom = top + row.height
            if (bottom >= view.top && top <= view.bottom) {
                if (index > 0) {
                    val sharedWidth = minOf(rows[index - 1].columns.sum(), row.columns.sum())
                    if (sharedWidth > 0f) separator(0f, top, sharedWidth, top)
                }
                var x = 0f
                for (column in row.columns.dropLast(1)) {
                    x += column
                    separator(x, top, x, bottom)
                }
            }
            top = bottom
        }
        return out
    }
}
