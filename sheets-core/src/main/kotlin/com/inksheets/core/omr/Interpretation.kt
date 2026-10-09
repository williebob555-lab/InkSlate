package com.inksheets.core.omr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Music played as a musician plays it: the whole piece looked at before a note sounds.
 *
 * A note machine plays each note as it comes - the same weight on every beat, every note the same
 * length, every phrase ending where the next begins. A player reads ahead: where each phrase goes
 * and where it rests, which note is its high point, where to breathe, when to lean on a beat and
 * when to let the line carry on through it, how a ritardando eases into the cadence it leads to.
 * So this works in two passes:
 *
 * 1. **Plan** ([analyse]): every note of every part put on one timeline, in quarters; the music cut
 *    into phrases (at rests, breaths, fermatas, the ends of slurs, long notes at the ends of bars,
 *    new sections - and no phrase longer than a long breath); each note given its loudness from
 *    the dynamics and hairpins, an arch over its phrase, the line's rise and fall and the weight
 *    of the beat it falls on; and its length from its articulation, its slur and its place.
 * 2. **Time** ([tempoMap]): one tempo for everyone - steady, but easing into the end of a phrase,
 *    more into the end of a section and most into the last bar; ritardandos and accelerandos as
 *    curves, not steps; fermatas held; a breath taken between phrases.
 *
 * Then [tones] turns a part's plan into what the [Synth] plays: swells on long notes, slurred notes
 * joined without a new attack, accents with their bite, a hair of human looseness in time and
 * weight. The same everywhere, every time: nothing random that differs between plays.
 */
object Interpretation {

    /** One note (or chord) as planned: when, how long, how loud, how joined. */
    class Planned(
        /** Where it starts and how long it is written, in quarters on the piece's timeline. */
        val q: Double,
        val len: Double,
        /** Its pitches as written (MIDI), or the drums it is (drum parts). */
        val keys: IntArray,
        val articulations: List<String>,
        val graces: List<IntArray>,
        /** Which bar of the timeline it is in, and where in its bar (quarters). */
        val bar: Int,
        val inBar: Double,
        val fermata: Boolean,
        val breathAfter: Boolean
    ) {
        var slurred = false          // joined to the note after it
        var tie = false              // held on into the next note of its pitch, not struck again
        var level = 0.68f            // its loudness, 0-1 (mf 0.68)
        var endLevel = 0.68f         // where its loudness has gone by its end (a swell, a taper)
        var accent = 0f
        var phraseEnd = false        // the last note of a phrase
        var phraseStart = false
        var sectionEnd = false
        /** How much of its written time it sounds (1 = all of it; over 1 overlaps the next, legato). */
        var sounding = 1.0
    }

    /** A phrase: from [startQ] to [endQ], its high point at [peakQ]. */
    class Phrase(val startQ: Double, val endQ: Double, val peakQ: Double, val section: Boolean,
                 /** How much the way into its end eases, 0-1: by what ends it - a rest or a new section most, a breath not at all. */
                 val ease: Float = 1f, val last: Boolean = false)

    /** A part's plan: its notes, its phrases, and the timeline's bars. */
    class Plan(val notes: List<Planned>, val phrases: List<Phrase>, val barQ: DoubleArray, val totalQ: Double,
               /** Tempo words where they come (q, +1 slowing, -1 pressing on, 0 a tempo). */
               val words: List<Pair<Double, Int>>)

    /** A break at a breath mark, and at a slur's end: phrases end there, but no time is taken. */
    private const val BREATH = 0.86f
    private const val SLUR_END = 0.55f

    /** How loud each dynamic is played, 0-1. */
    val LEVELS = Performance.LEVELS
    private val STRUCK = setOf("sf", "sfz", "sffz", "fz", "rfz", "rf", "sfp", "fp", "sfzp")

    // ---- the plan --------------------------------------------------------------------------------

