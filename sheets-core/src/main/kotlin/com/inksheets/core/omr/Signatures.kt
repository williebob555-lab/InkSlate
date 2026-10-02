package com.inksheets.core.omr

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.math.abs

/**
 * A clef, key or time misread, put right by hand: from a bar on, until the print changes it - the
 * reading changing it there, as the print does (a clef or key printed again at each line's start,
 * the same, carries the fix on). Each bar under it given its clef, key or time, and its notes their
 * pitches again from where they sit on the staff - what the beats come to said again too.
 */
@Serializable
data class SigFix(val clef: Clef? = null, val key: Int? = null, val beats: Int? = null, val beatType: Int? = null) {
    val time: TimeSig? get() = if (beats != null && beatType != null) TimeSig(beats, beatType) else null
}

object Signatures {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(Int.serializer(), SigFix.serializer())

    fun encode(fixes: Map<Int, SigFix>): String? = if (fixes.isEmpty()) null else json.encodeToString(serializer, fixes)
    fun decode(text: String?): Map<Int, SigFix> = text?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    /** [events]' pitches again for [clef] and [key]: each from where it sits, an accidental written carrying on through the bar. */
    fun repitch(events: List<Event>, clef: Clef, key: Key): List<Event> {
        val written = HashMap<Int, Int>()
        return events.map { e ->
            if (e !is Note) e else {
                val pitches = e.steps.map { step ->
                    val d = clef.at(step)
                    e.accidentals[step]?.let { written[d] = it }
                    Pitch.fromDiatonic(d, written[d] ?: key.alterOf(d.mod(7)))
                }
                e.copy(pitches = pitches)
            }
        }
    }

    private fun fmt(q: Double) = if (q == Math.floor(q)) q.toInt().toString() else q.toString()

    /** [s] with [fixes] (by the bar each starts at) laid over its reading. */
    fun apply(s: Score, fixes: Map<Int, SigFix>): Score {
        if (fixes.isEmpty()) return s
        var clef: Clef? = null; var key: Key? = null; var time: TimeSig? = null
        var prev: Measure? = null
        val out = s.measures.map { m ->
            // The print changing it (the reading changes there): the fix ends.
            val p = prev
            if (p != null) {
                if (m.clef != p.clef) clef = null
                if (m.key != p.key) key = null
                if (m.time != p.time) time = null
            }
            fixes[m.number]?.let { f -> f.clef?.let { clef = it }; f.key?.let { key = Key(it) }; f.time?.let { time = it } }
            prev = m
            val c = clef; val k = key; val t = time
            if (c == null && k == null && t == null) m else {
                val nc = c ?: m.clef; val nk = k ?: m.key; val nt = t ?: m.time
                val events = if (nc != m.clef || nk != m.key) repitch(m.events, nc, nk) else m.events
                // What the beats come to, said again against the time as it now is.
                var doubts = m.doubts.filterNot { it.contains("beats found") || it.startsWith("time signature not read") }
                val q = events.sumOf { it.duration.quarters }
                if (m.bars == 1 && events.isNotEmpty() && abs(q - nt.quarters) > 1e-6 && m.number != s.measures.first().number)
                    doubts = doubts + "${fmt(q)} beats found, ${fmt(nt.quarters)} expected"
                val graces = if (nc != m.clef || nk != m.key) repitch(m.graces, nc, nk).filterIsInstance<Note>() else m.graces
                m.copy(clef = nc, key = nk, time = nt, events = events, doubts = doubts, graces = graces)
            }
        }
        return s.copy(measures = out)
    }
}
