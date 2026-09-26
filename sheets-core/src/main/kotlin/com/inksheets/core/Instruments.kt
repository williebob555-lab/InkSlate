package com.inksheets.core

import kotlinx.serialization.Serializable

/**
 * An instrument a part can be written for.
 *
 * [names] are the ways it is printed at the top of a part, as words ("bass trombone",
 * "euphonium tc"); abbreviations and punctuation are dealt with by [InstrumentReader] before
 * matching, so "Euph. T.C." and "Euphonium Treble Clef" both arrive as "euphonium tc".
 * [transpose] is how many semitones the written note sits above the sounding one (a B-flat
 * treble-clef baritone reads a major ninth up: 14), which the tuner uses to name notes in the
 * player's own key.
 */
@Serializable
data class Instrument(
    val id: String,
    val name: String,
    val names: List<String>,
    val transpose: Int = 0,
    val clef: String = "bass",
    /**
     * Instruments that read the same parts, kept apart for clarity: a euphonium player can read
     * a Baritone B.C. part. Choosing one opens the other's part when a song has only that.
     */
    val sameAs: List<String> = emptyList()
)

/**
 * What a person plays, and so which parts they want to see: "Baritone/Euphonium" takes both the
 * bass-clef baritone and euphonium parts; a treble-clef reader would pick the TC ones instead.
 * Several profiles can exist and one is chosen at a time.
 */
@Serializable
data class InstrumentProfile(
    val id: String,
    val name: String,
    /** Instrument ids whose parts this profile shows, most preferred first. */
    val instruments: List<String>
)

object Instruments {

    /**
     * Every instrument parts can be for: the built-in ones, with any names added to them in the
     * library, and the library's own - so what one device is taught, every device reads.
     */
    val all: List<Instrument> get() = merged

    val byId: Map<String, Instrument> get() = mergedById

    /** Bumped whenever [all] changes, for anything that caches what it built from it. */
    @Volatile var revision = 0
        private set

    /**
     * The library's instruments: new ones, and built-in ones given more names. Names are matched
     * as [InstrumentReader] reads a page, so "Mello." and "Mellophone" can both be taught.
     */
    fun use(fromLibrary: List<Instrument>) {
        val added = fromLibrary.associateBy { it.id }
        val next = builtIn.map { b ->
            added[b.id]?.let { extra ->
                b.copy(names = (b.names + extra.names.map(::spoken)).distinct(), sameAs = (b.sameAs + extra.sameAs).distinct())
            } ?: b
        } + fromLibrary.filter { it.id !in builtInIds }.map { it.copy(names = it.names.map(::spoken).filter { n -> n.isNotBlank() }.distinct()) }
        if (next == merged) return
        merged = next
        mergedById = next.associateBy { it.id }
        revision++
    }

    /** [id] and the instruments that read the same parts, either way round. */
    fun sisters(id: String): Set<String> {
        val own = byId[id]?.sameAs.orEmpty()
        val theirs = all.filter { id in it.sameAs }.map { it.id }
        return (own + theirs).toSet() - id
    }

    /** A part as a player names it: "Trumpet 2", "Tuba", or what it was read from. */
    fun partName(p: Part): String {
        val name = p.instrument?.let { byId[it]?.name } ?: return p.label?.takeIf { it.isNotBlank() } ?: "Part"
        return if (p.chair != null) "$name ${p.chair}" else name
    }

    /** A name as it reads once printed words are normalised: "Mello." becomes "mellophone". */
    private fun spoken(name: String): String = InstrumentReader.normalise(name).joinToString(" ")