    /**
     * [bars] - one part's bars along the timeline, in playing order; null where the part has no
     * bar (it rests) - analysed. [lengths] is each timeline bar's length in quarters.
     * [drums]: the drums each note is, in place of its pitches.
     */
    fun analyse(bars: List<Measure?>, lengths: DoubleArray, drums: DrumKind? = null): Plan {
        val barQ = DoubleArray(bars.size + 1)
        for (i in bars.indices) barQ[i + 1] = barQ[i] + lengths[i]
        val notes = ArrayList<Planned>()
        val words = ArrayList<Pair<Double, Int>>()
        // Where breaks may fall: (q, strength 0-1, a new section).
        val breaks = ArrayList<Triple<Double, Float, Boolean>>()
        var level = LEVELS.getValue("mf")
        var pendingStruck: String? = null
        val pinsDone = HashSet<String>()
        // A hairpin that runs to its bar's end (to the end of the line, often) goes on into the next
        // bar until a dynamic says where it got to: its change per bar, and bars left.
        var carry = 0f
        var carryBars = 0
        for ((bi, m) in bars.withIndex()) {
            val start = barQ[bi]
            if (m == null || m.bars > 1 || m.events.all { it is Rest }) {
                // Resting: whatever came before ends there.
                breaks += Triple(start, 0.9f, false)
                continue
            }
            // A new section: a repeat, or a key or time that is not the one before. A key or time merely
            // printed again at the start of a new line (a system break) is no section - the music goes straight on.
            val before = (bi - 1 downTo 0).firstNotNullOfOrNull { bars[it] }
            if (bi > 0 && (m.repeatStart || (before != null && ((m.showsKey && m.key != before.key) || (m.showsTime && m.time != before.time))))) breaks += Triple(start, 1f, true)
            val sp = m.space
            val dyn = m.directions.filter { it.kind == "dynamic" }.sortedBy { it.x }
            val pins = m.directions.filter { it.kind == "cresc" || it.kind == "dim" }
            val slurs = m.directions.filter { it.kind == "slur" }
            val breaths = m.directions.filter { it.kind == "breath" }.map { it.x }
            // A hairpin reaching the bar's end with no dynamic after it: carried into the next bar.
            pins.firstOrNull { it.x2 >= m.box.right - sp * 1.5f && dyn.none { d -> d.x > it.x2 - sp } }?.let { h ->
                carry = if (h.kind == "cresc") 0.12f else -0.12f
                carryBars = 2
            }
            for (d in m.directions.filter { it.kind == "text" }) tempoWord(d.text)?.let { w ->
                val ev = m.events.firstOrNull { it.x >= d.x - sp } ?: m.events.lastOrNull()
                var qq = 0.0
                for (e in m.events) { if (e === ev) break; qq += e.duration.quarters }
                words += (start + qq) to w
            }
            val graces = m.gracesBefore()
            var q = 0.0
            var di = 0
            val played = lengths[bi]
            // Carried in from the bar before: spread over this one, unless it starts afresh.
            val carrying = if (carryBars > 0 && dyn.none { it.x < m.box.left + sp * 6 } && pins.none { it.x < m.box.left + sp * 4 }) carry else 0f
            if (carrying == 0f) carryBars = 0 else carryBars--
            for ((i, e) in m.events.withIndex()) {
                if (q >= played - 1e-9) break
                val len = min(e.duration.quarters, played - q)
                while (di < dyn.size && dyn[di].x <= e.x + sp) {
                    val t = dyn[di].text.lowercase()
                    LEVELS[t]?.let { level = it }
                    if (t in STRUCK) pendingStruck = t
                    di++
                }
                for (h in pins) {
                    val key = "${m.page}/${m.staff}/${h.x}/${h.x2}"
                    if (e.x > h.x2 && key !in pinsDone) { pinsDone += key; level = (level + if (h.kind == "cresc") 0.16f else -0.16f).coerceIn(0.15f, 1.05f) }
                }
                // The carried hairpin, this far through this bar.
                if (carrying != 0f) level = (level + carrying * (len / played).toFloat()).coerceIn(0.15f, 1.05f)
                if (e is Note) {
                    val keys = (drums?.keys(e) ?: e.pitches.map { it.midi }).toIntArray()
                    val nextX = m.events.getOrNull(i + 1)?.x ?: Float.MAX_VALUE
                    val p = Planned(start + q, len, keys, e.articulations, graces[e].orEmpty().map { g -> (drums?.keys(g) ?: g.pitches.map { it.midi }).toIntArray() },
                        bi, q, "fermata" in e.articulations, breaths.any { it > e.x && it < nextX })
                    var v = level
                    for (h in pins) if (e.x >= h.x && e.x <= h.x2 && h.x2 > h.x) {
                        val f = ((e.x - h.x) / (h.x2 - h.x)).coerceIn(0f, 1f)
                        v += (if (h.kind == "cresc") 0.16f else -0.16f) * f
                        // Within a hairpin the note itself grows (or fades) as it sounds.
                        p.endLevel = v + (if (h.kind == "cresc") 0.16f else -0.16f) * (len.toFloat() / 4f).coerceAtMost(0.5f)
                    }
                    p.level = v
                    if (p.endLevel == 0.68f) p.endLevel = v
                    pendingStruck?.let { st -> p.accent = 1f; p.level = max(v + 0.3f, 0.95f); if (st.endsWith("p")) level = LEVELS.getValue("p"); pendingStruck = null }
                    when {
                        // Leant on, not shouted: the bite is in the attack (Synth's accent), the
                        // loudness only a little more - and never past fortissimo.
                        "marcato" in e.articulations -> { p.accent = max(p.accent, 1f); p.level = min(p.level + 0.07f, max(v, 1.0f)) }
                        "accent" in e.articulations -> { p.accent = max(p.accent, 0.8f); p.level = min(p.level + 0.05f, max(v, 0.98f)) }
                        "sforzando" in e.articulations -> { p.accent = 1f; p.level = min(p.level + 0.1f, 1.02f) }
                    }
                    // Under a slur, all but the note it ends on (or all, where it runs on past the bar's edge into the next).
                    p.slurred = slurs.any { s ->
                        e.x >= s.x - sp * 0.5f && e.x <= s.x2 + sp * 0.5f &&
                            (e.x < s.x2 - sp * 0.8f || (s.x2 >= m.box.right - sp && e === lastUnder(m, s, sp)))
                    } || e.tie
                    // A tie carries on into the next note: one sound, not two.
                    if (e.tie) { p.slurred = true; p.tie = true }
                    notes += p
                    // A breath is taken from the note before it - the beat after it comes on time.
                    if (p.breathAfter) breaks += Triple(start + q + len, BREATH, false)
                    if (p.fermata) breaks += Triple(start + q + len, 1f, false)
                    // The end of a slur: the end of a breath, often.
                    if (slurs.any { s -> abs(e.x - (s.x2 - sp)) < sp * 1.5f }) breaks += Triple(start + q + len, SLUR_END, false)
                } else {
                    // A rest: a break in the line by how long it is - a bar's rest ends a phrase; a
                    // beat's is a breath inside one (a figure repeated bar after bar is one line,
                    // not a phrase a bar).
                    val strength = when { len >= played - 1e-6 || len >= 3.0 -> 0.9f; len >= 1.0 -> 0.6f; len >= 0.5 -> 0.4f; else -> 0f }
                    if (strength > 0f) breaks += Triple(start + q, strength, false)
                }
                q += len
            }
        }
        val total = barQ.last()
        slurGroups(notes, drums, breaks, if (barQ.size > 1) total / (barQ.size - 1) else 4.0)
        val phrases = phrasesOf(notes, breaks, barQ, total)
        shape(notes, phrases, barQ, bars, lengths)
        return Plan(notes, phrases, barQ, total, words.sortedBy { it.first })
    }

