package com.inksheets.core.omr

import org.junit.Test

/**
 * For fitting the euphonium to recordings, to WAV only: INKSHEETS_TONECHECK=<file> writes the euphonium (no vibrato, a dry
 * room) on long steady notes across the range at three loudnesses, for measuring with the same script as the recordings;
 * INKSHEETS_DARK=<folder> writes the lyrical passage with the dark, lyrical end of the tone as F-dark-lyrical.wav.
 */
class PlaybackWavTone {
    private fun wavOf(file: java.io.File, rate: Int, seconds: Double, synth: Synth) {
        file.parentFile.mkdirs()
        val w = com.inksheets.core.WavWriter(file, rate)
        val buf = FloatArray(rate / 20)
        repeat((seconds * 20).toInt()) { java.util.Arrays.fill(buf, 0f); synth.fill(buf); w.write(buf) }
        w.close()
        println("PQ wav $file")
    }

    @Test
    fun `the euphonium on steady notes for measuring`() {
        val file = System.getenv("INKSHEETS_TONECHECK")?.takeIf { it.isNotBlank() } ?: return
        val rate = 44_100
        val patch = Synth.euphonium(System.getenv("INKSHEETS_BLEND")?.toDoubleOrNull() ?: 1.0, vibratoCents = 0.0)
        val tones = ArrayList<Synth.Tone>()
        var at = 0L
        for (rep in 0 until 3) for (midi in 40..66) for (v in listOf(0.45f, 0.75f, 1.0f)) {
            tones += Synth.Tone(midi, at, (1.0 * rate).toLong(), v, patch)
            at += (1.4 * rate).toLong()
        }
        val synth = Synth(rate); synth.add(tones)
        wavOf(java.io.File(file), rate, at / rate.toDouble() + 1.0, synth)
    }

    @Test
    fun `the lyrical passage with the dark tone`() {
        val dir = System.getenv("INKSHEETS_DARK")?.takeIf { it.isNotBlank() } ?: return
        val rate = 44_100
        val p = Performance.play(DemoPassages.lyrical(), 66.0, rate, 0, Synth.LOW_BRASS_DARK)
        val synth = Synth(rate); synth.add(p.tones)
        wavOf(java.io.File(dir, (System.getenv("INKSHEETS_PREFIX") ?: "F") + "-dark-lyrical.wav"), rate, p.length / rate.toDouble() + 1.5, synth)
    }
}
