package com.inksheets.core.watch

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * What the watch and the phone say to each other, over the watch's own link to the phone (the
 * Wear OS data layer): paths, and the packing of a calibration's readings.
 */
object WatchWire {
    private const val ROOT = "/inksheets/watch"
    /** Phone → watch: who are you, what are you doing. Answered with [HELLO]. */
    const val PING = "$ROOT/ping"
    /** Watch → phone: [Hello] as text. Sent when asked, and whenever the watch starts or stops listening. */
    const val HELLO = "$ROOT/hello"
    /** Phone → watch: the [FlickModel] to listen with (empty: forget it). */
    const val MODEL = "$ROOT/model"
    /** Phone → watch: "start" sending readings for a calibration, or "stop". */
    const val CALIBRATE = "$ROOT/calibrate"
    /** Phone → watch: a calibration's prompt - "play", "next", "back" or "done"; "next"/"back" buzz and are noted in the readings. */
    const val CUE = "$ROOT/cue"
    /** Watch → phone: a batch of readings, and the cues given among them ([packBatch]). */
    const val SAMPLES = "$ROOT/samples"
    /** Watch → phone: a flick - "next:<seq>" or "back:<seq>". */
    const val FLICK = "$ROOT/flick"
    /** Phone → watch: "<seq>:ok", or "<seq>:<why not>" - the watch buzzes long when a turn did not happen. */
    const val TURNED = "$ROOT/turned"
    /**
     * Phone → watch: listen ("on:<session>", sent again each minute while the phone is in use) or
     * stop ("off"). A session the player stopped by hand on the watch is not started again; a new
     * one (the phone connecting to a tablet again) is.
     */
    const val LISTEN = "$ROOT/listen"
    /** The watch stops listening after this long without a word from the phone. */
    const val QUIET_MS = 60_000L

    /** The watch's side of things, as it says it. */
    data class Hello(val listening: Boolean, val model: String?, val version: String, val calibrating: Boolean = false) {
        fun encode(): String = listOf(if (listening) "1" else "0", model.orEmpty().replace('|', '/'), version, if (calibrating) "1" else "0").joinToString("|")

        companion object {
            fun decode(text: String): Hello? {
                val p = text.split('|')
                if (p.size < 3) return null
                return Hello(p[0] == "1", p[1].ifEmpty { null }, p[2], p.getOrNull(3) == "1")
            }
        }
    }

    /** Readings and cues as bytes: a count of each, then each reading (time, gyroscope, accelerometer), then each cue. */
    fun packBatch(samples: List<Sample>, cues: List<Cue>): ByteArray {
        val out = ByteArrayOutputStream(8 + samples.size * 32 + cues.size * 9)
        DataOutputStream(out).use { d ->
            d.writeInt(samples.size)
            d.writeInt(cues.size)
            for (s in samples) {
                d.writeLong(s.t)
                d.writeFloat(s.gx); d.writeFloat(s.gy); d.writeFloat(s.gz)
                d.writeFloat(s.ax); d.writeFloat(s.ay); d.writeFloat(s.az)
            }
            for (c in cues) { d.writeLong(c.t); d.writeBoolean(c.next) }
        }
        return out.toByteArray()
    }

    fun unpackBatch(bytes: ByteArray): Pair<List<Sample>, List<Cue>>? = runCatching {
        DataInputStream(ByteArrayInputStream(bytes)).use { d ->
            val n = d.readInt()
            val m = d.readInt()
            require(n in 0..100_000 && m in 0..1000)
            val samples = List(n) { Sample(d.readLong(), d.readFloat(), d.readFloat(), d.readFloat(), d.readFloat(), d.readFloat(), d.readFloat()) }
            val cues = List(m) { Cue(d.readLong(), d.readBoolean()) }
            samples to cues
        }
    }.getOrNull()

    /** A calibration as text, kept to learn from again: a line a reading ("s,t,gx,gy,gz,ax,ay,az"), a line a cue ("c,t,next"). */
    fun writeSession(s: Session): String = buildString {
        append("# InkSheets watch calibration: s = reading (ms, gyroscope rad/s, accelerometer m/s2), c = cue\n")
        for (x in s.samples) append("s,").append(x.t).append(',').append(x.gx).append(',').append(x.gy).append(',').append(x.gz)
            .append(',').append(x.ax).append(',').append(x.ay).append(',').append(x.az).append('\n')
        for (c in s.cues) append("c,").append(c.t).append(',').append(if (c.next) "next" else "back").append('\n')
    }

    fun readSession(text: String): Session {
        val samples = ArrayList<Sample>()
        val cues = ArrayList<Cue>()
        for (line in text.lineSequence()) {
            val p = line.split(',')
            when (p.firstOrNull()) {
                "s" -> if (p.size >= 5) runCatching {
                    samples += Sample(p[1].toLong(), p[2].toFloat(), p[3].toFloat(), p[4].toFloat(),
                        p.getOrNull(5)?.toFloat() ?: 0f, p.getOrNull(6)?.toFloat() ?: 0f, p.getOrNull(7)?.toFloat() ?: 0f)
                }
                "c" -> if (p.size >= 3) runCatching { cues += Cue(p[1].toLong(), p[2] == "next") }
            }
        }
        return Session(samples.sortedBy { it.t }, cues.sortedBy { it.t })
    }
}
