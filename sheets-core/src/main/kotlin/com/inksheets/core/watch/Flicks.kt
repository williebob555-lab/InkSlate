package com.inksheets.core.watch

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Turning pages with a flick of the wrist, from a watch's gyroscope (experimental).
 *
 * A player's arm never stops - a bass's plucking hand, a trombone's slide arm - so nothing here is
 * guessed: a calibration ([FlickTrainer]) records the player playing normally and flicking at
 * cues, and learns the way each flick turns the watch and how fast it has to be to stand clear of
 * everything the playing does. [FlickDetector] then runs on the watch, a sample at a time.
 */

/** One gyroscope reading (rad/s, the watch's own axes) with the accelerometer's (m/s²) beside it, at [t] ms. */
data class Sample(val t: Long, val gx: Float, val gy: Float, val gz: Float, val ax: Float = 0f, val ay: Float = 0f, val az: Float = 0f)

/** A cue given during calibration: at [t] ms (the watch's clock) the player was asked to flick for [next] or back. */
data class Cue(val t: Long, val next: Boolean)

/** What one calibration recorded: playing, with cues among it. */
data class Session(val samples: List<Sample>, val cues: List<Cue>)

/**
 * One way of flicking: the way the watch turns ([x], [y], [z], a unit vector in its own axes), how
 * fast along it a flick has to be ([threshold], rad/s), and how close to that way it must be
 * ([cosMin], the cosine of the widest angle off it).
 */
@Serializable
data class FlickWay(val x: Float, val y: Float, val z: Float, val threshold: Float, val cosMin: Float)

/** A calibration's result: the way for the next page, and for the one before (none when it could not be told). */
@Serializable
data class FlickModel(val next: FlickWay, val back: FlickWay? = null, val name: String = "", val made: Long = 0L) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun decode(text: String?): FlickModel? = text?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/**
 * Flicks out of a stream of gyroscope readings. One flick is one turn; the swing back after it is
 * not a flick the other way; a second flick straight after the first is a second turn - as fast as
 * the hand can make them.
 */
class FlickDetector(val model: FlickModel) {
    private val ways = listOfNotNull(model.next, model.back)
    /** Each way ready to fire again: it fell back below half its threshold since it last fired. */
    private val armed = BooleanArray(ways.size) { true }
    private val blockedUntil = LongArray(ways.size) { Long.MIN_VALUE }

    /** Feed one reading; [NEXT] or [BACK] when it completes a flick, else 0. */
    fun feed(t: Long, gx: Float, gy: Float, gz: Float): Int {
        val mag = sqrt(gx * gx + gy * gy + gz * gz)
        var fired = -1
        for ((i, w) in ways.withIndex()) {
            val p = gx * w.x + gy * w.y + gz * w.z
            if (!armed[i]) {
                if (p < w.threshold * REARM && t >= blockedUntil[i]) armed[i] = true
                continue
            }
            if (fired < 0 && t >= blockedUntil[i] && p >= w.threshold && mag > 0f && p / mag >= w.cosMin) fired = i
        }
        if (fired < 0) return 0
        for (i in ways.indices) {
            armed[i] = false
            // The swing back from a flick is a flick the other way to the gyroscope: not a turn.
            if (i != fired) blockedUntil[i] = t + RETURN_MS
        }
        return if (fired == 0) NEXT else BACK
    }

    fun feed(s: Sample): Int = feed(s.t, s.gx, s.gy, s.gz)

    companion object {
        const val NEXT = 1
        const val BACK = -1
        /** How far below its threshold a way must fall before it can fire again. */
        const val REARM = 0.5f
        /** How long after a flick the other way is held off, while the hand swings back. */
        const val RETURN_MS = 300L
    }
}

/**
 * Learning the flicks from calibrations: which way each one turns the watch, and a speed above all
 * the playing and below the flicks. Every number it decides is reported, so it is plain whether to
 * trust it.
 */
object FlickTrainer {
    /** A cue's flick is looked for from this long after it (no one reacts quicker)... */
    const val REACT_MS = 120L
    /** ...until this long after it. */
    const val WINDOW_MS = 3000L
    /** Around each cue, this much is neither playing nor flick: getting ready, settling after. */
    const val GUARD_BEFORE_MS = 600L
    const val GUARD_AFTER_MS = 3500L
    /** Slower than this is never taken as a flick (rad/s, about 170°/s), whatever the playing does. */
    const val MIN_THRESHOLD = 3.0f

