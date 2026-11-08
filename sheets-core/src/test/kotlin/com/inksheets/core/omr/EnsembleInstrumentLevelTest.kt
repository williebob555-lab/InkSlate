package com.inksheets.core.omr

import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Test

/** Field balance is measured through the same four-partial fallback used by a large band. */
class EnsembleInstrumentLevelTest {
    private val rate = 24_000

    private fun level(instrument: String, midi: Int): Double {
        val synth = Synth(rate).apply { harmonicLimit = 4; roomEnabled = false }
        synth.add(listOf(Synth.Tone(midi, 0, rate.toLong(), .72f, Synth.patchFor(instrument))))
        val audio = FloatArray(rate)
        synth.fill(audio)
        val from = rate / 5
        val to = rate * 4 / 5
        return sqrt((from until to).sumOf { audio[it].toDouble() * audio[it] } / (to - from))
    }

    @Test fun `large ensemble fallback levels are measured at representative pitches`() {
        val levels = linkedMapOf(
            "piccolo" to level("piccolo", 84),
            "flute" to level("flute", 76),
            "clarinet" to level("clarinet", 67),
            "trumpet" to level("trumpet", 67),
            "trombone" to level("trombone", 50),
            "euphonium" to level("euphonium", 45),
            "tuba" to level("tuba", 36)
        )
        println("ensemble fallback RMS " + levels.entries.joinToString { "${it.key}=${"%.5f".format(it.value)}" })
        assertTrue(levels.values.all { it > .001 })
        val corrected = levels.mapValues { (instrument, rms) -> rms * EnsembleBalance.default(instrument) }
        // A single high wind cuts through many simultaneous parts; low voices need substantially
        // more electrical level to remain present after masking, while total ensemble energy is
        // normalised separately by EnsemblePlayer.
        assertTrue(corrected.getValue("trombone") > corrected.getValue("trumpet") * 3.0)
        assertTrue(corrected.getValue("tuba") > corrected.getValue("piccolo") * 7.0)
        assertTrue(EnsembleBalance.default("drums") < 1f)
    }
}
