package com.inksheets.core.omr

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Drum parts played as drums: which drum each note is (by the part - a snare's part, the bass
 * drums', the tenors', the cymbals', a drum set's - and where the note sits on the staff), and how
 * each drum sounds, made on the spot like the rest of [Synth]: a struck skin's falling pitch, a
 * snare's rattle, a cymbal's wash of noise. General MIDI's drum numbers name them.
 */
enum class DrumKind {
    /** A drum set (or percussion not otherwise named): each line and space its own drum, as set notation has it. */
    KIT,
    SNARE,
    /** A drum line's bass drums: five or so, tuned low to high, the highest note on the staff the smallest drum. */
    BASS_DRUMS,
    /** Tenors (quads, quints): four or five drums and a spock, high to low down the staff. */
    TENORS,
    CYMBALS;

    /** The drum note [step] (half-spaces down from the top line) is, as a General MIDI drum number. */
    fun key(step: Int): Int = when (this) {
        SNARE -> 38
        CYMBALS -> 49
        // Played on toms' sound, tuned down (see Synth.BASS_DRUMS) or up (Synth.TENORS).
        BASS_DRUMS, TENORS -> when {
            step <= 1 -> 50; step <= 3 -> 48; step <= 4 -> 47; step <= 5 -> 45; step <= 6 -> 43; else -> 41
        }
        KIT -> when {
            step <= -2 -> 49     // a ledger line over the staff: crash
            step == -1 -> 42     // the space over it: hi-hat
            step == 0 -> 51      // the top line: ride
            step == 1 -> 50      // high tom
            step == 2 -> 47      // mid tom
            step == 3 -> 38      // the third space: snare
            step == 4 -> 45      // low tom
            step <= 6 -> 43      // floor tom
            step <= 8 -> 36      // the bottom space and line: bass drum
            else -> 44           // under the staff: the hi-hat's pedal
        }
    }

    /** Note [n]'s drums, one for each of its heads. */
    fun keys(n: Note): List<Int> = n.steps.map { key(it) }.distinct()

    /** How it sounds. */
    val patch: Synth.Patch get() = when (this) {
        BASS_DRUMS -> Synth.BASS_DRUMS
        TENORS -> Synth.TENORS
        else -> Synth.DRUMS
    }

    companion object {
        private val pitched = listOf("mallet", "xylo", "marimba", "vibe", "bells", "glock", "timpani", "chime", "crotale", "steel pan", "pans")

        /**
         * The drums a part is, from its instrument ([instrumentId]: drums, drumline, percussion) and its
         * name: null for a part with pitches (a tenor sax, mallets, timpani).
         */
        fun of(instrumentId: String?, name: String): DrumKind? {
            val n = name.lowercase()
            val drumWords = Regex("\\b(drums?|drumset|drumline|dl|snare|cymbals?|quads|quints|battery|perc(ussion)?)\\b").containsMatchIn(n)
            val drummy = instrumentId in setOf("drums", "drumline", "percussion") || drumWords
            if (!drummy || pitched.any { it in n }) return null
            return when {
                "bass drum" in n -> BASS_DRUMS
                "tenor" in n || "quad" in n || "quint" in n -> TENORS
                "snare" in n -> SNARE
                "cymbal" in n -> CYMBALS
                else -> KIT
            }
        }
    }
}

/** One drum struck, into [buf] from sample [from]: true when it has rung out. See [Synth]. */
internal class DrumVoice(private val key: Int, private val start: Long, private val velocity: Float, private val accent: Float,
                         private val gain: Float, private val tune: Double, seed: Long) {
    private var noise = seed or 1L
    private var phase = 0.0
    private var hpIn = 0.0; private var hpOut = 0.0

    private fun white(): Double {
        noise = noise * 6364136223846793005L + 1442695040888963407L
        return (noise ushr 33).toDouble() / (1L shl 31) - 1.0
    }

    /** How long it rings, in seconds. */
    private val ring = when (key) {
        49, 57 -> 2.2; 51, 59 -> 1.4; 46 -> 0.6; 42, 44 -> 0.12; 35, 36 -> 0.45; 38, 40 -> 0.35; else -> 0.6
    }

    fun render(buf: FloatArray, from: Long, rate: Int): Boolean {
        val sr = rate.toDouble()
        val strike = velocity * (1f + 0.6f * accent)
        for (i in buf.indices) {
            val s = from + i
            if (s < start) continue
            val t = (s - start) / sr
            if (t > ring) return true
            val x = when (key) {
                // Bass drum: a skin's pitch falling fast, and a click at the front.
                35, 36 -> {
                    val f = (48.0 + 110.0 * exp(-t / 0.035)) * tune
                    phase += 2 * PI * f / sr
                    Synth.sine(phase) * exp(-t / 0.16) * 1.2 + white() * exp(-t / 0.004) * 0.3
                }
                // Snare: a short skin's tone and the wires' rattle (noise, its lows taken out).
                38, 40 -> {
                    phase += 2 * PI * 190.0 * tune / sr
                    Synth.sine(phase) * exp(-t / 0.05) * 0.55 + highpass(white(), 0.7) * exp(-t / 0.11) * 0.75
                }
                // Hi-hat closed, its pedal, and open: bright noise, short or ringing.
                42, 44, 46 -> highpass(white(), 0.92) * exp(-t / (if (key == 46) 0.18 else 0.03)) * 0.6
                // Crash and ride: a wash of bright noise, the ride's with a bell's tone in it.
                49, 57 -> highpass(white(), 0.85) * exp(-t / 0.6) * 0.55 * (1 - exp(-t / 0.003))
                51, 59 -> {
                    phase += 2 * PI * 520.0 / sr
                    highpass(white(), 0.9) * exp(-t / 0.35) * 0.35 + Synth.sine(phase) * exp(-t / 0.4) * 0.12
                }
                // Toms (and tuned drums on them): a skin's pitch, falling a little, ringing.
                else -> {
                    val base = when (key) { 41 -> 82.0; 43 -> 98.0; 45 -> 117.0; 47 -> 139.0; 48 -> 165.0; 50 -> 196.0; else -> 130.0 } * tune
                    val f = base * (1 + 0.45 * exp(-t / 0.04))
                    phase += 2 * PI * f / sr
                    Synth.sine(phase) * exp(-t / 0.22) + white() * exp(-t / 0.006) * 0.25
                }
            }
            buf[i] += (x * strike * gain).toFloat()
        }
        return from + buf.size - start > ring * sr
    }

    /** A first-order high-pass: [a] near 1 keeps only the highest. */
    private fun highpass(x: Double, a: Double): Double {
        hpOut = a * (hpOut + x - hpIn); hpIn = x
        return hpOut
    }
}
