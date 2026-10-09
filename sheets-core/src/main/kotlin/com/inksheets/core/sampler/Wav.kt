package com.inksheets.core.sampler

import java.io.File
import kotlin.math.floor

/** A decoded recording: one channel (stereo is averaged - playback is mono), at its own [rate]. */
class Pcm(val data: FloatArray, val rate: Int) {
    val frames: Int get() = data.size
}

/** Reads WAV files: PCM 8/16/24/32-bit and float 32/64, mono or more channels, any rate. */
object Wav {
    fun read(file: File): Pcm = decode(file.readBytes())

    fun decode(b: ByteArray): Pcm {
        require(b.size >= 12 && tag(b, 0) == "RIFF" && tag(b, 8) == "WAVE") { "not a WAV file" }
        var pos = 12
        var format = 1; var channels = 1; var rate = 44100; var bits = 16
        var dataAt = -1; var dataLen = 0
        while (pos + 8 <= b.size) {
            val id = tag(b, pos)
            var len = le32(b, pos + 4)
            val body = pos + 8
            if (len < 0 || body + len > b.size) len = b.size - body
            when (id) {
                "fmt " -> {
                    format = le16(b, body); channels = le16(b, body + 2).coerceAtLeast(1)
                    rate = le32(b, body + 4); bits = le16(b, body + 14)
                    if (format == 0xFFFE && len >= 26) format = le16(b, body + 24)
                }
                "data" -> { dataAt = body; dataLen = len }
            }
            if (dataAt >= 0) break
            pos = body + len + (len and 1)
        }
        require(dataAt >= 0) { "WAV has no data" }
        val bytes = bits / 8
        require(bytes in 1..8) { "unsupported WAV depth $bits" }
        val frames = dataLen / (bytes * channels)
        val out = FloatArray(frames)
        val inv = 1f / channels
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                val p = dataAt + (f * channels + c) * bytes
                sum += when {
                    format == 3 && bits == 32 -> java.lang.Float.intBitsToFloat(le32(b, p))
                    format == 3 && bits == 64 -> java.lang.Double.longBitsToDouble((le32(b, p).toLong() and 0xFFFFFFFFL) or (le32(b, p + 4).toLong() shl 32)).toFloat()
                    bits == 8 -> ((b[p].toInt() and 0xFF) - 128) / 128f
                    bits == 16 -> ((b[p].toInt() and 0xFF) or (b[p + 1].toInt() shl 8)).toShort() / 32768f
                    bits == 24 -> (((b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or (b[p + 2].toInt() shl 16))) / 8388608f
                    bits == 32 -> le32(b, p) / 2147483648f
                    else -> 0f
                }
            }
            out[f] = sum * inv
        }
        return Pcm(out, rate)
    }

    /** [pcm] at [to] samples a second (cubic between the points; unchanged when the rate already is). */
    fun resample(pcm: Pcm, to: Int): Pcm {
        if (pcm.rate == to || pcm.frames == 0) return Pcm(pcm.data, to)
        val n = (pcm.frames.toLong() * to / pcm.rate).toInt()
        val step = pcm.rate.toDouble() / to
        val out = FloatArray(n)
        val d = pcm.data
        fun at(i: Int) = d[i.coerceIn(0, d.size - 1)]
        for (k in 0 until n) {
            val p = k * step
            val i = floor(p).toInt(); val t = (p - i).toFloat()
            out[k] = hermite(at(i - 1), at(i), at(i + 1), at(i + 2), t)
        }
        return Pcm(out, to)
    }

    /** Catmull-Rom (cubic Hermite) between [y0] and [y1] at [t] in 0..1. */
    fun hermite(ym: Float, y0: Float, y1: Float, y2: Float, t: Float): Float {
        val c1 = 0.5f * (y1 - ym)
        val c2 = ym - 2.5f * y0 + 2f * y1 - 0.5f * y2
        val c3 = 0.5f * (y2 - ym) + 1.5f * (y0 - y1)
        return ((c3 * t + c2) * t + c1) * t + y0
    }

    private fun tag(b: ByteArray, p: Int) = String(b, p, 4, Charsets.ISO_8859_1)
    private fun le16(b: ByteArray, p: Int) = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, p: Int) = le16(b, p) or (le16(b, p + 2) shl 16)
}