    /** Built in: the instruments InkSheets knows without being told. */
    val builtIn: List<Instrument> = listOf(
        Instrument("trombone", "Trombone", listOf("trombone", "tenor trombone")),
        Instrument("bass-trombone", "Bass Trombone", listOf("bass trombone")),
        Instrument("baritone-bc", "Baritone B.C.", listOf("baritone", "baritone bc", "baritone horn", "baritone horn bc")),
        Instrument("baritone-tc", "Baritone T.C.", listOf("baritone tc", "baritone horn tc"), transpose = 14, clef = "treble"),
        Instrument("euphonium", "Euphonium", listOf("euphonium", "euphonium bc", "tenor tuba"), sameAs = listOf("baritone-bc")),
        Instrument("euphonium-tc", "Euphonium T.C.", listOf("euphonium tc"), transpose = 14, clef = "treble", sameAs = listOf("baritone-tc")),
        Instrument("tuba", "Tuba", listOf("tuba", "bb tuba", "eb tuba", "sousaphone")),
        Instrument("bass-guitar", "Bass Guitar", listOf("bass guitar", "electric bass", "e bass", "bass", "bass gtr")),
        Instrument("string-bass", "String Bass", listOf("string bass", "double bass", "upright bass", "contrabass")),
        Instrument("horn", "Horn in F", listOf("horn", "french horn", "horn in f", "f horn"), transpose = 7, clef = "treble"),
        Instrument("mellophone", "Mellophone", listOf("mellophone", "mellophone in f"), transpose = 7, clef = "treble", sameAs = listOf("horn")),
        Instrument("trumpet", "Trumpet", listOf("trumpet", "bb trumpet", "cornet", "flugelhorn"), transpose = 2, clef = "treble"),
        Instrument("flute", "Flute", listOf("flute"), clef = "treble"),
        Instrument("piccolo", "Piccolo", listOf("piccolo"), transpose = -12, clef = "treble"),
        Instrument("oboe", "Oboe", listOf("oboe"), clef = "treble"),
        Instrument("english-horn", "English Horn", listOf("english horn", "cor anglais"), transpose = 7, clef = "treble"),
        Instrument("bassoon", "Bassoon", listOf("bassoon")),
        Instrument("clarinet", "Clarinet", listOf("clarinet", "bb clarinet"), transpose = 2, clef = "treble"),
        Instrument("alto-clarinet", "Alto Clarinet", listOf("alto clarinet", "eb alto clarinet"), transpose = 9, clef = "treble"),
        Instrument("contra-clarinet", "Contrabass Clarinet", listOf("contrabass clarinet", "contra alto clarinet", "contra clarinet"), transpose = 26, clef = "treble"),
        Instrument("soprano-sax", "Soprano Saxophone", listOf("soprano saxophone", "soprano sax"), transpose = 2, clef = "treble"),
        Instrument("bass-clarinet", "Bass Clarinet", listOf("bass clarinet"), transpose = 14, clef = "treble"),
        Instrument("alto-sax", "Alto Saxophone", listOf("alto saxophone", "alto sax", "eb alto saxophone"), transpose = 9, clef = "treble"),
        Instrument("tenor-sax", "Tenor Saxophone", listOf("tenor saxophone", "tenor sax"), transpose = 14, clef = "treble"),
        Instrument("bari-sax", "Baritone Saxophone", listOf("baritone saxophone", "baritone sax", "bari sax", "bari saxophone"), transpose = 21, clef = "treble"),
        Instrument("guitar", "Guitar", listOf("guitar", "electric guitar", "acoustic guitar"), transpose = 12, clef = "treble"),
        Instrument("piano", "Piano", listOf("piano", "keyboard", "keys")),
        Instrument("drums", "Drum Set", listOf("drums", "drum set", "drumset", "drum kit")),
        Instrument("drumline", "Drum Line", listOf("drum line", "drumline", "battery", "marching percussion", "drum cadence"), clef = "percussion"),
        Instrument(
            "percussion", "Percussion",
            listOf(
                "percussion", "mallets", "mallet percussion", "timpani", "snare drum", "snare", "bass drum",
                "bells", "orchestra bells", "glockenspiel", "xylophone", "marimba", "vibraphone", "chimes",
                "auxiliary percussion", "crash cymbals", "suspended cymbal", "tambourine", "triangle"
            )
        ),
        Instrument("violin", "Violin", listOf("violin"), clef = "treble"),
        Instrument("viola", "Viola", listOf("viola"), clef = "alto"),
        Instrument("cello", "Cello", listOf("cello", "violoncello")),
        Instrument("vocals", "Vocals", listOf("vocal", "vocals", "voice", "lead sheet")),
        Instrument("score", "Full Score", listOf("score", "full score", "conductor", "conductor score", "condensed score"))
    )

    private val builtInIds = builtIn.map { it.id }.toSet()
    @Volatile private var merged: List<Instrument> = builtIn
    @Volatile private var mergedById: Map<String, Instrument> = builtIn.associateBy { it.id }

