package com.inksheets.core.omr

import com.inksheets.core.Instruments
import com.inksheets.core.PartChoice
import com.inksheets.core.Song
import kotlinx.serialization.Serializable

/** One playable part; multiple staff streams (for example, a piano's two staves) sound together. */
@Serializable
data class ScorePart(
    /** Stable identifier within the document. */
    val id: String,
    val staves: List<Score>,
    /** Instrument identifier as used by the library, when known. */
    val instrument: String? = null,
    val chair: Int? = null,
    val label: String? = null,
    /** Semitones written above sounding pitch: sounding MIDI = written MIDI - transposition. */
    val transposition: Int = 0,
    /** Parts sharing a group are alternative versions of the same musical role. */
    val alternative: ScoreAlternative? = null,
    /** Where this playable part came from. Video times and file pages remain provenance, not bars. */
    val provenance: List<ScoreProvenance> = emptyList()
) {
    init {
        require(id.isNotBlank()) { "A part must have an id" }
        require(staves.isNotEmpty()) { "A part must have at least one staff stream" }
    }
}

/** A source reference retained beside semantic music without making core open that source. */
@Serializable
data class ScoreProvenance(
    /** "file", "video", "musicxml", "generated", or another adapter-owned kind. */
    val kind: String,
    val reference: String,
    /** Inclusive, one-based source pages where applicable. */
    val firstPage: Int? = null,
    val lastPage: Int? = null,
    /** Inclusive source-media times where applicable. */
    val startMs: Long? = null,
    val endMs: Long? = null
) {
    init {
        require(kind.isNotBlank() && reference.isNotBlank()) { "Score provenance must name its kind and source" }
        require(firstPage == null || firstPage > 0)
        require(lastPage == null || lastPage > 0)
        require(firstPage == null || lastPage == null || lastPage >= firstPage)
        require(startMs == null || startMs >= 0)
        require(endMs == null || endMs >= 0)
        require(startMs == null || endMs == null || endMs >= startMs)
    }
}

@Serializable
data class ScoreAlternative(
    val group: String,
    val label: String? = null,
    val difficulty: String? = null
) {
    init { require(group.isNotBlank()) { "An alternative group must have an id" } }
}

/** Explicit playback choice: zero or more normal parts and exactly one part per alternative group. */
@Serializable
data class ScoreSelection(
    val normalPartIds: Set<String>,
    /** Alternative group id to selected part id. */
    val alternativePartIds: Map<String, String>
)

/** One written bar on the document-wide timeline. The ID, rather than a printed number, is the
 * durable identity; numbers may be absent or repeated in later imported scores.
 */
@Serializable
data class CanonicalBar(val id: String, val number: Int? = null) {
    init { require(id.isNotBlank()) { "A canonical bar must have an id" } }
}

@Serializable
enum class AlignmentMethod { WRITTEN_NUMBER, GEOMETRY, MANUAL }

/** A source measure mapped to one or more canonical bars (multi-bar rests map to several). */
@Serializable
data class MeasureAlignment(
    val partId: String,
    val staffIndex: Int,
    val measureIndex: Int,
    val canonicalBarIds: List<String>,
    val confidence: Float = 1f,
    val method: AlignmentMethod = AlignmentMethod.WRITTEN_NUMBER
) {
    init {
        require(partId.isNotBlank() && staffIndex >= 0 && measureIndex >= 0)
        require(canonicalBarIds.isNotEmpty() && canonicalBarIds.none(String::isBlank))
        require(confidence.isFinite() && confidence in 0f..1f)
    }
}

