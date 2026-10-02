package com.inksheets.core.omr

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * The second look at one symbol: what a head, a rest or an accidental the trained reader found
 * really is - which, or none at all (a dynamic's letter, a word's, a breath mark, a hairpin's end). Not the page: only the little picture round it, 2.6 staff spaces
 * by 5, and how high on the staff it stands. A small network (resources omr/symnet.bin, made by
 * train/symbols.py from SymbolExport's samples) answers "what is this?", and the rules round it
 * decide what the bar is.
 */
object SymbolReader {
    /** What it can say, in the network's order. */
    val LABELS = listOf("other", "sharp", "flat", "natural", "block", "rest4", "rest8", "rest16", "black", "half", "whole")

    /** What the trained reader finds that this looks at again. */
    val KINDS = setOf(Printed.Kind.REST_1, Printed.Kind.REST_2, Printed.Kind.REST_4, Printed.Kind.REST_8, Printed.Kind.REST_16,
        Printed.Kind.SHARP, Printed.Kind.FLAT, Printed.Kind.NATURAL, Printed.Kind.HEAD_BLACK, Printed.Kind.HEAD_HALF, Printed.Kind.HEAD_WHOLE)

    const val W = 20
    const val H = 40

    /** A symbol's kind (by name, a Printed or answer-key kind) as one of [LABELS]. */
    fun labelOf(kind: String): String = when (kind) {
        "SHARP" -> "sharp"; "FLAT" -> "flat"; "NATURAL" -> "natural"
        "REST_1", "REST_2" -> "block"; "REST_4" -> "rest4"; "REST_8" -> "rest8"; "REST_16", "REST_32" -> "rest16"
        "HEAD_BLACK" -> "black"; "HEAD_HALF" -> "half"; "HEAD_WHOLE" -> "whole"
        else -> "other"
    }

    /**
     * The picture round ([cx], [cy]) on the page in [grey] ([w] by [h], 0 black - 255 paper), [sp]
     * pixels to a staff space: [W] by [H] cells across 2.6 spaces by 5, each how dark it is (0 paper
     * - 1 black), each the mean of four points in it.
     */
    fun crop(grey: IntArray, w: Int, h: Int, cx: Float, cy: Float, sp: Float): FloatArray {
        val out = FloatArray(W * H)
        val cw = sp * 2.6f / W; val ch = sp * 5f / H
        val x0 = cx - sp * 1.3f; val y0 = cy - sp * 2.5f
        for (j in 0 until H) for (i in 0 until W) {
            var sum = 0f
            for (sy in 0..1) for (sx in 0..1) {
                val x = (x0 + (i + 0.25f + sx * 0.5f) * cw).toInt(); val y = (y0 + (j + 0.25f + sy * 0.5f) * ch).toInt()
                sum += if (x in 0 until w && y in 0 until h) 1f - grey[y * w + x] / 255f else 0f
            }
            out[j * W + i] = sum / 4f
        }
        return out
    }

    /** One layer: [n] outputs from [m] inputs. */
    private class Dense(val n: Int, val m: Int, val w: FloatArray, val b: FloatArray)

    private val layers: List<Dense>? by lazy {
        // (-Dinksheets.omr.symnet=<file>: other weights, for measuring - one never shown the held-out songs.)
        val bytes = System.getProperty("inksheets.omr.symnet")?.let { java.io.File(it).readBytes() }
            ?: SymbolReader::class.java.getResourceAsStream("/omr/symnet.bin")?.use { it.readBytes() } ?: return@lazy null
        runCatching {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4); buf.get(magic)
            require(String(magic) == "INKS")
            List(buf.int) { val n = buf.int; val m = buf.int; Dense(n, m, FloatArray(n * m) { buf.float }, FloatArray(n) { buf.float }) }
        }.getOrNull()
    }

    val available: Boolean get() = layers != null

    /**
     * What the symbol round ([cx], [cy]) is, and how likely: its picture, how high it stands on its
     * staff ([step], half spaces down from the top line) and what the trained reader took it for
     * ([was], one of [LABELS]). Null where there is no network.
     */
    fun read(grey: IntArray, w: Int, h: Int, cx: Float, cy: Float, sp: Float, step: Float, was: String): Pair<String, Float>? {
        val net = layers ?: return null
        val px = crop(grey, w, h, cx, cy, sp)
        val x = FloatArray(W * H + 1 + LABELS.size)
        System.arraycopy(px, 0, x, 0, px.size)
        x[W * H] = step / 8f
        LABELS.indexOf(was).takeIf { it >= 0 }?.let { x[W * H + 1 + it] = 1f }
        var a = x
        for ((k, l) in net.withIndex()) {
            val o = FloatArray(l.n)
            for (i in 0 until l.n) {
                var s = l.b[i]
                val row = i * l.m
                for (j in 0 until min(l.m, a.size)) s += l.w[row + j] * a[j]
                o[i] = if (k < net.size - 1) max(0f, s) else s
            }
            a = o
        }
        val top = a.max()
        val e = a.map { exp(it - top) }
        val sum = e.sum()
        val best = a.indices.maxBy { a[it] }
        return LABELS[best] to e[best] / sum
    }
}
