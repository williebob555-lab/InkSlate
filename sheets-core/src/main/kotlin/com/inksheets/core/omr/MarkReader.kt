package com.inksheets.core.omr

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * What a mark printed round the notes is, on a picture of the page: a dynamic's letter, an accent,
 * a staccato, a tenuto, a marcato, a fermata, a slur's or tie's curve, a hairpin's side, a word's
 * letters, a figure - or something else. Asked of each shape a bar keeps as printed (what its
 * redraw does not draw - see [Measure.kept]): the picture of it fitted to a square, how big it is
 * and where on the staff it stands. A small network (resources omr/marknet.bin, made by
 * train/marks.py from MarkExport's samples) answers; the music is played by what it says.
 */
object MarkReader {
    val LABELS = listOf("other", "dyn_p", "dyn_m", "dyn_f", "dyn_s", "dyn_z", "dyn_r",
        "accent", "staccato", "tenuto", "marcato", "fermata", "curve", "hairpin", "word", "digit")

    const val N = 16
    const val EXTRA = 5

    /** A shape's box: [l], [t], [r], [b] (inclusive), from its outline's points. */
    fun box(outline: IntArray): IntArray {
        var l = Int.MAX_VALUE; var r = Int.MIN_VALUE; var t = Int.MAX_VALUE; var b = Int.MIN_VALUE
        for (i in 0 until outline.size - 1 step 2) { l = min(l, outline[i]); r = max(r, outline[i]); t = min(t, outline[i + 1]); b = max(b, outline[i + 1]) }
        return intArrayOf(l, t, r, b)
    }

    /**
     * What the network is given for the shape in box [bx] on the page in [grey] ([w] by [h]),
     * [sp] pixels to a space, its staff's top line at [top]: the shape's box (a little margin
     * round it) fitted into [N] by [N], its longer side across the whole, how dark each cell is;
     * then its width and height in spaces, how high its middle stands (half spaces down from the
     * top line), and whether it is over the staff.
     */
    fun features(grey: IntArray, w: Int, h: Int, bx: IntArray, sp: Float, top: Float): FloatArray {
        val out = FloatArray(N * N + EXTRA)
        val bw = (bx[2] - bx[0] + 1).toFloat(); val bh = (bx[3] - bx[1] + 1).toFloat()
        val side = max(bw, bh) * 1.15f + 2f
        val cx = (bx[0] + bx[2]) / 2f; val cy = (bx[1] + bx[3]) / 2f
        val cell = side / N
        for (j in 0 until N) for (i in 0 until N) {
            var sum = 0f
            for (sy in 0..1) for (sx in 0..1) {
                val x = (cx - side / 2 + (i + 0.25f + sx * 0.5f) * cell).toInt(); val y = (cy - side / 2 + (j + 0.25f + sy * 0.5f) * cell).toInt()
                sum += if (x in 0 until w && y in 0 until h) 1f - grey[y * w + x] / 255f else 0f
            }
            out[j * N + i] = sum / 4f
        }
        out[N * N] = min(bw / sp, 16f) / 8f
        out[N * N + 1] = min(bh / sp, 8f) / 4f
        out[N * N + 2] = ((cy - top) / (sp / 2)).coerceIn(-24f, 32f) / 16f
        out[N * N + 3] = if (cy < top) 1f else 0f
        out[N * N + 4] = bw / max(1f, bh) / 8f
        return out
    }

    /** One layer: [n] outputs from [m] inputs (a convolution's: [n] channels from [m], 3 by 3). */
    private class Layer(val n: Int, val m: Int, val w: FloatArray, val b: FloatArray)

    /** Two 3x3 convolutions, then two dense layers (train/marks.py's Net). */
    private val layers: List<Layer>? by lazy {
        val bytes = System.getProperty("inksheets.omr.marknet")?.let { java.io.File(it).readBytes() }
            ?: MarkReader::class.java.getResourceAsStream("/omr/marknet.bin")?.use { it.readBytes() } ?: return@lazy null
        runCatching {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4); buf.get(magic)
            require(String(magic) == "INKC")
            List(4) { k -> val n = buf.int; val m = buf.int; val size = if (k < 2) n * m * 9 else n * m; Layer(n, m, FloatArray(size) { buf.float }, FloatArray(n) { buf.float }) }
        }.getOrNull()
    }

    /** A 3x3 convolution of [x] ([l].m planes of [s] by [s], padded), relu, then 2x2 max-pooled. */
    private fun convPool(l: Layer, x: FloatArray, s: Int): FloatArray {
        val conv = FloatArray(l.n * s * s)
        for (o in 0 until l.n) for (y in 0 until s) for (xx in 0 until s) {
            var v = l.b[o]
            for (i in 0 until l.m) for (ky in 0..2) {
                val yy = y + ky - 1
                if (yy < 0 || yy >= s) continue
                for (kx in 0..2) {
                    val xk = xx + kx - 1
                    if (xk < 0 || xk >= s) continue
                    v += l.w[((o * l.m + i) * 3 + ky) * 3 + kx] * x[(i * s + yy) * s + xk]
                }
            }
            conv[(o * s + y) * s + xx] = max(0f, v)
        }
        val h = s / 2
        return FloatArray(l.n * h * h) { idx ->
            val o = idx / (h * h); val y = idx / h % h; val xx = idx % h
            maxOf(conv[(o * s + 2 * y) * s + 2 * xx], conv[(o * s + 2 * y) * s + 2 * xx + 1], conv[(o * s + 2 * y + 1) * s + 2 * xx], conv[(o * s + 2 * y + 1) * s + 2 * xx + 1])
        }
    }

    private fun dense(l: Layer, x: FloatArray, relu: Boolean) = FloatArray(l.n) { i ->
        var v = l.b[i]
        for (j in 0 until min(l.m, x.size)) v += l.w[i * l.m + j] * x[j]
        if (relu) max(0f, v) else v
    }

    val available: Boolean get() = layers != null

    /** What the shape in box [bx] is, and how likely (see [features]); null without the network. */
    fun read(grey: IntArray, w: Int, h: Int, bx: IntArray, sp: Float, top: Float): Pair<String, Float>? =
        classify(features(grey, w, h, bx, sp, top))

    /** What [features] (see [features]) say the shape is, and how likely. */
    fun classify(f: FloatArray): Pair<String, Float>? {
        val net = layers ?: return null
        val p1 = convPool(net[0], f.copyOf(N * N), N)
        val p2 = convPool(net[1], p1, N / 2)
        val a = dense(net[3], dense(net[2], p2 + f.copyOfRange(N * N, N * N + EXTRA), relu = true), relu = false)
        val top1 = a.max()
        val e = a.map { exp(it - top1) }
        val best = a.indices.maxBy { a[it] }
        return LABELS[best] to e[best] / e.sum()
    }
}
