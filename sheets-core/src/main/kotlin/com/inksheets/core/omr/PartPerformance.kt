package com.inksheets.core.omr

/** Plans independent part stems against a single performed timeline, without rendering audio. */
object PartPerformance {
    /** The caller chooses each part's sound and optional percussion mapping. */
    data class Sound(val patch: Synth.Patch, val drums: DrumKind? = null)
    data class Part(val id: String, val tones: List<Synth.Tone>)
    /** Starts and length are sample offsets from zero; numbers can repeat in playback order. */
    class Plan(val parts: List<Part>, val barStarts: LongArray, val barNumbers: IntArray, val length: Long)

    /**
     * The first selected staff containing measures leads the play order and bar lengths. Other
     * staves align by written bar number, as in EnsemblePlayer; they should describe the same
     * written bar structure. [writtenBars], when present, includes every played occurrence of
     * each written bar in the inclusive range, including individual bars of multi-bar rests.
     * Each staff is analysed independently so its phrasing and ties remain local to that staff.
     */
    fun plan(document: ScoreDocument, bpm: Double, rate: Int,
             selection: ScoreSelection = document.defaultSelection(), writtenBars: IntRange? = null,
             sound: (ScorePart) -> Sound): Plan {
        require(bpm.isFinite() && bpm > 0) { "bpm must be finite and positive" }
        require(rate > 0) { "sample rate must be positive" }
        require(writtenBars == null || !writtenBars.isEmpty()) { "bar range must not be empty" }
        val selected = document.selectedParts(selection)
        val leadRef = selected.asSequence().flatMap { part ->
            part.staves.asSequence().mapIndexed { staffIndex, staff -> Triple(part, staffIndex, staff) }
        }.firstOrNull { it.third.measures.isNotEmpty() }
        val lead = leadRef?.third
        val numbers = ArrayList<Int>()
        val canonicalIds = ArrayList<String>()
        val lengths = ArrayList<Double>()
        for (measure in lead?.let { PlayOrder.unrolled(it).measures }.orEmpty()) {
            val leadScore = leadRef?.third ?: continue
            val sourceIndex = leadScore.measures.indexOfFirst { it === measure }.takeIf { it >= 0 }
                ?: leadScore.measures.indexOf(measure)
            val aligned = leadRef.let { (part, staffIndex, _) ->
                document.alignmentFor(part.id, staffIndex, sourceIndex)?.canonicalBarIds
            }.orEmpty()
            for (offset in 0 until measure.bars) {
                val number = measure.number + offset
                if (writtenBars != null && number !in writtenBars) continue
                numbers += number
                canonicalIds += aligned.getOrNull(offset) ?: "written-$number"
                lengths += if (measure.bars > 1) measure.time.quarters else measure.playedQuarters
            }
        }
        val len = lengths.toDoubleArray()
        val sounds = selected.map(sound)
        val plans = selected.mapIndexed { index, part ->
            part.staves.mapIndexed { staffIndex, staff ->
                val alignedMeasures = document.alignments.asSequence()
                    .filter { it.partId == part.id && it.staffIndex == staffIndex }
                    .flatMap { alignment -> alignment.canonicalBarIds.asSequence().map { it to alignment.measureIndex } }
                    .toMap()
                val bars = numbers.indices.map { timelineIndex ->
                    alignedMeasures[canonicalIds[timelineIndex]]?.let(staff.measures::getOrNull)
                        ?: staff.measures.firstOrNull { numbers[timelineIndex] >= it.number && numbers[timelineIndex] < it.number + it.bars }
                }.map { measure ->
                    measure
                        ?.takeIf { it.bars == 1 }
                }
                Interpretation.analyse(bars, len, sounds[index].drums)
            }
        }
        val allPlans = plans.flatten()
        val leadIndex = selected.flatMap { it.staves }.indexOfFirst { it === lead }
        val tempoPlans = if (leadIndex > 0) listOf(allPlans[leadIndex]) + allPlans.filterIndexed { i, _ -> i != leadIndex } else allPlans
        val map = Interpretation.tempoMap(tempoPlans, bpm, ensemble = allPlans.size > 1)
        val parts = selected.mapIndexed { index, part ->
            Part(part.id, plans[index].flatMap { staff ->
                Interpretation.tones(staff, map, rate, part.transposition, sounds[index].patch, drums = sounds[index].drums)
            }.sortedBy { it.start })
        }
        var q = 0.0
        val starts = LongArray(len.size) { i ->
            val start = Math.round(map.seconds(q) * rate)
            q += len[i]
            start
        }
        return Plan(parts, starts, numbers.toIntArray(), Math.round(map.seconds(q) * rate))
    }
}
