package com.inksheets.core.omr

import org.junit.Test

/** The same passages through the engine as it is, to WAV (INKSHEETS_WAV): now-<passage>.wav. */
class PlaybackWavNow {
    @Test
    fun `the passages to wav as now`() {
        val dir = System.getenv("INKSHEETS_WAV")?.takeIf { it.isNotBlank() } ?: return
        val rate = 44_100
        for ((pass, bars, bpm) in listOf(Triple("legato", DemoPassages.legato(), 96.0), Triple("copprasch", DemoPassages.copprasch(), 108.0))) {
            val p = Performance.play(bars, bpm, rate, 0, Synth.LOW_BRASS)
            val synth = Synth(rate); synth.add(p.tones)
            val wav = java.io.File(dir, "now-$pass.wav")
            wav.parentFile.mkdirs()
            val w = com.inksheets.core.WavWriter(wav, rate)
            val buf = FloatArray(rate / 20)
            repeat(((p.length / rate.toDouble() + 1.5) * 20).toInt()) { java.util.Arrays.fill(buf, 0f); synth.fill(buf); w.write(buf) }
            w.close()
            println("PQ wav $wav")
        }
    }
}
