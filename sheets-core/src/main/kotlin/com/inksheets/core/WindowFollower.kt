package com.inksheets.core

import kotlin.math.abs
import kotlin.math.max

/**
 * Where in the music the band has got to, judged from the last several seconds heard at once
 * rather than a tenth at a time: every half second the stretch just heard is lined up against the
 * music (subsequence dynamic time warping: it may start anywhere, and goes at half to twice the
 * music's pace), and where its best line-up ends - near where the band should be by now, unless
 * somewhere else sounds plainly more like it - is where the band is now.
 *
 * A tenth of a second of a band's sound matches a dozen places in a song; fifteen seconds of it
 * matches one. Following a tenth at a time loses its place in a moment's confusion and does not
 * find it again; this cannot wander far, and finds its way back from wherever a mistake left it.
 */
class WindowFollower(
    private val reference: List<Chroma.Frame>,
    startMs: Long = 0,
    /** How much of what was heard is lined up each time, in frames (tenths of a second). */
    private val heardFrames: Int = 150,
    /** How often it lines up again, in frames heard. */
    private val every: Int = 5,
    /**
     * What being a frame away from where the band should be by now costs, against how well the
     * frames match: enough to tell a chorus from the same chorus a minute on, too little to keep it
     * from a place that sounds plainly more like what is heard (found again after a mistake).
     */
    private val prior: Float = 0.0002f,
    /** How many times dearer a frame back is than a frame on. */
    private val backCost: Float = 6f,
    /**
     * Where the band may be starting, at the latest: the music can start anywhere from [startMs]
     * to here (the page in front - the band may be anywhere on it) at no cost, until it is found.
     */
    startEndMs: Long = startMs
) {
    private val n = reference.size
    private val heard = ArrayList<Chroma.Frame>()
    private var sinceAlign = 0
    private var at = (startMs / Chroma.FRAME_MS).toInt().coerceIn(0, max(0, n - 1))
    /** Frames heard since the last line-up: the band has moved on by about that many since. */
    private var sinceLast = 0
    /** Where the band may be, until it is first found: the page in front, moving on as time passes. */
    private val windowLo = (startMs / Chroma.FRAME_MS).toInt()
    private val windowHi = max(windowLo, (startEndMs / Chroma.FRAME_MS).toInt())
    private var heardSinceStart = 0

    /** Found: the music heard has been lined up surely at least once. Until then the place is a guess. */
    var found = false
        private set

    /**
     * How sure the last line-up was, 0-1: how much better the best place matched than the best
     * place clearly elsewhere (four seconds or more away). Low means two places sound alike, or
     * nothing sounds like what is heard - the band is not where it thinks.
     */
    var confidence = 0f
        private set

    /** How fast the band goes against the music, from the last line-up (1 = as written). */
    var pace = 1.0
        private set

    /** Where the music heard has got to, in ms of the music. */
    var positionMs: Long = startMs
        private set

    val lengthMs: Long get() = n * Chroma.FRAME_MS

    /** One frame heard. Quiet frames - a rest, a gap - hold the place rather than move it. */
    fun hear(frame: Chroma.Frame, quiet: Boolean): Long {
        if (n == 0 || quiet) return positionMs
        heard += frame
        if (heard.size > heardFrames * 2) heard.subList(0, heard.size - heardFrames).clear()
        sinceLast++
        heardSinceStart++
        // (Not before five seconds are heard: two of an intro sound like half the song.)
        if (++sinceAlign >= every && heard.size >= 50) {
            sinceAlign = 0
            align()?.let { at = it; sinceLast = 0 }
        }
        // Between line-ups, on at the band's pace.
        val now = (at + sinceLast * pace).toInt().coerceIn(0, n - 1)
        positionMs = now * Chroma.FRAME_MS
        return positionMs
    }

    /**
     * The end of the best line-up of the frames just heard in the music near [at]; null when none
     * is clearly better than staying put. Steps: on one in both, or two in the music for one heard
     * (twice as fast), or one in the music for two heard (half as fast) - each costed by how well
     * the frames match, so the path goes where the music sounds like what was heard.
     */
    private fun align(): Int? {
        val q = heard.takeLast(heardFrames)
        val m = q.size
        val predicted = (at + sinceLast * pace).toInt()
        // Not found yet: anywhere on the page in front (moved on by what has been heard since) is
        // as likely as anywhere else on it.
        val winLo = windowLo + heardSinceStart
        val winHi = windowHi + heardSinceStart
        // The whole of the music: a place lost is found again wherever it is.
        val lo = 0
        val hi = n - 1
        val w = hi - lo + 1
        if (w <= 2) return null
        val inf = Float.MAX_VALUE / 4
        // Cost and length of the best path to each cell; two rows back are needed for the 2-steps,
        // and each frame's distances to the music are worked out once, for its row and the next.
        var prev2 = FloatArray(w) { inf }; var prev2Len = IntArray(w)
        var prev = FloatArray(w) { inf }; var prevLen = IntArray(w)
        var cur = FloatArray(w); var curLen = IntArray(w)
        var dPrev = FloatArray(w); var dCur = FloatArray(w)
        for (i in 0 until m) {
            val qi = q[i].notes
            for (jj in 0 until w) {
                val r = reference[lo + jj]
                dCur[jj] = if (r.loudness == 0f) 0.35f else { val rn = r.notes; var dot = 0f; for (k in 0 until 12) dot += qi[k] * rn[k]; 1f - dot }
            }
            for (jj in 0 until w) {
                val c = dCur[jj]
                if (i == 0) { cur[jj] = c; curLen[jj] = 1; continue }
                var best = inf; var len = 1; var bestPer = inf
                // One and one.
                if (jj >= 1 && prev[jj - 1] < inf) { val v = prev[jj - 1] + c; val l = prevLen[jj - 1] + 1; val per = v / l; if (per < bestPer) { best = v; len = l; bestPer = per } }
                // Two in the music for one heard.
                if (jj >= 2 && prev[jj - 2] < inf) { val v = prev[jj - 2] + c + dCur[jj - 1]; val l = prevLen[jj - 2] + 2; val per = v / l; if (per < bestPer) { best = v; len = l; bestPer = per } }
                // One in the music for two heard.
                if (i >= 2 && jj >= 1 && prev2[jj - 1] < inf) { val v = prev2[jj - 1] + c + dPrev[jj]; val l = prev2Len[jj - 1] + 2; val per = v / l; if (per < bestPer) { best = v; len = l; bestPer = per } }
                cur[jj] = best; curLen[jj] = len
            }
            val t2 = prev2; prev2 = prev; prev = cur; cur = t2
            val l2 = prev2Len; prev2Len = prevLen; prevLen = curLen; curLen = l2
            val td = dPrev; dPrev = dCur; dCur = td
        }
        // The best end: lowest cost per step, with a little cost for being far from where the band
        // should be by now (so two places alike are told apart by which follows on).
        var bestJ = -1; var bestCost = Float.MAX_VALUE
        val raw = FloatArray(w) { Float.MAX_VALUE }
        for (jj in 0 until w) {
            if (prev[jj] >= inf) continue
            val per = prev[jj] / prevLen[jj]
            // Back costs more than on: repeats are already laid out in the music as played, so a band
            // goes back only when it starts again - and a passage a few parts carry alone (the rest
            // resting) sounds less like the band than an earlier tutti does, which must not pull it back.
            val off = if (!found) {
                val j = lo + jj
                when { j < winLo -> j - winLo; j > winHi -> j - winHi; else -> 0 }
            } else lo + jj - predicted
            // (The less heard, the less it says: the pull to where the band should be is the stronger.)
            // (Before it is found, the page in front is only a hint: the music heard decides.)
            val pull = if (found) prior else prior * 0.3f
            val c = per + pull * heardFrames / m * (if (off < -20) -off * backCost else abs(off).toFloat())
            raw[jj] = per
            if (c < bestCost) { bestCost = c; bestJ = jj }
        }
        if (bestJ < 0) return null
        // How sure: how much better the music heard matches here than anywhere clearly elsewhere -
        // by the match alone, not by where the band was expected (a place chosen only because it
        // was expected is not a place found).
        var here = Float.MAX_VALUE
        for (jj in max(0, bestJ - 5)..minOf(w - 1, bestJ + 5)) here = minOf(here, raw[jj])
        var other = Float.MAX_VALUE
        for (jj in 0 until w) if (abs(jj - bestJ) > 40 && raw[jj] < other) other = raw[jj]
        val sure = if (other == Float.MAX_VALUE) 1f else ((other - here) / other.coerceAtLeast(1e-6f) * SHARPNESS).coerceIn(0f, 1f)
        val end = lo + bestJ
        // An unsure line-up far from where the band should be is not believed: the place carries on
        // at the band's pace instead - a moment's confusion must not throw the page about.
        if (found && sure < UNSURE && abs(end - predicted) > 30) { confidence = sure; return null }
        confidence = sure
        if (sure >= UNSURE) found = true
        // The band's pace from how far the music moved since the last line-up.
        if (sinceLast > 0) {
            val moved = (end - at).toDouble() / sinceLast
            if (moved in 0.4..2.5) pace = 0.7 * pace + 0.3 * moved
        }
        return end
    }

    companion object {
        /** Below this a line-up is unsure (see [confidence]). */
        const val UNSURE = 0.35f
        /** How a match's lead over the best elsewhere maps to [confidence]: a lead of an eighth of the cost is sure. */
        private const val SHARPNESS = 8f
    }
}
