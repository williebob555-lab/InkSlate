package com.inksheets.core.omr

/** What another reading disagrees with in the base measure. Geometry is deliberately excluded:
 * alternate raster sizes and staff fits move source coordinates without changing the music.
 */
enum class ReadingDisagreement {
    STRUCTURE,
    SIGNATURE,
    EVENT_INVENTORY,
    PITCH,
    DURATION
}

/** Stability is evidence about repeatability, not a calibrated probability of correctness. */
enum class ReadingStability { UNAUDITED, STABLE, MIXED, UNSTABLE }

/** Shadow evidence gathered from alternate looks at one measure. It cannot by itself make a
 * measure green: correlated readers may agree on the same mistake.
 */
data class MeasureEvidence(
    val looks: Int,
    val agreeingLooks: Int,
    val sureLooks: Int,
    val distinctReadings: Int,
    val disagreements: Set<ReadingDisagreement>,
    val stability: ReadingStability,
    val model: ReadingModelEvidence,
    val events: EventConsensusEvidence,
    val dot: DotAuditEvidence? = null,
    val rest: RestAuditEvidence? = null,
    val multiRest: MultiRestAuditEvidence? = null
) {
    val agreement: Float get() = if (looks == 0) 0f else agreeingLooks.toFloat() / looks
    val hasCriticalDisagreement: Boolean get() = disagreements.isNotEmpty()
}

/** Probability evidence retained by the trained symbol reader. Margins are the probability of the
 * selected interpretation less the strongest alternative; negative means later rules selected a
 * result other than the network's first choice. Null means no trained-reader odds were retained.
 */
data class ReadingModelEvidence(
    val notes: Int,
    val notesWithOdds: Int,
    val minHeadProbability: Float?,
    val minValueMargin: Float?,
    val minDotMargin: Float?,
    /** Smallest head probability minus strongest rest probability on a read note. */
    val minHeadVsRestMargin: Float?,
    /** Smallest strongest-rest probability minus strongest-head probability on a read rest. */
    val minRestVsHeadMargin: Float?,
    /** Selected rest class probability less the strongest other rest class. */
    val minRestKindMargin: Float?
)

data class NovelEventEvidence(
    val kind: String,
    val x: Float,
    val steps: List<Int>,
    val duration: Duration,
    val supportingLooks: Int
)

/** Event-existence consensus across looks. Value and pitch disagreements remain separate so a
 * duration error does not masquerade as an invented or missed notehead.
 */
data class EventConsensusEvidence(
    val looks: Int,
    val minimumBaseSupport: Int?,
    val weakBaseEventIndices: List<Int>,
    val majorityNovelEvents: List<NovelEventEvidence>
)

/** Semantic comparison and aggregation for multi-pass readings. */
object ReadingEvidence {
    fun assess(base: Measure, looks: List<Measure>): MeasureEvidence {
        val model = modelEvidence(base)
        val events = eventEvidence(base, looks)
        if (looks.isEmpty()) return MeasureEvidence(0, 0, 0, 1, emptySet(), ReadingStability.UNAUDITED, model, events)
        val agreeing = looks.count { sameMeasure(base, it) }
        val disagreements = looks.flatMapTo(linkedSetOf()) { differences(base, it) }
        val distinct = (listOf(base) + looks).map(::semanticKey).distinct().size
        val stability = when {
            agreeing == looks.size -> ReadingStability.STABLE
            agreeing == 0 -> ReadingStability.UNSTABLE
            else -> ReadingStability.MIXED
        }
        return MeasureEvidence(looks.size, agreeing, looks.count { it.sure }, distinct, disagreements, stability, model, events)
    }

    fun sameMeasure(a: Measure, b: Measure): Boolean = differences(a, b).isEmpty()

    fun differences(a: Measure, b: Measure): Set<ReadingDisagreement> {
        val out = linkedSetOf<ReadingDisagreement>()
        if (a.bars != b.bars || a.repeatsBar != b.repeatsBar || a.repeatStart != b.repeatStart ||
            a.repeatEnd != b.repeatEnd || a.ending != b.ending || a.segno != b.segno || a.coda != b.coda
        ) out += ReadingDisagreement.STRUCTURE
        if (a.clef != b.clef || a.key != b.key || a.time != b.time) out += ReadingDisagreement.SIGNATURE
        if (a.events.size != b.events.size || a.events.indices.any { i -> a.events[i]::class != b.events.getOrNull(i)?.let { it::class } }) {
            out += ReadingDisagreement.EVENT_INVENTORY
        }
        val pairs = a.events.zip(b.events)
        if (pairs.any { (x, y) -> x is Note && y is Note && (x.steps != y.steps || x.pitches != y.pitches || x.accidentals != y.accidentals) }) {
            out += ReadingDisagreement.PITCH
        }
        if (pairs.any { (x, y) -> x.duration != y.duration }) out += ReadingDisagreement.DURATION
        return out
    }

    /** Stable representation used only to count distinct semantic readings. */
    private fun semanticKey(m: Measure): String = buildString {
        append(m.clef).append('|').append(m.key.fifths).append('|').append(m.time.beats).append('/').append(m.time.beatType)
        append('|').append(m.bars).append('|').append(m.repeatsBar).append('|').append(m.repeatStart).append('|').append(m.repeatEnd)
        append('|').append(m.ending).append('|').append(m.segno).append('|').append(m.coda)
        for (e in m.events) when (e) {
            is Note -> append("|n:").append(e.steps).append(':').append(e.pitches).append(':').append(e.accidentals).append(':').append(e.duration)
            is Rest -> append("|r:").append(e.duration)
        }
    }

