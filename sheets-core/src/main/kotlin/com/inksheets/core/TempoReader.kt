package com.inksheets.core

/**
 * Reading a song's tempo from the words at the top of its first page.
 *
 * A metronome mark - "♩ = 120", "q = 120" (how many fonts carry the note), "M.M. 120", "120 bpm" -
 * is the tempo itself. A tempo word - "Allegro", "Andante", "Moderately", "Ballad" - is a range,
 * and the song gets its middle, with the range kept so a better number can be picked from it.
 */
object TempoReader {

    /** What was read: a number, the word it came from and that word's range, or both. */
    data class Reading(
        /** Beats a minute: the mark's, or the middle of the word's range. */
        val bpm: Int,
        /** The tempo word, as printed ("Allegro con brio" -> "Allegro"). */
        val mark: String? = null,
        /** The range the word means, when it is a word. */
        val range: IntRange? = null
    )

    /** Tempo words and the beats a minute each usually means, longest first so "Allegretto" beats "Allegro". */
    val MARKS: List<Pair<String, IntRange>> = listOf(
        "prestissimo" to 200..220, "vivacissimo" to 172..190, "allegrissimo" to 168..184,
        "larghissimo" to 20..24, "adagissimo" to 24..40, "larghetto" to 60..66, "adagietto" to 70..80,
        "allegretto" to 112..120, "andantino" to 80..108, "moderato" to 108..120, "allegro" to 120..156,
        "andante" to 76..108, "adagio" to 66..76, "presto" to 168..200, "vivace" to 156..176,
        "largo" to 40..60, "lento" to 45..60, "grave" to 25..45, "maestoso" to 72..96,
        "moderately fast" to 116..132, "moderately slow" to 76..96, "moderately" to 100..120,
        "medium swing" to 120..144, "medium fast" to 132..152, "medium slow" to 84..100, "medium" to 100..126,
        "up tempo" to 180..240, "uptempo" to 180..240, "very fast" to 176..208, "very slow" to 40..56,
        "brightly" to 140..170, "bright" to 140..170, "briskly" to 120..140, "lively" to 132..160,
        "fast" to 140..176, "quickly" to 140..176, "slowly" to 60..76, "slow" to 60..76,
        "ballad" to 60..84, "march" to 112..126, "marcia" to 112..126, "tempo di marcia" to 112..126,
        "waltz" to 84..108, "swing" to 120..160, "rock" to 110..140, "funk" to 96..116, "shuffle" to 100..130,
        "samba" to 96..108, "bossa nova" to 110..140, "bossa" to 110..140, "mambo" to 180..200, "cha cha" to 112..128,
        "latin" to 110..140
    ).sortedByDescending { it.first.length }

    // "♩ = 120", "q = 120", "J = 120", "quarter note = 120", "h. = 60": a note and an equals sign.
    private val MARK_NUMBER = Regex(
        """(?<![\p{L}\d])(?:(?:[♩♪qQJjhe]|\x{1D15F}|\x{1D15E}|\x{1D158}\x{1D165}|\x{1D157}\x{1D165})\.?|quarter(?:\s*note)?|half(?:\s*note)?|dotted\s+\w+)\s*=\s*[~≈c.]*\s*(\d{2,3})\b"""
    )
    // "M.M. 120", "MM = 96".
    private val METRONOME = Regex("""(?<![\p{L}])m\.?\s?m\.?\s*=?\s*(\d{2,3})\b""", RegexOption.IGNORE_CASE)
    private val LONE_EQUALS = Regex("""(?:^|\s)=\s*[~≈c.]*\s*(\d{2,3})\b""")
    private val BPM = Regex("""\b(\d{2,3})\s*(?:bpm|b\.p\.m\.?|beats per minute)\b""", RegexOption.IGNORE_CASE)

    /** The tempo in [text] (a page's first lines), or null when it says none. */
    fun read(text: String): Reading? {
        // Only the top of the page: a tempo change halfway down is not the song's tempo.
        val top = text.lines().filter { it.isNotBlank() }.take(20)
        val joined = top.joinToString("\n")
        val number = sequenceOf(BPM, MARK_NUMBER, METRONOME, LONE_EQUALS)
            .mapNotNull { r -> r.find(joined)?.groupValues?.get(1)?.toIntOrNull() }
            .firstOrNull { it in 20..320 }
        val mark = markIn(top)
        return when {
            number != null -> Reading(number, mark?.first, mark?.second)
            mark != null -> Reading((mark.second.first + mark.second.last) / 2, mark.first, mark.second)
            else -> null
        }
    }

    /**
     * A tempo word on a line of its own or leading one ("Allegro con brio", "Moderately, with a
     * swing"), not one inside a title - "Allegro" the song is not an allegro marking unless it
     * stands where one is printed. A title line is recognised as the one the file is named for,
     * which the caller cannot give, so a word is only trusted as the start of a short line.
     */
    private fun markIn(lines: List<String>): Pair<String, IntRange>? {
        for (line in lines) {
            val words = line.lowercase().replace(Regex("""[^\p{L}\s]"""), " ").trim().replace(Regex("""\s+"""), " ")
            if (words.isEmpty() || words.split(' ').size > 6) continue
            for ((word, range) in MARKS) {
                if (words == word || words.startsWith("$word ")) {
                    val printed = line.trim().split(Regex("""\s+""")).take(word.split(' ').size).joinToString(" ").trim(',', '.', ';', ':', '(', ')')
                    return printed.replaceFirstChar { it.uppercase() } to range
                }
            }
        }
        return null
    }

    /** The range a tempo word means, for offering choices around a mark already read. */
    fun rangeOf(mark: String?): IntRange? {
        val m = mark?.lowercase()?.trim() ?: return null
        return MARKS.firstOrNull { it.first == m }?.second
    }
}
