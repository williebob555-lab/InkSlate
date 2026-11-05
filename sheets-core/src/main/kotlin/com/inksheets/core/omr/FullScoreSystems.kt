package com.inksheets.core.omr

import kotlinx.serialization.Serializable
import kotlin.math.abs

/** Geometry-only staff evidence. Detection and notation reading stay in their existing adapters. */
@Serializable
data class ScoreStaffLane(
    val id: String,
    val page: Int,
    val bounds: Box,
    val confidence: Float = 1f,
    val printedLabel: String? = null
) {
    init {
        require(id.isNotBlank() && page >= 0)
        require(bounds.width > 0 && bounds.height > 0) { "A staff lane must have positive bounds" }
        require(confidence.isFinite() && confidence in 0f..1f)
    }
}

@Serializable
enum class ScoreSystemReviewReason {
    LOW_STAFF_CONFIDENCE,
    AMBIGUOUS_VERTICAL_GAP,
    HORIZONTAL_MISALIGNMENT,
    INCONSISTENT_STAFF_COUNT
}

/** One top-to-bottom score system. Lane IDs retain the detector's identity for later correction. */
@Serializable
data class ScoreStaffSystem(
    val id: String,
    val page: Int,
    val indexOnPage: Int,
    val staffLaneIds: List<String>,
    val bounds: Box,
    val confidence: Float,
    val reviewReasons: Set<ScoreSystemReviewReason> = emptySet()
) {
    init {
        require(id.isNotBlank() && page >= 0 && indexOnPage >= 0 && staffLaneIds.isNotEmpty())
        require(staffLaneIds.none(String::isBlank))
        require(bounds.width > 0 && bounds.height > 0)
        require(confidence.isFinite() && confidence in 0f..1f)
    }

    val needsReview: Boolean get() = reviewReasons.isNotEmpty()
}

@Serializable
data class ScoreSystemLayout(
    val systems: List<ScoreStaffSystem>,
    val staffLanes: List<ScoreStaffLane>
) {
    init {
        require(staffLanes.map { it.id }.distinct().size == staffLanes.size) { "Staff lane IDs must be unique" }
        val known = staffLanes.map { it.id }.toSet()
        require(systems.flatMap { it.staffLaneIds }.all { it in known })
    }

    val needsReview: Boolean get() = systems.any { it.needsReview }
}

@Serializable
data class ScoreSystemGroupingOptions(
    /** System whitespace is normally several staff heights; this also handles one-staff systems. */
    val gapToStaffHeight: Double = 2.5,
    /** Repeated within-system gaps provide a second, scale-independent boundary estimate. */
    val gapToTypicalGap: Double = 1.8,
    val minimumHorizontalOverlap: Double = .72,
    /** A boundary decision this close to the threshold is retained for review. */
    val ambiguityFraction: Double = .15,
    val lowStaffConfidence: Float = .7f
) {
    init {
        require(gapToStaffHeight > 0 && gapToTypicalGap > 0)
        require(minimumHorizontalOverlap in 0.0..1.0 && ambiguityFraction in 0.0..1.0)
        require(lowStaffConfidence.isFinite() && lowStaffConfidence in 0f..1f)
    }
}

/** Deterministic first-pass grouping for the score-setup review. It consumes geometry only and is
 * never reached by the existing single-part reader unless a caller explicitly starts score setup.
 */
object ScoreSystemGrouper {
    fun group(lanes: List<ScoreStaffLane>, options: ScoreSystemGroupingOptions = ScoreSystemGroupingOptions()): ScoreSystemLayout {
        require(lanes.map { it.id }.distinct().size == lanes.size) { "Staff lane IDs must be unique" }
        val ordered = lanes.sortedWith(compareBy<ScoreStaffLane>({ it.page }, { it.bounds.top }, { it.bounds.left }, { it.id }))
        val systems = ArrayList<ScoreStaffSystem>()
        for ((page, pageLanes) in ordered.groupBy { it.page }.toSortedMap()) {
            systems += groupPage(page, pageLanes, options)
        }
        return ScoreSystemLayout(markInconsistentCounts(systems), ordered)
    }

    private data class Boundary(val split: Boolean, val reasons: Set<ScoreSystemReviewReason>)

