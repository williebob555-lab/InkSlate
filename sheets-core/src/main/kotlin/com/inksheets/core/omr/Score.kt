package com.inksheets.core.omr

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Outlines (closed loops of whole-pixel corners) kept as text the compact way: each loop its point
 * count and first corner, then each step to the next corner, as zigzag variable-length numbers,
 * all in base 64 - a fifth of the room of the numbers written out, which a page's readings sync in.
 */
object OutlinesSerializer : KSerializer<List<IntArray>> {
    override val descriptor = PrimitiveSerialDescriptor("Outlines", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: List<IntArray>) {
        val out = java.io.ByteArrayOutputStream()
        fun put(v: Int) { var z = (v shl 1) xor (v shr 31); while (z and 0x7F.inv() != 0) { out.write((z and 0x7F) or 0x80); z = z ushr 7 }; out.write(z) }
        put(value.size)
        for (loop in value) {
            put(loop.size / 2)
            var px = 0; var py = 0
            for (i in loop.indices step 2) { put(loop[i] - px); put(loop[i + 1] - py); px = loop[i]; py = loop[i + 1] }
        }
        encoder.encodeString(java.util.Base64.getEncoder().withoutPadding().encodeToString(out.toByteArray()))
    }

    override fun deserialize(decoder: Decoder): List<IntArray> {
        val bytes = java.util.Base64.getDecoder().decode(decoder.decodeString())
        var at = 0
        fun get(): Int { var z = 0; var shift = 0; while (true) { val b = bytes[at++].toInt() and 0xFF; z = z or ((b and 0x7F) shl shift); if (b and 0x80 == 0) break; shift += 7 }; return (z ushr 1) xor -(z and 1) }
        val n = get()
        return List(n) {
            val points = get()
            var px = 0; var py = 0
            IntArray(points * 2).also { a -> for (i in 0 until points) { px += get(); py += get(); a[2 * i] = px; a[2 * i + 1] = py } }
        }
    }
}

/**
 * Music read off the page: its measures, and in each the notes and rests in order, with the clef,
 * key and time they are read in - and how sure the reading is, so a measure read badly can be
 * shown for checking rather than trusted.
 */
@Serializable
enum class Clef(
    /** The pitch on the top line, as a diatonic number (C4 = 28). */
    val topLine: Int
) {
    TREBLE(Pitch.diatonic(5, 3)),   // F5
    BASS(Pitch.diatonic(3, 5)),     // A3
    ALTO(Pitch.diatonic(4, 4)),     // G4
    TENOR(Pitch.diatonic(4, 2));    // E4

    /** The pitch at [step] half-spaces below the top line (0 the top line, 8 the bottom). */
    fun at(step: Int): Int = topLine - step
}

/** A written pitch: [step] 0-6 is C to B, [octave] as in C4 = middle C, [alter] -1 flat, +1 sharp. */
@Serializable
data class Pitch(val step: Int, val octave: Int, val alter: Int = 0) {
    val midi: Int get() = 12 * (octave + 1) + SEMITONES[step] + alter
    val diatonic: Int get() = octave * 7 + step
    override fun toString() = "CDEFGAB"[step] + (if (alter > 0) "#".repeat(alter) else "b".repeat(-alter)) + octave

    companion object {
        val SEMITONES = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        fun diatonic(octave: Int, step: Int) = octave * 7 + step
        fun fromDiatonic(d: Int, alter: Int = 0) = Pitch(d.mod(7), Math.floorDiv(d, 7), alter)
    }
}

/**
 * A note value: [base] 1 whole, 2 half, 4 quarter, 8, 16, 32; with dots; and in a tuplet,
 * [actual] notes in the time of [normal] (a triplet: 3 in the time of 2).
 */
@Serializable
data class Duration(val base: Int, val dots: Int = 0, val actual: Int = 1, val normal: Int = 1) {
    /** Length in quarter notes. */
    val quarters: Double get() {
        var q = 4.0 / base
        var add = q
        repeat(dots) { add /= 2; q += add }
        return q * normal / actual
    }
    val tuplet: Boolean get() = actual != normal
    val beams: Int get() = when (base) { 8 -> 1; 16 -> 2; 32 -> 3; else -> 0 }
}

