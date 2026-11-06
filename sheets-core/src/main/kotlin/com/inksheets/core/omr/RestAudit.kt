package com.inksheets.core.omr

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
data class RestAuditCandidate(
    val eventIndex: Int,
    val selectedBase: Int,
    val suggestedBase: Int,
    val selectedScore: Float,
    val suggestedScore: Float
) {
    val margin: Float get() = suggestedScore - selectedScore
}

@Serializable
data class RestAuditEvidence(val restsChecked: Int, val candidates: List<RestAuditCandidate>) {
    val suspicious: Boolean get() = candidates.any {
        it.suggestedScore >= RestAudit.MIN_SCORE && it.margin >= RestAudit.MIN_MARGIN
    }
}

/** Independently compares an already-located rest with every canonical rest shape. It does not
 * search the page for new rests, so a permissive comparison cannot create unrelated events.
 */
object RestAudit {
    const val MIN_SCORE = .62f
    const val MIN_MARGIN = .1f
    private val kinds = listOf(1 to "restWhole", 2 to "restHalf", 4 to "restQuarter",
        8 to "rest8th", 16 to "rest16th", 32 to "rest32nd")

    fun inspect(measure: Measure, ink: Ink): RestAuditEvidence {
        var checked = 0
        val candidates = ArrayList<RestAuditCandidate>()
        val recognizer = Recognizer()
        measure.events.forEachIndexed { eventIndex, event ->
            val rest = event as? Rest ?: return@forEachIndexed
            checked++
            val anchor = rest.geometry?.anchor ?: SourcePoint(rest.x,
                measure.yAt((rest.step ?: 4) * .5f, rest.x))
            fun score(name: String): Float {
                var best = -1f
                val radius = (measure.space * .12f).roundToInt().coerceIn(1, 3)
                for (dy in -radius..radius) for (dx in -radius..radius) best = maxOf(best,
                    recognizer.score(ink, name, measure.space, anchor.x.roundToInt() + dx, anchor.y.roundToInt() + dy))
                return best
            }
            val scores = kinds.associate { (base, name) -> base to score(name) }
            val selected = scores[rest.duration.base] ?: return@forEachIndexed
            val best = scores.entries.filter { it.key != rest.duration.base }.maxByOrNull { it.value } ?: return@forEachIndexed
            if (best.value > selected) candidates += RestAuditCandidate(eventIndex, rest.duration.base, best.key, selected, best.value)
        }
        return RestAuditEvidence(checked, candidates)
    }
}
