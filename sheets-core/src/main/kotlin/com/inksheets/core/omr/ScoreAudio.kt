package com.inksheets.core.omr

import com.inksheets.core.Chroma
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * A part read off its pages, heard: what notes sound when, a tenth of a second at a time, as the
 * same twelve-note frames [Chroma] makes from sound - so the music on the page can be lined up
 * with a recording of it, or followed by ear on its own. Where each page starts falls out of that,
 * and so the page turns, with nobody having to turn them first.
 */
object ScoreAudio {
    /** Where each measure starts, in ms, at [bpm] quarter notes a minute (multi-bar rests counted). */
    fun measureStarts(score: Score, bpm: Double): List<Long> = score.timeline(bpm).map { it.second }

    /** Where each page after the first starts, in ms at [bpm]: the turns. */
    fun pageStarts(score: Score, bpm: Double): List<Long> {
        val starts = measureStarts(score, bpm)
        return (1 until score.pages).mapNotNull { p ->
            val i = score.measures.indexOfFirst { it.page >= p }
            if (i < 0) null else starts[i]
        }
    }

    /** The whole length, in ms at [bpm]. */
    fun length(score: Score, bpm: Double): Long {
        val t = score.timeline(bpm).lastOrNull() ?: return 0
        val m = t.first
        return t.second + ((if (m.bars > 1) m.time.quarters * m.bars else m.time.quarters) * 60_000.0 / bpm).toLong()
    }

    /**
     * The score as frames at [bpm]: each sounding note's pitch class, and a little of the fifth
     * above (its strongest overtone besides the octave), as a recording's frames would have them.
     * [transpose] is how far the written notes sit above the sounding ones.
     */
    fun frames(score: Score, bpm: Double, transpose: Int = 0): List<Chroma.Frame> {
        val total = (length(score, bpm) / Chroma.FRAME_MS).toInt() + 1
        val energy = Array(total) { FloatArray(12) }
        val starts = measureStarts(score, bpm)
        val msPerQuarter = 60_000.0 / bpm
        for ((i, m) in score.measures.withIndex()) {
            if (m.bars > 1) continue
            var t = starts[i].toDouble()
            val barEnd = t + m.time.quarters * msPerQuarter
            for (e in m.events) {
                val len = e.duration.quarters * msPerQuarter
                if (t >= barEnd) break
                if (e is Note) {
                    val from = (t / Chroma.FRAME_MS).toInt()
                    val to = (minOf(t + len, barEnd) / Chroma.FRAME_MS).toInt()
                    for (f in from until minOf(to, total)) for (p in e.pitches) {
                        val pc = (p.midi - transpose).mod(12)
                        // A note is loudest as it starts.
                        val decay = 1f / (1f + 0.15f * (f - from))
                        energy[f][pc] += decay
                        energy[f][(pc + 7) % 12] += 0.3f * decay
                    }
                }
                t += len
            }
        }
        return energy.map { v ->
            val n = FloatArray(12) { ln(1f + 10f * v[it]) }
            val norm = sqrt(n.sumOf { (it * it).toDouble() }).toFloat()
            if (norm > 1e-6f) for (k in 0 until 12) n[k] /= norm
            Chroma.Frame(n, if (norm > 1e-6f) 0.1f else 0f)
        }
    }

    /**
     * For each frame of [score], the frame of [recording] it lines up with - the whole of each
     * compared at once (dynamic time warping), the tempo free to change as the band's does.
     */
    fun align(score: List<Chroma.Frame>, recording: List<Chroma.Frame>): IntArray {
        val n = score.size; val m = recording.size
        if (n == 0 || m == 0) return IntArray(n)
        // Backpointers only: 0 diagonal, 1 hold the score, 2 hold the recording.
        val back = ByteArray(n * m)
        var prev = FloatArray(m) { Float.MAX_VALUE }
        var cur = FloatArray(m)
        fun d(i: Int, j: Int): Float {
            val a = score[i].notes; val b = recording[j].notes
            var dot = 0f
            for (k in 0 until 12) dot += a[k] * b[k]
            // A quiet frame of the score (a rest) costs nothing against anything.
            return if (score[i].loudness == 0f) 0.3f else 1f - dot
        }
        for (i in 0 until n) {
            for (j in 0 until m) {
                val c = d(i, j)
                if (i == 0 && j == 0) { cur[j] = c; continue }
                val diag = if (i > 0 && j > 0) prev[j - 1] else Float.MAX_VALUE
                val up = if (i > 0) prev[j] + 0.15f else Float.MAX_VALUE
                val left = if (j > 0) cur[j - 1] + 0.15f else Float.MAX_VALUE
                val best = minOf(diag, up, left)
                cur[j] = c + best
                back[i * m + j] = when (best) { diag -> 0; up -> 1; else -> 2 }
            }
            val t = prev; prev = cur; cur = t
        }
        // Back from the end: the recording may run on past the score (applause, a tag).
        val map = IntArray(n)
        var i = n - 1
        var j = (0 until m).minByOrNull { prev[it] }!!
        while (i >= 0 && j >= 0) {
            map[i] = j
            when (back[i * m + j].toInt()) {
                0 -> { i--; j-- }
                1 -> i--
                else -> j--
            }
            if (i < 0 || j < 0) break
        }
        while (i >= 0) { map[i] = 0; i-- }
        return map
    }

    /**
     * The page turns in [recording] (its frames): where each page of [score] starts, lined up
     * with it - no one having to turn them first. [bpm] is only where to start from; the
     * recording's own tempo is followed.
     */
    fun turnsIn(score: Score, recording: List<Chroma.Frame>, bpm: Double, transpose: Int = 0): List<Long> {
        val frames = frames(score, bpm, transpose)
        val map = align(frames, recording)
        return pageStarts(score, bpm).map { ms ->
            val f = (ms / Chroma.FRAME_MS).toInt().coerceIn(0, map.size - 1)
            map[f] * Chroma.FRAME_MS
        }
    }
}
