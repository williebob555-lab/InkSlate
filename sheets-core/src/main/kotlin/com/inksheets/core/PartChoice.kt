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
        if (song.parts.any { it.instrument in profile.instruments }) return Fit.YES
        if (song.parts.any { it.instrument == null }) return Fit.UNKNOWN
        return Fit.NO
    }

    /**
     * The part to open: the profile's most preferred instrument that the song has, then a part
     * of unknown instrument, then (with no profile, or nothing fitting) the first part.
     */
    fun partFor(song: Song, profile: InstrumentProfile?): Part? {
        if (profile != null) {
            for (instrument in profile.instruments) {
                song.parts.firstOrNull { it.instrument == instrument }?.let { return it }
            }
            song.parts.firstOrNull { it.instrument == null }?.let { return it }
        }
        return song.parts.firstOrNull()
    }

    /** The songs to list for [profile], best fits first within the given order. */
    fun songsFor(songs: List<Song>, profile: InstrumentProfile?): List<Pair<Song, Fit>> =
        songs.map { it to fit(it, profile) }.filter { it.second != Fit.NO }
}
