package com.inksheets.core.omr

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * The trained reader's network, run in plain Kotlin (no library: a phone runs it as it is). Its
 * weights are those train/reader.py export() writes: each layer a convolution (batch norm folded
 * in), 3x3 or 1x1. The shape of the network is fixed here as there (see Reader.forward).
 */
class Net private constructor(private val layers: Map<String, Layer>) {
    class Layer(val cout: Int, val cin: Int, val k: Int, val stride: Int, val dil: Int, val w: FloatArray, val b: FloatArray)

    /** A stack of planes: [c] channels of [h] by [w]. */
    class Planes(val c: Int, val h: Int, val w: Int, val data: FloatArray = FloatArray(c * h * w))

    /** The network's output over [strip]: [Planes] of 21 channels at a quarter its size (see reader.py). */
    fun run(strip: Strip): Planes {
        val x = Planes(1, Strips.H, strip.width, strip.pixels.copyOf())
        val a = conv("c4", conv("c3", conv("c2", conv("c1", x))))
        val b0 = conv("d3", conv("d2", conv("d1", a)))
        val c = conv("e3", conv("e2", conv("e1", b0)))
        val b = addUp(b0, conv("u2", c, relu = false))
        val a2 = addUp(a, conv("u1", b, relu = false))
        return conv("out", conv("h1", a2), relu = false)
    }

    /** [small] doubled in size (nearest) and added to [big]. */
    private fun addUp(big: Planes, small: Planes): Planes {
        val out = big.data.copyOf()
        for (ch in 0 until big.c) for (y in 0 until big.h) {
            val sy = min(small.h - 1, y * small.h / big.h)
            for (x in 0 until big.w) out[(ch * big.h + y) * big.w + x] += small.data[(ch * small.h + sy) * small.w + min(small.w - 1, x * small.w / big.w)]
        }
        return Planes(big.c, big.h, big.w, out)
    }

    private fun conv(name: String, x: Planes, relu: Boolean = true): Planes {
        val l = layers.getValue(name)
        val pad = if (l.k == 3) l.dil else 0
        val oh = (x.h + 2 * pad - l.dil * (l.k - 1) - 1) / l.stride + 1
        val ow = (x.w + 2 * pad - l.dil * (l.k - 1) - 1) / l.stride + 1
        val out = Planes(l.cout, oh, ow)
        // Output channels shared out among the cores.
        val per = max(1, (l.cout + threads - 1) / threads)
        val jobs = (0 until l.cout step per).map { c0 ->
            Callable {
                for (co in c0 until min(l.cout, c0 + per)) {
                    val o = co * oh * ow
                    java.util.Arrays.fill(out.data, o, o + oh * ow, l.b[co])
                    for (ci in 0 until l.cin) {
                        val ib = ci * x.h * x.w
                        for (ky in 0 until l.k) for (kx in 0 until l.k) {
                            val wv = l.w[((co * l.cin + ci) * l.k + ky) * l.k + kx]
                            if (wv == 0f) continue
                            val dy = ky * l.dil - pad; val dx = kx * l.dil - pad
                            // Columns of the output whose input column is inside the picture.
                            val xs = max(0, (-dx + l.stride - 1) / l.stride)
                            val xe = min(ow, (x.w - dx + l.stride - 1) / l.stride)
                            if (xs >= xe) continue
                            for (oy in 0 until oh) {
                                val iy = oy * l.stride + dy
                                if (iy < 0 || iy >= x.h) continue
                                val ir = ib + iy * x.w + dx
                                val orow = o + oy * ow
                                if (l.stride == 1) for (ox in xs until xe) out.data[orow + ox] += wv * x.data[ir + ox]
                                else for (ox in xs until xe) out.data[orow + ox] += wv * x.data[ir + ox * l.stride]
                            }
                        }
                    }
                    if (relu) for (i in o until o + oh * ow) if (out.data[i] < 0f) out.data[i] = 0f
                }
                Unit
            }
        }
        if (jobs.size == 1) jobs[0].call() else pool.invokeAll(jobs).forEach { it.get() }
        return out
    }

    companion object {
        private val threads = max(1, min(4, Runtime.getRuntime().availableProcessors() - 1))
        private val pool by lazy { Executors.newFixedThreadPool(threads) { r -> Thread(r, "reader-net").apply { isDaemon = true } } }

        fun load(input: InputStream): Net {
            val bytes = input.readBytes()
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4); buf.get(magic)
            require(String(magic) == "INKR") { "not reader weights" }
            val n = buf.int
            val layers = HashMap<String, Layer>()
            repeat(n) {
                val nameBytes = ByteArray(buf.int); buf.get(nameBytes)
                val cout = buf.int; val cin = buf.int; val kh = buf.int; buf.int; val stride = buf.int; val dil = buf.int
                val w = FloatArray(cout * cin * kh * kh) { buf.float }
                val b = FloatArray(cout) { buf.float }
                layers[String(nameBytes)] = Layer(cout, cin, kh, stride, dil, w, b)
            }
            return Net(layers)
        }

        /** The weights shipped with the app (resources omr/reader.bin), or null where there are none. */
        val shipped: Net? by lazy { Net::class.java.getResourceAsStream("/omr/reader.bin")?.use { load(it) } }
    }
}
