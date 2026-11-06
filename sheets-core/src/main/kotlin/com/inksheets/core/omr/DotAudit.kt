package com.inksheets.core.omr

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A compact mark beside a note which may be an augmentation dot the main reader missed. */
@Serializable
data class DotAuditCandidate(
    val eventIndex: Int,
    val step: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    /** Centre distance from the head's right edge, in staff spaces. */
    val gap: Float,
    /** Distance from the expected augmentation-dot row, in staff spaces. */
    val verticalOffset: Float,
    val density: Float,
    val contrast: Float,
    val score: Float,
    /** The trained reader's strongest one/two-dot probability at this note, when available. */
    val modelDotProbability: Float? = null
)

/** Evidence from a symbol-specific pass. It never edits a reading; calibration decides how much
 * weight to give [candidates] when confidence is ranked.
 */
@Serializable
data class DotAuditEvidence(val notesChecked: Int, val candidates: List<DotAuditCandidate>) {
    val strongest: DotAuditCandidate? get() = candidates.maxByOrNull { it.score }
    val suspicious: Boolean get() = candidates.any {
        it.score >= DotAudit.SUSPICIOUS_SCORE && abs(it.verticalOffset) <= DotAudit.MAX_VERTICAL_OFFSET &&
            (it.modelDotProbability == null || it.modelDotProbability >= DotAudit.MIN_MODEL_DOT)
    }
}

/** Persisted separately from readings: specialists are evidence, not replacement measures. */
@Serializable
data class MeasureSpecialistEvidence(
    val number: Int,
    val dot: DotAuditEvidence,
    val rest: RestAuditEvidence = RestAuditEvidence(0, emptyList()),
    val multiRest: MultiRestAuditEvidence = MultiRestAuditEvidence(emptyList())
)

object SpecialistEvidenceCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(MeasureSpecialistEvidence.serializer())
    fun encode(evidence: List<MeasureSpecialistEvidence>): String = json.encodeToString(serializer, evidence)
    fun decode(text: String?): List<MeasureSpecialistEvidence> = text?.let {
        runCatching { json.decodeFromString(serializer, it) }.getOrNull()
    }.orEmpty()
}

/** Looks only where an augmentation dot can occur. Unlike another whole-page read, this pass can
 * use a permissive local threshold without inventing noteheads, stems, or barlines elsewhere.
 */
object DotAudit {
    const val SUSPICIOUS_SCORE = .75f
    const val MAX_VERTICAL_OFFSET = .32f
    const val MIN_MODEL_DOT = .002f

    fun inspect(measure: Measure, grey: IntArray, width: Int, height: Int): DotAuditEvidence {
        if (grey.size < width * height || width <= 0 || height <= 0) return DotAuditEvidence(0, emptyList())
        var checked = 0
        val candidates = ArrayList<DotAuditCandidate>()
        measure.events.forEachIndexed { eventIndex, event ->
            val note = event as? Note ?: return@forEachIndexed
            if (note.duration.dots != 0) return@forEachIndexed
            checked++
            val heads = note.geometry?.heads.orEmpty().ifEmpty {
                note.steps.map { step ->
                    val glyph = MusicGlyphs[if (note.duration.base == 1) "noteheadWhole" else if (note.duration.base == 2) "noteheadHalf" else "noteheadBlack"]
                    val bounds = glyph.bounds
                    SourceHead(step, SourcePoint(note.x + (bounds[0] + bounds[2]) * measure.space / 2,
                        measure.yAt(step * .5f, note.x)), (bounds[2] - bounds[0]) * measure.space,
                        (bounds[3] - bounds[1]) * measure.space)
                }
            }
            val modelDot = note.odds.takeIf { it.size == 8 }?.let { maxOf(it[5], it[6]) }
            for (head in heads) inspectHead(measure, grey, width, height, eventIndex, head, modelDot)?.let(candidates::add)
        }
        return DotAuditEvidence(checked, candidates)
    }

