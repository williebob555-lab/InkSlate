package com.inksheets.core

/**
 * Which of a song's parts to open for the instrument being played, and whether a song belongs in
 * the list at all.
 *
 * A song whose parts are all for other instruments is hidden: that is the point of choosing an
 * instrument. A song with a part nobody has named an instrument for is still shown, marked, so an
 * unread scan never vanishes from the library just because it could not be read.
 */
object PartChoice {

    enum class Fit { YES, UNKNOWN, NO }

    /** How well [song] fits [profile]; everything fits when no instrument is chosen. */
    fun fit(song: Song, profile: InstrumentProfile?): Fit {
        if (profile == null || song.parts.isEmpty()) return Fit.YES
        val ids = profile.instruments.map { seat(it).first }.flatMap { listOf(it) + Instruments.sisters(it) }
        if (song.parts.any { p -> p.instrument in ids || p.also.any { it in ids } }) return Fit.YES
        if (song.parts.any { it.instrument == null }) return Fit.UNKNOWN
        return Fit.NO
    }

    /**
     * The part to open: the profile's most preferred instrument that the song has, then a part
     * of unknown instrument, then (with no profile, or nothing fitting) the first part.
     */
    fun partFor(song: Song, profile: InstrumentProfile?): Part? {
        if (profile != null) {
            for (entry in profile.instruments) {
                val (id, chair) = seat(entry)
                // Your instrument's parts; with none, those of one that reads the same (a
                // euphonium reads a Baritone B.C. part).
                val theirs = song.parts.filter { it.instrument == id }
                    .ifEmpty { Instruments.sisters(id).let { s -> song.parts.filter { it.instrument in s } } }
                // Your chair's part; failing that, the lowest.
                (theirs.firstOrNull { chair != null && it.chair == chair } ?: theirs.minByOrNull { it.chair ?: 0 })?.let { return it }
            }
            // A part printed for several instruments, one of them yours.
            for (entry in profile.instruments) {
                val id = seat(entry).first
                song.parts.firstOrNull { id in it.also }?.let { return it }
            }
            song.parts.firstOrNull { it.instrument == null }?.let { return it }
        }
        return song.parts.firstOrNull()
    }

    /** A profile's entry as instrument and chair: "trumpet:2" is 2nd trumpet, "trumpet" any. */
    fun seat(entry: String): Pair<String, Int?> =
        entry.substringBefore(':') to entry.substringAfter(':', "").toIntOrNull()

    /** "Trumpet 2" for a profile entry. */
    fun seatName(entry: String): String? {
        val (id, chair) = seat(entry)
        val name = Instruments.byId[id]?.name ?: return null
        return if (chair != null) "$name $chair" else name
    }

    /** The songs to list for [profile], best fits first within the given order. */
    fun songsFor(songs: List<Song>, profile: InstrumentProfile?): List<Pair<Song, Fit>> =
        songs.map { it to fit(it, profile) }.filter { it.second != Fit.NO }
}
