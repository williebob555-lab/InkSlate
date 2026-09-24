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
    val clef: String = "bass"
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

    val all: List<Instrument> = listOf(
        Instrument("trombone", "Trombone", listOf("trombone", "tenor trombone")),
        Instrument("bass-trombone", "Bass Trombone", listOf("bass trombone")),
        Instrument("baritone-bc", "Baritone B.C.", listOf("baritone", "baritone bc", "baritone horn", "baritone horn bc")),
        Instrument("baritone-tc", "Baritone T.C.", listOf("baritone tc", "baritone horn tc"), transpose = 14, clef = "treble"),
        Instrument("euphonium", "Euphonium", listOf("euphonium", "euphonium bc")),
        Instrument("euphonium-tc", "Euphonium T.C.", listOf("euphonium tc"), transpose = 14, clef = "treble"),
        Instrument("tuba", "Tuba", listOf("tuba", "bb tuba", "eb tuba", "sousaphone")),
        Instrument("bass-guitar", "Bass Guitar", listOf("bass guitar", "electric bass", "e bass", "bass", "bass gtr")),
        Instrument("string-bass", "String Bass", listOf("string bass", "double bass", "upright bass", "contrabass")),
        Instrument("horn", "Horn in F", listOf("horn", "french horn", "horn in f", "f horn"), transpose = 7, clef = "treble"),
        Instrument("trumpet", "Trumpet", listOf("trumpet", "bb trumpet", "cornet", "flugelhorn"), transpose = 2, clef = "treble"),
        Instrument("flute", "Flute", listOf("flute"), clef = "treble"),
        Instrument("piccolo", "Piccolo", listOf("piccolo"), transpose = -12, clef = "treble"),
        Instrument("oboe", "Oboe", listOf("oboe"), clef = "treble"),
        Instrument("english-horn", "English Horn", listOf("english horn", "cor anglais"), transpose = 7, clef = "treble"),
        Instrument("bassoon", "Bassoon", listOf("bassoon")),
        Instrument("clarinet", "Clarinet", listOf("clarinet", "bb clarinet"), transpose = 2, clef = "treble"),
        Instrument("bass-clarinet", "Bass Clarinet", listOf("bass clarinet"), transpose = 14, clef = "treble"),
        Instrument("alto-sax", "Alto Saxophone", listOf("alto saxophone", "alto sax", "eb alto saxophone"), transpose = 9, clef = "treble"),
        Instrument("tenor-sax", "Tenor Saxophone", listOf("tenor saxophone", "tenor sax"), transpose = 14, clef = "treble"),
        Instrument("bari-sax", "Baritone Saxophone", listOf("baritone saxophone", "baritone sax", "bari sax", "bari saxophone"), transpose = 21, clef = "treble"),
        Instrument("guitar", "Guitar", listOf("guitar", "electric guitar", "acoustic guitar"), transpose = 12, clef = "treble"),
        Instrument("piano", "Piano", listOf("piano", "keyboard", "keys")),
        Instrument("drums", "Drum Set", listOf("drums", "drum set", "drumset", "drum kit")),
        Instrument("percussion", "Percussion", listOf("percussion", "mallets", "timpani", "snare drum", "bass drum")),
        Instrument("violin", "Violin", listOf("violin"), clef = "treble"),
        Instrument("viola", "Viola", listOf("viola"), clef = "alto"),
        Instrument("cello", "Cello", listOf("cello", "violoncello")),
        Instrument("vocals", "Vocals", listOf("vocal", "vocals", "voice", "lead sheet")),
        Instrument("score", "Full Score", listOf("score", "full score", "conductor", "conductor score", "condensed score"))
    )

    val byId: Map<String, Instrument> = all.associateBy { it.id }

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
        "hn" to "horn", "hns" to "horn", "horns" to "horn",
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
        val lines = text.lines().filter { it.isNotBlank() }
        var best: Match? = null
        for (line in lines) {
            val words = normalise(line)
            if (words.isEmpty()) continue
            for (instrument in among) {
                for (name in instrument.names) {
                    val nameWords = name.split(' ')
                    if (!containsRun(words, nameWords)) continue
                    val strength = nameWords.size
                    if (best == null || strength > best.strength) {
                        best = Match(instrument, line.trim(), strength)
                    }
                }
            }
        }
        return best
    }

    /** Read the instrument from a file name: "Liberty Bell - Trombone 2.pdf". */
    fun readFileName(name: String): Match? =
        read(name.substringBeforeLast('.').replace('_', ' ').replace('-', ' '))

    /**
     * Words as matching wants them: lower case, punctuation gone, abbreviations spelled out,
     * "Treble Clef"/"T.C." made "tc", and B-flat written any way made "bb".
     */
    fun normalise(line: String): List<String> {
        val cleaned = line.lowercase()
            .replace("♭", "b")
            .replace(Regex("""\bt\.\s*c\.?"""), " tc ")
            .replace(Regex("""\bb\.\s*c\.?"""), " bc ")
            .replace(Regex("""treble\s+clef"""), " tc ")
            .replace(Regex("""bass\s+clef"""), " bc ")
            .replace(Regex("""\bb-?flat\b"""), " bb ")
            .replace(Regex("""\be-?flat\b"""), " eb ")
            .replace(Regex("""[^a-z0-9]+"""), " ")
        val words = cleaned.split(' ').filter { it.isNotBlank() }
            .map { abbreviations[it] ?: it }
            .flatMap { it.split(' ') }
        // "bari sax" is the saxophone; "bari" alone, on a brass part, is the horn.
        return words.mapIndexed { i, w ->
            when {
                w == "bari" && words.getOrNull(i + 1)?.startsWith("sax") == true -> "baritone"
                w == "bari" -> "baritone"
                w == "sax" -> "saxophone"
                else -> w
            }
        }
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
