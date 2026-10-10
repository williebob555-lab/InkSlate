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
        /** A new dynamic is written at it: the loudness moves over to it in a moment (not a jump), from where the note before left it. */
        var stepped = false
        /** The new dynamic is a sudden one (sfz then p, fp, "sub."): the only kind that moves quickly. */
        var subito = false
        /** Its loudness before the phrase's shape is laid over it: at its start and at its end. */
        var baseStart = 0.68f
        var baseEnd = 0.68f
        var fromLevel = 0.68f
        var phrase: Phrase? = null
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
        var stepNext = false
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
                var steppedHere = false
                while (di < dyn.size && dyn[di].x <= e.x + sp) {
                    val t = dyn[di].text.lowercase()
                    LEVELS[t]?.let { if (abs(it - level) > 0.01f) steppedHere = true; level = it }
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
                    p.stepped = steppedHere || stepNext
                    p.subito = stepNext || (steppedHere && m.directions.any { d -> d.kind == "text" && d.text.lowercase().trim().startsWith("sub") && abs(d.x - e.x) < sp * 6 })
                    stepNext = false
                    pendingStruck?.let { st -> p.accent = 1f; p.level = min(v + 0.04f, 1.05f); if (st.endsWith("p")) { level = LEVELS.getValue("p"); stepNext = true; p.endLevel = level }; pendingStruck = null }
                    // Leant on, not shouted: a gentle weight in the sound (Synth's accent, 2 dB at most
                    // over about a tenth of a second) - the level itself is the phrase's, no spike.
                    when {
                        "marcato" in e.articulations -> p.accent = max(p.accent, 1f)
                        "accent" in e.articulations -> p.accent = max(p.accent, 0.8f)
                        "sforzando" in e.articulations -> p.accent = 1f
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
            // A hairpin that ended within this bar leaves the music where it got to (not back where it began).
            for (h in pins) {
                val key = "${m.page}/${m.staff}/${h.x}/${h.x2}"
                if (key !in pinsDone && h.x2 < m.box.right - sp * 1.5f) { pinsDone += key; level = (level + if (h.kind == "cresc") 0.16f else -0.16f).coerceIn(0.15f, 1.05f) }
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

    /** The phrase's gentle arch at quarter [q]: rising to its high point, easing away after it. */
    private fun archAt(ph: Phrase?, q: Double): Float {
        if (ph == null) return 0f
        val span = (ph.endQ - ph.startQ).coerceAtLeast(1e-6)
        val u = ((q - ph.startQ) / span).coerceIn(0.0, 1.0)
        val peakU = ((ph.peakQ - ph.startQ) / span).coerceIn(0.25, 0.85)
        val arch = if (u <= peakU) (u / peakU) else (1.0 - (u - peakU) / (1.0 - peakU))
        return (0.1 * arch.pow(1.3) - 0.04).toFloat()
    }

    /**
     * Each note's loudness and length. The loudness is one continuous line through the music: the
     * dynamics and hairpins, a gentle arch over each phrase - nothing per note. A note ends at the
     * loudness the next one (joined to it) starts at, so a slurred line never steps, swells or
     * balloons on a note; a new dynamic is moved to over a moment ([Planned.stepped]).
     */
    private fun shape(notes: List<Planned>, phrases: List<Phrase>, barQ: DoubleArray, bars: List<Measure?>, lengths: DoubleArray) {
        for (ph in phrases) {
            val inside = notes.filter { it.q >= ph.startQ - 1e-6 && it.q < ph.endQ - 1e-6 }
            if (inside.isEmpty()) continue
            inside.first().phraseStart = true
            inside.last().phraseEnd = true
            inside.last().sectionEnd = ph.section
            for (n in inside) n.phrase = ph
        }
        for (n in notes) { n.baseStart = n.level; n.baseEnd = n.endLevel; n.level = n.baseStart + archAt(n.phrase, n.q) }
        for ((i, n) in notes.withIndex()) {
            val next = notes.getOrNull(i + 1)
            val contiguous = next != null && abs(n.q + n.len - next.q) < 1e-6
            // A slight taper at a phrase's end, and before a rest.
            val own = n.baseEnd + archAt(n.phrase, n.q + n.len) - (if (n.phraseEnd) 0.04f else 0f) - (if (!contiguous) 0.04f else 0f)
            n.endLevel = if (n.slurred && contiguous && !next!!.stepped) next.level else own
        }
        for ((i, n) in notes.withIndex()) {
            val prev = notes.getOrNull(i - 1)
            n.fromLevel = if (prev != null && prev.slurred && abs(prev.q + prev.len - n.q) < 1e-6) prev.endLevel else n.level
        }
        // How long each note sounds, from its articulation and where it is.
        for ((i, n) in notes.withIndex()) {
            val next = notes.getOrNull(i + 1)
            val touching = next != null && next.q - (n.q + n.len) < 1e-6
            val a = n.articulations
            n.sounding = when {
                "staccatissimo" in a -> 0.28
                "staccato" in a -> 0.45
                "marcato" in a -> 0.75
                "tenuto" in a -> TENUTO
                n.slurred && touching && !n.phraseEnd -> JOINED      // joined to the next, no gap
                "accent" in a -> DETACHED_ACCENT
                else -> DETACHED
            }
            n.level = n.level.coerceIn(0.08f, 1.25f)
            n.endLevel = n.endLevel.coerceIn(0.05f, 1.3f)
            n.fromLevel = n.fromLevel.coerceIn(0.05f, 1.3f)
        }
    }

    private const val TENUTO = -3.0
    private const val JOINED = -4.0

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
        class Ramp(val t0: Long, val len: Long, val ref: Float)
        val ramps = ArrayList<Ramp>()
        for ((i, n) in notes.withIndex()) if (n.stepped && i > 0) {
            val ref = (notes[i - 1].endLevel * balance * 1.1f).coerceIn(0.03f, 1.5f)
            val own = (n.level * balance * 1.1f).coerceIn(0.05f, 1.5f)
            val dB = abs(own - ref) * 26.8
            val noteSecs = map.seconds(n.q + n.len) - map.seconds(n.q)
            val secs = if (n.subito) 0.12 else max(max(0.6, dB / 14.0), min(3.0, noteSecs))
            ramps += Ramp(sample(n.q), (secs * rate).toLong(), ref)
        }
        val vibrato = vibratoPlans(plan, notes, map, rate, at, sounds, drums, ::sample)
        for ((i, n) in notes.withIndex()) {
            val start = sample(n.q)
            val nominal = sample(n.q + n.len) - start
            val next = notes.getOrNull(i + 1)
            val prev = notes.getOrNull(i - 1)
            val nextTouches = next != null && abs(n.q + n.len - next.q) < 1e-6
            val joined = prev != null && (prev.sounding == JOINED || prev.tie) && abs(prev.q + prev.len - n.q) < 1e-6
            val joinsNext = drums == null && n.sounding == JOINED && nextTouches
            val feel = Feel.now
            // The note's own release - a natural decay (brass 80-150 ms): what is heard goes on after the hold.
            val rel = (sounds.release * rate).toLong()
            var length: Long
            if (joinsNext) {
                // Joined to the next: held to it; the next note takes the sound over.
                length = nominal
            } else {
                // Not joined: it is let go before the next is tongued, and the release decays into the gap -
                // separated, not chopped.
                val gap = max(nominal * feel.gap, rate * feel.gapMinMs / 1000).coerceAtMost(nominal * 0.35)
                var hold = when (n.sounding) {
                    TENUTO -> nominal - (rate * 0.02).toLong()
                    DETACHED, DETACHED_ACCENT, JOINED -> nominal - gap.toLong() - (rel * 0.3).toLong()
                    // Staccato and marcato: a share of the note, the release (shorter for staccato) making up the rest.
                    else -> (nominal * n.sounding).toLong() - (if (n.sounding < 0.5) rate * 0.025 else rel * 0.3).toLong()
                }
                // Before a breath (marked, or between phrases): let go a breath early - a little, never most of the note.
                if ((n.breathAfter || (n.phraseEnd && nextTouches)) && "staccato" !in n.articulations)
                    hold = min(hold, max(nominal * 6 / 10, nominal - (rate * 0.13).toLong()))
                // A fermata sounds over its hold.
                if (n.fermata) hold = sample(n.q + n.len) - start + (rate * (n.len * 0.5)).toLong()
                // Staccato in seconds, not only a share: never a click, never a long note.
                if ("staccato" in n.articulations) hold = hold.coerceIn((rate * 0.035).toLong(), (rate * 0.28).toLong())
                length = max(hold, (rate * 0.02).toLong())
            }
            // The level line: dynamics arrive over 0.6-3 s (an S-curve), over the first note or two when they are long, a
            // sudden mark (sfz-p, fp, sub.) in a moment.
            fun shaped(level: Float, at: Long): Float {
                val r = ramps.lastOrNull { it.t0 <= at } ?: return level
                if (at >= r.t0 + r.len) return level
                val x = (at - r.t0).toDouble() / r.len
                val sm = (x * x * (3 - 2 * x)).toFloat()
                return r.ref * (1 - sm) + level * sm
            }
            val v = shaped((n.level * balance * 1.1f * (if (drums != null && n.inBar < 1e-6) 1.06f else 1f)).coerceIn(0.05f, 1.5f), start)
            val vEnd = shaped((n.endLevel * balance * 1.1f).coerceIn(0.03f, 1.5f), start + nominal)
            val fromV = v
            val ramp = 0L
            // Grace notes: quick, just before the beat.
            val each = min(rate * 0.055, nominal / 3.0).toLong().coerceAtLeast(1L)
            for ((gi, g) in n.graces.withIndex()) {
                val gStart = max(at, start - each * (n.graces.size - gi))
                for (k in g) out += Synth.Tone(sound(k, transpose, drums), gStart, each, v * 0.8f, sounds, legato = gi > 0)
            }
            val keys = n.keys.map { sound(it, transpose, drums) }
            val a = n.articulations
            val art = (if ("staccato" in a) Synth.ART_STACCATO else 0) or (if ("accent" in a) Synth.ART_ACCENT else 0) or
                (if ("tenuto" in a) Synth.ART_TENUTO else 0) or (if ("marcato" in a) Synth.ART_MARCATO else 0)
            val layer = (n.level * 1.1f).coerceIn(0.05f, 1.5f)
            for ((ki, k) in keys.withIndex()) {
                // Tied into: the sound carries on - its tone stretched to this note's end, not struck again.
                if (drums == null && prev != null && prev.tie && joined && n.keys[ki] in prev.keys) {
                    val last = out.indexOfLast { it.midi == k }
                    if (last >= 0) {
                        val t = out[last]
                        out[last] = Synth.Tone(t.midi, t.start, start + length - t.start, t.velocity, t.patch, t.accent, vEnd, t.legato, t.from, t.fromVelocity, t.ramp, t.art, t.layer, t.vib)
                        continue
                    }
                }
                // A slurred line, one note at a time: each moves over from the last, not struck anew.
                val from = if (joined && drums == null && prev != null && prev.keys.size == 1 && n.keys.size == 1) sound(prev.keys[0], transpose, null) else null
                out += Synth.Tone(k, start, length, v, sounds, n.accent, vEnd, legato = joined && drums == null, from = from, fromVelocity = fromV, ramp = ramp, art = art, layer = layer, vib = vibrato[i])
            }
        }
        return out
    }

    /**
     * The vibrato of each note, planned a slurred line at a time (it goes on through a slur rather than starting again on every
     * note), and only from the music: there is nothing random in it. One intensity line follows the phrase - wider and a little
     * quicker with the dynamic, with higher notes, toward the phrase's high point and in a crescendo; narrower and slower toward a
     * phrase's end - drawn through the middle of each note and joined smoothly. Within a line it is straight for a moment, then
     * blooms evenly to its depth, quickens a little through a long note, and eases to straight before the release (the last note of a
     * phrase, a note before a rest, the last note of all). Short lines get none. [Feel.expression] scales how far it swings
     * from the plain middle; [Feel.drift] adds a slow wander of a couple of percent at most (none by default).
     */
    private fun vibratoPlans(plan: Plan, notes: List<Planned>, map: TempoMap, rate: Int, at: Long, patch: Synth.Patch, drums: DrumKind?,
                             sample: (Double) -> Long): Array<Vibrato?> {
        val out = arrayOfNulls<Vibrato>(notes.size)
        if (drums != null || patch.sampled != null || patch.vibratoCents <= 0.0 || patch.drum) return out
        val e = Feel.expression
        val drift = Feel.drift
        /** How intense the vibrato wants to be at note [n]'s middle, 1 the plain middle. */
        fun want(n: Planned): Double {
            val level = (n.level + n.endLevel) / 2
            val pitch = n.keys.maxOrNull() ?: 55
            var a = 1.0
            a += 0.6 * (level - 0.68)                                   // louder: wider
            a += (pitch - 55).coerceIn(-10, 12) * 0.015                  // higher: wider
            a += (n.endLevel - n.level) * 1.2                            // a crescendo: wider, a diminuendo: narrower
            val ph = n.phrase
            if (ph != null) {
                val mid = n.q + n.len / 2
                val spanQ = (ph.endQ - ph.startQ).coerceAtLeast(1e-6)
                val toPeak = (1 - abs(mid - ph.peakQ) / (0.35 * spanQ + 1.0)).coerceAtLeast(0.0)
                a += 0.3 * toPeak * toPeak * (3 - 2 * toPeak)             // toward the high point
                val u = ((mid - ph.startQ) / spanQ).coerceIn(0.0, 1.0)
                a -= 0.3 * smooth01((u - 0.7) / 0.3)                      // toward the phrase's end
            }
            return 1 + (a - 1) * e
        }
        var i = 0
        while (i < notes.size) {
            // The line: this note and those slurred or tied on to it.
            var j = i
            while (j < notes.size - 1 && (notes[j].sounding == JOINED || notes[j].tie) && abs(notes[j].q + notes[j].len - notes[j + 1].q) < 1e-6) j++
            val first = notes[i]; val last = notes[j]
            val startS = sample(first.q); val endS = sample(last.q + last.len)
            val span = (endS - startS) / rate.toDouble()
            val seed = hashLong((first.q * 977).toLong() + i * 7919L + 31)
            val room = smooth01((span - 0.35) / 0.45)          // short lines: little or none
            val phraseEnds = notes.subList(i, j + 1).any { it.phraseEnd }
            val nextAfter = notes.getOrNull(j + 1)
            val toRest = nextAfter == null || nextAfter.q - (last.q + last.len) > 1e-6
            val finalNote = nextAfter == null
            // The intensity line, through the middle of each note, joined smoothly.
            val times = ArrayList<Double>(); val depthK = ArrayList<Double>(); val rateK = ArrayList<Double>()
            times += 0.0; depthK += want(first); rateK += 1 + 0.35 * (want(first) - 1)
            for (k in i..j) {
                val mid = (sample(notes[k].q + notes[k].len / 2) - startS) / rate.toDouble()
                if (mid > times.last() + 0.05) { times += mid; depthK += want(notes[k]); rateK += 1 + 0.35 * (want(notes[k]) - 1) }
            }
            if (span > times.last() + 0.05) { times += span; depthK += depthK.last(); rateK += rateK.last() }
            val rateK2 = rateK.map { it.coerceIn(0.97, 1.08) }.toDoubleArray()
            val taper = finalNote || phraseEnds || toRest
            val taperTo = if (finalNote || phraseEnds) 0.0 else 0.5
            val longNote = span > 1.6
            val vib = Vibrato(startS, seed, patch.vibratoCents * room, patch.vibratoHz,
                delay = patch.vibDelay * 0.4, swell = 0.5, span = span,
                growth = if (longNote) 0.2 else 0.1, rateTrend = 0.14,
                taperSecs = if (taper) 0.4 else 0.0, taperTo = taperTo,
                depthNoise = drift, rateNoise = drift, corr = 3.0,
                knotTimes = times.toDoubleArray(), knotDepth = depthK.map { it.coerceIn(0.4, 1.3) }.toDoubleArray(), knotRate = rateK2)
            val capped = vib
            if (capped.depth > 0.2) for (k in i..j) out[k] = capped
            i = j + 1
        }
        return out
    }

    private fun smooth01(x: Double): Double { val u = x.coerceIn(0.0, 1.0); return u * u * (3 - 2 * u) }
    private fun hashLong(x: Long): Long { var z = x * -0x61c8864680b583ebL; z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L; return z xor (z ushr 31) }

    private fun sound(k: Int, transpose: Int, drums: DrumKind?) = if (drums != null) k else (k - transpose).coerceIn(12, 115)

    private fun hash(x: Long): Double {
        var z = x * -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        z = z xor (z ushr 31)
        return ((z ushr 11).toDouble() / (1L shl 53).toDouble())
    }
}
