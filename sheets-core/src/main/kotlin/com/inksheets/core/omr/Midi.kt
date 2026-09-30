package com.inksheets.core.omr

import java.io.ByteArrayOutputStream

/**
 * Music read off the page as a standard MIDI file: one track, the notes as they sound (written
 * pitch less the instrument's transposition), at the tempo given, in the instrument's General MIDI
 * sound. A measure read wrongly still takes exactly its bar, padded or cut, so every later measure
 * stays where it belongs.
 */
object Midi {
    const val TICKS = 480   // per quarter note

    /** The General MIDI program for an instrument id (see Instruments), piano when unknown. */
    fun program(instrument: String?): Int = when (instrument?.substringBefore('|')) {
        "piccolo" -> 72; "flute" -> 73; "oboe" -> 68; "english-horn" -> 69; "bassoon", "contrabassoon" -> 70
        "clarinet", "alto-clarinet", "bass-clarinet", "contra-clarinet" -> 71
        "soprano-sax" -> 64; "alto-sax" -> 65; "tenor-sax" -> 66; "bari-sax" -> 67
        "trumpet" -> 56; "horn", "mellophone" -> 60; "trombone", "bass-trombone" -> 57
        "baritone-tc", "baritone-bc", "euphonium-tc", "euphonium-bc", "euphonium" -> 58
        "tuba", "sousaphone" -> 58
        "violin" -> 40; "viola" -> 41; "cello" -> 42; "double-bass", "string-bass" -> 43
        "bass-guitar", "electric-bass" -> 33; "guitar" -> 25
        "voice", "soprano", "alto", "tenor", "bass" -> 52
        else -> 0
    }

    /**
     * [score] as MIDI bytes at [bpm] quarter notes a minute; [transpose] semitones the written note
     * is above the sounding one (2 for a B-flat trumpet).
     */
    fun write(score: Score, bpm: Double, transpose: Int = 0, program: Int = 0, name: String = "Part"): ByteArray {
        val events = ArrayList<Triple<Long, Int, ByteArray>>()   // tick, order, bytes
        fun meta(tick: Long, type: Int, data: ByteArray) = events.add(Triple(tick, 0, byteArrayOf(0xFF.toByte(), type.toByte()) + varLen(data.size) + data))
        meta(0, 0x03, name.toByteArray(Charsets.UTF_8))
        val micros = (60_000_000.0 / bpm).toInt()
        meta(0, 0x51, byteArrayOf((micros shr 16).toByte(), (micros shr 8).toByte(), micros.toByte()))
        events += Triple(0L, 1, byteArrayOf(0xC0.toByte(), program.coerceIn(0, 127).toByte()))
        var at = 0L
        var lastTime: TimeSig? = null
        // As played: repeats twice, the right ending each time.
        for (m in PlayOrder.unrolled(score).measures) {
            if (m.time != lastTime) {
                lastTime = m.time
                val denomPow = Integer.numberOfTrailingZeros(m.time.beatType)
                meta(at, 0x58, byteArrayOf(m.time.beats.toByte(), denomPow.toByte(), 24, 8))
            }
            val bar = (m.time.quarters * TICKS).toLong()
            if (m.bars > 1 || m.events.all { it is Rest }) { at += bar * m.bars; continue }
            var t = 0L
            for (e in m.events) {
                val len = (e.duration.quarters * TICKS).toLong()
                if (t >= bar) break
                if (e is Note) {
                    val end = minOf(t + len, bar)
                    for (p in e.pitches) {
                        val key = (p.midi - transpose).coerceIn(0, 127)
                        events += Triple(at + t, 3, byteArrayOf(0x90.toByte(), key.toByte(), 88))
                        // A hair short of full length, so repeated notes are heard apart.
                        events += Triple(at + maxOf(t + 1, end - 8), 2, byteArrayOf(0x80.toByte(), key.toByte(), 0))
                    }
                }
                t += len
            }
            at += bar
        }
        meta(at, 0x2F, ByteArray(0))
        events.sortWith(compareBy({ it.first }, { it.second }))
        val track = ByteArrayOutputStream()
        var last = 0L
        for ((tick, _, bytes) in events) {
            track.write(varLen((tick - last).toInt()))
            track.write(bytes)
            last = tick
        }
        val body = track.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("MThd".toByteArray()); out.write(int32(6)); out.write(int16(0)); out.write(int16(1)); out.write(int16(TICKS))
        out.write("MTrk".toByteArray()); out.write(int32(body.size)); out.write(body)
        return out.toByteArray()
    }

    private fun varLen(v: Int): ByteArray {
        var value = v
        val bytes = ArrayList<Byte>()
        bytes += (value and 0x7F).toByte()
        value = value shr 7
        while (value > 0) { bytes.add(0, ((value and 0x7F) or 0x80).toByte()); value = value shr 7 }
        return bytes.toByteArray()
    }

    private fun int32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun int16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
}
