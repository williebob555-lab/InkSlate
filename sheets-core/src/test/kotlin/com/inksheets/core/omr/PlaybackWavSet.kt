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
        Feel.drift = System.getenv("INKSHEETS_DRIFT")?.toDoubleOrNull() ?: 0.0
        write(dir, "$prefix-legato", DemoPassages.legato(), 96.0, Synth.LOW_BRASS, 0)
        write(dir, "$prefix-copprasch", DemoPassages.copprasch(), 108.0, Synth.LOW_BRASS, 0)
        write(dir, "$prefix-accents", DemoPassages.accents(), 92.0, Synth.LOW_BRASS, 0)
        write(dir, "$prefix-lyrical", DemoPassages.lyrical(), 66.0, Synth.LOW_BRASS, 0)
        if (System.getenv("INKSHEETS_EXPRESSIONS") == "1") {
            // How much the vibrato (and its swings) is: x1.5 and x0.6.
            try {
                Feel.expression = 1.5; write(dir, "$prefix-lyrical-more", DemoPassages.lyrical(), 66.0, Synth.LOW_BRASS, 0)
                Feel.expression = 0.6; write(dir, "$prefix-lyrical-less", DemoPassages.lyrical(), 66.0, Synth.LOW_BRASS, 0)
            } finally { Feel.expression = 1.0 }
        }
        if (System.getenv("INKSHEETS_FAMILIES") == "1") {
            write(dir, "$prefix-trumpet-legato", DemoPassages.legato(), 96.0, Synth.BRASS, -12)
            write(dir, "$prefix-clarinet-legato", DemoPassages.legato(), 96.0, Synth.CLARINET, -12)
        }
    }

    /** With INKSHEETS_ONSETS=1: for each onset model A-D (see Feel.onset), X-accents, X-copprasch and X-onenote (a Bb2 plain, accent, marcato, staccato, a second apart). */
    @Test
    fun `the onset models to wav`() {
        val dir = System.getenv("INKSHEETS_WAV")?.takeIf { it.isNotBlank() } ?: return
        if (System.getenv("INKSHEETS_ONSETS") != "1") return
        try {
            for (model in "ABCD") {
                Feel.onset = model
                write(dir, "$model-accents", DemoPassages.accents(), 92.0, Synth.LOW_BRASS, 0)
                write(dir, "$model-copprasch", DemoPassages.copprasch(), 108.0, Synth.LOW_BRASS, 0)
                val rate = 44_100
                val one = listOf(
                    Synth.Tone(46, 0, (0.5 * rate).toLong(), 0.7f, Synth.LOW_BRASS),
                    Synth.Tone(46, rate.toLong(), (0.5 * rate).toLong(), 0.7f, Synth.LOW_BRASS, accent = 0.8f, art = Synth.ART_ACCENT),
                    Synth.Tone(46, 2L * rate, (0.5 * rate).toLong(), 0.7f, Synth.LOW_BRASS, accent = 1f, art = Synth.ART_MARCATO),
                    Synth.Tone(46, 3L * rate, (0.12 * rate).toLong(), 0.7f, Synth.LOW_BRASS, art = Synth.ART_STACCATO))
                val synth = Synth(rate); synth.add(one)
                val wav = java.io.File(dir, "$model-onenote.wav")
                val w = com.inksheets.core.WavWriter(wav, rate)
                val buf = FloatArray(rate / 20)
                repeat(5 * 20) { java.util.Arrays.fill(buf, 0f); synth.fill(buf); w.write(buf) }
                w.close()
                println("PQ wav $wav")
            }
        } finally { Feel.onset = 'A' }
    }
}
