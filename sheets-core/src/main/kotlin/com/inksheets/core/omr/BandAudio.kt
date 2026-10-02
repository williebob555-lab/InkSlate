package com.inksheets.core.omr

import com.inksheets.core.Chroma
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The whole band as the room hears it, worked out from the parts read off their pages: every
 * part's notes laid out on one player's bars, as the same twelve-note frames [Chroma] makes from
 * sound. A microphone on a stand in a band hears the band - the tune, the bass line, the chords
 * - not one part alone, and a part resting for eight bars still has the band to follow through
 * them. Bars come from the player's own part (its numbers, its repeats, its pages), so where the
 * music is turns straight into which page should be up.
 */
object BandAudio {
    /** One part: its notes as read, how far it is written above where it sounds, how much of the room it is. */
    class Voice(val score: Score, val transpose: Int, val weight: Float = 1f)

    /** A bar of the player's part as played: its number, when it starts and how long it lasts (ms), and its page. */
    class Bar(val number: Int, val startMs: Long, val lengthMs: Long, val page: Int)

    /**
     * Every bar of [mine] in playing order (repeats taken, a multi-bar rest as its bars), each as
     * long as its time signature says at [bpm] quarter notes a minute - not as long as the notes
     * read in it add up to: a bar misread is still a bar long.
     */
    fun bars(mine: Score, bpm: Double): List<Bar> {
        val out = ArrayList<Bar>()
        var t = 0.0
        val msPerQuarter = 60_000.0 / bpm
        for (m in PlayOrder.unrolled(mine).measures) for (k in 0 until m.bars) {
            // (A pickup as long as its notes.)
            val len = (if (m.bars > 1) m.time.quarters else m.playedQuarters) * msPerQuarter
            out += Bar(m.number + k, t.toLong(), len.toLong(), m.page)
            t += len
        }
        return out
    }

    /** Every change of page as [mine] is played at [bpm]: (ms, page) - back, for a repeat across a page break. */
    fun pageChanges(mine: Score, bpm: Double): List<Pair<Long, Int>> {
        val out = ArrayList<Pair<Long, Int>>()
        var page = -1
        for (b in bars(mine, bpm)) {
            if (page >= 0 && b.page != page) out += b.startMs to b.page
            page = b.page
        }
        return out
    }

    /**
     * The band at [bpm] along [mine]'s bars: [mine] itself (written [myTranspose] above where it
     * sounds) and [others], each bar of theirs found by its number. A note sounds loudest as it
     * starts and fades; a little of its fifth goes with it, as an instrument's overtones would.
     */
    fun frames(mine: Score, myTranspose: Int, others: List<Voice>, bpm: Double, myWeight: Float = 1.5f): List<Chroma.Frame> {
        val bars = bars(mine, bpm)
        val total = ((bars.lastOrNull()?.let { it.startMs + it.lengthMs } ?: 0L) / Chroma.FRAME_MS).toInt() + 1
        val energy = Array(total) { FloatArray(12) }
        val msPerQuarter = 60_000.0 / bpm
        val voices = listOf(Voice(mine, myTranspose, myWeight)) + agreeing(mine, others)
        // Each part's bars by number, for laying them on the player's.
        val byNumber = voices.map { v ->
            HashMap<Int, Measure>().also { map -> for (m in v.score.measures) if (m.bars == 1) map.putIfAbsent(m.number, m) }
        }
        for (b in bars) {
            val barEnd = b.startMs + b.lengthMs.toDouble()
            for ((vi, v) in voices.withIndex()) {
                val m = byNumber[vi][b.number] ?: continue
                var t = b.startMs.toDouble()
                for (e in m.events) {
                    if (t >= barEnd) break
                    val len = e.duration.quarters * msPerQuarter
                    if (e is Note) {
                        val from = (t / Chroma.FRAME_MS).toInt()
                        val to = (minOf(t + len, barEnd) / Chroma.FRAME_MS).toInt().coerceAtLeast(from + 1)
                        for (f in from until minOf(to, total)) for (p in e.pitches) {
                            val pc = (p.midi - v.transpose).mod(12)
                            val decay = v.weight / (1f + 0.15f * (f - from))
                            energy[f][pc] += decay
                            energy[f][(pc + 7) % 12] += 0.3f * decay
                        }
                    }
                    t += len
                }
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
     * The parts of [others] whose bars run as [mine]'s do: a part read with far more or fewer bars
     * (a figure over a multi-bar rest misread, a page missed) would put its notes against the wrong
     * bars of the player's, and the band heard would match nothing played.
     */
    fun agreeing(mine: Score, others: List<Voice>): List<Voice> {
        fun last(s: Score) = s.measures.maxOfOrNull { it.number + it.bars - 1 } ?: 0
        val n = last(mine)
        return others.filter { v -> abs(last(v.score) - n) <= maxOf(2, n / 20) }
    }

    /** Where each change of page falls in [recording] (its frames), the band's music lined up with it: (ms in the recording, page). */
    fun changesIn(mine: Score, myTranspose: Int, others: List<Voice>, recording: List<Chroma.Frame>, bpm: Double): List<Pair<Long, Int>> {
        val map = ScoreAudio.align(frames(mine, myTranspose, others, bpm), recording)
        if (map.isEmpty()) return emptyList()
        return pageChanges(mine, bpm).map { (ms, page) ->
            val f = (ms / Chroma.FRAME_MS).toInt().coerceIn(0, map.size - 1)
            map[f] * Chroma.FRAME_MS to page
        }
    }
}