    /** The last note of [m] under slur [s]. */
    private fun lastUnder(m: Measure, s: Direction, sp: Float): Note? =
        m.events.filterIsInstance<Note>().lastOrNull { it.x >= s.x - sp * 0.5f && it.x <= s.x2 + sp * 0.5f }

    /**
     * Slurs that are ties, and slurs that are phrases. A slur joining just two notes of one pitch
     * (over a barline, over a line's end) is a tie: one held sound. A slur carrying a line over a
     * bar or more is a phrase: its end, a place to breathe.
     */
    private fun slurGroups(notes: List<Planned>, drums: DrumKind?, breaks: MutableList<Triple<Double, Float, Boolean>>, bar: Double) {
        var i = 0
        while (i < notes.size) {
            if (!notes[i].slurred || (i > 0 && notes[i - 1].slurred && abs(notes[i - 1].q + notes[i - 1].len - notes[i].q) < 1e-6)) { i++; continue }
            var j = i
            while (j < notes.size - 1 && notes[j].slurred && abs(notes[j].q + notes[j].len - notes[j + 1].q) < 1e-6) j++
            val first = notes[i]; val last = notes[j]
            if (j == i + 1 && drums == null && !first.tie && first.keys.isNotEmpty() && first.keys.contentEquals(last.keys)) { first.tie = true }
            else if (j > i && last.q + last.len - first.q >= bar * 0.9) breaks += Triple(last.q + last.len, 0.86f, false)
            i = j + 1
        }
    }

