package com.inksheets.core.omr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Written music as a player would play it, not as a machine: the notes it says, how loud its
 * dynamics and hairpins say (mf until told), accents and marcatos leant on, staccatos short,
 * tenutos and slurred notes held full and joined, tied notes held through - not struck again - a
 * fermata held over, a ritardando slowing to its "a tempo", an accelerando pressing on. What a
 * bar does not say, it plays plainly. Each bar as long as its time (a pickup as its notes - see
 * [Measure.playedQuarters]), stretched where the tempo gives.
 */
object Performance {
    /** The tones, where each bar starts (samples from [at]), its number, and the whole length. */
    class Played(val tones: List<Synth.Tone>, val barStarts: LongArray, val barNumbers: IntArray, val length: Long)

    /** How loud each dynamic is played, 0-1 (mf 0.68). */
    val LEVELS = mapOf("ppp" to 0.22f, "pp" to 0.32f, "p" to 0.44f, "mp" to 0.56f, "mf" to 0.68f, "f" to 0.82f, "ff" to 0.94f, "fff" to 1.04f)

    /** A dynamic that strikes one note hard and then returns ("sfz"), or drops at once after ("fp"). */
    private val STRUCK = setOf("sf", "sfz", "sffz", "fz", "rfz", "rf", "sfp", "fp", "sfzp")

    private fun tempoWord(text: String): Int? {
        val w = text.lowercase().trim('.', ' ')
        return when {
            w.startsWith("rit") || w.startsWith("rall") || w.startsWith("allarg") || w.startsWith("slower") || w.startsWith("morendo") || w.startsWith("calando") -> 1
            w.startsWith("accel") || w.startsWith("string") || w.startsWith("faster") -> -1
            w == "tempo" || w.startsWith("a tempo") || w == "primo" || w.startsWith("tempo i") || w.startsWith("tempo 1") -> 0
            else -> null
        }
    }

    private class Held(val midi: Int, val start: Long, var end: Long, val velocity: Float)

    /**
     * [bars] (in the order played) at [bpm] quarter notes a minute, [rate] samples a second, as
     * [patch] sounds them ([transpose] semitones written above sounding), from sample [at].
     */
    fun play(bars: List<Measure>, bpm: Double, rate: Int, transpose: Int, patch: Synth.Patch, at: Long = 0L): Played {
        val tones = ArrayList<Synth.Tone>()
        val starts = ArrayList<Long>(); val numbers = ArrayList<Int>()
        val perQuarter = rate * 60.0 / bpm
        var t = at.toDouble()
        var level = LEVELS.getValue("mf")
        var tempo = 1.0          // beats' length against the written tempo: over 1 is slower
        var pressing = 0         // 1 slowing, -1 pressing on, 0 steady
        val open = HashMap<Int, Held>()
        val hairpinsDone = HashSet<String>()
        val gap = rate / 60L      // a hair between notes, so repeated ones are heard apart

        fun flush(midi: Int) { open.remove(midi)?.let { h -> tones += Synth.Tone(h.midi, h.start, max(1L, h.end - h.start), h.velocity, patch) } }
        fun flushAll() { for (k in open.keys.toList()) flush(k) }

        for (m in bars) {
            starts += (t - at).toLong(); numbers += m.number
            val played = m.playedQuarters
            // A new section (its own time or key printed) starts in time again.
            if (m.showsTime || m.showsKey) { tempo = 1.0; pressing = 0 }
            if (m.bars > 1 || m.events.all { it is Rest }) {
                flushAll()
                t += played * perQuarter * tempo
                continue
            }
            val sp = m.space
            val dyn = m.directions.filter { it.kind == "dynamic" }.sortedBy { it.x }
            val words = m.directions.filter { it.kind == "text" }.mapNotNull { d -> tempoWord(d.text)?.let { d.x to it } }.sortedBy { it.first }
            val pins = m.directions.filter { it.kind == "cresc" || it.kind == "dim" }
            val slurs = m.directions.filter { it.kind == "slur" }
            var di = 0; var wi = 0
            var q = 0.0
            var struck: String? = null
            val barStart = t
            for (e in m.events) {
                if (q >= played - 1e-9) break
                val len = min(e.duration.quarters, played - q)
                // What is marked at or before this note: dynamics, tempo words.
                while (di < dyn.size && dyn[di].x <= e.x + sp) {
                    val text = dyn[di].text.lowercase()
                    LEVELS[text]?.let { level = it }
                    if (text in STRUCK) struck = text
                    di++
                }
                while (wi < words.size && words[wi].first <= e.x + sp) {
                    when (words[wi].second) { 0 -> { tempo = 1.0; pressing = 0 }; else -> pressing = words[wi].second }
                    wi++
                }
                // A hairpin past: the level it was heading for, held (until a dynamic says otherwise).
                for (h in pins) {
                    val key = "${m.page}/${m.staff}/${h.x}/${h.x2}"
                    if (e.x > h.x2 && key !in hairpinsDone) { hairpinsDone += key; level = (level + if (h.kind == "cresc") 0.18f else -0.18f).coerceIn(0.15f, 1.1f) }
                }
                val beats = len * perQuarter * tempo
                if (e is Note) {
                    var v = level
                    // In a hairpin: on the way there.
                    for (h in pins) if (e.x >= h.x && e.x <= h.x2 && h.x2 > h.x) {
                        val f = ((e.x - h.x) / (h.x2 - h.x)).coerceIn(0f, 1f)
                        v += (if (h.kind == "cresc") 0.18f else -0.18f) * f
                    }
                    val a = e.articulations
                    when {
                        struck != null -> { v = max(v + 0.3f, 0.95f); if (struck!!.endsWith("p")) level = LEVELS.getValue("p"); struck = null }
                        "marcato" in a -> v += 0.3f
                        "accent" in a -> v += 0.22f
                    }
                    val slurred = slurs.any { s -> e.x >= s.x - sp * 0.5f && e.x < s.x2 - sp * 0.8f }
                    val sounding = when {
                        "staccatissimo" in a -> beats * 0.3
                        "staccato" in a -> beats * 0.5
                        "marcato" in a -> beats * 0.8
                        "tenuto" in a || slurred -> beats
                        else -> beats - gap
                    }
                    // A fermata: held about twice over, then a breath before going on.
                    val hold = if ("fermata" in a) beats * 0.9 + rate * 0.15 else 0.0
                    val start = t.toLong()
                    val end = (t + sounding + hold).toLong()
                    val velocity = (v.coerceIn(0.1f, 1.15f) * 1.15f)
                    val pitches = e.pitches.map { (it.midi - transpose).coerceIn(12, 115) }
                    // Tied notes no longer sounding go; one tied into this note sounds on through it.
                    for (k in open.keys.toList()) if (k !in pitches) flush(k)
                    for (p in pitches) {
                        val h = open[p]
                        if (h != null) h.end = end else open[p] = Held(p, start, end, velocity)
                        if (!e.tie) flush(p)
                    }
                    t += beats + hold
                } else {
                    flushAll()
                    t += beats
                }
                // Slowing or pressing on, a little more every beat (to half again as slow, or a third quicker).
                if (pressing == 1) tempo = min(1.5, tempo + 0.07 * len)
                if (pressing == -1) tempo = max(0.75, tempo - 0.05 * len)
                q += len
            }
            // A bar whose notes fall short of it: the rest of it, at the tempo it ends in.
            if (q < played - 1e-9) t += (played - q) * perQuarter * tempo
            if (t < barStart) t = barStart
        }
        flushAll()
        return Played(tones, starts.toLongArray(), numbers.toIntArray(), (t - at).toLong())
    }
}
