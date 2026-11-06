package com.inksheets.core.omr

/** Operational priority, not a probability of correctness. In particular, STABLE means that the
 * shipped passes agree; correlated readers can still agree on the same mistake.
 */
enum class ReadingConfidenceTier { STABLE, UNAUDITED, REVIEW, URGENT }

enum class ReadingConfidenceReason {
    BASE_DOUBT,
    UNCLEAR_SYMBOL,
    NOT_FULLY_AUDITED,
    ALTERNATE_DISAGREES,
    MULTIPLE_ALTERNATIVES,
    WEAK_HEAD,
    WEAK_DURATION_MARGIN,
    WEAK_DOT_MARGIN,
    BASE_EVENT_UNSUPPORTED,
    NOVEL_EVENT_CONSENSUS,
    REST_KIND_DISAGREEMENT,
    POSSIBLE_MISSED_DOT,
    POSSIBLE_MULTI_MEASURE_REST
}

data class MeasureConfidence(
    val number: Int,
    /** 0-100 evidence index used only for ordering work. */
    val index: Int,
    val tier: ReadingConfidenceTier,
    val reasons: Set<ReadingConfidenceReason>
)

data class ScoreConfidence(
    val measures: Int,
    val stable: Int,
    val unaudited: Int,
    val review: Int,
    val urgent: Int,
    val meanIndex: Float,
    val minimumIndex: Int
) {
    /** Larger means the score should be worked sooner. */
    val priority: Int get() = urgent * 10_000 + review * 1_000 + unaudited * 10 + (100 - meanIndex).toInt()
}

object ReadingConfidence {
    fun measure(measure: Measure, evidence: MeasureEvidence): MeasureConfidence {
        var index = when (measure.certainty) {
            Certainty.SURE -> 70
            Certainty.LOOK -> 45
            Certainty.FIX -> 15
        }
        val reasons = linkedSetOf<ReadingConfidenceReason>()
        when (measure.certainty) {
            Certainty.FIX -> reasons += ReadingConfidenceReason.BASE_DOUBT
            Certainty.LOOK -> reasons += ReadingConfidenceReason.UNCLEAR_SYMBOL
            Certainty.SURE -> Unit
        }

        when (evidence.stability) {
            ReadingStability.UNAUDITED -> {
                index -= 5
                reasons += ReadingConfidenceReason.NOT_FULLY_AUDITED
            }
            ReadingStability.STABLE -> index += if (evidence.looks >= 4) 15 else 8
            ReadingStability.MIXED -> {
                index -= 20
                reasons += ReadingConfidenceReason.ALTERNATE_DISAGREES
            }
            ReadingStability.UNSTABLE -> {
                index -= 30
                reasons += ReadingConfidenceReason.ALTERNATE_DISAGREES
            }
        }
        if (evidence.looks in 1..3) reasons += ReadingConfidenceReason.NOT_FULLY_AUDITED
        if (evidence.distinctReadings > 2) {
            index -= 8
            reasons += ReadingConfidenceReason.MULTIPLE_ALTERNATIVES
        }

        evidence.model.minHeadProbability?.let {
            if (it < .75f) { index -= 18; reasons += ReadingConfidenceReason.WEAK_HEAD }
            else if (it < .85f) { index -= 7; reasons += ReadingConfidenceReason.WEAK_HEAD }
        }
        evidence.model.minValueMargin?.takeIf { it < .2f }?.let {
            index -= 10; reasons += ReadingConfidenceReason.WEAK_DURATION_MARGIN
        }
        evidence.model.minDotMargin?.takeIf { it < .2f }?.let {
            index -= 10; reasons += ReadingConfidenceReason.WEAK_DOT_MARGIN
        }
        if (evidence.events.weakBaseEventIndices.isNotEmpty()) {
            index -= 25
            reasons += ReadingConfidenceReason.BASE_EVENT_UNSUPPORTED
        }
        if (evidence.events.majorityNovelEvents.isNotEmpty()) {
            index -= 25
            reasons += ReadingConfidenceReason.NOVEL_EVENT_CONSENSUS
        }
        if (evidence.dot?.suspicious == true) {
            index -= 35
            reasons += ReadingConfidenceReason.POSSIBLE_MISSED_DOT
        }
        if (evidence.rest?.suspicious == true) {
            index -= 8
            reasons += ReadingConfidenceReason.REST_KIND_DISAGREEMENT
        }
        if (evidence.multiRest?.suspicious == true) {
            index -= 35
            reasons += ReadingConfidenceReason.POSSIBLE_MULTI_MEASURE_REST
        }

        val bounded = index.coerceIn(0, 100)
        val tier = when {
            measure.certainty == Certainty.FIX || bounded < 30 -> ReadingConfidenceTier.URGENT
            evidence.stability == ReadingStability.UNAUDITED -> ReadingConfidenceTier.UNAUDITED
            bounded < 75 || reasons.any { it == ReadingConfidenceReason.ALTERNATE_DISAGREES ||
                it == ReadingConfidenceReason.POSSIBLE_MISSED_DOT || it == ReadingConfidenceReason.WEAK_HEAD ||
                it == ReadingConfidenceReason.POSSIBLE_MULTI_MEASURE_REST ||
                it == ReadingConfidenceReason.BASE_EVENT_UNSUPPORTED || it == ReadingConfidenceReason.NOVEL_EVENT_CONSENSUS } -> ReadingConfidenceTier.REVIEW
            else -> ReadingConfidenceTier.STABLE
        }
        return MeasureConfidence(measure.number, bounded, tier, reasons)
    }

    fun score(measures: List<MeasureConfidence>): ScoreConfidence {
        if (measures.isEmpty()) return ScoreConfidence(0, 0, 0, 0, 0, 0f, 0)
        return ScoreConfidence(
            measures.size,
            measures.count { it.tier == ReadingConfidenceTier.STABLE },
            measures.count { it.tier == ReadingConfidenceTier.UNAUDITED },
            measures.count { it.tier == ReadingConfidenceTier.REVIEW },
            measures.count { it.tier == ReadingConfidenceTier.URGENT },
            measures.map { it.index }.average().toFloat(),
            measures.minOf { it.index }
        )
    }

    /** Lowest-confidence scores first, with [id] as a deterministic tie-breaker. */
    fun <T : Comparable<T>> rank(scores: Map<T, List<MeasureConfidence>>): List<Pair<T, ScoreConfidence>> =
        scores.mapValues { score(it.value) }.entries
            .sortedWith(compareByDescending<Map.Entry<T, ScoreConfidence>> { it.value.priority }.thenBy { it.key })
            .map { it.key to it.value }
}
