package com.inksheets.core

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * A metronome that is written, not timed.
 *
 * Clicks scheduled with a timer drift and stutter whenever the machine is busy - which is exactly
 * when a page is being rendered. So the clicks are placed in the audio itself: [fill] writes the
 * next stretch of samples with each click at the sample where it belongs, and the platform's only
 * job is to keep the output buffer full. Timing is then as exact as the sound card's clock.
 */
class Metronome(private val sampleRate: Int = 48_000) {

    enum class Accent { STRONG, NORMAL, SUB, SILENT }

    data class Settings(
        val bpm: Double = 100.0,
        /** Beats in a bar; the first is accented. */
        val beatsPerBar: Int = 4,
        /** Clicks per beat: 1 plain, 2 eighths, 3 triplets, 4 sixteenths. */
        val subdivision: Int = 1,
        /** Per-beat accents overriding the default (first strong, rest normal). */
        val accents: List<Accent>? = null,
        val volume: Float = 0.8f
    ) {
        fun accentOf(beat: Int): Accent =
            accents?.getOrNull(beat) ?: if (beat == 0) Accent.STRONG else Accent.NORMAL
    }

    @Volatile
    var settings = Settings()

    /** Called on the audio thread at each beat (not subdivision), with the beat number in the bar. */
    var onBeat: ((beat: Int) -> Unit)? = null

    private var sampleInTick = 0L
    private var tick = 0L
    private var voice: FloatArray? = null
    private var voiceAt = 0

    private val strong = click(1760.0, 0.035)
    private val normal = click(1320.0, 0.03)
    private val sub = click(990.0, 0.02, gain = 0.45f)

    /** Start again from the first beat of a bar. */
    fun reset() {
        sampleInTick = 0
        tick = 0
        voice = null
    }

    /** Write the next [out].size mono samples. */
    fun fill(out: FloatArray) {
        val s = settings
        val samplesPerTick = sampleRate * 60.0 / s.bpm / s.subdivision.coerceAtLeast(1)
        for (i in out.indices) {
            if (sampleInTick == 0L) start(s)
            var v = 0f
            val playing = voice
            if (playing != null) {
                v = playing[voiceAt++] * s.volume
                if (voiceAt >= playing.size) voice = null
            }
            out[i] = v
            sampleInTick++
            if (sampleInTick >= samplesPerTick) {
                sampleInTick = 0
                tick++
            }
        }
    }

    private fun start(s: Settings) {
        val sub = s.subdivision.coerceAtLeast(1)
        val inBeat = (tick % sub).toInt()
        val beat = ((tick / sub) % s.beatsPerBar.coerceAtLeast(1)).toInt()
        val accent = if (inBeat == 0) s.accentOf(beat) else Accent.SUB
        voice = when (accent) {
            Accent.STRONG -> strong
            Accent.NORMAL -> normal
            Accent.SUB -> this.sub
            Accent.SILENT -> null
        }
        voiceAt = 0
        if (inBeat == 0) onBeat?.invoke(beat)
    }

    /** A short pitched click with a fast decay: clearer than noise over a band. */
    private fun click(hz: Double, seconds: Double, gain: Float = 1f): FloatArray {
        val n = (sampleRate * seconds).toInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() / sampleRate
            (sin(2 * PI * hz * t) * exp(-t * 90) * gain).toFloat()
        }
    }

    companion object {
        /**
         * Tempo from taps: the average of the most recent intervals, ignoring a gap long enough
         * to be a new start. Returns null until there are two taps to go on.
         */
        fun tapTempo(tapsMs: List<Long>): Double? {
            // Only the taps since the last pause: a gap of more than three seconds is a new start.
            val sinceStart = tapsMs.drop(
                tapsMs.zipWithNext().indexOfLast { (a, b) -> b - a > 3000 } + 1
            )
            val gaps = sinceStart.takeLast(8).zipWithNext { a, b -> b - a }.filter { it >= 150 }
            if (gaps.isEmpty()) return null
            return 60_000.0 / gaps.takeLast(4).average()
        }
    }
}
