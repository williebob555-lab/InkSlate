package com.inksheets.desktop

import org.junit.Assume.assumeTrue
import org.junit.Test
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine
import kotlin.math.sqrt

/**
 * What this machine's microphone gives Java Sound, as Listen opens it: every input device, then
 * four seconds from the default one, its loudness each half second. Run by hand:
 * -Dinksheets.omr=mic.
 */
class MicCheck {
    /** The app's own microphone: from the system's input, which here gives silence, to one that hears. */
    @Test
    fun `the app's microphone passes over a silent input`() {
        assumeTrue(System.getProperty("inksheets.omr") == "mic")
        val mic = DesktopSheetsPlatform {}.microphone
        println("inputs: ${mic.devices}")
        var peak = 0f; var chunks = 0
        val t0 = System.currentTimeMillis()
        val log = ArrayList<String>()
        check(mic.start { c -> chunks++; for (v in c) peak = maxOf(peak, kotlin.math.abs(v)) })
        var last = mic.inUse
        log += "0.0 s: ${mic.inUse}"
        while (System.currentTimeMillis() - t0 < 6_000) {
            Thread.sleep(500)
            if (mic.inUse != last) { last = mic.inUse; log += "${(System.currentTimeMillis() - t0) / 1000.0} s: now ${mic.inUse}" }
            log += "${(System.currentTimeMillis() - t0) / 1000.0} s: peak ${(peak * 32768).toInt()} ($chunks chunks)"
            peak = 0f
        }
        mic.stop()
        log.forEach(::println)
    }

    @Test
    fun `the microphone hears`() {
        assumeTrue(System.getProperty("inksheets.omr") == "mic")
        for (info in AudioSystem.getMixerInfo()) {
            val mixer = AudioSystem.getMixer(info)
            if (mixer.targetLineInfo.any { it.lineClass == TargetDataLine::class.java }) println("input: ${info.name} - ${info.description}")
        }
        val format = AudioFormat(48_000f, 16, 1, true, false)
        for (info in AudioSystem.getMixerInfo()) {
            val mixer = AudioSystem.getMixer(info)
            if (mixer.targetLineInfo.none { it.lineClass == TargetDataLine::class.java }) continue
            val l = runCatching { AudioSystem.getTargetDataLine(format, info).apply { open(format); start() } }.getOrElse { println("${info.name}: cannot open - ${it.message}"); null } ?: continue
            val b = ByteArray(96_000)
            var got = 0
            while (got < b.size) { val n = l.read(b, got, b.size - got); if (n > 0) got += n }
            var peak = 0
            for (i in 0 until got / 2) peak = maxOf(peak, kotlin.math.abs(((b[2 * i].toInt() and 0xFF) or (b[2 * i + 1].toInt() shl 8)).toShort().toInt()))
            println("${info.name}: peak $peak in 1 s")
            l.stop(); l.close()
        }
        val line = AudioSystem.getTargetDataLine(format)
        line.open(format)
        line.start()
        val bytes = ByteArray(48_000)        // half a second
        repeat(8) {
            var got = 0
            while (got < bytes.size) { val n = line.read(bytes, got, bytes.size - got); if (n > 0) got += n }
            var sum = 0.0; var peak = 0
            for (i in 0 until got / 2) {
                val v = ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort().toInt()
                sum += v.toDouble() * v; peak = maxOf(peak, kotlin.math.abs(v))
            }
            println("0.5 s: rms ${"%.1f".format(sqrt(sum / (got / 2)))}, peak $peak")
        }
        line.stop(); line.close()
    }
}
