package com.inkslate.core

import kotlin.math.floor
import java.util.concurrent.CancellationException

/** Spatial metadata only: querying a region never builds drawing geometry. */
class StrokeRegions(
    strokes: List<Stroke>,
    private val cellSize: Float = 256f,
    private val cancelled: () -> Boolean = { false }
) {
    private val marks = strokes.toList()
    private val bounds = ArrayList<Box>(marks.size)
    private val baseIndexById = marks.mapIndexed { index, stroke -> stroke.id to index }.toMap()
    private var currentMarks: List<Stroke> = marks
    private var currentById: Map<String, Stroke>? = null
    private var currentOrder: Map<String, Int> = baseIndexById
    private var overriddenIds: Set<String> = emptySet()
    private var overrides: List<Pair<Stroke, Box>> = emptyList()
    private var overrideBoundsById: Map<String, Box> = emptyMap()
    private val cells = HashMap<Pair<Int, Int>, MutableList<Int>>()
    private val oversized = ArrayList<Int>()
    // Pen samples normally reuse the same viewport. Retain a few recent views for quick jumps.
    private val views = object : LinkedHashMap<Box, List<Stroke>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Box, List<Stroke>>) = size > 128
    }

    init {
        require(cellSize > 0f && cellSize.isFinite())
        marks.forEachIndexed { index, mark ->
            if (index and 63 == 0 && cancelled()) throw CancellationException("spatial index superseded")
            bounds.add(mark.boundsBox().expanded(mark.baseWidth + 2f + mark.brush.edgeSoftness * 3f))
        }
        bounds.forEachIndexed { i, b ->
            if (i and 63 == 0 && cancelled()) throw CancellationException("spatial index superseded")
            val x0 = cell(b.left); val x1 = cell(b.right)
            val y0 = cell(b.top); val y1 = cell(b.bottom)
            if (marks[i].kind == Stroke.Kind.TEXT || !finite(b) || (x1.toLong() - x0 + 1) * (y1.toLong() - y0 + 1) !in 1..1024) {
                oversized.add(i)
            } else {
                for (x in x0..x1) for (y in y0..y1) cells.getOrPut(x to y) { ArrayList() }.add(i)
            }
        }
    }

    private fun cell(v: Float) = floor(v / cellSize).toInt()
    private fun finite(b: Box) = b.left.isFinite() && b.top.isFinite() && b.right.isFinite() && b.bottom.isFinite()

    /**
     * Update a mostly unchanged page without recalculating bounds for every old stroke.
     *
     * A dense notebook normally changes by one stroke at a time. Rebuilding the complete spatial
     * index after each pen-up made editing cost grow with the whole notebook. The immutable base
     * index remains useful; only new, replaced, and removed ids are kept as an overlay.
     *
     * Returns the number of objects that differ from the base index. A caller may use that to
     * compact a very old overlay in the background, but ordinary writing needs no rebuild.
     */
    @Synchronized
    fun refresh(strokes: List<Stroke>): Int {
        val next = strokes.toList()
        val byId = HashMap<String, Stroke>(next.size * 4 / 3 + 1)
        val order = HashMap<String, Int>(next.size * 4 / 3 + 1)
        val replacements = ArrayList<Pair<Stroke, Box>>()
        val changed = HashSet<String>()

        next.forEachIndexed { index, stroke ->
            byId[stroke.id] = stroke
            order[stroke.id] = index
            if (baseIndexById[stroke.id]?.let { marks[it] } !== stroke) {
                changed += stroke.id
                replacements += stroke to stroke.boundsBox()
                    .expanded(stroke.baseWidth + 2f + stroke.brush.edgeSoftness * 3f)
            }
        }
        for (stroke in marks) if (!byId.containsKey(stroke.id)) changed += stroke.id

        currentMarks = next
        currentById = byId
        currentOrder = order
        overriddenIds = changed
        overrides = replacements
        overrideBoundsById = replacements.associate { it.first.id to it.second }
        views.clear()
        return changed.size
    }

    /** Original order is preserved for alpha blending, with cross-cell marks returned once. */
    @Synchronized
    fun query(region: Box): List<Stroke> {
        views[region]?.let { return it }
        val x0 = cell(region.left); val x1 = cell(region.right)
        val y0 = cell(region.top); val y1 = cell(region.bottom)
        val count = (x1.toLong() - x0 + 1) * (y1.toLong() - y0 + 1)
        if (!finite(region) || count !in 1..4096) return currentMarks
        val candidateIds = HashSet<String>()
        fun addBase(index: Int) {
            val stroke = marks[index]
            if (stroke.id !in overriddenIds) candidateIds += stroke.id
        }
        oversized.forEach(::addBase)
        for (x in x0..x1) for (y in y0..y1) cells[x to y]?.forEach(::addBase)
        for ((stroke, b) in overrides) {
            if (stroke.kind == Stroke.Kind.TEXT || !finite(b) ||
                (b.right >= region.left && b.left <= region.right &&
                    b.bottom >= region.top && b.top <= region.bottom)
            ) candidateIds += stroke.id
        }
        return candidateIds.mapNotNull { id ->
            currentById?.get(id) ?: baseIndexById[id]?.let { marks[it] }
        }
            .sortedBy { currentOrder[it.id] ?: Int.MAX_VALUE }
            .filter { stroke ->
                val b = overrideBoundsById[stroke.id]
                    ?: baseIndexById[stroke.id]?.let { bounds[it] }
                    ?: return@filter true
                stroke.kind == Stroke.Kind.TEXT || !finite(b) ||
                    (b.right >= region.left && b.left <= region.right &&
                        b.bottom >= region.top && b.top <= region.bottom)
            }
            .also { views[region] = it }
    }
}