    private fun inspectHead(measure: Measure, grey: IntArray, pageWidth: Int, pageHeight: Int,
                            eventIndex: Int, head: SourceHead, modelDot: Float?): DotAuditCandidate? {
        val sp = measure.space
        if (!sp.isFinite() || sp < 3f) return null
        val right = head.center.x + head.width / 2
        val spaceStep = if (head.step % 2 != 0) head.step else head.step - 1
        val expectedY = measure.yAt(spaceStep * .5f, right)
        val x0 = (right + sp * .04f).roundToInt().coerceIn(0, pageWidth - 1)
        val x1 = (right + sp * 1.65f).roundToInt().coerceIn(0, pageWidth - 1)
        val y0 = (expectedY - sp * .48f).roundToInt().coerceIn(0, pageHeight - 1)
        val y1 = (expectedY + sp * .48f).roundToInt().coerceIn(0, pageHeight - 1)
        if (x1 <= x0 || y1 <= y0) return null

        // The light half of this tiny staff-space box estimates its paper. A dot is required to be
        // locally dark, rather than merely below a fixed scan-wide grey value.
        val shades = IntArray((x1 - x0 + 1) * (y1 - y0 + 1))
        var shadeAt = 0
        for (y in y0..y1) for (x in x0..x1) shades[shadeAt++] = grey[y * pageWidth + x]
        shades.sort()
        val paper = shades[(shades.size * 3 / 4).coerceIn(0, shades.lastIndex)]
        val cutoff = min(210, paper - 22)
        if (cutoff < 20) return null

        val seen = BooleanArray((x1 - x0 + 1) * (y1 - y0 + 1))
        fun local(x: Int, y: Int) = (y - y0) * (x1 - x0 + 1) + x - x0
        var best: DotAuditCandidate? = null
        for (sy in y0..y1) for (sx in x0..x1) {
            val seed = local(sx, sy)
            if (seen[seed] || grey[sy * pageWidth + sx] > cutoff) continue
            val stack = ArrayDeque<Int>()
            stack += sy * pageWidth + sx
            var pixels = 0; var sum = 0L
            var left = sx; var top = sy; var componentRight = sx; var bottom = sy
            while (stack.isNotEmpty()) {
                val p = stack.removeLast(); val x = p % pageWidth; val y = p / pageWidth
                if (x !in x0..x1 || y !in y0..y1) continue
                val li = local(x, y)
                if (seen[li]) continue
                seen[li] = true
                val shade = grey[p]
                if (shade > cutoff) continue
                pixels++; sum += shade
                left = min(left, x); componentRight = max(componentRight, x)
                top = min(top, y); bottom = max(bottom, y)
                if (x > x0) stack += p - 1
                if (x < x1) stack += p + 1
                if (y > y0) stack += p - pageWidth
                if (y < y1) stack += p + pageWidth
            }
            if (pixels == 0) continue
            val w = componentRight - left + 1; val h = bottom - top + 1
            val minSize = max(2, (sp * .23f).roundToInt())
            val maxSize = max(minSize, (sp * .76f).roundToInt())
            if (w !in minSize..maxSize || h !in minSize..maxSize) continue
            val density = pixels.toFloat() / (w * h)
            val roundness = min(w, h).toFloat() / max(w, h)
            if (density < .28f || roundness < .7f) continue
            val cx = (left + componentRight) / 2f; val cy = (top + bottom) / 2f
            val contrast = paper - sum.toFloat() / pixels
            val contrastScore = ((contrast - 18f) / 92f).coerceIn(0f, 1f)
            val densityScore = ((density - .28f) / .62f).coerceIn(0f, 1f)
            val size = (w + h) / (2f * sp)
            val sizeScore = (1f - abs(size - .38f) / .38f).coerceIn(0f, 1f)
            val verticalScore = (1f - abs(cy - expectedY) / (sp * .5f)).coerceIn(0f, 1f)
            val roundnessScore = ((roundness - .7f) / .3f).coerceIn(0f, 1f)
            val score = .3f * contrastScore + .22f * densityScore + .18f * sizeScore + .14f * verticalScore + .16f * roundnessScore
            val candidate = DotAuditCandidate(eventIndex, head.step, cx, cy, w.toFloat(), h.toFloat(),
                (cx - right) / sp, (cy - expectedY) / sp, density, contrast, score, modelDot)
            if (best == null || candidate.score > best.score) best = candidate
        }
        return best
    }
}
