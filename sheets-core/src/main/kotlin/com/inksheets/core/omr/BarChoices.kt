package com.inksheets.core.omr

import kotlin.math.abs
import kotlin.math.ln

/**
 * Other readings of a bar the reader was not sure of, best first - three offered to pick from.
 *
 * Each is the reading with a few of its uncertain decisions made the other way: a note a beam
 * longer or shorter, a dot there or not, a rest a size up or down, a note taken out (a smudge or a
 * loop read as one), one the reader saw and let go put back ([Measure.maybe]), a note a step higher
 * or lower, three equal notes taken as a triplet. Every change has a cost - how rarely a change of
 * its kind is the right one ([COSTS], measured on the library), more for a note the reader was
 * surer of - and a reading that makes the bar come to its time is worth far more than one that does
 * not. [rejected] readings are never offered again, and deeper looks further (three changes at
 * once), for when the first ones offered were all wrong.
 */
object BarChoices {
    /** A reading of the bar: its events, what was changed to get it (for saying so), and its cost. */
    data class Choice(val events: List<Event>, val changes: List<String>, val cost: Float, val kinds: List<String> = emptyList()) {
        /** Whether it comes to [quarters] beats. */
        fun addsUp(quarters: Double) = abs(events.sumOf { it.duration.quarters } - quarters) < 1e-6
    }

    /**
     * What each kind of change costs: -ln of how often, among the bars read wrong whose right
     * reading is a change or two away, a change of that kind was one of them (see the reading
     * benchmark's calibration). Rarer kinds cost more.
     */
    var COSTS: Map<String, Float> = mapOf(
        // Fitted on the tuning songs as scans (557 bars read wrong, 95 right a change or two away).
        "rest" to 0.84f, "remove" to 1.17f, "value" to 1.21f, "pitch" to 3.19f, "chord-head" to 3.19f, "dot" to 3.48f,
        // Never the right change there: as dear as the rarest seen.
        "add" to 3.5f, "triplet" to 3.5f, "hollow" to 3.5f,
        // A beamed group's value all at once (one beam counted wrong is every note under it wrong),
        // and a rest taken out (a dynamic, a letter or a breath mark read as one).
        // (Measured on the held-out scans: a rest taken out is the right change as often as -ln says;
        // a group's never yet, so as dear as the rarest.)
        "group" to 3.5f, "rest-out" to 1.44f
    )

    /**
     * One change: to the event at [index] ([make] its new form, or null to take it out), or a new
     * one ([add]) - of kind [kind], costing [cost].
     */
    private class Edit(val index: Int, val kind: String, val cost: Float, val say: String, val make: ((Event) -> Event?)? = null, val add: Event? = null,
                       /** For a beamed group: each of its notes (by index) and the value it becomes. */
                       val group: Map<Int, Int>? = null)

    private fun withBase(e: Event, base: Int): Event = when (e) {
        // Another value: the print's beam and stem end it was read with no longer go with it.
        is Note -> e.copy(duration = e.duration.copy(base = base), beam = if (base == e.duration.base) e.beam else 0, stemTip = if (base == e.duration.base) e.stemTip else null)
        is Rest -> e.copy(duration = e.duration.copy(base = base))
    }

    private fun withDots(e: Event, dots: Int): Event = when (e) {
        is Note -> e.copy(duration = e.duration.copy(dots = dots))
        is Rest -> e.copy(duration = e.duration.copy(dots = dots))
    }

    /** Which one it is in the bar, for saying so: "2nd note", "1st rest". */
    private fun ordinal(n: Int) = "$n" + when { n % 100 in 11..13 -> "th"; n % 10 == 1 -> "st"; n % 10 == 2 -> "nd"; n % 10 == 3 -> "rd"; else -> "th" }

    private fun named(events: List<Event>, i: Int): String {
        val e = events[i]
        val nth = events.take(i + 1).count { it::class == e::class }
        return ordinal(nth) + " " + name(e)
    }

    private fun name(e: Event) = when (e) {
        is Note -> (if (e.steps.size > 1) "chord" else "note") + " " + e.duration.base.let { if (it == 1) "whole" else if (it == 2) "half" else "1/$it" }
        is Rest -> "rest " + e.duration.base.let { if (it == 1) "whole" else if (it == 2) "half" else "1/$it" }
    }

