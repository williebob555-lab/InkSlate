package com.inksheets.core

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Finding the pitch of a played note, and naming it the way the player reads it.
 *
 * The detector is YIN (de Cheveigné & Kawahara, 2002): it looks for the lag at which the signal
 * best matches itself, which for brass and bass is far steadier than looking for the loudest
 * frequency - a trombone's second harmonic is often louder than its fundamental, and a peak-picker
 * reads it an octave high. The window has to hold at least two periods of the lowest note wanted:
 * a bass guitar's low E is 41.2 Hz, so at 48 kHz that is about 2,400 samples; [windowFor] works it
 * out.
 */
object Tuner {

    data class Reading(
        /** Detected frequency in Hz. */
        val hz: Double,
        /** How sure: 0 (noise) .. 1 (a clean tone). */
        val clarity: Double
    )

    data class Note(
        /** "B♭", named in the player's key. */
        val name: String,
        val octave: Int,
        /** How far off, in cents: negative is flat. */
        val cents: Double,
        /** The sounding (concert) note's name, for reference beside a transposed one. */
        val concertName: String
    )

    /** Samples needed to hear down to [lowestHz]. */
    fun windowFor(sampleRate: Int, lowestHz: Double = 38.0): Int =
        (2.2 * sampleRate / lowestHz).toInt()

    /**
     * The pitch in [samples], or null for silence or noise.
     *
     * [threshold] is YIN's absolute threshold; 0.15 accepts a real instrument through a laptop
     * microphone without accepting room noise.
     */
    fun detect(
        samples: FloatArray,
        sampleRate: Int,
        lowestHz: Double = 38.0,
        highestHz: Double = 1400.0,
        threshold: Double = 0.15
    ): Reading? {
        // Too quiet to be playing.
        val rms = sqrt(samples.sumOf { (it * it).toDouble() } / samples.size)
        if (rms < 0.004) return null

        val maxLag = minOf(samples.size / 2, (sampleRate / lowestHz).toInt() + 2)
        val minLag = maxOf(2, (sampleRate / highestHz).toInt())
        if (maxLag <= minLag + 2) return null
        val window = samples.size - maxLag

        // Difference function and its cumulative mean normalisation.
        val d = DoubleArray(maxLag + 1)
        for (lag in 1..maxLag) {
            var sum = 0.0
            for (i in 0 until window) {
                val delta = samples[i] - samples[i + lag]
                sum += delta * delta
            }
            d[lag] = sum
        }
        val cmnd = DoubleArray(maxLag + 1)
        cmnd[0] = 1.0
        var running = 0.0
        for (lag in 1..maxLag) {
            running += d[lag]
            cmnd[lag] = if (running == 0.0) 1.0 else d[lag] * lag / running
        }

        // The first dip below the threshold, followed down to its bottom.
        var lag = minLag
        var found = -1
        while (lag < maxLag) {
            if (cmnd[lag] < threshold) {
                while (lag + 1 < maxLag && cmnd[lag + 1] < cmnd[lag]) lag++
                found = lag
                break
            }
            lag++
        }
        if (found < 0) {
            // No clean dip: take the deepest, but only if it is convincing.
            var best = minLag
            for (l in minLag until maxLag) if (cmnd[l] < cmnd[best]) best = l
            if (cmnd[best] > 0.35) return null
            found = best
        }

        // Parabolic interpolation between neighbouring lags, for fractions of a cent.
        val refined = if (found in 1 until maxLag) {
            val a = cmnd[found - 1]
            val b = cmnd[found]
            val c = cmnd[found + 1]
            val denominator = a - 2 * b + c
            if (abs(denominator) > 1e-12) found + (a - c) / (2 * denominator) else found.toDouble()
        } else found.toDouble()

        return Reading(sampleRate / refined, (1 - cmnd[found]).coerceIn(0.0, 1.0))
    }

    private val sharps = listOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")

    /**
     * Name [hz] as a note. [transpose] is the instrument's written-above-sounding interval in
     * semitones (Instrument.transpose), so a treble-clef baritone playing a concert B♭ is told C.
     */
    fun note(hz: Double, transpose: Int = 0, a4: Double = 440.0): Note {
        val midiExact = 69 + 12 * log2(hz / a4)
        val midi = midiExact.roundToInt()
        val cents = (midiExact - midi) * 100
        val written = midi + transpose
        return Note(
            name = sharps[Math.floorMod(written, 12)],
            octave = Math.floorDiv(written, 12) - 1,
            cents = cents,
            concertName = sharps[Math.floorMod(midi, 12)]
        )
    }

    /** The frequency of a note, for drones and reference tones. */
    fun hzOf(midi: Int, a4: Double = 440.0): Double = a4 * 2.0.pow((midi - 69) / 12.0)
}