    /** The three the library starts with, for the instruments its owner plays. */
    val defaultProfiles = listOf(
        InstrumentProfile("trombone", "Trombone", listOf("trombone", "bass-trombone")),
        InstrumentProfile("baritone", "Baritone / Euphonium", listOf("euphonium", "baritone-bc")),
        InstrumentProfile("bass-guitar", "Bass Guitar", listOf("bass-guitar", "string-bass"))
    )
}

/**
 * Reading which instrument a part is for from the words printed on it.
 *
 * The words come from a PDF's own text where it has any, from recognising the text of a scan
 * where it does not, or from the file name. Parts from notation software and scans of printed
 * parts put the instrument in a few predictable forms - "Trombone 2", "2nd Trombone", "Tbn. I",
 * "Euph. T.C.", "Baritone B.C." - so this normalises the words and then looks for the longest
 * instrument name among them. Longest wins, which is what keeps "Bass Trombone" from being read
 * as a trombone and "Baritone Saxophone" from being read as a baritone.
 */
object InstrumentReader {

    data class Match(
        val instrument: Instrument,
        /** The words it was found in, as printed. */
        val label: String,
        /** Words matched, so a caller can prefer a confident match over a lone "bass". */
        val strength: Int
    )

    /** Abbreviations as they are printed, lower-case and without their full stops. */
    private val abbreviations = mapOf(
        "tbn" to "trombone", "tbne" to "trombone", "trb" to "trombone", "trbn" to "trombone",
        "tromb" to "trombone", "posaune" to "trombone", "trombones" to "trombone",
        "euph" to "euphonium", "euphoniums" to "euphonium",
        "bar" to "baritone", "bari" to "bari", "barit" to "baritone", "baritones" to "baritone",
        "tba" to "tuba", "tubas" to "tuba",
        "tpt" to "trumpet", "trp" to "trumpet", "trumpets" to "trumpet",
        "hn" to "horn", "hns" to "horn", "horns" to "horn", "hrn" to "horn",
        "btbn" to "bass trombone", "btb" to "bass trombone",
        "aux" to "auxiliary", "timp" to "timpani", "glock" to "glockenspiel", "xylo" to "xylophone",
        "vibes" to "vibraphone", "mar" to "marimba", "tamb" to "tambourine",
        "cor" to "horn", "flugel" to "flugelhorn", "mello" to "mellophone", "mellos" to "mellophone", "mellophones" to "mellophone", "crnt" to "cornet", "cnt" to "cornet",
        "ssx" to "soprano saxophone", "asx" to "alto saxophone", "tsx" to "tenor saxophone", "bsx" to "baritone saxophone",
        "euphs" to "euphonium", "tbns" to "trombone", "tpts" to "trumpet", "cls" to "clarinet", "fls" to "flute",
        "bcl" to "bass clarinet", "acl" to "alto clarinet", "tuba's" to "tuba",
        "contra" to "contra", "drumset" to "drum set", "drumkit" to "drum kit",
        "cl" to "clarinet", "clar" to "clarinet", "clarinets" to "clarinet",
        "fl" to "flute", "flutes" to "flute", "picc" to "piccolo",
        "bsn" to "bassoon", "ob" to "oboe",
        "sax" to "sax", "saxes" to "sax", "saxophones" to "saxophone",
        "gtr" to "guitar", "gtrs" to "guitar",
        "perc" to "percussion", "vln" to "violin", "vla" to "viola", "vc" to "cello", "vlc" to "cello",
        "pno" to "piano", "kbd" to "keyboard",
        "el" to "electric", "elec" to "electric",
        "bs" to "bass",
        "treble" to "tc", "tc" to "tc", "bc" to "bc"
    )

    /** Find the instrument named in [text], or null when none is. */
    fun read(text: String, among: List<Instrument> = Instruments.all): Match? {
        // " - " and brackets part a title from a part name as a line break does:
        // "All About That Bass - Trombone 1" is not a bass trombone.
        val lines = text.lines().flatMap { it.split(Regex("""\s+[-–—]\s+|[()\[\]]""")) }.filter { it.isNotBlank() }
        var best: Match? = null
        var bestScore = -1
        for (line in lines) {
            val words = normalise(line)
            if (words.isEmpty()) continue
            // A line that is nothing but a part name beats one where the name is a word in a title.
            val pure = words.all { w -> w in nameWords || w.matches(Regex("""\d+(st|nd|rd|th)?|i{1,3}|iv|v|and|in|part""")) }
            for (instrument in among) {
                for (name in instrument.names) {
                    val run = name.split(' ')
                    if (!containsRun(words, run)) continue
                    val score = run.size * 10 + if (pure) 5 else 0
                    if (score > bestScore) {
                        bestScore = score
                        best = Match(instrument, line.trim(), run.size)
                    }
                }
            }
        }
        return best
    }

