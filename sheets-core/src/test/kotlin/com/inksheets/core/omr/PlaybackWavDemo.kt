package com.inksheets.core.omr

import org.junit.Test

/** Writes a euphonium-ish legato passage to WAV for listening to, where INKSHEETS_WAV names a folder (otherwise does nothing). */
class PlaybackWavDemo {
    @Test
    fun `a legato euphonium passage to wav`() {
        val dir = System.getenv("INKSHEETS_WAV")?.takeIf { it.isNotBlank() } ?: return
        val rate = 44_100
        fun pitchOf(midi: Int): Pitch {
            val names = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6)
            val alters = intArrayOf(0, 1, 0, 1, 0, 0, 1, 0, 1, 0, 1, 0)
            return Pitch(names[midi % 12], midi / 12 - 1, alters[midi % 12])
        }
        fun bar(n: Int, ms: List<Int>, base: Int, arts: Map<Int, List<String>>, dirs: List<Direction>, ties: Set<Int> = emptySet()): Measure {
            val gap = if (base == 8) 40f else 90f
            val left = (n - 1) * 400
            return Measure(n, 0, 0, Box(left, 0, left + 400, 40), 10f, Clef.TREBLE, Key(-2), TimeSig(4, 4),
                ms.mapIndexed { i, m -> Note(listOf(0), listOf(pitchOf(m)), Duration(base), left + 20f + i * gap, articulations = arts[i].orEmpty(), tie = i in ties) }, directions = dirs)
        }
        // Bars 1-2: one long slur over eight quarter notes, mp. Bars 3-4: a tie across the barline. Bar 5: slurred pairs,
        // staccato, an accent. Bars 6-7: p, then f.
        val slur12 = Direction("slur", 20f, 400f + 20f + 3 * 90f + 5f)
        val bars = listOf(
            bar(1, listOf(46, 49, 53, 51), 4, emptyMap(), listOf(slur12, Direction("dynamic", 5f, text = "mp"))),
            bar(2, listOf(53, 51, 49, 46), 4, emptyMap(), listOf(slur12)),
            bar(3, listOf(48, 50, 53, 55), 4, emptyMap(), emptyList(), ties = setOf(3)),
            bar(4, listOf(55, 53, 51, 50), 4, emptyMap(), emptyList()),
            bar(5, listOf(46, 48, 50, 51, 53, 51, 50, 53), 8, mapOf(3 to listOf("staccato"), 4 to listOf("staccato"), 7 to listOf("accent")),
                listOf(Direction("slur", 4 * 400f + 20f, 4 * 400f + 65f), Direction("slur", 4 * 400f + 20f + 5 * 40f, 4 * 400f + 20f + 6 * 40f + 5f))),
            bar(6, listOf(46, 46, 49, 46), 4, emptyMap(), listOf(Direction("dynamic", 2005f, text = "p"))),
            bar(7, listOf(46, 46, 49, 46), 4, emptyMap(), listOf(Direction("dynamic", 2405f, text = "f")))
        )
        val p = Performance.play(bars, 96.0, rate, 0, Synth.LOW_BRASS)
        val synth = Synth(rate); synth.add(p.tones)
        val wav = java.io.File(dir, System.getenv("INKSHEETS_WAV_NAME") ?: "euphonium-legato.wav")
        wav.parentFile.mkdirs()
        val w = com.inksheets.core.WavWriter(wav, rate)
        val buf = FloatArray(rate / 20)
        repeat(((p.length / rate.toDouble() + 1.5) * 20).toInt()) { java.util.Arrays.fill(buf, 0f); synth.fill(buf); w.write(buf) }
        w.close()
        println("PQ wav $wav")
    }
}
