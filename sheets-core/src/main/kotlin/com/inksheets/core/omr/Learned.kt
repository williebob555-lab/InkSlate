package com.inksheets.core.omr

import kotlin.math.abs
import kotlin.math.exp

/**
 * What the trained reader ([Net]) finds on a page: each staff cut into a [Strip], the network run
 * over it, and each peak of its heat maps taken as a symbol - heads with their beams and dots,
 * rests, notes' dots, accidentals - with how likely it is. Given to [Recognizer.read] as a
 * [Printed] marked learned, which builds the bars from them as from a PDF's own symbols.
 */
object Learned {
    private const val STRIDE = 4
    private const val NC = 12
    private val kinds = listOf(Printed.Kind.HEAD_BLACK, Printed.Kind.HEAD_HALF, Printed.Kind.HEAD_WHOLE, Printed.Kind.REST_1, Printed.Kind.REST_2,
        Printed.Kind.REST_4, Printed.Kind.REST_8, Printed.Kind.REST_16, Printed.Kind.DOT, Printed.Kind.SHARP, Printed.Kind.FLAT, Printed.Kind.NATURAL)

    /** Below this a peak is not taken at all; between it and [SURE] it is taken, doubted. */
    var floor = 0.3f
    var SURE = 0.7f
    /** A head this likely, under [floor], is faint: offered among a doubtful bar's other readings. */
    const val FAINT = 0.12f

    /** Whether [s] is a faint head (see [FAINT]). */
    fun faint(s: Printed.Symbol) = s.odds.size == 8 && s.odds[7] < floor

    private fun sigmoid(v: Float) = 1f / (1f + exp(-v))

    /** One symbol found on one staff's strip: page position, and how far from the strip's middle (for a symbol two staves' strips both hold). */
    private class Found(val sym: Printed.Symbol, val fromMiddle: Float)

    fun symbols(grey: IntArray, w: Int, h: Int, staves: List<Recognizer.Staff>, net: Net): Printed {
        // The staves through the network side by side, on a device with the cores for it.
        val all = ArrayList<Found>()
        for (found in Workers.map(staves) { s -> val strip = Strips.cut(grey, w, h, s); decode(strip, net.run(strip), s.space) }) all += found
        // A symbol in two staves' strips (between staves close together): kept from the one it is nearer the middle of.
        all.sortBy { it.fromMiddle }
        val kept = ArrayList<Printed.Symbol>()
        for (f in all) {
            val sp = staves.first().space
            if (kept.none { k -> k.kind == f.sym.kind && abs(k.x - f.sym.x) < sp * 0.5f && abs(k.y - f.sym.y) < sp * 0.4f }) kept += f.sym
        }
        return Printed(w.toFloat(), h.toFloat(), kept, emptyList(), emptyList(), learned = true)
    }

    private fun decode(strip: Strip, out: Net.Planes, space: Float): List<Found> {
        val gh = out.h; val gw = out.w
        fun at(c: Int, y: Int, x: Int) = out.data[(c * gh + y) * gw + x]
        val found = ArrayList<Found>()
        for (c in 0 until NC) for (y in 0 until gh) for (x in 0 until gw) {
            val v = at(c, y, x)
            val p = sigmoid(v)
            // Heads a little under the floor are kept too, as faint: not read, but offered (a bar's maybe).
            if (p < (if (c <= 2) FAINT else floor)) continue
            // A peak: no neighbour higher.
            var peak = true
            loop@ for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val yy = y + dy; val xx = x + dx
                if (yy in 0 until gh && xx in 0 until gw && at(c, yy, xx) > v) { peak = false; break@loop }
            }
            if (!peak) continue
            val col = (x + sigmoid(at(NC, y, x))) * STRIDE
            val row = (y + sigmoid(at(NC + 1, y, x))) * STRIDE
            val px = strip.pageX(col); val py = strip.pageY(col, row)
            val kind = kinds[c]
            // A head's left edge and width as a font's character would give them; the rest by their middles.
            val width = when (kind) { Printed.Kind.HEAD_WHOLE -> space * 1.6f; Printed.Kind.HEAD_BLACK, Printed.Kind.HEAD_HALF -> space * 1.18f; Printed.Kind.DOT -> space * 0.4f; else -> space }
            var beams = -1; var dots = -1; var conf = p; var odds = emptyList<Float>()
            if (c <= 2) {
                beams = argmax(4) { at(NC + 2 + it, y, x) }
                dots = argmax(3) { at(NC + 6 + it, y, x) }
                // (Kept to three places: what is stored of it is read by the page, not to the last digit.)
                odds = (softmax(4) { at(NC + 2 + it, y, x) } + softmax(3) { at(NC + 6 + it, y, x) } + listOf(p)).map { Math.round(it * 1000f) / 1000f }
                // How sure, all told: that it is a head, and of its value.
                conf = p * softmaxMax(4) { at(NC + 2 + it, y, x) } * softmaxMax(3) { at(NC + 6 + it, y, x) }
            }
            found += Found(Printed.Symbol(kind, px - width / 2, py, width, space * 4f, beams = beams, dots = dots, confidence = conf, odds = odds), abs(row - (Strips.TOP + 20f)))
        }
        return found
    }

    private inline fun argmax(n: Int, f: (Int) -> Float): Int { var b = 0; for (i in 1 until n) if (f(i) > f(b)) b = i; return b }

    private inline fun softmax(n: Int, f: (Int) -> Float): List<Float> {
        var m = f(0); for (i in 1 until n) m = maxOf(m, f(i))
        val e = List(n) { exp(f(it) - m) }; val sum = e.sum()
        return e.map { it / sum }
    }

    private inline fun softmaxMax(n: Int, f: (Int) -> Float): Float {
        var m = f(0); for (i in 1 until n) m = maxOf(m, f(i))
        var sum = 0f; for (i in 0 until n) sum += exp(f(i) - m)
        return 1f / sum
    }
}