    /** The phrases: cut at the strongest breaks, none longer than about eight bars, none shorter than one. */
    private fun phrasesOf(notes: List<Planned>, breaks: List<Triple<Double, Float, Boolean>>, barQ: DoubleArray, total: Double): List<Phrase> {
        if (notes.isEmpty()) return emptyList()
        // A long note at the end of a bar with shorter ones after it: where a line rests, too.
        val more = ArrayList(breaks)
        for (i in 0 until notes.size - 1) {
            val n = notes[i]; val next = notes[i + 1]
            val end = n.q + n.len
            val atBarEnd = abs(end - barQ[min(n.bar + 1, barQ.size - 1)]) < 1e-6
            if (n.len >= 2.0 && atBarEnd && next.len < n.len) more += Triple(end, 0.6f, false)
            if (n.len >= 1.5 && next.q - end > 0.24) more += Triple(end, 0.7f, false)
        }
        val cuts = more.filter { it.first > notes.first().q + 1e-6 && it.first < notes.last().q + 1e-6 }
            .groupBy { (it.first * 64).toLong() }.map { (_, g) -> Triple(g.first().first, g.maxOf { it.second }, g.any { it.third }) }
            .sortedBy { it.first }
        val bar = if (barQ.size > 1) (barQ.last() / (barQ.size - 1)).coerceAtLeast(1.0) else 4.0
        // Keep the strong breaks; then split what is still too long at its best break, near its middle.
        val chosen = ArrayList(cuts.filter { it.second >= 0.85f })
        val strength = cuts.associate { it.first to it.second }
        fun spans(): List<Pair<Double, Double>> {
            val pts = listOf(notes.first().q) + chosen.map { it.first }.sorted() + (notes.last().q + notes.last().len)
            return pts.zipWithNext()
        }
        var guard = 0
        while (guard++ < 200) {
            val long = spans().firstOrNull { (a, b) -> b - a > bar * 8.5 } ?: break
            val (a, b) = long
            val mid = (a + b) / 2
            val best = cuts.filter { it.first > a + bar && it.first < b - bar && chosen.none { c -> c.first == it.first } }
                .maxByOrNull { it.second - abs(it.first - mid) / (b - a) } ?: Triple(barQ.minByOrNull { abs(it - mid) } ?: mid, 0.3f, false)
            if (chosen.any { abs(it.first - best.first) < 1e-6 }) break
            chosen += best
        }
        chosen.sortBy { it.first }
        // A phrase of less than a bar and a half is part of the one before it (a short figure
        // after a rest is not a phrase of its own).
        run {
            var last = notes.first().q
            val keep = ArrayList<Triple<Double, Float, Boolean>>()
            for (c in chosen) { if (c.first - last >= bar * 1.5 - 1e-6 || c.third) { keep += c; last = c.first } }
            chosen.clear(); chosen += keep
        }
        val sections = chosen.filter { it.third }.map { it.first }.toSet()
        return spans().filter { (a, b) -> b - a > 1e-6 }.mapIndexed { i, (a, b) ->
            val inside = notes.filter { it.q >= a - 1e-6 && it.q < b - 1e-6 }
            // Its high point: the highest of its longer notes, past its first few - where a line leans.
            val peak = inside.filter { it.q > a + (b - a) * 0.2 }.maxByOrNull { (it.keys.maxOrNull() ?: 0) * 4.0 + it.len * 3.0 }?.q ?: (a + (b - a) * 0.6)
            val at = strength.entries.firstOrNull { abs(it.key - b) < 1e-6 }?.value
            val ease = when {
                at == null -> 0f
                at == BREATH || at == SLUR_END -> 0f
                at >= 0.95f -> 1f       // a fermata, a section
                at >= 0.85f -> 0.6f     // a bar's rest: the rest is most of the breath
                else -> 0.4f
            }
            Phrase(a, b, peak, sections.any { abs(it - b) < 1e-6 }, ease, last = i == chosen.size)
        }
    }

