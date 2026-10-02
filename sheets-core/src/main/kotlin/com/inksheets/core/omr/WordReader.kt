package com.inksheets.core.omr

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * The words that change the tempo, told on a picture of the page: slowing ("rit.", "rall.",
 * "allarg.", "slower"), back in time ("a tempo", "tempo I"), pressing on ("accel.", "string.") - or
 * any other word. Not read letter by letter: the word's picture, fitted to [W] by [H], is recognised
 * whole by a small network (resources omr/wordnet.bin, made by train/words.py from words drawn in
 * the fonts parts are printed in, roughened as a scan is). What it is sure of, the music is played by.
 */
object WordReader {
    val LABELS = listOf("other", "rit", "atempo", "accel")
    /** As the music is played by it ([Performance] reads these). */
    val TEXT = mapOf("rit" to "rit.", "atempo" to "a tempo", "accel" to "accel.")

    /** Each word the reading finds on a page, as it finds it (for gathering training samples): this thread's. */
    val candidates = ThreadLocal<((IntArray) -> Unit)?>()

    const val W = 48
    const val H = 16

    /** The word in box [bx] ([l], [t], [r], [b]) on the page in [grey]: [W] by [H] cells, dark 1, and how long it is for its height. */
    fun features(grey: IntArray, w: Int, h: Int, bx: IntArray): FloatArray {
        val out = FloatArray(W * H + 1)
        val bw = (bx[2] - bx[0] + 1).toFloat(); val bh = (bx[3] - bx[1] + 1).toFloat()
        val x0 = bx[0] - bw * 0.04f; val y0 = bx[1] - bh * 0.12f
        val cw = bw * 1.08f / W; val ch = bh * 1.24f / H
        for (j in 0 until H) for (i in 0 until W) {
            var sum = 0f
            for (sy in 0..1) for (sx in 0..1) {
                val x = (x0 + (i + 0.25f + sx * 0.5f) * cw).toInt(); val y = (y0 + (j + 0.25f + sy * 0.5f) * ch).toInt()
                sum += if (x in 0 until w && y in 0 until h) 1f - grey[y * w + x] / 255f else 0f
            }
            out[j * W + i] = sum / 4f
        }
        out[W * H] = min(bw / max(1f, bh), 16f) / 8f
        return out
    }

    private class Dense(val n: Int, val m: Int, val w: FloatArray, val b: FloatArray)

    private val layers: List<Dense>? by lazy {
        val bytes = System.getProperty("inksheets.omr.wordnet")?.let { java.io.File(it).readBytes() }
            ?: WordReader::class.java.getResourceAsStream("/omr/wordnet.bin")?.use { it.readBytes() } ?: return@lazy null
        runCatching {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4); buf.get(magic)
            require(String(magic) == "INKS")
            List(buf.int) { val n = buf.int; val m = buf.int; Dense(n, m, FloatArray(n * m) { buf.float }, FloatArray(n) { buf.float }) }
        }.getOrNull()
    }

    val available: Boolean get() = layers != null

    /** What [features] say the word is, and how likely. */
    fun classify(f: FloatArray): Pair<String, Float>? {
        val net = layers ?: return null
        var a = f
        for ((k, l) in net.withIndex()) {
            a = FloatArray(l.n) { i ->
                var v = l.b[i]
                for (j in 0 until min(l.m, a.size)) v += l.w[i * l.m + j] * a[j]
                if (k < net.size - 1) max(0f, v) else v
            }
        }
        val top = a.max()
        val e = a.map { exp(it - top) }
        val best = a.indices.maxBy { a[it] }
        return LABELS[best] to e[best] / e.sum()
    }

    fun read(grey: IntArray, w: Int, h: Int, bx: IntArray): Pair<String, Float>? = classify(features(grey, w, h, bx))
}
