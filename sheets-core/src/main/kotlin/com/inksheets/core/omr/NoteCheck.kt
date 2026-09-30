package com.inksheets.core.omr

import com.inksheets.core.Chroma

/**
 * Notes played against the notes read: while the music is followed by ear, each moment heard is
 * compared with the notes the part has there - which of the twelve notes sound - and each bar
 * keeps its score. A bar that sounds, again and again, unlike what is written is one to look at:
 * a wrong note, a missed accidental. Not a judge of tone or time; just "this bar sounded off".
 *
 * [played] is the part in playing order ([PlayOrder.unrolled]), at [bpm], [transpose] semitones
 * written above sounding - the same timeline the follower follows.
 */
class NoteCheck(private val played: Score, bpm: Double, transpose: Int) {
    private val expected = ScoreAudio.frames(played, bpm, transpose)
    private val starts = ScoreAudio.measureStarts(played, bpm)
    private val sum = HashMap<Int, Double>()
    private val count = HashMap<Int, Int>()
    /** The longest run of moments in each bar that sounded nothing like it, and the run going on. */
    private val worstRun = HashMap<Int, Int>()
    private var run = 0
    private var runBar = -1

    /** What was heard at [atMs] into the music (where the follower says it is). */
    fun hear(atMs: Long, heard: Chroma.Frame) {
        val i = (atMs / Chroma.FRAME_MS).toInt()
        val e = expected.getOrNull(i) ?: return
        // Only where the part plays, and something is heard: a rest, or quiet, says nothing.
        if (e.loudness == 0f || heard.loudness < 0.02f) return
        val k = starts.indexOfLast { it <= atMs }.takeIf { it >= 0 } ?: return
        val bar = played.measures[k].number
        var dot = 0.0
        for (n in 0 until 12) dot += e.notes[n] * heard.notes[n]
        sum[bar] = (sum[bar] ?: 0.0) + dot
        count[bar] = (count[bar] ?: 0) + 1
        // One wrong note in a bar of right ones: a stretch that sounds nothing like it.
        if (bar != runBar) { run = 0; runBar = bar }
        run = if (dot < 0.35) run + 1 else 0
        if (run > (worstRun[bar] ?: 0)) worstRun[bar] = run
    }

    /** How alike each bar sounded to what is written, 0 to 1, for bars heard long enough. */
    fun likeness(minFrames: Int = 4): Map<Int, Double> =
        sum.keys.filter { (count[it] ?: 0) >= minFrames }.associateWith { sum[it]!! / count[it]!! }

    /** Bars that sounded unlike what is written. */
    fun doubtful(below: Double = LIKE, minFrames: Int = 4): List<Int> =
        (likeness(minFrames).filter { it.value < below }.keys + worstRun.filter { it.value >= WRONG_RUN }.keys).distinct().sorted()

    companion object {
        /** Below this, a bar sounded unlike its notes. Set from the tests: right notes on any instrument come well above it. */
        const val LIKE = 0.62
        /** A stretch this many tenths of a second long sounding nothing like the notes: a wrong note. */
        const val WRONG_RUN = 3
    }
}