    fun modelEvidence(m: Measure): ReadingModelEvidence {
        val notes = m.events.filterIsInstance<Note>()
        val withOdds = notes.filter { it.odds.size == 8 }
        fun margin(values: List<Float>, selected: Int): Float {
            if (selected !in values.indices) return -1f
            return values[selected] - values.indices.filter { it != selected }.maxOf { values[it] }
        }
        val valueMargins = withOdds.mapNotNull { note ->
            val selected = when (note.duration.base) { 4 -> 0; 8 -> 1; 16 -> 2; 32 -> 3; else -> return@mapNotNull null }
            margin(note.odds.subList(0, 4), selected)
        }
        val dotMargins = withOdds.map { note -> margin(note.odds.subList(4, 7), note.duration.dots.coerceIn(0, 2)) }
        val headVsRest = notes.mapNotNull { it.headVsRestMargin }
        val rests = m.events.filterIsInstance<Rest>()
        val restVsHead = rests.mapNotNull { it.restVsHeadMargin }
        val restKind = rests.mapNotNull { it.restKindMargin }
        return ReadingModelEvidence(
            notes.size,
            withOdds.size,
            withOdds.minOfOrNull { it.odds[7] },
            valueMargins.minOrNull(),
            dotMargins.minOrNull(),
            headVsRest.minOrNull(),
            restVsHead.minOrNull(),
            restKind.minOrNull()
        )
    }

    fun eventEvidence(base: Measure, looks: List<Measure>): EventConsensusEvidence {
        if (looks.isEmpty()) return EventConsensusEvidence(0, null, emptyList(), emptyList())
        fun sameExistence(a: Event, b: Event): Boolean = when {
            a is Note && b is Note -> a.steps == b.steps && kotlin.math.abs(a.x - b.x) <= maxOf(2f, base.space * .8f)
            a is Rest && b is Rest -> kotlin.math.abs(a.x - b.x) <= maxOf(2f, base.space * .8f)
            else -> false
        }
        val majority = looks.size / 2 + 1
        val support = base.events.map { event -> looks.count { look -> look.events.any { sameExistence(event, it) } } }
        data class Cluster(val event: Event, val looks: MutableSet<Int>)
        val novel = ArrayList<Cluster>()
        looks.forEachIndexed { lookIndex, look ->
            look.events.filter { candidate -> base.events.none { sameExistence(candidate, it) } }.forEach { candidate ->
                val cluster = novel.firstOrNull { sameExistence(it.event, candidate) }
                    ?: Cluster(candidate, linkedSetOf()).also(novel::add)
                cluster.looks += lookIndex
            }
        }
        fun describe(cluster: Cluster): NovelEventEvidence {
            val event = cluster.event
            return NovelEventEvidence(if (event is Note) "note" else "rest", event.x,
                (event as? Note)?.steps.orEmpty(), event.duration, cluster.looks.size)
        }
        return EventConsensusEvidence(looks.size, support.minOrNull(),
            support.indices.filter { support[it] < majority },
            novel.filter { it.looks.size >= majority }.map(::describe).sortedBy { it.x })
    }
}

/** A staff is the useful unit for alternate recognition: the recognizer already reads the whole
 * staff for every look, so auditing all of its measures costs no more than retaining one red bar.
 */
data class ReadingAuditBatch(val page: Int, val staff: Int, val measures: List<Measure>)

object ReadingAuditPlanner {
    /** Every generated single-bar reading not owned by a musician, grouped in page/staff order. */
    fun batches(score: Score, fixedNumbers: Set<Int> = emptySet()): List<ReadingAuditBatch> =
        score.measures.asSequence()
            .filter { it.bars == 1 && it.number !in fixedNumbers }
            .groupBy { it.page to it.staff }
            .entries
            .sortedWith(compareBy({ it.key.first }, { it.key.second }))
            .map { (key, measures) ->
                ReadingAuditBatch(key.first, key.second, measures.sortedWith(compareBy<Measure> { it.box.left }.thenBy { it.number }))
            }
}

/** One independent view used by the idle audit. A null [window] reuses the production binary
 * page; otherwise the original greyscale page is thresholded again for this pass.
 */
data class ReadingAuditVariant(
    val id: String,
    val look: Pair<Float, Float>,
    val window: Int? = null,
    val k: Double = 0.2
)

object ReadingAuditVariants {
    /** Four passes, matching the old audit's count but adding two genuinely different binary
     * pages. Both alternate thresholds conservatively reject background/noise; missed faint marks
     * are handled by symbol-specific passes rather than destabilising the whole page.
     */
    val all: List<ReadingAuditVariant> = listOf(
        ReadingAuditVariant("geometry-large", 1.15f to 0f),
        ReadingAuditVariant("geometry-small", 0.87f to 0f),
        ReadingAuditVariant("ink-clean", 1f to -0.04f, window = 39, k = 0.24),
        ReadingAuditVariant("ink-cleaner", 1f to -0.08f, window = 31, k = 0.28)
    )

    /** Callers hold only one returned page at a time. */
    fun ink(base: Ink, grey: IntArray, variant: ReadingAuditVariant): Ink =
        variant.window?.let { Ink.fromGrey(base.width, base.height, grey, window = it, k = variant.k) } ?: base
}
