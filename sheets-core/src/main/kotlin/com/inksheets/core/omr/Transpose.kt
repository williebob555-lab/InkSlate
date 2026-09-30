package com.inksheets.core.omr

/**
 * Music written for one instrument re-written for another - a B-flat trumpet's part as an E-flat
 * alto sax reads it - sounding the same: every note moved by the difference in the two
 * instruments' transpositions, spelled in the new key (moved round the circle of fifths the same
 * way), and the accidentals the new bars need worked out afresh.
 */
object Transpose {
    /** The key shift, in fifths, for [semitones]: the nearer way round (a tone up is two sharps). */
    fun fifthsFor(semitones: Int): Int {
        val f = Math.floorMod(semitones * 7, 12)
        return if (f > 6) f - 12 else f
    }

    /** Letter names moved for [semitones], spelled as a key [fifthsFor] away would spell them. */
    fun stepsFor(semitones: Int): Int {
        val f = fifthsFor(semitones)
        // Each fifth is four letters up; then whole octaves to come to the interval's size.
        val letters = Math.floorMod(4 * f, 7)
        val size = Math.floorMod(7 * f, 12)   // semitones of that interval, 0-11 up
        val octaves = Math.floorDiv(semitones - size, 12)
        return letters + 7 * octaves
    }

    /**
     * [score] re-written [semitones] higher (negative lower). Clefs stay as they are; notes too
     * high or low for the staff simply take ledger lines.
     */
    fun score(score: Score, semitones: Int): Score {
        if (semitones == 0) return score
        return score.copy(measures = score.measures.map { measure(it, semitones) })
    }

    /**
     * [score], written for an instrument [from] semitones above where it sounds, re-written for
     * one [to] above - in whichever octave keeps the part on its staff best.
     */
    fun forInstrument(score: Score, from: Int, to: Int): Score {
        val base = to - from
        val heads = score.measures.flatMap { m -> m.events.filterIsInstance<Note>().flatMap { it.pitches } }
        if (heads.isEmpty()) return score(score, base)
        val clef = score.measures.first().clef
        // The octave putting the average note nearest the middle line.
        val shift = (-2..2).minByOrNull { k ->
            val semis = base + 12 * k
            val mean = heads.map { clef.topLine - (it.diatonic + stepsFor(semis)) }.average()
            kotlin.math.abs(mean - 4)
        } ?: 0
        return score(score, base + 12 * shift)
    }

    fun measure(m: Measure, semitones: Int): Measure {
        val f = fifthsFor(semitones)
        val steps = stepsFor(semitones)
        var fifths = m.key.fifths + f
        // More than seven sharps or flats: the same key spelled the other way.
        if (fifths > 7) fifths -= 12
        if (fifths < -7) fifths += 12
        val key = Key(fifths)
        // Spelled from its sound where the key wrapped round, else moved by letters.
        val respelled = fifths != m.key.fifths + f
        val written = HashMap<Int, Int>()   // diatonic -> alter shown, for the rest of the bar
        val events = m.events.map { e ->
            if (e !is Note) e else {
                val pitches = e.pitches.map { p ->
                    val midi = p.midi + semitones
                    val d = if (!respelled) p.diatonic + steps else nearestSpelling(midi, key)
                    Pitch.fromDiatonic(d, midi - natural(d))
                }
                // A head's place on the staff: the clef's step for its letter.
                val newSteps = pitches.map { m.clef.topLine - it.diatonic }
                val accs = HashMap<Int, Int>()
                pitches.forEachIndexed { i, p ->
                    val expected = written[p.diatonic] ?: key.alterOf(p.step)
                    if (p.alter != expected) { accs[newSteps[i]] = p.alter; written[p.diatonic] = p.alter }
                }
                val order = newSteps.indices.sortedBy { newSteps[it] }
                e.copy(steps = order.map { newSteps[it] }, pitches = order.map { pitches[it] }, accidentals = accs)
            }
        }
        return m.copy(key = key, events = events, showsKey = m.showsKey || key.fifths != m.key.fifths)
    }

    private fun natural(diatonic: Int): Int = Pitch.fromDiatonic(diatonic, 0).midi

    /** The letter [midi] is best spelled with in [key]: its own note of the key, else the nearest natural. */
    private fun nearestSpelling(midi: Int, key: Key): Int {
        val base = Math.floorDiv(midi, 12) * 7 - 7
        return (base - 7..base + 14).minByOrNull { d ->
            val alter = midi - natural(d)
            if (kotlin.math.abs(alter) > 2) 100 else kotlin.math.abs(alter - key.alterOf(Math.floorMod(d, 7))) * 2 + kotlin.math.abs(alter)
        } ?: base
    }
}
