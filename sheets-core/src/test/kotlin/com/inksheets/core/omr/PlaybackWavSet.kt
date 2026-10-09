package com.inksheets.core.omr

import org.junit.Test

/**
 * Writes the passages of [DemoPassages] to WAV, never to the speakers: where INKSHEETS_WAV names a folder
 * (otherwise does nothing), <INKSHEETS_PREFIX>-legato.wav and -copprasch.wav for the euphonium, and with
 * INKSHEETS_FAMILIES=1 the legato passage as a trumpet and as a clarinet too (an octave up).
 */
class PlaybackWavSet {
    private fun write(dir: String, name: String, bars: List<Measure>, bpm: Double, patch: Synth.Patch, transpose: Int) {
        val rate = 44_100
        val p = Performance.play(bars, bpm, rate, transpose, patch)
        val synth = Synth(rate); synth.add(p.tones)
        val wav = java.io.File(dir, "$name.wav")
        wav.parentFile.mkdirs()
        val w = com.inksheets.core.WavWriter(wav, rate)
        val buf = FloatArray(rate / 20)
        repeat(((p.length / rate.toDouble() + 1.5) * 20).toInt()) { java.util.Arrays.fill(buf, 0f); synth.fill(buf); w.write(buf) }
        w.close()
        println("PQ wav $wav")
    }

    @Test
    fun `the passages to wav`() {
        val dir = System.getenv("INKSHEETS_WAV")?.takeIf { it.isNotBlank() } ?: return
        val prefix = System.getenv("INKSHEETS_PREFIX") ?: "D"
        write(dir, "$prefix-legato", DemoPassages.legato(), 96.0, Synth.LOW_BRASS, 0)
        write(dir, "$prefix-copprasch", DemoPassages.copprasch(), 108.0, Synth.LOW_BRASS, 0)
        if (System.getenv("INKSHEETS_FAMILIES") == "1") {
            write(dir, "$prefix-trumpet-legato", DemoPassages.legato(), 96.0, Synth.BRASS, -12)
            write(dir, "$prefix-clarinet-legato", DemoPassages.legato(), 96.0, Synth.CLARINET, -12)
        }
    }
}