    private fun cost(kind: String, sure: Float) = (COSTS[kind] ?: 2f) + sure * 0.8f

    /** Every single change worth considering to [m]'s reading, cheapest first. */
    private fun edits(m: Measure): List<Edit> {
        val events = m.events
        val out = ArrayList<Edit>()
        fun pitchOf(step: Int): Pitch { val d = m.clef.at(step); return Pitch.fromDiatonic(d, m.key.alterOf(d.mod(7))) }
        for ((i, e) in events.withIndex()) {
            // Where the trained reader gave its odds, each change costs as much as it is less likely.
            val odds = (e as? Note)?.odds.orEmpty()
            if (odds.size == 8) {
                out += likely(events, i, e as Note, odds)
                // A step higher or lower, or a chord's head less: the trained reader's odds say nothing of these.
                val sureOf = e.confidence
                if (e.steps.size > 1) for (st in e.steps) out += Edit(i, "chord-head", cost("chord-head", sureOf), "${named(events, i)}: one head less", { n ->
                    (n as Note).let { c -> val keep = c.steps.indices.filter { c.steps[it] != st }; c.copy(steps = keep.map { c.steps[it] }, pitches = keep.map { c.pitches[it] }) }
                })
                if (e.steps.size == 1) for (d in listOf(-1, 1)) out += Edit(i, "pitch", cost("pitch", sureOf), "${named(events, i)} a step ${if (d < 0) "higher" else "lower"}", { n ->
                    (n as Note).let { c -> val st = c.steps[0] + d; c.copy(steps = listOf(st), pitches = listOf(pitchOf(st)), accidentals = emptyMap()) }
                })
                continue
            }
            // A note the reader was surer of costs more to change.
            val sure = (e as? Note)?.confidence ?: 0.85f
            val base = e.duration.base
            val kind = if (e is Rest) "rest" else "value"
            // One beam (or flag) fewer or more; a hollow head is a half or a whole, nothing between.
            if (base in 8..32) out += Edit(i, kind, cost(kind, sure), "${named(events, i)} as 1/${base / 2}", { withBase(it, base / 2) })
            if (base in 4..16) out += Edit(i, kind, cost(kind, sure), "${named(events, i)} as 1/${base * 2}", { withBase(it, base * 2) })
            if (base == 1 || base == 2) out += Edit(i, "hollow", cost("hollow", sure), "${named(events, i)} as ${if (base == 1) "half" else "whole"}", { withBase(it, if (base == 1) 2 else 1) })
            // A dot there or not.
            out += if (e.duration.dots > 0) Edit(i, "dot", cost("dot", sure * 0.6f), "${named(events, i)} without its dot", { withDots(it, 0) })
            else Edit(i, "dot", cost("dot", sure * 0.6f), "${named(events, i)} dotted", { withDots(it, 1) })
            if (e is Note) {
                // Not a note at all (a smudge, a loop taken for a head) - or one head of a chord not.
                out += Edit(i, "remove", cost("remove", sure), "${named(events, i)} not a note", { null })
                if (e.steps.size > 1) for (st in e.steps) out += Edit(i, "chord-head", cost("chord-head", sure), "${named(events, i)}: one head less", { n ->
                    (n as Note).let { c -> val keep = c.steps.indices.filter { c.steps[it] != st }; c.copy(steps = keep.map { c.steps[it] }, pitches = keep.map { c.pitches[it] }) }
                })
                // A step higher or lower: a head on a line taken for one in a space.
                if (e.steps.size == 1) for (d in listOf(-1, 1)) out += Edit(i, "pitch", cost("pitch", sure), "${named(events, i)} a step ${if (d < 0) "higher" else "lower"}", { n ->
                    (n as Note).let { c -> val st = c.steps[0] + d; c.copy(steps = listOf(st), pitches = listOf(pitchOf(st)), accidentals = emptyMap()) }
                })
            }
        }
        // A rest not there: a dynamic, a letter, a breath mark or a cue's rest read as one.
        for ((i, e) in events.withIndex()) if (e is Rest) out += Edit(i, "rest-out", cost("rest-out", 0f), "${named(events, i)} not a rest", { null })
        // A beamed group all a beam more or fewer: one beam miscounted (a scan's two beams run into
        // one, a slur along them taken for another) is every note under it misread alike.
        for ((beam, members) in events.indices.filter { (events[it] as? Note)?.beam?.let { b -> b != 0 } == true }.groupBy { (events[it] as Note).beam }) {
            if (members.size < 2 || beam == 0) continue
            val bases = members.map { events[it].duration.base }
            val first = members.first()
            for ((label, f) in listOf("a beam fewer" to { b: Int -> b / 2 }, "a beam more" to { b: Int -> b * 2 })) {
                if (bases.any { f(it) !in 8..32 }) continue
                out += Edit(first, "group", cost("group", 0.3f), "the notes beamed from the ${named(events, first)}: $label", group = members.associateWith { i -> f(bases[members.indexOf(i)]) })
            }
        }
        // What the reader saw and let go, put back.
        for (a in m.maybe) out += Edit(-1, "add", cost("add", 1f - ((a as? Note)?.confidence ?: 0.5f)), "${name(a)} there after all", add = a)
        // Three equal notes or rests together, taken as a triplet.
        for (i in 0..events.size - 3) {
            val d = events[i].duration
            if (!d.tuplet && d.dots == 0 && (1..2).all { events[i + it].duration == d }) out += Edit(i, "triplet", cost("triplet", 0f), "a triplet from the ${named(events, i)}")
        }
        return out.sortedBy { it.cost }
    }