    private fun groupPage(page: Int, lanes: List<ScoreStaffLane>, options: ScoreSystemGroupingOptions): List<ScoreStaffSystem> {
        if (lanes.isEmpty()) return emptyList()
        val heights = lanes.map { it.bounds.height }.sorted()
        val typicalHeight = heights[heights.size / 2].toDouble()
        val gaps = lanes.zipWithNext { a, b -> (b.bounds.top - a.bounds.bottom).coerceAtLeast(0) }.sorted()
        val typicalGap = if (gaps.isNotEmpty()) gaps[(gaps.size - 1) / 2].toDouble() else typicalHeight
        val verticalThreshold = minOf(typicalHeight * options.gapToStaffHeight,
            maxOf(typicalHeight, typicalGap * options.gapToTypicalGap))

        val groups = ArrayList<Pair<MutableList<ScoreStaffLane>, MutableSet<ScoreSystemReviewReason>>>()
        var current = mutableListOf(lanes.first())
        var reasons = linkedSetOf<ScoreSystemReviewReason>()
        for (i in 0 until lanes.lastIndex) {
            val a = lanes[i]
            val b = lanes[i + 1]
            val gap = (b.bounds.top - a.bounds.bottom).coerceAtLeast(0).toDouble()
            val overlap = horizontalOverlap(a.bounds, b.bounds)
            val ambiguous = verticalThreshold > 0 && abs(gap - verticalThreshold) / verticalThreshold <= options.ambiguityFraction
            val boundaryReasons = linkedSetOf<ScoreSystemReviewReason>()
            if (ambiguous) boundaryReasons += ScoreSystemReviewReason.AMBIGUOUS_VERTICAL_GAP
            if (overlap < options.minimumHorizontalOverlap) boundaryReasons += ScoreSystemReviewReason.HORIZONTAL_MISALIGNMENT
            val boundary = Boundary(gap >= verticalThreshold || overlap < options.minimumHorizontalOverlap, boundaryReasons)
            if (boundary.split) {
                reasons += boundary.reasons
                groups += current to reasons
                current = mutableListOf(b)
                reasons = LinkedHashSet(boundary.reasons)
            } else {
                reasons += boundary.reasons
                current += b
            }
        }
        groups += current to reasons

        return groups.mapIndexed { index, (staffs, groupReasons) ->
            if (staffs.any { it.confidence < options.lowStaffConfidence }) {
                groupReasons += ScoreSystemReviewReason.LOW_STAFF_CONFIDENCE
            }
            val bounds = Box(
                staffs.minOf { it.bounds.left }, staffs.minOf { it.bounds.top },
                staffs.maxOf { it.bounds.right }, staffs.maxOf { it.bounds.bottom }
            )
            val averageStaffConfidence = staffs.map { it.confidence }.average().toFloat()
            val reviewMultiplier = if (groupReasons.isEmpty()) .96f else .72f
            ScoreStaffSystem(
                id = "page-$page-system-$index", page = page, indexOnPage = index,
                staffLaneIds = staffs.map { it.id }, bounds = bounds,
                confidence = (averageStaffConfidence * reviewMultiplier).coerceIn(0f, 1f),
                reviewReasons = groupReasons.toSet()
            )
        }
    }

    private fun horizontalOverlap(a: Box, b: Box): Double {
        val intersection = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0)
        return intersection.toDouble() / maxOf(a.width, b.width)
    }

    /** A full-score page normally repeats one staff count. Minority counts are reviewable rather
     * than silently padded or merged; pages containing only one system provide no comparison.
     */
    private fun markInconsistentCounts(systems: List<ScoreStaffSystem>): List<ScoreStaffSystem> {
        val byPage = systems.groupBy { it.page }
        return systems.map { system ->
            val pageSystems = byPage.getValue(system.page)
            if (pageSystems.size < 2) return@map system
            val counts = pageSystems.groupingBy { it.staffLaneIds.size }.eachCount()
            val expected = counts.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenByDescending { it.key }).first().key
            if (system.staffLaneIds.size == expected) system else system.copy(
                confidence = (system.confidence * .75f).coerceIn(0f, 1f),
                reviewReasons = system.reviewReasons + ScoreSystemReviewReason.INCONSISTENT_STAFF_COUNT
            )
        }
    }
}
