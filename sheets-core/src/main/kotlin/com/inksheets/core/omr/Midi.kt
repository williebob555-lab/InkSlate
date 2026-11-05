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
        "glockenspiel", "orchestra-bells", "crotales" -> 9
        "vibraphone" -> 11; "marimba" -> 12; "xylophone" -> 13
        "chimes", "tubular-bells" -> 14; "timpani" -> 47
        else -> 0
    }

    /**
     * [score] as MIDI bytes at [bpm] quarter notes a minute; [transpose] semitones the written note
     * is above the sounding one (2 for a B-flat trumpet).
     */
    fun write(score: Score, bpm: Double, transpose: Int = 0, program: Int = 0, name: String = "Part"): ByteArray {
        require(bpm.isFinite() && bpm > 0.0) { "Tempo must be finite and positive" }
        val events = ArrayList<Triple<Long, Int, ByteArray>>()   // tick, order, bytes
        fun meta(tick: Long, type: Int, data: ByteArray) = events.add(Triple(tick, if (type == 0x2F) 4 else 0, byteArrayOf(0xFF.toByte(), type.toByte()) + varLen(data.size) + data))
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

    /** Settings for one playable part in a type-1 file. Its staves share one named track;
     * percussion uses MIDI channel 10.
     */
    data class Part(
        val staves: List<Score>,
        val name: String = "Part",
        val transpose: Int = 0,
        val program: Int = 0,
        val percussion: Boolean = false,
        val drums: DrumKind? = null,
    ) {
        constructor(score: Score, name: String = "Part", transpose: Int = 0, program: Int = 0,
                    percussion: Boolean = false) : this(listOf(score), name, transpose, program, percussion)
        init { require(staves.isNotEmpty()) { "A MIDI part must have at least one staff" } }
        val score: Score get() = staves.first()
    }

    /** A semantic document as one conductor track plus one track per selected playable part. */
    fun write(document: ScoreDocument, bpm: Double,
              selection: ScoreSelection = document.defaultSelection()): ByteArray = write(
        document.selectedParts(selection).map { part ->
            val instrument = part.instrument?.substringBefore(':')
            val named = part.label ?: com.inksheets.core.Instruments.byId[instrument]?.name
                ?.let { if (part.chair != null) "$it ${part.chair}" else it } ?: part.id
            val pitched = DrumKind.pitchedInstrument(instrument, named)
            val drums = DrumKind.of(instrument, named)
            Part(
                staves = part.staves,
                name = named,
                transpose = part.transposition,
                program = program(pitched ?: instrument),
                percussion = drums != null || (pitched == null && instrument in setOf("percussion", "drumline", "drums")),
                drums = drums
            )
        }, bpm
    )

    /**
     * Write a type-1 file with a shared conductor track. The first part supplies the time
     * signatures; parts should describe the same performed bar and repeat structure. Melodic
     * parts get distinct channels, while percussion parts share the standard drum channel.
     */
    fun write(parts: List<Part>, bpm: Double): ByteArray {
        require(parts.isNotEmpty()) { "At least one part is required" }
        require(bpm.isFinite() && bpm > 0.0) { "Tempo must be finite and positive" }
        require(parts.filter { !it.percussion }.map { it.program }.distinct().size <= 15) {
            "MIDI supports at most 15 simultaneous melodic programs"
        }
        val conductor = ArrayList<Triple<Long, Int, ByteArray>>()
        fun meta(events: MutableList<Triple<Long, Int, ByteArray>>, tick: Long, type: Int, data: ByteArray) {
            events += Triple(tick, if (type == 0x2F) 4 else 0, byteArrayOf(0xFF.toByte(), type.toByte()) + varLen(data.size) + data)
        }
        meta(conductor, 0, 0x03, "Conductor".toByteArray(Charsets.UTF_8))
        val micros = (60_000_000.0 / bpm).toInt()
        meta(conductor, 0, 0x51, byteArrayOf((micros shr 16).toByte(), (micros shr 8).toByte(), micros.toByte()))
        var conductorAt = 0L
        var lastTime: TimeSig? = null
        for (m in PlayOrder.unrolled(parts.first().score).measures) {
            if (m.time != lastTime) {
                lastTime = m.time
                meta(conductor, conductorAt, 0x58, byteArrayOf(m.time.beats.toByte(), Integer.numberOfTrailingZeros(m.time.beatType).toByte(), 24, 8))
            }
            conductorAt += (m.time.quarters * TICKS).toLong() * m.bars
        }
        meta(conductor, conductorAt, 0x2F, ByteArray(0))
        val allTracks = arrayListOf(conductor)
        val channels = (0..15).filter { it != 9 }.iterator()
        val channelByProgram = LinkedHashMap<Int, Int>()
        for (part in parts) {
            val channel = if (part.percussion) 9 else channelByProgram.getOrPut(part.program) { channels.next() }
            val events = ArrayList<Triple<Long, Int, ByteArray>>()
            meta(events, 0, 0x03, part.name.toByteArray(Charsets.UTF_8))
            if (!part.percussion) events += Triple(0L, 1, byteArrayOf((0xC0 or channel).toByte(), part.program.coerceIn(0, 127).toByte()))
            var trackEnd = 0L
            for (staff in part.staves) {
                var at = 0L
                for (m in PlayOrder.unrolled(staff).measures) {
                    val bar = (m.time.quarters * TICKS).toLong()
                    if (m.bars > 1 || m.events.all { it is Rest }) { at += bar * m.bars; continue }
                    var t = 0L
                    for (e in m.events) {
                        val len = (e.duration.quarters * TICKS).toLong()
                        if (t >= bar) break
                        if (e is Note) {
                            val end = minOf(t + len, bar)
                            val keys = part.drums?.keys(e) ?: e.pitches.map { (it.midi - part.transpose).coerceIn(0, 127) }
                            for (key in keys) {
                                events += Triple(at + t, 3, byteArrayOf((0x90 or channel).toByte(), key.toByte(), 88))
                                events += Triple(at + maxOf(t + 1, end - 8), 2, byteArrayOf((0x80 or channel).toByte(), key.toByte(), 0))
                            }
                        }
                        t += len
                    }
                    at += bar
                }
                trackEnd = maxOf(trackEnd, at)
            }
            meta(events, trackEnd, 0x2F, ByteArray(0))
            allTracks += events
        }
        val out = ByteArrayOutputStream()
        out.write("MThd".toByteArray()); out.write(int32(6)); out.write(int16(1)); out.write(int16(allTracks.size)); out.write(int16(TICKS))
        for (events in allTracks) {
            events.sortWith(compareBy({ it.first }, { it.second }))
            val body = ByteArrayOutputStream()
            var last = 0L
            for ((tick, _, bytes) in events) {
                body.write(varLen((tick - last).toInt())); body.write(bytes); last = tick
            }
            val bytes = body.toByteArray()
            out.write("MTrk".toByteArray()); out.write(int32(bytes.size)); out.write(bytes)
        }
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