    /**
     * The changes to note [i] the trained reader's [odds] allow, each costing -ln of how much less
     * likely it is than what was read: another number of beams or flags, of dots, or not a note.
     */
    private fun likely(events: List<Event>, i: Int, e: Note, odds: List<Float>): List<Edit> {
        val out = ArrayList<Edit>()
        fun c(pNew: Float, pNow: Float) = (-ln(pNew.coerceAtLeast(1e-4f)) + ln(pNow.coerceAtLeast(1e-4f))).coerceAtLeast(0.05f)
        val base = e.duration.base
        if (base >= 4) {
            val now = when (base) { 4 -> 0; 8 -> 1; 16 -> 2; else -> 3 }
            for (k in 0..3) if (k != now) out += Edit(i, "value", c(odds[k], odds[now]), "${named(events, i)} as 1/${4 shl k}", { withBase(it, 4 shl k) })
        } else out += Edit(i, "hollow", cost("hollow", e.confidence), "${named(events, i)} as ${if (base == 1) "half" else "whole"}", { withBase(it, if (base == 1) 2 else 1) })
        val dotsNow = e.duration.dots.coerceIn(0, 2)
        for (d in 0..2) if (d != dotsNow) out += Edit(i, "dot", c(odds[4 + d], odds[4 + dotsNow]),
            "${named(events, i)} ${if (d == 0) "without its dot" else if (d == 1) "dotted" else "double dotted"}", { withDots(it, d) })
        val head = odds[7]
        out += Edit(i, "remove", c(1f - head, head), "${named(events, i)} not a note", { null })
        return out
    }

    private fun apply(m: Measure, chosen: List<Edit>): List<Event>? {
        val out = m.events.toMutableList<Event?>()
        val added = ArrayList<Event>()
        val touched = HashSet<Int>()
        for (e in chosen) {
            if (e.add != null) { added += e.add; continue }
            if (e.group != null) {
                if (e.group.keys.any { it in touched }) return null
                for ((k, base) in e.group) { out[k] = out[k]?.let { withBase(it, base) }; touched += k }
                continue
            }
            if (e.kind == "triplet") {
                if ((0..2).any { e.index + it in touched }) return null
                for (k in 0..2) {
                    val ev = out[e.index + k] ?: return null
                    out[e.index + k] = when (ev) {
                        is Note -> ev.copy(duration = ev.duration.copy(actual = 3, normal = 2))
                        is Rest -> ev.copy(duration = ev.duration.copy(actual = 3, normal = 2))
                    }
                    touched += e.index + k
                }
                continue
            }
            // One change to an event at a time.
            if (!touched.add(e.index)) return null
            out[e.index] = out[e.index]?.let(e.make!!)
        }
        val events = out.filterNotNull().toMutableList()
        // A note put back beside another at the same place is another head of that chord.
        for (a in added) {
            val at = events.indexOfFirst { it is Note && a is Note && abs(it.x - a.x) < m.space * 0.6f && it.duration == a.duration }
            if (at >= 0 && a is Note) {
                val c = events[at] as Note
                if (a.steps[0] in c.steps) return null
                val all = (c.steps.zip(c.pitches) + a.steps.zip(a.pitches)).sortedBy { it.first }
                events[at] = c.copy(steps = all.map { it.first }, pitches = all.map { it.second })
            } else events += a
        }
        return events.sortedBy { it.x }.takeIf { it.isNotEmpty() }
    }

