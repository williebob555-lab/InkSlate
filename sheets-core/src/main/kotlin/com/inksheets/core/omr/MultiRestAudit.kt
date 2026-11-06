package com.inksheets.core.omr

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.roundToInt

@Serializable
data class MultiRestAuditCandidate(
    val start: Int,
    val end: Int,
    val step: Int,
    val count: Int
)

@Serializable
data class MultiRestAuditEvidence(val candidates: List<MultiRestAuditCandidate>) {
    val suspicious: Boolean get() = candidates.isNotEmpty()
}

/** Finds the unmistakable combination used by a multi-measure rest: a thick horizontal bar at the
 * staff's middle and a count centred above it. It only challenges generated one-bar measures and
 * never supplies a count unless the independent digit reader can read one.
 */
object MultiRestAudit {
    fun inspect(measure: Measure, ink: Ink): MultiRestAuditEvidence {
        if (measure.bars != 1 || measure.space < 3f || measure.box.width <= 0) return MultiRestAuditEvidence(emptyList())
        // Calibrated production case: the main reader saw the compact mark as one ordinary whole
        // rest and lost the count. More permissive note/beam decompositions remain benchmark-only
        // until they have an independent corroborator.
        val readRest = measure.events.singleOrNull() as? Rest
        if (readRest?.duration?.base != 1 || readRest.duration.dots != 0) return MultiRestAuditEvidence(emptyList())
        val sp = measure.space
        val from = max(measure.box.left + 1, measure.start.takeIf { it > 0 } ?: measure.box.left + 1)
        val to = (measure.box.right - 1).coerceAtMost(ink.width - 1)
        if (to - from < sp * 3f) return MultiRestAuditEvidence(emptyList())

        data class Run(val start: Int, val end: Int, val step: Int)
        var best: Run? = null
        for (step in listOf(4, 3, 5)) {
            var start = -1; var last = -1; var gaps = 0
            fun finish() {
                if (start >= 0 && last >= start) {
                    val run = Run(start, last, step)
                    if (best == null || run.end - run.start > best!!.end - best!!.start) best = run
                }
                start = -1; last = -1; gaps = 0
            }
            for (x in from..to) {
                val y = measure.yAt(step * .5f, x.toFloat()).roundToInt()
                var top = y
                while (top > 0 && ink[x, top - 1] && y - top < sp * 1.5f) top--
                var bottom = y
                while (bottom + 1 < ink.height && ink[x, bottom + 1] && bottom - y < sp * 1.5f) bottom++
                val thickness = if (ink[x, y]) bottom - top + 1 else 0
                val thick = thickness >= max(3, (sp * .28f).roundToInt()) && thickness <= (sp * 1.25f).roundToInt()
                if (thick) {
                    if (start < 0) start = x
                    last = x; gaps = 0
                } else if (start >= 0 && ++gaps > 2) finish()
            }
            finish()
        }
        // Some contemporary percussion parts use a compact cap-and-block only about one space
        // wide; the older long H-bar is several spaces. Geometry below, not length alone, rejects
        // beams and noteheads.
        val run = best?.takeIf { it.end - it.start + 1 >= sp * .9f } ?: return MultiRestAuditEvidence(emptyList())
        // A multi-rest has an upright cap at one or both ends and no note stems crossing the
        // interior. (Several scan/engraver families leave one end open.) A beam has internal stems
        // and was the dominant false hit in calibration.
        fun uprightNear(x0: Int): Boolean = (x0 - 3..x0 + 3).any { x ->
            if (x !in 0 until ink.width) return@any false
            val middle = measure.yAt(run.step * .5f, x.toFloat()).roundToInt().coerceIn(0, ink.height - 1)
            val reach = max(3, (sp * 1.15f).roundToInt())
            val top = (middle - reach).coerceAtLeast(0)
            val bottom = (middle + reach).coerceAtMost(ink.height - 1)
            // Compact engravings use a cap on only one side of the block; long H-bars often use
            // both. Require a near-continuous one-space upright in either direction.
            (top..middle).count { y -> ink[x, y] } >= sp * .65f ||
                (middle..bottom).count { y -> ink[x, y] } >= sp * .65f
        }
        if (!uprightNear(run.start) && !uprightNear(run.end)) return MultiRestAuditEvidence(emptyList())
        val inset = max(4, (sp * .7f).roundToInt())
        val interiorFrom = run.start + inset
        val interiorTo = run.end - inset
        if (interiorFrom <= interiorTo) {
            val stride = max(1, (sp * .25f).roundToInt())
            if ((interiorFrom..interiorTo step stride).any(::uprightNear)) return MultiRestAuditEvidence(emptyList())
        }
        val centre = (run.start + run.end) / 2
        val half = max(sp * 2.5f, (run.end - run.start) * .35f).roundToInt()
        val number = Digits.number(ink,
            (centre - half).coerceAtLeast(measure.box.left),
            (centre + half).coerceAtMost(measure.box.right),
            measure.yAt(-4.5f, centre.toFloat()).roundToInt(),
            measure.yAt(-.5f, centre.toFloat()).roundToInt(),
            (sp * .8f).roundToInt(), (sp * 3.2f).roundToInt(),
            bottomFrom = measure.yAt(-3f, centre.toFloat()).roundToInt(), space = sp, musicFont = true
        )?.first?.takeIf { it in 2..64 } ?: return MultiRestAuditEvidence(emptyList())
        return MultiRestAuditEvidence(listOf(MultiRestAuditCandidate(run.start, run.end, run.step, number)))
    }
}