/** Sharps (positive) or flats (negative) in the key signature. */
@Serializable
data class Key(val fifths: Int) {
    /** How [step] is altered by the key. */
    fun alterOf(step: Int): Int {
        val sharps = intArrayOf(3, 0, 4, 1, 5, 2, 6)   // F C G D A E B
        val flats = intArrayOf(6, 2, 5, 1, 4, 0, 3)    // B E A D G C F
        return when {
            fifths > 0 && step in sharps.take(fifths) -> 1
            fifths < 0 && step in flats.take(-fifths) -> -1
            else -> 0
        }
    }
}

@Serializable
data class TimeSig(val beats: Int, val beatType: Int) {
    val quarters: Double get() = beats * 4.0 / beatType
}

@Serializable
sealed class Event {
    abstract val duration: Duration
    /** Where it is across the page, in pixels, or in staff spaces in a drawn score. */
    abstract val x: Float
}

/**
 * A note or chord. [steps] are where the heads sit (half-spaces below the top line), [pitches]
 * what they mean with the clef, key and accidentals; [accidentals] as written, by step.
 */
@Serializable
@SerialName("note")
data class Note(
    val steps: List<Int>,
    val pitches: List<Pitch>,
    override val duration: Duration,
    override val x: Float,
    val accidentals: Map<Int, Int> = emptyMap(),
    val stemUp: Boolean? = null,
    /** How well the heads matched, 0-1. */
    val confidence: Float = 1f,
    /** Marks on it: "accent", "staccato", "staccatissimo", "tenuto", "marcato", "fermata". */
    val articulations: List<String> = emptyList(),
    /** Tied to the next note at its pitch (held on, not struck again). */
    val tie: Boolean = false,
    /**
     * As the trained reader saw it, where it did: how likely 0-3 beams or flags (4), 0-2 dots (3),
     * and that it is a note at all (1) - what the other readings of its bar are weighed by.
     */
    val odds: List<Float> = emptyList(),
    /** Where its stem ends as printed: spaces down from the top line (null where none was seen). */
    val stemTip: Float? = null,
    /** Notes of a bar with the same non-zero number share one beam, as printed. */
    val beam: Int = 0,
    /** Its [articulations] were seen on a picture of the page: kept there as printed, not drawn again. */
    val marksSeen: Boolean = false
) : Event()

/**
 * Something marked over or under a bar rather than on one note: a dynamic ("p", "mf", "sfz" in
 * [text]), a hairpin ("cresc", "dim": from [x] to [x2]), a slur ("slur": [x] to [x2], at heights
 * [step] and [step2] - steps down from the top line - running on past the bar's edge where it goes
 * on into the next), or words ("text").
 */
@Serializable
data class Direction(
    val kind: String,
    val x: Float,
    val x2: Float = x,
    val text: String = "",
    val above: Boolean = false,
    val step: Int? = null,
    val step2: Int? = null,
    /** Seen on a picture of the page (for playing it): kept there as printed, not drawn again. */
    val seen: Boolean = false
)

@Serializable
@SerialName("rest")
data class Rest(
    override val duration: Duration,
    override val x: Float,
    /** Its height on the staff (steps down from the top line), where known: in two voices, which voice's it is. */
    val step: Int? = null
) : Event()

/** A box on a page, in pixels. */
@Serializable
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

