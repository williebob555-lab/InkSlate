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
 * It is a two-state tracker. SEARCHING: nothing is believed until the best line-up has been the
 * same place, moving on at a plausible pace, for [LOCK_ALIGNS] line-ups in a row (a start part way
 * down the page is found in a few seconds, from anywhere). LOCKED: the place is followed in a window
 * round where the band should be, so it cannot be thrown about by a moment's confusion; it is left
 * only when somewhere else has matched clearly better for several seconds on end (then it jumps
 * straight there, still locked), or when nothing has matched at all for a long time (then it
 * searches again). Rests hold the place. The state is calm: [locked] changes rarely.
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
    startEndMs: Long = startMs,
    /**
     * Which page the music is on at a time in it (ms), when known: two places that sound alike (a repeated
     * chorus) matter only if the page turns at different times from them, so only those make the turn unsafe.
     */
    pageAt: ((Long) -> Int)? = null
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
    private var quietRun = 0
    private val candidates = ArrayList<Int>()
    private var rivalStreak = 0
    private var lastRival = -1
    private var poorAligns = 0
    private var typical = 0f
    private var smooth = 0f
    private var aligns = 0
    private var tieRun = 0
    private var clearRun = 0
    /** Frames from each place to the next page turn (a large number past the last). */
    private val toTurn = IntArray(n) { NO_TURN }.also { t ->
        if (pageAt != null && n > 0) {
            val page = IntArray(n) { pageAt(it * Chroma.FRAME_MS) }
            for (j in n - 2 downTo 0) t[j] = if (page[j + 1] != page[j]) 0 else minOf(NO_TURN, t[j + 1] + 1)
        }
    }

    /** Locked: the place has been found surely, and is followed. Until then (and when lost) it is searching. */
    var locked = false
        private set

    /** Looking for where the band is (nothing believed yet, or lost for a long time). */
    val searching: Boolean get() = !locked

    /** Kept for older callers: found is locked. */
    val found: Boolean get() = locked

    /**
     * Whether the next page turn can be trusted from here: no place that sounds as much like what is heard
     * has the turn at a different time. (Repeats that fall on the same pages don't count.) Hysteresis, no flicker.
     */
    var turnSafe = false
        private set

    /** Locked, but somewhere else has matched clearly better for a moment: the place is in doubt, turns should wait. */
    var suspect = false
        private set

    /** How many times it has jumped to somewhere else while locked (the band had gone elsewhere). */
    var jumps = 0
        private set

    /** Smoothed over time, 0-1: how sure it is of where the band is. At least [UNSURE] while locked and not in doubt. */
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
        if (n == 0) return positionMs
        if (quiet) {
            // A rest, a gap: the place holds (on at the band's pace for a few seconds at most - the music goes on
            // through a rest - and never dropped).
            if (locked && quietRun < 30) { quietRun++; positionMs = (at + (sinceLast + quietRun) * pace).toInt().coerceIn(0, n - 1) * Chroma.FRAME_MS }
            return positionMs
        }
        quietRun = 0
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
        val raw = FloatArray(w) { Float.MAX_VALUE }
        for (jj in 0 until w) if (prev[jj] < inf) raw[jj] = prev[jj] / prevLen[jj]
        aligns++
        return if (locked) follow(raw, w, predicted) else search(raw, w, winLo, winHi)
    }

    /** The match at [jj] and the best match clearly elsewhere (four seconds or more away): how much better, 0-1. */
    private fun lead(raw: FloatArray, jj: Int): Pair<Float, Float> {
        var here = Float.MAX_VALUE
        for (k in max(0, jj - 5)..minOf(raw.size - 1, jj + 5)) here = minOf(here, raw[k])
        var other = Float.MAX_VALUE
        for (k in raw.indices) if (abs(k - jj) > 40 && raw[k] < other) other = raw[k]
        val sure = if (other == Float.MAX_VALUE) 1f else ((other - here) / other.coerceAtLeast(1e-6f) * SHARPNESS).coerceIn(0f, 1f)
        return sure to here
    }

    /** Updates [turnSafe]: is there a place about as good as [jj], clearly away from it, with the turn at another time? */
    private fun tieCheck(raw: FloatArray, jj: Int, here: Float) {
        var tie = false
        val t0 = toTurn[jj]
        for (k in raw.indices) {
            if (abs(k - jj) <= 40 || raw[k] > here * (1f + TIE) + 0.01f) continue
            val t1 = toTurn[k]
            if (abs(t1 - t0) > TURN_TOL) { tie = true; break }
        }
        // Once a stretch has been told apart from the places that sound like it, following it on (in the window
        // round where the band should be) keeps it told apart; a jump, or being lost, starts that again.
        if (tie) { tieRun++; clearRun = 0 } else { clearRun++; tieRun = 0; if (clearRun >= 4) turnSafe = true }
    }

    /** Not locked: the best line-up overall (the page in front pulls a little); locks once it has been one place, going on, a few times running. */
    private fun search(raw: FloatArray, w: Int, winLo: Int, winHi: Int): Int? {
        var bestJ = -1; var bestCost = Float.MAX_VALUE
        for (jj in 0 until w) {
            if (raw[jj] == Float.MAX_VALUE) continue
            val off = when { jj < winLo -> jj - winLo; jj > winHi -> jj - winHi; else -> 0 }
            val c = raw[jj] + prior * EARLY_PULL * heardFrames / minOf(heard.size, heardFrames) * (if (off < -20) -off * backCost else abs(off).toFloat())
            if (c < bestCost) { bestCost = c; bestJ = jj }
        }
        if (bestJ < 0) return null
        val (sure, here) = lead(raw, bestJ)
        smooth = sure
        tieCheck(raw, bestJ, here)
        confidence = sure.coerceAtMost(UNSURE - 0.01f)
        candidates += bestJ
        if (candidates.size > LOCK_ALIGNS) candidates.removeAt(0)
        if (DEBUG) println("FOLLOW search heard=$heardSinceStart cand=$bestJ per=${"%.3f".format(here)} sure=${"%.2f".format(sure)}")
        if (candidates.size == LOCK_ALIGNS && here <= MAX_PER && (sure >= LOCK_SURE || heard.size >= FULL_AFTER)) {
            // Consistent: each line-up a little on from the last (about [every] frames at the band's pace), none far off.
            val ok = candidates.zipWithNext().all { (x, y) -> y - x in -6..35 }
            if (ok) {
                locked = true; suspect = false; rivalStreak = 0; poorAligns = 0; lastRival = -1
                typical = here
                val moved = (candidates.last() - candidates.first()).toDouble() / ((LOCK_ALIGNS - 1) * every)
                if (moved in 0.5..2.0) pace = 0.5 * pace + 0.5 * moved
                smooth = UNSURE + 0.15f
                confidence = smooth
                candidates.clear()
            }
        }
        return bestJ
    }

    /** Locked: the place near where the band should be; a rival place that matches clearly better for several seconds takes over. */
    private fun follow(raw: FloatArray, w: Int, predicted: Int): Int? {
        var localJ = -1; var localCost = Float.MAX_VALUE
        val lo = max(0, predicted - LOCAL_BACK); val hi = minOf(w - 1, predicted + LOCAL_FORWARD)
        for (jj in lo..hi) {
            if (raw[jj] == Float.MAX_VALUE) continue
            val off = jj - predicted
            val c = raw[jj] + prior * heardFrames / minOf(heard.size, heardFrames) * (if (off < -20) -off * backCost else abs(off).toFloat())
            if (c < localCost) { localCost = c; localJ = jj }
        }
        // A window with nothing in it (the place is beyond the music): search again.
        if (localJ < 0) { lose(); return null }
        val here = raw[localJ]
        tieCheck(raw, localJ, here)
        // The rival is judged as the local place is: a place far from where the band should be must match
        // by more than the pull of distance (a chorus that comes back later sounds nearly as good).
        var rivalJ = -1; var rival = Float.MAX_VALUE; var rivalCost = Float.MAX_VALUE
        val pull = prior * heardFrames / minOf(heard.size, heardFrames)
        for (jj in raw.indices) if (abs(jj - localJ) > 40 && raw[jj] < Float.MAX_VALUE) {
            val off = jj - predicted
            val c = raw[jj] + pull * minOf(if (off < -20) -off * backCost else abs(off).toFloat(), PEN_CAP)
            if (c < rivalCost) { rivalCost = c; rival = raw[jj]; rivalJ = jj }
        }
        val advantage = if (rivalJ < 0 || here <= 1e-6f) 0f else (here - rivalCost) / here
        val sureNow = if (rivalJ < 0) 1f else ((rival - here) / rival.coerceAtLeast(1e-6f) * SHARPNESS).coerceIn(0f, 1f)
        smooth = 0.8f * smooth + 0.2f * sureNow
        // Doubt: somewhere else matches clearly better; it must stay the same somewhere, going on, to count.
        // (A rival that matches no better than the place held ever did is not a place the band has gone to:
        // the music there is simply unlike the part - a solo, a cut - and the place is held.)
        if (advantage > RIVAL && rival <= typical * RIVAL_GOOD) {
            val consistent = lastRival >= 0 && rivalJ - lastRival in -40..(40 + every * 3)
            rivalStreak = if (consistent) rivalStreak + 1 else 1
            lastRival = rivalJ
        } else if (rivalStreak > 0) { rivalStreak--; if (rivalStreak == 0) lastRival = -1 } else lastRival = -1
        suspect = rivalStreak >= 3
        // Nothing matches (a solo the parts do not have, a stop): hold on a good while before searching again.
        if (here > typical * POOR && here > POOR_FLOOR) poorAligns++ else poorAligns = max(0, poorAligns - 2)
        if (DEBUG) println("FOLLOW lock heard=$heardSinceStart at=$localJ per=${"%.3f".format(here)} typ=${"%.3f".format(typical)} rival=$rivalJ ${"%.3f".format(rival)} adv=${"%.2f".format(advantage)} streak=$rivalStreak poor=$poorAligns")
        if (poorAligns >= LOSE_ALIGNS && rivalStreak == 0) { lose(); return localJ }
        confidence = if (suspect) minOf(smooth, UNSURE - 0.01f) else max(smooth, UNSURE + 0.05f)
        var end = localJ
        // (A rival that wins by a wide margin is believed sooner.)
        if (rivalStreak >= LOST_ALIGNS || (advantage > STRONG && rivalStreak >= STRONG_ALIGNS)) {
            // Gone elsewhere for good: straight there, still locked.
            end = rivalJ; jumps++; turnSafe = false; clearRun = 0; rivalStreak = 0; lastRival = -1; suspect = false; typical = rival; poorAligns = 0
            smooth = UNSURE + 0.05f; confidence = smooth
        } else {
            if (advantage <= RIVAL) typical = 0.9f * typical + 0.1f * here
            if (sinceLast > 0) {
                val moved = (end - at).toDouble() / sinceLast
                if (moved in 0.4..2.5) pace = 0.8 * pace + 0.2 * moved
            }
        }
        return end
    }

    private fun lose() { locked = false; turnSafe = false; tieRun = 0; clearRun = 0; suspect = false; candidates.clear(); rivalStreak = 0; poorAligns = 0; confidence = 0f }

    companion object {
        /** Below this a line-up is unsure (see [confidence]). */
        const val UNSURE = 0.35f
        /** How a match's lead over the best elsewhere maps to [confidence]: a lead of an eighth of the cost is sure. */
        private val SHARPNESS = System.getProperty("inksheets.turns.sharp")?.toFloatOrNull() ?: 8f
        /** Before it is found, how strongly the page in front pulls (against the found pull). */
        private val EARLY_PULL = System.getProperty("inksheets.turns.early")?.toFloatOrNull() ?: 0.3f
        private fun knob(name: String, d: Float) = System.getProperty("inksheets.turns.$name")?.toFloatOrNull() ?: d
        private val DEBUG = System.getProperty("inksheets.turns.debug") != null
        /** Line-ups in a row at one place, going on, before it is believed. */
        private val LOCK_ALIGNS = knob("lockAligns", 3f).toInt()
        /** Two places sounding alike (short of this lead) are not told apart until this many frames have been heard. */
        private val LOCK_SURE = knob("lockSure", 0f)
        private val FULL_AFTER = knob("fullAfter", 150f).toInt()
        private const val NO_TURN = 3000
        /** A place within this fraction of the best match is as good as it. */
        private val TIE = knob("tie", 0.08f)
        /** Turn times from two such places closer than this (frames) are the same turn. */
        private val TURN_TOL = knob("turnTol", 25f).toInt()
        /** A match this poor per step (1 - cosine) is not a place found. */
        private val MAX_PER = knob("maxPer", 0.6f)
        /** How much better (fraction of the local cost) a rival place must match to count against the place held. */
        private val RIVAL = knob("rival", 0.15f)
        /** A rival must match at least this well against the usual match of the place held. */
        private val RIVAL_GOOD = knob("rivalGood", 1.0f)
        /** Line-ups (half seconds) a rival must lead for before the place jumps to it. */
        private val LOST_ALIGNS = knob("lostAligns", 8f).toInt()
        /** A rival leading by this much (fraction of the local cost) is believed after [STRONG_ALIGNS] line-ups. */
        private val STRONG = knob("strong", 0.4f)
        private val STRONG_ALIGNS = knob("strongAligns", 3f).toInt()
        /** A match this many times worse than usual, and above [POOR_FLOOR], counts as nothing matching. */
        private val POOR = knob("poor", 1.8f)
        private val POOR_FLOOR = knob("poorFloor", 0.5f)
        /** Line-ups of nothing matching before it is searching again (half seconds). */
        private val LOSE_ALIGNS = knob("loseAligns", 40f).toInt()
        /** The most a far place can be pulled against, in frames' worth of distance. */
        private val PEN_CAP = knob("penCap", 300f)
        private val LOCAL_BACK = knob("localBack", 30f).toInt()
        private val LOCAL_FORWARD = knob("localForward", 50f).toInt()
    }
}