/** A collection of independently playable parts, retaining existing [Score] staff streams. */
@Serializable
data class ScoreDocument(
    val parts: List<ScorePart>,
    val bars: List<CanonicalBar> = emptyList(),
    val alignments: List<MeasureAlignment> = emptyList()
) {
    init {
        require(parts.map { it.id }.distinct().size == parts.size) { "Part ids must be unique" }
        require(bars.map { it.id }.distinct().size == bars.size) { "Canonical bar ids must be unique" }
        val partById = parts.associateBy { it.id }
        val barIds = bars.map { it.id }.toSet()
        for (alignment in alignments) {
            val part = partById[alignment.partId] ?: throw IllegalArgumentException("Alignment references an unknown part")
            require(alignment.staffIndex in part.staves.indices) { "Alignment references an unknown staff" }
            require(alignment.measureIndex in part.staves[alignment.staffIndex].measures.indices) { "Alignment references an unknown measure" }
            require(alignment.canonicalBarIds.all { it in barIds }) { "Alignment references an unknown canonical bar" }
        }
    }

    /** Select every normal part and the first listed alternative in each group. */
    fun defaultSelection(): ScoreSelection = ScoreSelection(
        normalPartIds = parts.filter { it.alternative == null }.map { it.id }.toSet(),
        alternativePartIds = parts.filter { it.alternative != null }
            .groupBy { it.alternative!!.group }.mapValues { (_, choices) -> choices.first().id }
    )

    /** Resolve a selection in document order, rejecting unknown or incompatible choices. */
    fun selectedParts(selection: ScoreSelection = defaultSelection()): List<ScorePart> {
        val normal = parts.filter { it.alternative == null }.map { it.id }.toSet()
        require(selection.normalPartIds.all { it in normal }) { "Unknown normal part in selection" }
        val groups = parts.filter { it.alternative != null }.groupBy { it.alternative!!.group }
        require(selection.alternativePartIds.keys == groups.keys) { "Select exactly one alternative per group" }
        require(selection.alternativePartIds.all { (group, id) -> groups.getValue(group).any { it.id == id } }) {
            "Selected alternative does not belong to its group"
        }
        val selected = selection.normalPartIds + selection.alternativePartIds.values
        return parts.filter { it.id in selected }
    }

    fun alignmentFor(partId: String, staffIndex: Int, measureIndex: Int): MeasureAlignment? =
        alignments.firstOrNull { it.partId == partId && it.staffIndex == staffIndex && it.measureIndex == measureIndex }

    companion object {
        /** Adapt an existing single-staff score without changing its data. */
        fun fromScore(score: Score, id: String = "part-1"): ScoreDocument =
            aligned(listOf(ScorePart(id = id, staves = listOf(score))))

        /**
         * Build playable parts from readings already obtained for a library song. Parts without a
         * reading are omitted; each supplied reading must contain at least one staff. Readings
         * for IDs outside the song are ignored. The song's part order and IDs are retained.
         */
        fun fromSong(song: Song, readings: Map<String, List<Score>>): ScoreDocument =
            aligned(song.parts.mapNotNull { part ->
                val staves = readings[part.id] ?: return@mapNotNull null
                val (instrument, seatChair) = part.instrument?.let(PartChoice::seat) ?: (null to null)
                ScorePart(
                    id = part.id,
                    staves = staves,
                    instrument = instrument,
                    chair = part.chair ?: seatChair,
                    label = part.label,
                    transposition = instrument?.let { Instruments.byId[it]?.transpose } ?: 0,
                    provenance = listOf(ScoreProvenance("file", part.file, part.firstPage, part.lastPage))
                )
            })

        /** Initial alignment for separate parts: written bar numbers are a useful hint, retained
         * with less-than-manual confidence so geometry or review can replace it later.
         */
        private fun aligned(parts: List<ScorePart>): ScoreDocument {
            val numbers = parts.flatMap { part -> part.staves.flatMap { staff ->
                staff.measures.flatMap { measure -> (0 until measure.bars).map { measure.number + it } }
            } }.distinct().sorted()
            val bars = numbers.map { CanonicalBar("written-$it", it) }
            val alignments = parts.flatMap { part -> part.staves.flatMapIndexed { staffIndex, staff ->
                staff.measures.mapIndexedNotNull { measureIndex, measure ->
                    val ids = (0 until measure.bars).map { "written-${measure.number + it}" }
                    ids.takeIf { it.isNotEmpty() }?.let {
                        MeasureAlignment(part.id, staffIndex, measureIndex, it, confidence = .8f)
                    }
                }
            } }
            return ScoreDocument(parts, bars, alignments)
        }
    }
}
