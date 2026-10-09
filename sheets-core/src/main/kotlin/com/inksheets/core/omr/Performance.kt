package com.inksheets.core.omr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Written music as a player would play it, not as a machine: the notes it says, how loud its
 * dynamics and hairpins say (mf until told), accents and marcatos leant on, staccatos short,
 * tenutos and slurred notes held full and joined, tied notes held through - not struck again - a
 * fermata held over, a ritardando slowing to its "a tempo", an accelerando pressing on. What a
 * bar does not say, it plays plainly. Each bar as long as its time (a pickup as its notes - see
 * [Measure.playedQuarters]), stretched where the tempo gives.
 */
object Performance {
    /** The tones, where each bar starts (samples from [at]), its number, and the whole length. */
    class Played(val tones: List<Synth.Tone>, val barStarts: LongArray, val barNumbers: IntArray, val length: Long)

    /** How loud each dynamic is played, 0-1 (mf 0.68). */
    val LEVELS = mapOf("ppp" to 0.22f, "pp" to 0.32f, "p" to 0.44f, "mp" to 0.56f, "mf" to 0.68f, "f" to 0.82f, "ff" to 0.94f, "fff" to 1.04f)

    /** A dynamic that strikes one note hard and then returns ("sfz"), or drops at once after ("fp"). */
    private val STRUCK = setOf("sf", "sfz", "sffz", "fz", "rfz", "rf", "sfp", "fp", "sfzp")

    /**
     * [bars] (in the order played) at [bpm] quarter notes a minute, [rate] samples a second, as
     * [patch] sounds them ([transpose] semitones written above sounding), from sample [at]: the
     * whole passage planned first and played as a player would ([Interpretation]).
     */
    fun play(bars: List<Measure>, bpm: Double, rate: Int, transpose: Int, patch: Synth.Patch, at: Long = 0L,
             /** Drums: the drums each note is, in place of its pitches (see [DrumKind]). */
             drums: DrumKind? = null): Played {
        val lengths = DoubleArray(bars.size) { bars[it].playedQuarters }
        val plan = Interpretation.analyse(bars, lengths, drums)
        val map = Interpretation.tempoMap(listOf(plan), bpm, ensemble = false)
        val tones = Interpretation.tones(plan, map, rate, transpose, patch, at, 1f, drums)
        val starts = LongArray(bars.size) { Math.round(map.seconds(plan.barQ[it]) * rate) }
        val numbers = IntArray(bars.size) { bars[it].number }
        return Played(tones, starts, numbers, Math.round(map.seconds(plan.totalQ) * rate))
    }
}