    /** Each note's loudness and length, from its phrase, its line and its beat. */
    private fun shape(notes: List<Planned>, phrases: List<Phrase>, barQ: DoubleArray, bars: List<Measure?>, lengths: DoubleArray) {
        val at = HashMap<Planned, Int>().also { m -> notes.forEachIndexed { k, n -> m[n] = k } }
        for (ph in phrases) {
            val inside = notes.filter { it.q >= ph.startQ - 1e-6 && it.q < ph.endQ - 1e-6 }
            if (inside.isEmpty()) continue
            inside.first().phraseStart = true
            inside.last().phraseEnd = true
            inside.last().sectionEnd = ph.section
            val mean = inside.flatMap { it.keys.toList() }.average()
            val span = (ph.endQ - ph.startQ).coerceAtLeast(1e-6)
            val peakU = ((ph.peakQ - ph.startQ) / span).coerceIn(0.25, 0.85)
            for (n in inside) {
                val idx = at.getValue(n)
                val u = (n.q - ph.startQ) / span
                // An arch over the phrase: rising to its high point, easing away after it.
                val arch = if (u <= peakU) (u / peakU) else (1.0 - (u - peakU) / (1.0 - peakU))
                var d = (0.13 * arch.pow(1.3) - 0.05).toFloat()
                // The line: higher notes a little fuller, lower a little lighter.
                val top = n.keys.maxOrNull() ?: mean.toInt()
                d += ((top - mean) * 0.005).toFloat().coerceIn(-0.06f, 0.06f)
                // The beat: the bar's first leant on, its strong beats a little, the off-beats light -
                // unless a slur carries the line through them.
                val m = bars.getOrNull(n.bar)
                val beat = m?.time?.let { 4.0 / it.beatType } ?: 1.0
                val beats = m?.time?.beats ?: 4
                val inBeat = n.inBar / beat
                val onBeat = abs(inBeat - Math.rint(inBeat)) < 1e-6
                val weight = when {
                    n.inBar < 1e-6 -> 0.05f
                    onBeat && beats % 2 == 0 && abs(inBeat - beats / 2.0) < 1e-6 -> 0.025f
                    onBeat && beats % 3 == 0 && (Math.rint(inBeat).toInt() % 3 == 0) -> 0.025f
                    onBeat -> 0f
                    // Off the beat and held over it: a syncopation, leant on.
                    n.len > beat * 0.5 + 1e-6 -> 0.035f
                    else -> -0.02f
                }
                // Carried through by a slur: the line goes on over the beat and the barline.
                val inSlur = n.slurred || (idx > 0 && notes[idx - 1].slurred && abs(notes[idx - 1].q + notes[idx - 1].len - n.q) < 1e-6)
                d += if (inSlur && weight > 0) 0f else weight
                // A phrase's last note: let go, not pushed.
                if (n.phraseEnd) d -= 0.04f
                n.level += d
                n.endLevel += d
                // Long notes live: a swell toward the high point, a taper after it and at the end.
                if (n.len >= 1.5 && abs(n.endLevel - n.level) < 1e-4f) {
                    n.endLevel = when {
                        n.phraseEnd -> n.level * 0.82f
                        u < peakU -> n.level * 1.1f
                        else -> n.level * 0.93f
                    }
                }
            }
            // A run of quick notes: driven - each beat's first a touch firmer.
            for (n in inside) if (n.len <= 0.25 + 1e-9 && n.inBar % 1.0 < 1e-6) n.level += 0.03f
        }
        // How long each note sounds, from its articulation and where it is.
        for ((i, n) in notes.withIndex()) {
            val next = notes.getOrNull(i + 1)
            val touching = next != null && next.q - (n.q + n.len) < 1e-6
            val a = n.articulations
            n.sounding = when {
                "staccatissimo" in a -> 0.28
                "staccato" in a -> 0.48
                "marcato" in a -> 0.72
                "tenuto" in a -> 1.0
                n.slurred && touching && !n.phraseEnd -> 1.0      // joined to the next, no gap
                "accent" in a -> DETACHED_ACCENT
                else -> DETACHED
            }
            n.level = n.level.coerceIn(0.08f, 1.25f)
            n.endLevel = n.endLevel.coerceIn(0.05f, 1.3f)
        }
    }