    /** How a calibration went, in numbers. */
    data class Report(
        val model: FlickModel?,
        /** Why there is no model, or what to do about a weak one; null when all is well. */
        val problem: String?,
        val nextCues: Int, val backCues: Int,
        /** The cues whose flick was found (others: no flick, or too slow to be one). */
        val nextFlicks: Int, val backFlicks: Int,
        /** The fastest the playing went along each way, and the slowest flick (rad/s). */
        val nextPlaying: Float, val nextSlowest: Float,
        val backPlaying: Float, val backSlowest: Float,
        /** Replaying the recordings through the result: cues turned the right way, the wrong way, missed, and turns with no cue. */
        val right: Int, val wrong: Int, val missed: Int, val falseTurns: Int,
        val playingSeconds: Int
    ) {
        /** How much faster the slowest flick was than the fastest playing, along the next page's way. */
        val margin: Float get() = if (nextPlaying > 0f) nextSlowest / nextPlaying else 0f
    }

    fun train(sessions: List<Session>, name: String = "", now: Long = System.currentTimeMillis()): Report {
        val nextCues = sessions.sumOf { s -> s.cues.count { it.next } }
        val backCues = sessions.sumOf { s -> s.cues.count { !it.next } }
        val playingMs = sessions.sumOf { s -> s.samples.count { x -> !nearCue(s, x.t) }.toLong() * stepMs(s) }
        fun none(problem: String) = Report(null, problem, nextCues, backCues, 0, 0, 0f, 0f, 0f, 0f, 0, 0, 0, 0, (playingMs / 1000).toInt())

        if (nextCues < 3) return none("Not enough cues to learn from - calibrate again and flick at every cue.")
        val next = learnWay(sessions, true) ?: return none("No flick could be found after the cues for the next page. Flick quickly, as soon as the watch buzzes.")
        val back = if (backCues >= 2) learnWay(sessions, false) else null
        // Two ways that are one: the back flick must turn the watch some other way.
        val backUsable = back?.takeIf { b -> dot(b.dir, next.dir) < 0.7f }

        val nextWay = wayFor(sessions, next, true)
        val backWay = backUsable?.let { wayFor(sessions, it, false) }
        val model = FlickModel(nextWay.way, backWay?.way, name, now)
        val replay = replay(sessions, model)

        val problem = when {
            nextWay.found < 3 -> "Only ${nextWay.found} of $nextCues flicks for the next page were found. Flick quickly, as soon as the watch buzzes."
            nextWay.slowest < nextWay.way.threshold || (backWay != null && backWay.slowest < backWay.way.threshold) ->
                "Some of the playing moved the watch as fast as a flick, so the flicks have to be sharper than some you made. " +
                    "Flick harder, or calibrate again playing as you really do."
            playingMs < 20_000 -> "Only ${playingMs / 1000} seconds of playing were recorded - calibrate again for longer, so it knows the playing."
            back != null && backUsable == null -> "The flicks for back turned the watch the same way as those for next. Flick the other way for back and calibrate again."
            else -> null
        }
        return Report(
            model, problem, nextCues, backCues, nextWay.found, backWay?.found ?: 0,
            nextWay.playing, nextWay.slowest, backWay?.playing ?: 0f, backWay?.slowest ?: 0f,
            replay.right, replay.wrong, replay.missed, replay.falseTurns, (playingMs / 1000).toInt()
        )
    }

    /** Cues answered, the right and wrong way, missed, and turns where there was no cue. */
    data class Replay(val right: Int, val wrong: Int, val missed: Int, val falseTurns: Int)

    /** Run the recordings through [model] as the watch would. */
    fun replay(sessions: List<Session>, model: FlickModel): Replay {
        var right = 0; var wrong = 0; var missed = 0; var falseTurns = 0
        for (s in sessions) {
            val d = FlickDetector(model)
            val turns = s.samples.mapNotNull { x -> d.feed(x).takeIf { it != 0 }?.let { x.t to (it == FlickDetector.NEXT) } }
            for (c in s.cues) {
                val first = turns.firstOrNull { (t, _) -> t >= c.t && t <= c.t + WINDOW_MS }
                when {
                    first == null -> missed++
                    first.second == c.next -> right++
                    else -> wrong++
                }
            }
            falseTurns += turns.count { (t, _) -> !nearCue(s, t) }
        }
        return Replay(right, wrong, missed, falseTurns)
    }

    private class Learned(val dir: FloatArray)
    private class WayResult(val way: FlickWay, val found: Int, val slowest: Float, val playing: Float)