@Serializable
data class Measure(
    /** Counting from 1 through the part. */
    val number: Int,
    /** 0-based page, and which staff on it (0 the top one). */
    val page: Int,
    val staff: Int,
    /** Where it is on the page: the staff's lines, from the barline before to the one after. */
    val box: Box,
    /** Pixels to a staff space on that page. */
    val space: Float,
    val clef: Clef,
    val key: Key,
    val time: TimeSig,
    val events: List<Event>,
    /** The clef, key or time printed at its start (rather than carried on). */
    val showsClef: Boolean = false,
    val showsKey: Boolean = false,
    val showsTime: Boolean = false,
    /** Why it may be read wrong: "4 beats found, 3 expected". */
    val doubts: List<String> = emptyList(),
    /** A repeat begins here (dots after its barline) / ends here (dots before the barline after it). */
    val repeatStart: Boolean = false,
    val repeatEnd: Boolean = false,
    /** Under a first or second ending's bracket: which (1, 2), or 0 for none. */
    val ending: Int = 0,
    /** A segno over its start (where D.S. goes back to), a coda sign (To Coda, or the coda's start). */
    val segno: Boolean = false,
    val coda: Boolean = false,
    /** How many bars it stands for: more than one for a multi-bar rest ("rest 4 bars"). */
    val bars: Int = 1,
    /** A bar-repeat sign (a slash between two dots): played as the bar before it, drawn as printed. */
    val repeatsBar: Boolean = false,
    /**
     * Where its staff's five lines are, as printed, just outside its left edge and its right (the
     * five at the left, top first, then the five at the right): a scan's staff runs a little
     * aslant, bowed, its lines not quite evenly apart - and a bar redrawn in place meets the print
     * either side of it only if it follows them. Empty when not known.
     */
    val lines: List<Float> = emptyList(),
    /** How thick its staff's lines are printed, in pixels (0 when not known). */
    val lineWidth: Float = 0f,
    /** Where its music starts, after the clef, key and time printed at its start (0 when not known). */
    val start: Int = 0,
    /** Dynamics, hairpins, slurs and words over or under it. */
    val directions: List<Direction> = emptyList(),
    /**
     * Notes the reader saw here and let go (too faint, a stem it thought another's): what it may
     * have missed, for offering other readings of the bar ([BarChoices]).
     */
    val maybe: List<Event> = emptyList(),
    /**
     * Whatever is printed in and round the bar that its redrawing does not draw - a bar number, a
     * rehearsal box, words, a hairpin, a mark not read - each as its exact outline (pixel corners,
     * even-odd loops, in the reading's pixels): drawn back over a cleaned bar as printed, whole.
     */
    @Serializable(with = OutlinesSerializer::class)
    val kept: List<IntArray> = emptyList(),
    /** How dark each of [kept]'s loops is printed, 0 black - 255 paper (pencil and highlighter come back light). */
    val keptShade: List<Int> = emptyList()
) {
    /** Line [i] (0 the top) at [x] across the bar (page pixels), as printed. */
    fun lineAt(i: Int, x: Float): Float {
        if (lines.size < 10) return box.top + (box.bottom - box.top) * i / 4f
        val u = ((x - box.left) / box.width.coerceAtLeast(1)).coerceIn(0f, 1f)
        return lines[i] + (lines[5 + i] - lines[i]) * u
    }

    /** The height [ys] staff spaces below the top line at [x], between the printed lines either side of it (beyond the staff, by the nearest space). */
    fun yAt(ys: Float, x: Float): Float {
        val i = kotlin.math.floor(ys).toInt().coerceIn(0, 3)
        val a = lineAt(i, x); val b = lineAt(i + 1, x)
        return a + (b - a) * (ys - i)
    }

    /** The top line's height at [x] across the bar, as printed. */
    fun topAt(x: Float): Float = lineAt(0, x)

    /** The bottom line's height at [x] across the bar, as printed. */
    fun bottomAt(x: Float): Float = lineAt(4, x)

    /** Read well enough to trust: its notes fill the bar exactly, and nothing was in doubt. */
    val sure: Boolean get() = doubts.isEmpty()

    val quarters: Double get() = events.sumOf { it.duration.quarters }

    /**
     * How long it is played, in quarters: as long as its time says - every bar of it, for a
     * multi-bar rest - but a bar read clearly short of its time (a pickup, a piece's last bar,
     * nothing in doubt) only as long as its notes. A bar misread long or short is still a bar long.
     */
    val playedQuarters: Double get() {
        if (bars > 1) return time.quarters * bars
        val q = quarters
        return if (events.isNotEmpty() && q > 1e-6 && q < time.quarters - 1e-6 && doubts.none { it.contains("beats found") }) q else time.quarters
    }
}