    /** A plain note, and an accented one, let go a little before the next: see [tones]. */
    private const val DETACHED = -1.0
    private const val DETACHED_ACCENT = -2.0

    private fun tempoWord(text: String): Int? {
        // "poco rit.", "molto rall.", "a little slower": the word that says it, wherever it comes.
        val w = text.lowercase().trim('.', ' ').removePrefix("poco a poco ").removePrefix("poco ").removePrefix("molto ").removePrefix("più ").removePrefix("piu ")
        return when {
            w.startsWith("rit") || w.startsWith("rall") || w.startsWith("allarg") || w.startsWith("slower") || w.startsWith("morendo") || w.startsWith("calando") -> 1
            w.startsWith("accel") || w.startsWith("string") || w.startsWith("faster") -> -1
            w == "tempo" || w.startsWith("a tempo") || w == "primo" || w.startsWith("tempo i") || w.startsWith("tempo 1") -> 0
            else -> null
        }
    }

    // ---- time --------------------------------------------------------------------------------

    /**
     * One tempo for the whole band: quarters to samples, steady at [bpm] but breathing - the way
     * into each phrase's end eased, more into a section's end and most into the last bar, words
     * of tempo as curves, fermatas held. [plans]' phrase ends count where most parts agree; the
     * first plan (the part being played from) leads.
     */
    class TempoMap(private val cumulative: DoubleArray, private val step: Double, val totalQ: Double) {
        /** Seconds from the timeline's start to quarter [q]. */
        fun seconds(q: Double): Double {
            if (q <= 0) return 0.0
            val i = (q / step).toInt()
            if (i >= cumulative.size - 1) return cumulative.last() + (q - (cumulative.size - 1) * step) * (cumulative.last() - cumulative[cumulative.size - 2]) / step
            val f = (q - i * step) / step
            return cumulative[i] + (cumulative[i + 1] - cumulative[i]) * f
        }
    }

