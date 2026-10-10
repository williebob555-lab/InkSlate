package com.inksheets.core.omr

import kotlinx.serialization.Serializable

/**
 * A bar put right in one part of a song, carried over to the same bar of the song's other parts:
 * when another part's reading of that bar is what this part's reading was before it was put right
 * (in concert pitch - the trumpet's and the alto sax's copies of one line), it is wrong in the same
 * way, and the correction, written for that instrument, is the fix. Cautious on purpose: a bar
 * that is only like it is left alone.
 */
object CarryFix {
    /** What [carry] decided for the other part's bar: its new [events] (in the part's own written pitch), and whether it only [confirmed] the reading it had. */
    data class Carried(val bar: Int, val events: List<Event>, val confirmed: Boolean)

    private fun notes(events: List<Event>) = events.any { it is Note }

    /** The rhythm of [events] alone: note or rest, and how long. */
    private fun rhythm(events: List<Event>) = events.map { (it is Note) to it.duration }

    /**
     * Whether [b] (written for an instrument [bSemis] semitones above where it sounds) is [a]
     * ([aSemis]) played the same: the same rests and notes of the same lengths, the same sounding
     * notes - all of them the same whole number of octaves apart. Returns that many octaves (b
     * above a), or null if they differ (or there is no note in them).
     */
    fun octaveOffset(a: List<Event>, aSemis: Int, b: List<Event>, bSemis: Int): Int? {
        if (a.size != b.size || !notes(a) || !notes(b)) return null
        var shift: Int? = null
        for (i in a.indices) {
            val x = a[i]; val y = b[i]
            if (x.duration != y.duration) return null
            if (x is Rest && y is Rest) continue
            if (x !is Note || y !is Note) return null
            if (x.pitches.size != y.pitches.size) return null
            val xs = x.pitches.map { it.midi - aSemis }.sorted()
            val ys = y.pitches.map { it.midi - bSemis }.sorted()
            for (j in xs.indices) {
                val d = ys[j] - xs[j]
                if (d % 12 != 0) return null
                if (shift == null) shift = d / 12 else if (shift != d / 12) return null
            }
        }
        return shift
    }

    /**
     * [events] of bar [m] (part A, [aSemis]) written for the part [b] is a bar of ([bSemis]),
     * [octaves] up - or null if its clef, time or (transposed) key is not [b]'s.
     */
    private fun writtenFor(m: Measure, events: List<Event>, aSemis: Int, b: Measure, bSemis: Int, octaves: Int): List<Event>? {
        if (m.clef != b.clef || m.time != b.time) return null
        val moved = Transpose.measure(m.copy(events = events), bSemis - aSemis + 12 * octaves)
        if (moved.key != b.key) return null
        // Where B's own reading put its events across the bar, where it has as many.
        val out = moved.events
        return if (out.size == b.events.size) out.mapIndexed { i, e ->
            val x = b.events[i].x
            when (e) { is Note -> e.copy(x = x); is Rest -> e.copy(x = x) }
        } else out
    }

    /** A plain bar: one bar, nothing to do with repeating the one before. */
    private fun plain(m: Measure) = m.bars == 1 && !m.repeatsBar

    /**
     * Part A's bar [bar] was read as [aRead] and put right as `aFixes[bar]`; is B's bar [bar]
     * ([bRead]) the same measure? Returns what to do with it, or null to leave it.
     *
     * Rules: both bars are plain single bars with notes in them, in the same clef and time, B's key
     * the transposed key; B's reading equals A's reading before the fix (octave aside); and a
     * neighbouring bar (before or after) is the same in rhythm in both parts - so that two
     * different bars that look alike are not taken for one. If B already reads as the fix, it is
     * only [Carried.confirmed] (and null when it was sure already).
     */
    fun carry(aRead: Score, aFixes: Map<Int, List<Event>>, aSemis: Int, bar: Int, bRead: Score, bSemis: Int): Carried? {
        val fixed = aFixes[bar] ?: return null
        val a = aRead.measures.firstOrNull { it.number == bar } ?: return null
        val b = bRead.measures.firstOrNull { it.number == bar } ?: return null
        if (!plain(a) || !plain(b)) return null
        if (!notes(a.events) || !notes(b.events) || !notes(fixed)) return null
        // A neighbour must agree in rhythm (A's as put right where it was).
        val neighbour = listOf(bar - 1, bar + 1).any { n ->
            val na = aRead.measures.firstOrNull { it.number == n }
            val nb = bRead.measures.firstOrNull { it.number == n }
            if (na == null || nb == null || !plain(na) || !plain(nb) || na.time != nb.time) return@any false
            val ea = aFixes[n] ?: na.events
            notes(ea) && notes(nb.events) && rhythm(ea) == rhythm(nb.events)
        }
        if (!neighbour) return null
        // Already what the fix says: confirmed.
        octaveOffset(fixed, aSemis, b.events, bSemis)?.let { o ->
            if (writtenFor(a, fixed, aSemis, b, bSemis, o) == null) return null
            return if (b.sure) null else Carried(bar, b.events, true)
        }
        val octaves = octaveOffset(a.events, aSemis, b.events, bSemis) ?: return null
        val events = writtenFor(a, fixed, aSemis, b, bSemis, octaves) ?: return null
        return Carried(bar, events, false)
    }
}

/** A bar put right from another part's fix: kept in the part's own edits, so it can be shown and undone. */
@Serializable
data class CarriedFix(
    /** The bar's number in the file (as fixes are numbered). */
    val bar: Int,
    /** The part it came from: its id, name, and bar number (in that file's numbering). */
    val fromId: String,
    val fromName: String,
    val fromBar: Int,
    val events: List<Event>,
    val confirmed: Boolean = false
)
