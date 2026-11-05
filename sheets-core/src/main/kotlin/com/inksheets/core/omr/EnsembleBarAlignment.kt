package com.inksheets.core.omr

import java.util.IdentityHashMap
import kotlin.math.roundToInt

/**
 * Lines another part up with the conductor part before synthesis. Printed measure numbers remain
 * valuable anchors, but they are not used as a hash lookup for every bar: one bad OCR number or a
 * duplicated number must not make the rest of that instrument jump to a different measure.
 *
 * The alignment is deliberately semantic and allocation-bounded. It expands multi-measure rests
 * to silent bar slots, finds the longest monotonic set of shared-number anchors, and fills between
 * those anchors by written order. Repeats are then taken solely from the conductor's play order.
 */
object EnsembleBarAlignment {
    private data class Cell(val measure: Measure, val offset: Int) {
        val number: Int get() = measure.number + offset
        val audible: Measure? get() = measure.takeIf { it.bars == 1 }
    }

    private fun cells(score: Score): List<Cell> = buildList {
        for (measure in score.measures) repeat(measure.bars.coerceAtLeast(1)) { add(Cell(measure, it)) }
    }

    /** One entry for every bar in [playedLead], including every occurrence made by repeats. */
    fun align(lead: Score, playedLead: List<Measure>, part: Score): List<Measure?> {
        val leadCells = cells(lead)
        val partCells = cells(part)
        if (leadCells.isEmpty() || partCells.isEmpty()) return playedLead.flatMap { List(it.bars.coerceAtLeast(1)) { null } }
        val writtenMap = writtenMap(leadCells, partCells)
        val firstCell = IntArray(lead.measures.size)
        var at = 0
        for (i in lead.measures.indices) { firstCell[i] = at; at += lead.measures[i].bars.coerceAtLeast(1) }
        val byIdentity = IdentityHashMap<Measure, Int>()
        lead.measures.forEachIndexed { index, measure -> byIdentity[measure] = index }
        return buildList {
            for (measure in playedLead) {
                val source = byIdentity[measure] ?: lead.measures.indexOf(measure).takeIf { it >= 0 }
                repeat(measure.bars.coerceAtLeast(1)) { offset ->
                    val leadIndex = source?.let { firstCell[it] + offset }
                    val partIndex = leadIndex?.let { writtenMap.getOrNull(it) } ?: -1
                    add(partCells.getOrNull(partIndex)?.audible)
                }
            }
        }
    }

    /** Lead written-bar index -> part written-bar index, or -1 when no safe correspondence exists. */
    private fun writtenMap(lead: List<Cell>, part: List<Cell>): IntArray {
        val leadNumbers = lead.groupBy { it.number }
        val partNumbers = part.groupBy { it.number }
        val candidates = lead.indices.mapNotNull { li ->
            val n = lead[li].number
            if (leadNumbers[n]?.size == 1 && partNumbers[n]?.size == 1) li to part.indexOfFirst { it.number == n } else null
        }
        val anchors = longestIncreasing(candidates)
        val out = IntArray(lead.size) { -1 }
        if (anchors.isEmpty()) {
            // Parts with wholly different numbering (or no readable numbers) still normally have
            // the same written bar sequence. Only trust order when their extents are reasonably close.
            val ratio = part.size.toDouble() / lead.size
            if (ratio in .8..1.25) for (li in lead.indices) {
                out[li] = if (lead.size == 1) 0 else (li * (part.size - 1).toDouble() / (lead.size - 1)).roundToInt()
            }
            return out
        }
        for (li in lead.indices) {
            val before = anchors.lastOrNull { it.first <= li }
            val after = anchors.firstOrNull { it.first >= li }
            val pi = when {
                before != null && after != null && before.first != after.first -> {
                    val f = (li - before.first).toDouble() / (after.first - before.first)
                    (before.second + f * (after.second - before.second)).roundToInt()
                }
                before != null -> before.second + (li - before.first)
                after != null -> after.second - (after.first - li)
                else -> -1
            }
            if (pi in part.indices) out[li] = pi
        }
        // Exact monotonic anchors always win over interpolation.
        anchors.forEach { (li, pi) -> out[li] = pi }
        return out
    }

    /** Longest increasing subsequence by part index; rejects a stray misread number crossing the score. */
    private fun longestIncreasing(points: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        if (points.isEmpty()) return emptyList()
        val length = IntArray(points.size) { 1 }
        val previous = IntArray(points.size) { -1 }
        for (i in points.indices) for (j in 0 until i) if (points[j].second < points[i].second && length[j] + 1 > length[i]) {
            length[i] = length[j] + 1
            previous[i] = j
        }
        var i = length.indices.maxByOrNull { length[it] } ?: return emptyList()
        val out = ArrayList<Pair<Int, Int>>()
        while (i >= 0) { out += points[i]; i = previous[i] }
        out.reverse()
        return out
    }
}
