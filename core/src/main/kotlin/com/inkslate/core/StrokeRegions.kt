package com.inkslate.core

import kotlin.math.floor

/** Spatial metadata only: querying a region never builds drawing geometry. */
class StrokeRegions(strokes: List<Stroke>, private val cellSize: Float = 256f) {
    private val marks = strokes.toList()
    private val bounds = marks.map { it.boundsBox().expanded(it.baseWidth + 2f + it.brush.edgeSoftness * 3f) }
    private val cells = HashMap<Pair<Int, Int>, MutableList<Int>>()
    private val oversized = ArrayList<Int>()
    // Pen samples normally reuse the same viewport. Retain a few recent views for quick jumps.
    private val views = object : LinkedHashMap<Box, List<Stroke>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Box, List<Stroke>>) = size > 128
    }

    init {
        require(cellSize > 0f && cellSize.isFinite())
        bounds.forEachIndexed { i, b ->
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

    /** Original order is preserved for alpha blending, with cross-cell marks returned once. */
    fun query(region: Box): List<Stroke> {
        views[region]?.let { return it }
        val x0 = cell(region.left); val x1 = cell(region.right)
        val y0 = cell(region.top); val y1 = cell(region.bottom)
        val count = (x1.toLong() - x0 + 1) * (y1.toLong() - y0 + 1)
        if (!finite(region) || count !in 1..4096) return marks
        val candidates = HashSet<Int>()
        candidates.addAll(oversized)
        for (x in x0..x1) for (y in y0..y1) cells[x to y]?.let { candidates.addAll(it) }
        return candidates.sorted().filter { i ->
            val b = bounds[i]
            marks[i].kind == Stroke.Kind.TEXT || !finite(b) || (b.right >= region.left && b.left <= region.right &&
                b.bottom >= region.top && b.top <= region.bottom)
        }.map { marks[it] }.also { views[region] = it }
    }
}
