package com.inksheets.core.omr

/**
 * Music read off the page: its measures, and in each the notes and rests in order, with the clef,
 * key and time they are read in - and how sure the reading is, so a measure read badly can be
 * shown for checking rather than trusted.
 */
enum class Clef(
    /** The pitch on the top line, as a diatonic number (C4 = 28). */
    val topLine: Int
) {
    TREBLE(Pitch.diatonic(5, 3)),   // F5
    BASS(Pitch.diatonic(3, 5)),     // A3
    ALTO(Pitch.diatonic(4, 4)),     // G4
    TENOR(Pitch.diatonic(4, 2));    // E4

    /** The pitch at [step] half-spaces below the top line (0 the top line, 8 the bottom). */
    fun at(step: Int): Int = topLine - step
}

/** A written pitch: [step] 0-6 is C to B, [octave] as in C4 = middle C, [alter] -1 flat, +1 sharp. */
data class Pitch(val step: Int, val octave: Int, val alter: Int = 0) {
    val midi: Int get() = 12 * (octave + 1) + SEMITONES[step] + alter
    val diatonic: Int get() = octave * 7 + step
    override fun toString() = "CDEFGAB"[step] + (if (alter > 0) "#".repeat(alter) else "b".repeat(-alter)) + octave

    companion object {
        val SEMITONES = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        fun diatonic(octave: Int, step: Int) = octave * 7 + step
        fun fromDiatonic(d: Int, alter: Int = 0) = Pitch(d.mod(7), Math.floorDiv(d, 7), alter)
    }
}

/** A note value: [base] 1 whole, 2 half, 4 quarter, 8, 16, 32; with dots. */
data class Duration(val base: Int, val dots: Int = 0) {
    /** Length in quarter notes. */
    val quarters: Double get() {
        var q = 4.0 / base
        var add = q
        repeat(dots) { add /= 2; q += add }
        return q
    }
    val beams: Int get() = when (base) { 8 -> 1; 16 -> 2; 32 -> 3; else -> 0 }
}

/** Sharps (positive) or flats (negative) in the key signature. */
data class Key(val fifths: Int) {
    /** How [step] is altered by the key. */
    fun alterOf(step: Int): Int {
        val sharps = intArrayOf(3, 0, 4, 1, 5, 2, 6)   // F C G D A E B
        val flats = intArrayOf(6, 2, 5, 1, 4, 0, 3)    // B E A D G C F
        return when {
            fifths > 0 && step in sharps.take(fifths) -> 1
            fifths < 0 && step in flats.take(-fifths) -> -1
            else -> 0
        }
    }
}

data class TimeSig(val beats: Int, val beatType: Int) {
    val quarters: Double get() = beats * 4.0 / beatType
}

sealed class Event {
    abstract val duration: Duration
    /** Where it is across the page, in pixels, or in staff spaces in a drawn score. */
    abstract val x: Float
}

/**
 * A note or chord. [steps] are where the heads sit (half-spaces below the top line), [pitches]
 * what they mean with the clef, key and accidentals; [accidentals] as written, by step.
 */
data class Note(
    val steps: List<Int>,
    val pitches: List<Pitch>,
    override val duration: Duration,
    override val x: Float,
    val accidentals: Map<Int, Int> = emptyMap(),
    val stemUp: Boolean? = null,
    /** How well the heads matched, 0-1. */
    val confidence: Float = 1f
) : Event()

data class Rest(override val duration: Duration, override val x: Float) : Event()

/** A box on a page, in pixels. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

data class Measure(
    /** Counting from 1 through the part. */
    val number: Int,
    /** 0-based page, and which staff on it (0 the top one). */
    val page: Int,
    val staff: Int,
    /** Where it is on the page: the staff's lines, from the barline before to the one after. */
    val box: Box,
    /** Pixels to a staff space on that page. */
    val space: Float,
    val clef: Clef,
    val key: Key,
    val time: TimeSig,
    val events: List<Event>,
    /** The clef, key or time printed at its start (rather than carried on). */
    val showsClef: Boolean = false,
    val showsKey: Boolean = false,
    val showsTime: Boolean = false,
    /** Why it may be read wrong: "4 beats found, 3 expected". */
    val doubts: List<String> = emptyList(),
    /** How many bars it stands for: more than one for a multi-bar rest ("rest 4 bars"). */
    val bars: Int = 1
) {
    /** Read well enough to trust: its notes fill the bar exactly, and nothing was in doubt. */
    val sure: Boolean get() = doubts.isEmpty()

    val quarters: Double get() = events.sumOf { it.duration.quarters }
}

/** A part read off its pages. */
data class Score(val measures: List<Measure>, val pages: Int) {
    /** Which measures are on [page]. */
    fun onPage(page: Int) = measures.filter { it.page == page }

    /** How long each measure lasts at [bpm] (quarter notes a minute), in ms, and where it starts. */
    fun timeline(bpm: Double): List<Pair<Measure, Long>> {
        var t = 0.0
        return measures.map { m ->
            val start = t
            val q = if (m.bars > 1) m.time.quarters * m.bars else m.quarters.takeIf { it > 0 } ?: m.time.quarters
            t += q * 60_000.0 / bpm
            m to start.toLong()
        }
    }
}
