package com.inksheets.core

import java.io.File
import java.io.RandomAccessFile

/**
 * Writing a recording as it is made: 16-bit mono WAV, with the length filled in when it is
 * closed. Anything that plays sound plays a WAV, and nothing is lost if the app is closed
 * mid-take except the length in the header, which most players work out for themselves.
 */
class WavWriter(val file: File, private val sampleRate: Int) : AutoCloseable {

    private val out = RandomAccessFile(file, "rw").apply { setLength(0); write(ByteArray(44)) }
    private var samples = 0L

    val seconds: Double get() = samples.toDouble() / sampleRate

    @Synchronized
    fun write(chunk: FloatArray) {
        val bytes = ByteArray(chunk.size * 2)
        for (i in chunk.indices) {
            val v = (chunk[i].coerceIn(-1f, 1f) * 32767).toInt()
            bytes[2 * i] = v.toByte()
            bytes[2 * i + 1] = (v shr 8).toByte()
        }
        out.write(bytes)
        samples += chunk.size
    }

    @Synchronized
    override fun close() {
        val data = samples * 2
        out.seek(0)
        out.write("RIFF".toByteArray())
        out.write(le32(36 + data))
        out.write("WAVEfmt ".toByteArray())
        out.write(le32(16))
        out.write(le16(1))                  // PCM
        out.write(le16(1))                  // mono
        out.write(le32(sampleRate.toLong()))
        out.write(le32(sampleRate * 2L))    // bytes per second
        out.write(le16(2))                  // bytes per frame
        out.write(le16(16))                 // bits
        out.write("data".toByteArray())
        out.write(le32(data))
        out.close()
    }

    private fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
}
