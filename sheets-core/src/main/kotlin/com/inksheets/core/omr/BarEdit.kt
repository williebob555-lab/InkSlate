package com.inksheets.core.omr

/**
 * A bar put right by hand, one event at a time - for when none of the readings offered is what is
 * printed. Each change gives the bar's events anew; [m] is the bar, for its clef and key.
 */
object BarEdit {
    private fun pitchOf(m: Measure, step: Int): Pitch {
        val d = m.clef.at(step)
        return Pitch.fromDiatonic(d, m.key.alterOf(d.mod(7)))
    }

    private fun replace(events: List<Event>, i: Int, e: Event?): List<Event> =
        events.toMutableList<Event?>().also { it[i] = e }.filterNotNull()

    /** Event [i] [by] steps higher (negative) or lower - a chord as a whole. A rest stays as it is. */
    fun step(m: Measure, events: List<Event>, i: Int, by: Int): List<Event> {
        val n = events.getOrNull(i) as? Note ?: return events
        val steps = n.steps.map { it + by }
        return replace(events, i, n.copy(steps = steps, pitches = steps.map { pitchOf(m, it) }, accidentals = emptyMap()))
    }

    /** Event [i] a [base] (1 whole, 2 half, 4 quarter, 8 eighth...), its dots kept, out of any triplet. */
    fun length(events: List<Event>, i: Int, base: Int): List<Event> {
        val e = events.getOrNull(i) ?: return events
        val d = e.duration.copy(base = base, actual = 1, normal = 1)
        return replace(events, i, when (e) { is Note -> e.copy(duration = d, beam = 0, stemTip = null); is Rest -> e.copy(duration = d) })
    }

    /** Event [i] dotted, or its dot taken off. */
    fun dot(events: List<Event>, i: Int): List<Event> {
        val e = events.getOrNull(i) ?: return events
        val d = e.duration.copy(dots = if (e.duration.dots > 0) 0 else 1)
        return replace(events, i, when (e) { is Note -> e.copy(duration = d); is Rest -> e.copy(duration = d) })
    }

    /** Event [i] a rest if a note, or a note on the middle line if a rest - its length kept. */
    fun restOrNote(m: Measure, events: List<Event>, i: Int): List<Event> {
        val e = events.getOrNull(i) ?: return events
        return replace(events, i, when (e) {
            is Note -> Rest(e.duration, e.x)
            is Rest -> Note(listOf(4), listOf(pitchOf(m, 4)), e.duration, e.x)
        })
    }

    /** Event [i] taken out. */
    fun delete(events: List<Event>, i: Int): List<Event> = if (i in events.indices) replace(events, i, null) else events

    /** A quarter note after event [i] (or first, for -1), at its pitch - the middle line after a rest. */
    fun addAfter(m: Measure, events: List<Event>, i: Int): List<Event> {
        val at = events.getOrNull(i)
        val step = (at as? Note)?.steps?.first() ?: 4
        val x = if (at == null) (events.firstOrNull()?.x ?: 0f) - 0.5f
            else (at.x + (events.getOrNull(i + 1)?.x ?: (at.x + 2f))) / 2f
        val n = Note(listOf(step), listOf(pitchOf(m, step)), Duration(4), x)
        return events.toMutableList().also { it.add(i + 1, n) }
    }

    /** Events [i]..[i]+2 made a triplet, or not one if they are. */
    fun triplet(events: List<Event>, i: Int): List<Event> {
        if (i < 0 || i + 2 >= events.size) return events
        val off = (i..i + 2).all { events[it].duration.tuplet }
        return events.mapIndexed { k, e ->
            if (k !in i..i + 2) e else {
                val d = if (off) e.duration.copy(actual = 1, normal = 1) else e.duration.copy(actual = 3, normal = 2)
                when (e) { is Note -> e.copy(duration = d); is Rest -> e.copy(duration = d) }
            }
        }
    }

    /** What [events] come to, in quarter notes. */
    fun quarters(events: List<Event>): Double = events.sumOf { it.duration.quarters }
}