    private fun same(a: List<Event>, b: List<Event>) = a.size == b.size && a.indices.all { i ->
        val x = a[i]; val y = b[i]
        x.duration == y.duration && x::class == y::class && (x !is Note || (y as Note).steps == x.steps)
    }

    /**
     * Up to [count] readings of [m], best first: those coming to its time before any that do not,
     * cheapest first among them - the reading as it is first, when it comes to its time. None
     * matching any of [rejected]. [deeper]: three changes at once, not two. [pool]: how many of the
     * cheapest single changes are combined.
     */
    fun of(m: Measure, count: Int = 3, rejected: List<List<Event>> = emptyList(), deeper: Boolean = false, pool: Int = if (deeper) 60 else 40,
           /** The bar as read on other looks at its staff (a little larger, smaller, higher, lower - see Recognizer.lookAgain). */
           looked: List<Measure> = emptyList()): List<Choice> {
        val beats = m.time.quarters
        val all = ArrayList<Choice>()
        all += Choice(m.events, emptyList(), 0f)
        // What another look saw: as likely as the more of them saw it, and the surer they were.
        for ((events, saw) in looked.groupBy { o -> o.events.joinToString("|") { e -> "${e::class.simpleName}${e.duration}${(e as? Note)?.steps}" } }.values.map { it.first().events to it }) {
            if (same(events, m.events)) continue
            val sure = saw.any { it.sure }
            all += Choice(events, listOf(if (saw.size > 1) "As read on ${saw.size} other looks" else "As read on another look"), (if (sure) 0.4f else 1.0f) - 0.15f * (saw.size - 1), listOf("look"))
        }
        val single = edits(m).take(pool)
        for (a in single.indices) {
            apply(m, listOf(single[a]))?.let { all += Choice(it, listOf(single[a].say), single[a].cost, listOf(single[a].kind)) }
            for (b in a + 1 until single.size) {
                apply(m, listOf(single[a], single[b]))?.let { all += Choice(it, listOf(single[a].say, single[b].say), single[a].cost + single[b].cost, listOf(single[a].kind, single[b].kind)) }
                if (deeper) for (c in b + 1 until minOf(single.size, b + 20)) {
                    apply(m, listOf(single[a], single[b], single[c]))?.let {
                        all += Choice(it, listOf(single[a].say, single[b].say, single[c].say), single[a].cost + single[b].cost + single[c].cost, listOf(single[a].kind, single[b].kind, single[c].kind))
                    }
                }
            }
        }
        // Coming to the bar's time outweighs anything: a reading that does not is a last resort - but
        // the reading as read, where its notes were all clearly seen (a pickup, two voices on one
        // staff, a bar in a time not read), weighs only a little less than one that does. (Held-out
        // scans: of the bars asked about though read right, offered first 71% -> 97%.)
        val clear = m.events.filterIsInstance<Note>().all { it.confidence >= 0.8f }
        val ranked = all.sortedBy { it.cost + if (it.addsUp(beats)) 0f else if (it.changes.isEmpty() && clear) 1.5f else 10f }
        val out = ArrayList<Choice>()
        for (c in ranked) {
            if (rejected.any { same(it, c.events) } || out.any { same(it.events, c.events) }) continue
            out += c
            if (out.size == count) break
        }
        return out
    }

    /** Every reading a change or two away (for measuring which kinds of change are right how often). */
    fun all(m: Measure): List<Choice> = of(m, count = 5000, pool = 60)

    /** Costs from how often each kind of change was among the right ones: [counts] per kind, of [total] right readings. */
    fun costsFrom(counts: Map<String, Int>, total: Int): Map<String, Float> =
        counts.mapValues { (_, n) -> (-ln((n + 1f) / (total + 2f))).toFloat() }
}