    fun tempoMap(plans: List<Plan>, bpm: Double, ensemble: Boolean = plans.size > 1): TempoMap {
        val total = plans.maxOfOrNull { it.totalQ } ?: 0.0
        val step = 1.0 / 32
        val n = (total / step).toInt() + 2
        val factor = DoubleArray(n) { 1.0 }
        val extra = DoubleArray(n)
        val secPerQ = 60.0 / bpm
        fun at(q: Double) = (q / step).toInt().coerceIn(0, n - 1)
        // The step just before [q]: time added there comes before whatever starts at [q].
        fun before(q: Double) = (Math.round(q / step).toInt() - 1).coerceIn(0, n - 1)
        // Easing into a phrase's end: over its last beat and a half, up to a few percent slower.
        val lead = plans.firstOrNull()
        val amount = if (ensemble) 0.6 else 1.0
        for (ph in lead?.phrases.orEmpty()) {
            // The last phrase eases into the end through the last bar's rallentando (below).
            if (ph.last || ph.ease <= 0f) continue
            val depth = (if (ph.section) 0.09 else 0.035) * amount * ph.ease
            val from = ph.endQ - 1.5
            for (i in at(max(from, ph.startQ))..at(ph.endQ)) {
                val u = ((i * step - from) / 1.5).coerceIn(0.0, 1.0)
                factor[i] = max(factor[i], 1.0 + depth * u * u)
            }
            // And a breath before the next: a moment, more at a section's end.
            if (ph.endQ < total - 1e-6) extra[before(ph.endQ)] += (if (ph.section) 0.12 else 0.04 * amount) * ph.ease
        }
        // The last bar: a broad rallentando, the last note held a little over.
        lead?.barQ?.let { bq ->
            if (bq.size >= 2) {
                // Over the last bar's last three beats or so: the cadence, not the whole bar.
                val from = max(bq[bq.size - 2], total - 3.0)
                for (i in at(from)..at(total)) {
                    val u = ((i * step - from) / (total - from).coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
                    factor[i] = max(factor[i], 1.0 + 0.18 * u * u)
                }
            }
        }
        // Words: a ritardando slows smoothly to about a third slower over two bars and holds there;
        // an accelerando presses on to about a fifth faster; "a tempo" lets go at once.
        val words = plans.flatMap { it.words }.sortedBy { it.first }.distinctBy { (it.first * 8).toLong() to it.second }
        var w = 0
        var mode = 0; var since = 0.0
        val bar = lead?.let { if (it.barQ.size > 1) it.totalQ / (it.barQ.size - 1) else 4.0 } ?: 4.0
        for (i in 0 until n) {
            val q = i * step
            while (w < words.size && words[w].first <= q + 1e-9) { mode = words[w].second; since = words[w].first; w++ }
            val u = ((q - since) / (bar * 2)).coerceIn(0.0, 1.0)
            val s = u * u * (3 - 2 * u)
            if (mode == 1) factor[i] = max(factor[i], 1.0 + 0.32 * s)
            if (mode == -1) factor[i] = min(factor[i], 1.0 - 0.18 * s)
        }
        // Fermatas, from any part: held about twice over, then a breath.
        for (p in plans) for (note in p.notes) if (note.fermata) extra[before(note.q + note.len)] += note.len * secPerQ * 0.9 + 0.2
        val cumulative = DoubleArray(n)
        for (i in 1 until n) cumulative[i] = cumulative[i - 1] + step * secPerQ * factor[i - 1] + extra[i - 1]
        return TempoMap(cumulative, step, total)
    }

    // ---- sound -------------------------------------------------------------------------------

    /**
     * [plan] played by [patch] on [map]'s time, [rate] samples a second, from sample [at];
     * [transpose] semitones written above sounding; [balance] scales its loudness in a band.
     */
    fun tones(plan: Plan, map: TempoMap, rate: Int, transpose: Int, patch: Synth.Patch, at: Long = 0L, balance: Float = 1f,
              drums: DrumKind? = null): List<Synth.Tone> {
        val out = ArrayList<Synth.Tone>()
        fun sample(q: Double) = at + Math.round(map.seconds(q) * rate)
        val sounds = drums?.patch ?: patch
        // Sounding now, by pitch: a slurred or tied note carries into the next of its pitch.
        var held = HashMap<Int, IntArray>()
        val notes = plan.notes
        for ((i, n) in notes.withIndex()) {
            val start = sample(n.q)
            val nominal = sample(n.q + n.len) - start
            // Detached notes let go a moment before the next - a moment in time, not a share: a
            // long note does not end a long way early, a quick one still sounds.
            var length = when (n.sounding) {
                DETACHED -> nominal - (nominal * 0.08).coerceIn(rate * 0.025, rate * 0.08).toLong()
                DETACHED_ACCENT -> nominal - (nominal * 0.14).coerceIn(rate * 0.04, rate * 0.12).toLong()
                else -> (nominal * n.sounding).toLong()
            }
            // Before a breath (marked, or between phrases): let go a breath early - a little, never most of the note.
            val next = notes.getOrNull(i + 1)
            if ((n.breathAfter || (n.phraseEnd && next != null && abs(next.q - (n.q + n.len)) < 1e-6)) && "staccato" !in n.articulations)
                length = min(length, max(nominal * 6 / 10, nominal - (rate * 0.13).toLong()))
            // A fermata sounds over its hold.
            if (n.fermata) length = sample(n.q + n.len) - start + (rate * (n.len * 0.5)).toLong()
            // Staccato in seconds, not only a share: never a click, never a long note.
            if ("staccato" in n.articulations) length = length.coerceIn((rate * 0.07).toLong(), (rate * 0.32).toLong())
            length = max(length, (rate * 0.03).toLong())
            // A hair of looseness - the same every time: a few milliseconds and a touch of weight.
            val seed = (n.q * 977 + i * 31).toLong()
            val prev = notes.getOrNull(i - 1)
            val joined = prev != null && prev.slurred && abs(prev.q + prev.len - n.q) < 1e-6
            // A hair of looseness in weight - but not within a slur, where the line is one sound.
            val wobble = if (joined || n.slurred) 0f else ((hash(seed + 7) - 0.5) * 0.04).toFloat()
            val v = ((n.level + wobble) * balance * 1.1f).coerceIn(0.05f, 1.5f)
            val vEnd = ((n.endLevel + wobble) * balance * 1.1f).coerceIn(0.03f, 1.5f)
            // Grace notes: quick, just before the beat.
            val each = min(rate * 0.055, nominal / 3.0).toLong().coerceAtLeast(1L)
            for ((gi, g) in n.graces.withIndex()) {
                val gStart = max(at, start - each * (n.graces.size - gi))
                for (k in g) out += Synth.Tone(sound(k, transpose, drums), gStart, each, v * 0.8f, sounds, legato = gi > 0)
            }
            val keys = n.keys.map { sound(it, transpose, drums) }
            for ((ki, k) in keys.withIndex()) {
                // Tied into: the sound carries on - its tone stretched to this note's end, not struck again.
                if (drums == null && prev != null && prev.tie && joined && n.keys[ki] in prev.keys) {
                    val last = out.indexOfLast { it.midi == k }
                    if (last >= 0) {
                        val t = out[last]
                        out[last] = Synth.Tone(t.midi, t.start, start + length - t.start, t.velocity, t.patch, t.accent, vEnd, t.legato, t.from)
                        continue
                    }
                }
                // A slurred line, one note at a time: each moves over from the last, not struck anew.
                val from = if (joined && drums == null && prev != null && prev.keys.size == 1 && n.keys.size == 1) sound(prev.keys[0], transpose, null) else null
                out += Synth.Tone(k, start, length, v, sounds, n.accent, vEnd, legato = joined && drums == null, from = from)
            }
        }
        return out
    }

    private fun sound(k: Int, transpose: Int, drums: DrumKind?) = if (drums != null) k else (k - transpose).coerceIn(12, 115)

    private fun hash(x: Long): Double {
        var z = x * -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        z = z xor (z ushr 31)
        return ((z ushr 11).toDouble() / (1L shl 53).toDouble())
    }
}