    private var nameWordsFor = -1
    private var nameWordsCache: Set<String> = emptySet()
    private val nameWords: Set<String>
        get() {
            if (nameWordsFor != Instruments.revision) {
                nameWordsCache = Instruments.all.flatMap { i -> i.names.flatMap { it.split(' ') } }.toSet() + setOf("bb", "eb", "f", "c", "tc", "bc")
                nameWordsFor = Instruments.revision
            }
            return nameWordsCache
        }

    /** Read the instrument from a file name: "Liberty Bell - Trombone 2.pdf". */
    fun readFileName(name: String): Match? =
        read(name.substringBeforeLast('.').replace('_', ' ').replace('-', ' '))

    /**
     * Words as matching wants them: lower case, punctuation gone, abbreviations spelled out,
     * "Treble Clef"/"T.C." made "tc", and B-flat written any way made "bb".
     */
    fun normalise(line: String): List<String> {
        val cleaned = line
            // "LibertyBell_Tbn1": a capital after a small letter, and a number stuck to a word,
            // start new words - otherwise "Tbn1" is one word that names nothing.
            .replace(Regex("""([a-z])([A-Z])"""), "$1 $2")
            .lowercase()
            .replace("♭", "b")
            .replace(Regex("""\bt\.\s*c\.?"""), " tc ")
            .replace(Regex("""\bb\.\s*c\.?"""), " bc ")
            .replace(Regex("""treble\s+clef"""), " tc ")
            .replace(Regex("""bass\s+clef"""), " bc ")
            .replace(Regex("""\bb-?flat\b"""), " bb ")
            .replace(Regex("""\be-?flat\b"""), " eb ")
            .replace(Regex("""([a-z])(\d)"""), "$1 $2")
            .replace(Regex("""(\d)(?!(st|nd|rd|th)\b)([a-z])"""), "$1 $3")
            .replace(Regex("""[^a-z0-9]+"""), " ")
        val words = cleaned.split(' ').filter { it.isNotBlank() }
            .map { abbreviations[it] ?: it }
            .flatMap { it.split(' ') }
        // "bari sax" is the saxophone; "bari" alone, on a brass part, is the horn. "B. Tbn." is
        // the bass trombone, as "B. Cl." is the bass clarinet.
        return words.mapIndexed { i, w ->
            when {
                w == "bari" -> "baritone"
                w == "sax" -> "saxophone"
                w == "b" && words.getOrNull(i + 1) in setOf("trombone", "clarinet") -> "bass"
                else -> w
            }
        }
    }

    /**
     * Every instrument a part is printed for, in the order printed: "Trombone / Euphonium B.C. /
     * Bassoon" gives all three. Read from the one line that names the part best (as [read]
     * chooses it), so a title elsewhere on the page adds nothing. A longer name hides a shorter
     * one inside it: "Bass Trombone" is not also a trombone.
     */
    fun readAll(text: String, among: List<Instrument> = Instruments.all): List<Instrument> {
        val best = read(text, among) ?: return emptyList()
        val words = normalise(best.label)
        data class Hit(val instrument: Instrument, val start: Int, val length: Int)
        val hits = ArrayList<Hit>()
        for (instrument in among) for (name in instrument.names) {
            val run = name.split(' ')
            if (run.size > words.size) continue
            for (start in 0..words.size - run.size) {
                if (run.indices.all { words[start + it] == run[it] }) hits += Hit(instrument, start, run.size)
            }
        }
        val taken = BooleanArray(words.size)
        val kept = ArrayList<Hit>()
        for (h in hits.sortedByDescending { it.length }) {
            if ((h.start until h.start + h.length).any { taken[it] }) continue
            (h.start until h.start + h.length).forEach { taken[it] = true }
            kept += h
        }
        return kept.sortedBy { it.start }.map { it.instrument }.distinct()
    }

    private fun containsRun(words: List<String>, run: List<String>): Boolean {
        if (run.size > words.size) return false
        for (start in 0..words.size - run.size) {
            if (run.indices.all { words[start + it] == run[it] || (run[it] == "sax" && words[start + it] == "saxophone") }) {
                return true
            }
        }
        return false
    }
}
