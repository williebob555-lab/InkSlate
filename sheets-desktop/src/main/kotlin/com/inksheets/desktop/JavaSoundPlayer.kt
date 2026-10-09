package com.inksheets.desktop

import com.inkslate.desktop.EventLog
import com.inksheets.core.TimeStretch
import com.inksheets.ui.AudioPlayer
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/**
 * Recordings on the desktop: the file decoded once into memory (mono, which is all practice
 * needs), then played through [TimeStretch] so the speed and pitch can change while it plays.
 * WAV and AIFF are the runtime's own; MP3 and Ogg come from the decoders on the classpath.
 */
class JavaSoundPlayer : AudioPlayer {

    @Volatile private var samples = FloatArray(0)
    @Volatile private var rate = 44_100
    @Volatile private var stretch: TimeStretch? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var loop: Pair<Long, Long>? = null

    @Volatile
    override var playing: Boolean = false
        private set

    override var speed: Double = 1.0
        set(v) { field = v; stretch?.speed = v }

    override var pitch: Int = 0
        set(v) { field = v; stretch?.pitch = v }

    @Volatile
    override var volume: Double = 1.0
        set(v) { field = v.coerceIn(0.0, 1.0) }

    /** Counts loads, so a decode still going for a file no longer wanted stops. */
    @Volatile private var loadId = 0

    /** While the rest of a file is still being decoded, how long it will be (an estimate); else 0. */
    @Volatile private var estimate = 0

    @Volatile private var filled = 0

    /**
     * The file decoded in blocks, straight to mono floats. The first couple of seconds are ready
     * when this returns - the rest is decoded behind the music, many times faster than it plays -
     * so a long recording starts at once and never holds the whole decoded file twice over.
     */
    override fun load(file: File): Boolean = runCatching {
        pause()
        val id = ++loadId
        stretch = null
        samples = FloatArray(0)
        filled = 0
        estimate = 0
        val raw = AudioFiles.open(file)
        var keep = false
        try {
            val source = raw.format
            val rate = source.sampleRate.takeIf { it > 0 } ?: 44_100f
            val channels = source.channels.coerceAtLeast(1)
            val pcm = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16, channels, channels * 2, rate, false)
            val decoded = AudioSystem.getAudioInputStream(pcm, raw)
            val known = listOf(decoded.frameLength, raw.frameLength).firstOrNull { it > 0 }
                ?: runCatching { (AudioSystem.getAudioFileFormat(file).properties()["duration"] as? Long)?.let { (it * rate / 1_000_000.0).toLong() } }.getOrNull()
                ?: 0L
            var buf = FloatArray(if (known > 0) (known + known / 20 + rate.toInt()).coerceAtMost(Int.MAX_VALUE / 2L).toInt() else rate.toInt() * 60)
            val bytes = ByteArray(16_384 * 2 * channels)
            var at = 0
            var done = false

            fun decode(until: Int): Boolean {
                while (at < until) {
                    if (id != loadId) return false
                    val n = decoded.read(bytes, 0, bytes.size)
                    if (n <= 0) { done = true; return true }
                    val frames = n / (2 * channels)
                    if (at + frames > buf.size) buf = buf.copyOf(maxOf(buf.size * 3 / 2, at + frames))
                    for (f in 0 until frames) {
                        var sum = 0f
                        for (c in 0 until channels) {
                            val i = (f * channels + c) * 2
                            sum += ((bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)).toShort() / 32768f
                        }
                        buf[at + f] = sum / channels
                    }
                    at += frames
                }
                return true
            }

            if (!decode((rate * 2).toInt())) return@runCatching false
            this.rate = rate.toInt()
            samples = buf
            filled = at
            val ts = TimeStretch(buf, rate.toInt().coerceAtLeast(1)).also { it.speed = speed; it.pitch = pitch }
            ts.extend(buf, at)
            stretch = ts
            if (done) return@runCatching true
            estimate = maxOf(known.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), at)
            keep = true
            Thread({
                try {
                    while (!done && id == loadId) {
                        if (!decode(at + rate.toInt() / 2)) break
                        samples = buf
                        filled = at
                        ts.extend(buf, at)
                    }
                } catch (e: Throwable) {
                    EventLog.warn("sheets", "Could not finish loading ${file.name}: ${e.message}")
                } finally {
                    if (id == loadId) estimate = 0
                    runCatching { decoded.close(); raw.close() }
                }
            }, "recording-decode").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1; start() }
            true
        } finally {
            if (!keep) runCatching { raw.close() }
        }
    }.onFailure { EventLog.warn("sheets", "Could not load ${file.name}: ${it.message}") }.getOrDefault(false)

    override fun play() {
        val ts = stretch ?: return
        if (playing) return
        if (ts.atEnd) ts.seek(0.0)
        playing = true
        thread = Thread({
            runCatching {
                val format = AudioFormat(rate.toFloat(), 16, 1, true, false)
                val line = AudioSystem.getSourceDataLine(format)
                line.open(format, rate / 10 * 2)
                line.start()
                val block = FloatArray(1024)
                val bytes = ByteArray(block.size * 2)
                while (playing) {
                    loop?.let { (a, b) ->
                        if (ts.position >= b * rate / 1000.0) ts.seek(a * rate / 1000.0)
                    }
                    if (ts.atEnd) { playing = false; break }
                    ts.read(block)
                    val gain = volume.toFloat()
                    for (i in block.indices) {
                        val v = ((block[i] * gain).coerceIn(-1f, 1f) * 32767).toInt()
                        bytes[2 * i] = v.toByte()
                        bytes[2 * i + 1] = (v shr 8).toByte()
                    }
                    line.write(bytes, 0, bytes.size)
                }
                line.stop()
                line.close()
            }.onFailure { EventLog.warn("sheets", "Playback failed: ${it.message}"); playing = false }
        }, "recording").apply { isDaemon = true; start() }
    }

    override fun pause() {
        playing = false
        thread?.join(300)
        thread = null
    }

    override fun seek(ms: Long) {
        // Further on than has been decoded yet (only in the first moments of a long file): the
        // decoding is many times faster than playing, so a short wait gets there.
        var waited = 0
        while (estimate > 0 && ms * rate / 1000 > filled && waited < 800) { Thread.sleep(20); waited += 20 }
        stretch?.seek(ms * rate / 1000.0)
    }

    override val positionMs: Long get() = ((stretch?.position ?: 0.0) * 1000 / rate).toLong()
    override val durationMs: Long get() = (if (estimate > 0) estimate else filled) * 1000L / rate

    override fun setLoop(startMs: Long?, endMs: Long?) {
        loop = if (startMs != null && endMs != null && endMs > startMs) startMs to endMs else null
    }

    override fun release() {
        pause()
        loadId++
        samples = FloatArray(0)
        filled = 0
        estimate = 0
        stretch = null
    }
}
