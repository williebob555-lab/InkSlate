package com.inksheets.core.omr

import org.junit.After
import org.junit.Test

/**
 * Writes the passages of [DemoPassages] to WAV, never to the speakers: where INKSHEETS_WAV names a
 * folder (otherwise does nothing), A-, B- and C-<passage>.wav, one for each of the [Feel] settings.
 */
class PlaybackWavDemo {
    @After
    fun restore() { Feel.now = Feel.C }

    @Test
    fun `the passages to wav in each feel`() {
        val dir = System.getenv("INKSHEETS_WAV")?.takeIf { it.isNotBlank() } ?: return
        val rate = 44_100
        for ((name, feel) in listOf("A" to Feel.A, "B" to Feel.B, "C" to Feel.C)) {
            Feel.now = feel
            for ((pass, bars, bpm) in listOf(Triple("legato", DemoPassages.legato(), 96.0), Triple("copprasch", DemoPassages.copprasch(), 108.0))) {
                val p = Performance.play(bars, bpm, rate, 0, Synth.LOW_BRASS)
                val synth = Synth(rate); synth.add(p.tones)
                val wav = java.io.File(dir, "$name-$pass.wav")
                wav.parentFile.mkdirs()
                val w = com.inksheets.core.WavWriter(wav, rate)
                val buf = FloatArray(rate / 20)
                repeat(((p.length / rate.toDouble() + 1.5) * 20).toInt()) { java.util.Arrays.fill(buf, 0f); synth.fill(buf); w.write(buf) }
                w.close()
                println("PQ wav $wav")
            }
        }
    }
}
