package com.inksheets.core

import kotlin.math.abs

/**
 * Which of a song's parts to open for the instrument being played, and whether a song belongs in
 * the list at all.
 *
 * A song with no part for your instrument opens the part nearest to it: a bass guitarist whose
 * pep band chart has no bass part gets the tuba part, which plays the same line in the same clef;
 * a euphonium with no euphonium part gets the trombone's. Nearness is where an instrument sounds,
 * which clef it reads, and what it does in a band. A song whose parts are all far from yours
 * (only a piccolo part, for a tuba) is hidden from the list; a part nobody has named an instrument
 * for is still shown, marked, so an unread scan never vanishes just because it could not be read.
 */
object PartChoice {

    enum class Fit {
        YES,
        /** No part for the instrument, but one near enough to play from (tuba for bass guitar). */
        CLOSE,
        UNKNOWN,
        NO
    }

    /** How far apart two instruments can be and still be worth opening in place of each other. */
    const val NEAR = 12

    /** Middle of where each instrument sounds (MIDI note), the clef it reads, its kind of part. */
    private class Voice(val pitch: Int, val clef: String, val role: String)

    private val voices = mapOf(
        "piccolo" to Voice(86, "treble", "melody"),
        "flute" to Voice(74, "treble", "melody"),
        "oboe" to Voice(72, "treble", "melody"),
        "english-horn" to Voice(64, "treble", "melody"),
        "bassoon" to Voice(50, "bass", "low"),
        "clarinet" to Voice(67, "treble", "melody"),
        "alto-clarinet" to Voice(58, "treble", "middle"),
        "bass-clarinet" to Voice(46, "treble", "low"),
        "contra-clarinet" to Voice(38, "treble", "low"),
        "soprano-sax" to Voice(67, "treble", "melody"),
        "alto-sax" to Voice(62, "treble", "middle"),
        "tenor-sax" to Voice(55, "treble", "middle"),
        "bari-sax" to Voice(44, "treble", "low"),
        "trumpet" to Voice(67, "treble", "melody"),
        "horn" to Voice(60, "treble", "middle"),
        "mellophone" to Voice(62, "treble", "middle"),
        "trombone" to Voice(52, "bass", "middle"),
        "bass-trombone" to Voice(44, "bass", "low"),
        "baritone-tc" to Voice(52, "treble", "middle"),
        "baritone-bc" to Voice(52, "bass", "middle"),
        "euphonium-tc" to Voice(52, "treble", "middle"),
        "euphonium" to Voice(52, "bass", "middle"),
        "tuba" to Voice(38, "bass", "low"),
        "string-bass" to Voice(38, "bass", "low"),
        "bass-guitar" to Voice(38, "bass", "low"),
        "piano" to Voice(60, "grand", "harmony"),
        "guitar" to Voice(55, "treble", "harmony"),
        "violin" to Voice(72, "treble", "melody"),
        "viola" to Voice(62, "alto", "middle"),
        "cello" to Voice(50, "bass", "low"),
        "vocals" to Voice(62, "treble", "melody"),
        "steel-pan" to Voice(70, "treble", "melody")
    )

    private val drums = setOf("percussion", "drumline", "drums")

    /**
     * How far a part for [part] is from the instrument [wanted]: 0 for the same one (or one that
     * reads the same parts), more the further apart; null when it would be no use at all (a
     * drum part for a flute). A full score holds everyone's line, so it is always some use.
     */
    fun distance(wanted: String, part: String): Int? {
        if (wanted == part || part in Instruments.sisters(wanted)) return 0
        if (part == "score") return 30
        if (wanted in drums || part in drums) return if (wanted in drums && part in drums) 5 else null
        val a = voices[wanted] ?: return null
        val b = voices[part] ?: return null
        var d = abs(a.pitch - b.pitch)
        if (a.clef != b.clef && a.clef != "grand" && b.clef != "grand") d += 8
        if (a.role != b.role) d += 2
        return d
    }

    /** How well [song] fits [profile]; everything fits when no instrument is chosen. */
    fun fit(song: Song, profile: InstrumentProfile?): Fit {
        if (profile == null || song.parts.isEmpty()) return Fit.YES
        val ids = profile.instruments.map { seat(it).first }.flatMap { listOf(it) + Instruments.sisters(it) }
        if (song.parts.any { p -> p.instrument in ids || p.also.any { it in ids } }) return Fit.YES
        if (nearest(song, profile)?.second?.let { it <= NEAR } == true) return Fit.CLOSE
        if (song.parts.any { it.instrument == null }) return Fit.UNKNOWN
        return Fit.NO
    }

    /**
     * The part to open: the profile's most preferred instrument that the song has, then one that
     * reads the same parts, then a part printed for several instruments one of which is yours,
     * then the nearest instrument's part, then a part of unknown instrument, then the nearest of
     * whatever is left. With no profile, the first part.
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
            val near = nearest(song, profile)
            if (near != null && near.second <= NEAR) return near.first
            song.parts.firstOrNull { it.instrument == null }?.let { return it }
            near?.let { return it.first }
        }
        return song.parts.firstOrNull()
    }

    /** The instrument whose part [partFor] falls back on, when it is not one of the profile's own. */
    fun standIn(song: Song, profile: InstrumentProfile?): Part? {
        if (profile == null) return null
        val part = partFor(song, profile) ?: return null
        val ids = profile.instruments.map { seat(it).first }.flatMap { listOf(it) + Instruments.sisters(it) }
        return part.takeIf { p -> p.instrument != null && p.instrument !in ids && p.also.none { it in ids } }
    }

    /**
     * The part nearest to any of the profile's instruments, and how near. Earlier (preferred)
     * instruments count a little nearer; among equals, the lower chair.
     */
    private fun nearest(song: Song, profile: InstrumentProfile): Pair<Part, Int>? {
        var best: Pair<Part, Int>? = null
        profile.instruments.forEachIndexed { rank, entry ->
            val id = seat(entry).first
            for (p in song.parts) {
                val inst = p.instrument ?: continue
                val d = (listOf(inst) + p.also).mapNotNull { distance(id, it) }.minOrNull() ?: continue
                val score = d + rank
                val b = best
                if (b == null || score < b.second || (score == b.second && (p.chair ?: 0) < (b.first.chair ?: 0))) best = p to score
            }
        }
        return best
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