    /** The way the watch turns at the cued flicks for [next] (or back): their common axis, and its sign from which lobe comes first. */
    private fun learnWay(sessions: List<Session>, next: Boolean): Learned? {
        val peaks = ArrayList<FloatArray>()
        val windows = ArrayList<List<Sample>>()
        for (s in sessions) for (c in s.cues.filter { it.next == next }) {
            val w = window(s, c)
            val peak = w.maxByOrNull { mag(it) } ?: continue
            if (mag(peak) < MIN_THRESHOLD * 0.5f) continue
            peaks += floatArrayOf(peak.gx, peak.gy, peak.gz)
            windows += w
        }
        if (peaks.size < 2) return null
        // The axis all the peaks lie along, whichever way each went: the main axis of their spread.
        val m = Array(3) { DoubleArray(3) }
        for (p in peaks) { val n = mag(p); for (i in 0..2) for (j in 0..2) m[i][j] += (p[i] / n * p[j] / n).toDouble() }
        val axis = principal(m)
        // Which way along it: the flick goes out before it comes back, so the first big lobe is the flick.
        var votes = 0
        for (w in windows) {
            val along = w.map { it.gx * axis[0] + it.gy * axis[1] + it.gz * axis[2] }
            val top = along.maxOf { abs(it) }
            val first = along.firstOrNull { abs(it) >= top * 0.6f } ?: continue
            votes += if (first > 0) 1 else -1
        }
        val sign = if (votes >= 0) 1f else -1f
        return Learned(floatArrayOf(axis[0] * sign, axis[1] * sign, axis[2] * sign))
    }

    /** The speed and closeness [learned]'s flicks need: clear of all the playing, and of the slowest flick only as far as it must be. */
    private fun wayFor(sessions: List<Session>, learned: Learned, next: Boolean): WayResult {
        val d = learned.dir
        // The flicks: the fastest along the way in each cue's window, and how close to the way it was there.
        val flicks = ArrayList<Pair<Float, Float>>()
        for (s in sessions) for (c in s.cues.filter { it.next == next }) {
            val w = window(s, c)
            val best = w.maxByOrNull { along(it, d) } ?: continue
            val p = along(best, d)
            val cos = if (mag(best) > 0f) p / mag(best) else 0f
            // No flick at all, or one some other way: says nothing about this way's flicks.
            if (cos < 0.5f || p < MIN_THRESHOLD * 0.5f) continue
            flicks += p to cos
        }
        val median = flicks.map { it.first }.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
        // A cue answered late or not at all leaves only playing in its window: not a flick.
        val kept = flicks.filter { it.first >= median * 0.4f }
        val cosMin = ((kept.minOfOrNull { it.second } ?: 0.8f) - 0.1f).coerceIn(0.5f, 0.9f)
        val slowest = kept.minOfOrNull { it.first } ?: 0f
        // The playing: everything away from the cues, at the closeness the flicks need.
        var playing = 0f
        for (s in sessions) for (x in s.samples) {
            if (nearCue(s, x.t)) continue
            val p = along(x, d)
            if (p > playing && mag(x) > 0f && p / mag(x) >= cosMin) playing = p
        }
        val threshold = when {
            slowest > playing * 1.25f -> max(playing + 0.45f * (slowest - playing), playing * 1.2f)
            // The flicks are no faster than the playing: never turn while playing - flicks will have to be sharper.
            else -> playing * 1.1f
        }.coerceAtLeast(MIN_THRESHOLD)
        return WayResult(FlickWay(d[0], d[1], d[2], threshold, cosMin), kept.size, slowest, playing)
    }

    private fun window(s: Session, c: Cue): List<Sample> = s.samples.filter { it.t >= c.t + REACT_MS && it.t <= c.t + WINDOW_MS }

    private fun nearCue(s: Session, t: Long): Boolean = s.cues.any { t >= it.t - GUARD_BEFORE_MS && t <= it.t + GUARD_AFTER_MS }

    private fun stepMs(s: Session): Long = if (s.samples.size < 2) 10L else
        ((s.samples.last().t - s.samples.first().t) / (s.samples.size - 1)).coerceAtLeast(1L)

    private fun mag(s: Sample) = sqrt(s.gx * s.gx + s.gy * s.gy + s.gz * s.gz)
    private fun mag(p: FloatArray) = sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2])
    private fun along(s: Sample, d: FloatArray) = s.gx * d[0] + s.gy * d[1] + s.gz * d[2]
    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    /** The main axis of a symmetric 3×3 matrix, by power iteration. */
    private fun principal(m: Array<DoubleArray>): FloatArray {
        var v = doubleArrayOf(1.0, 0.7, 0.4)
        repeat(60) {
            val w = DoubleArray(3) { i -> m[i][0] * v[0] + m[i][1] * v[1] + m[i][2] * v[2] }
            val n = sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2])
            if (n == 0.0) return floatArrayOf(1f, 0f, 0f)
            v = DoubleArray(3) { w[it] / n }
        }
        return FloatArray(3) { v[it].toFloat() }
    }
}