/** A part read off its pages. */
@Serializable
data class Score(
    val measures: List<Measure>,
    val pages: Int,
    /** How wide each page was drawn to be read, in pixels: the scale of every [Measure.box] on it. */
    val pageWidths: List<Int> = emptyList(),
    /**
     * The pages read (0-based), where only some were - a page or two of a long book read for the
     * passage being worked on; null when the whole part was.
     */
    val readPages: List<Int>? = null
) {
    /** Whether [page] has been read. */
    fun hasRead(page: Int) = readPages?.contains(page) ?: (page in 0 until pages)

    /** Which measures are on [page]. */
    fun onPage(page: Int) = measures.filter { it.page == page }

    /** How long each measure lasts at [bpm] (quarter notes a minute), in ms, and where it starts. */
    fun timeline(bpm: Double): List<Pair<Measure, Long>> {
        var t = 0.0
        return measures.map { m ->
            val start = t
            t += m.playedQuarters * 60_000.0 / bpm
            m to start.toLong()
        }
    }
}

/** A read part kept as text: read once per device, not every time it is wanted. */
object Scores {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; classDiscriminator = "kind" }
    fun encode(s: Score): String = json.encodeToString(Score.serializer(), s)
    fun decode(text: String): Score? = runCatching { json.decodeFromString(Score.serializer(), text) }.getOrNull()

    private val fixesSerializer = kotlinx.serialization.builtins.MapSerializer(kotlinx.serialization.serializer<Int>(),
        kotlinx.serialization.builtins.ListSerializer(Event.serializer()))

    /** Bars put right by hand - bar number to what it is - as text to keep. */
    fun encodeFixes(fixes: Map<Int, List<Event>>): String = json.encodeToString(fixesSerializer, fixes)
    fun decodeFixes(text: String): Map<Int, List<Event>> = runCatching { json.decodeFromString(fixesSerializer, text) }.getOrDefault(emptyMap())

    /** [s] with the bars in [fixes] as they were put right: what was picked, and no longer in doubt. */
    fun withFixes(s: Score, fixes: Map<Int, List<Event>>): Score =
        if (fixes.isEmpty()) s else s.copy(measures = s.measures.map { m -> fixes[m.number]?.let { m.copy(events = it, doubts = emptyList()) } ?: m })
}

/**
 * The order bars are played in: repeats played twice, a first ending the first time round and a
 * second the second, and on. Bar numbers as read, a multi-bar rest counted out bar by bar.
 */
object PlayOrder {
    /** The measures (by index into [measures]) in the order they are played. */
    fun indices(measures: List<Measure>): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        var start = 0
        var pass = 1
        val repeated = HashSet<Int>()
        var guard = 0
        while (i < measures.size && guard++ < measures.size * 4) {
            val m = measures[i]
            if (m.repeatStart && pass == 1) start = i
            // An ending not for this time round is stepped over.
            if (m.ending != 0 && m.ending != pass) { i++; continue }
            out += i
            if (m.repeatEnd && i !in repeated) {
                repeated += i
                pass = 2
                i = start
                continue
            }
            // Past the section repeated: back to the first time round for the next.
            if (pass == 2 && (m.repeatEnd || (m.ending == 0 && i > start && measures.getOrNull(i - 1)?.ending != 0))) {
                pass = 1
                start = i + 1
            }
            i++
        }
        return out
    }

    /**
     * [score] with its bars in the order they are played - a repeated bar in it twice, D.S. al
     * Coda taken - for hearing it, or following it.
     */
    fun unrolled(score: Score): Score = score.copy(measures = withJumps(score.measures).map { score.measures[it] })

    /**
     * [indices], and D.S. al Coda where a part has one segno and two coda signs - "To Coda" in
     * the body, the coda's own start after the D.S.: up to the coda, back to the segno (repeats
     * not taken again), on to "To Coda", then the coda. Anything else is played as it stands:
     * a jump is never guessed at.
     */
    fun withJumps(measures: List<Measure>): List<Int> {
        val order = indices(measures)
        val segnos = measures.indices.filter { measures[it].segno }
        val codas = measures.indices.filter { measures[it].coda }
        if (segnos.size != 1 || codas.size != 2) return order
        val s = segnos[0]; val (toCoda, coda) = codas
        if (!(s <= toCoda && toCoda < coda)) return order
        return order.filter { it < coda } + (s..toCoda) + order.filter { it >= coda }.distinct()
    }

    /** Bar numbers in the order played. */
    fun bars(measures: List<Measure>): List<Int> = indices(measures).flatMap { k -> measures[k].let { m -> (m.number until m.number + m.bars).toList() } }
}
