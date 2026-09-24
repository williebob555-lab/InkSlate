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

    override fun load(file: File): Boolean = runCatching {
        pause()
        AudioSystem.getAudioInputStream(file).use { raw ->
            val source = raw.format
            val pcm = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                source.sampleRate.takeIf { it > 0 } ?: 44_100f,
                16,
                source.channels.coerceAtLeast(1),
                source.channels.coerceAtLeast(1) * 2,
                source.sampleRate.takeIf { it > 0 } ?: 44_100f,
                false
            )
            AudioSystem.getAudioInputStream(pcm, raw).use { decoded ->
                val bytes = decoded.readAllBytes()
                val channels = pcm.channels
                val frames = bytes.size / (2 * channels)
                val mono = FloatArray(frames)
                for (f in 0 until frames) {
                    var sum = 0f
                    for (c in 0 until channels) {
                        val at = (f * channels + c) * 2
                        sum += ((bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() shl 8)).toShort() / 32768f
                    }
                    mono[f] = sum / channels
                }
                samples = mono
                rate = pcm.sampleRate.toInt()
            }
        }
        stretch = TimeStretch(samples, rate).also { it.speed = speed; it.pitch = pitch }
        true
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
                    for (i in block.indices) {
                        val v = (block[i].coerceIn(-1f, 1f) * 32767).toInt()
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
        stretch?.seek(ms * rate / 1000.0)
    }

    override val positionMs: Long get() = ((stretch?.position ?: 0.0) * 1000 / rate).toLong()
    override val durationMs: Long get() = samples.size * 1000L / rate

    override fun setLoop(startMs: Long?, endMs: Long?) {
        loop = if (startMs != null && endMs != null && endMs > startMs) startMs to endMs else null
    }

    override fun release() {
        pause()
        samples = FloatArray(0)
        stretch = null
    }
}
