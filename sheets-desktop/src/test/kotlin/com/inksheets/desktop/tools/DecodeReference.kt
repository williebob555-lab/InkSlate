package com.inksheets.desktop.tools

import com.inksheets.desktop.AudioFiles
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.AudioFileFormat

/** A reference recording decoded to a mono 16-bit WAV for measuring (-Dinksheets.ref=<mp3> -Dinksheets.refOut=<wav>). */
class DecodeReference {
    @Test
    fun decode() {
        val src = System.getProperty("inksheets.ref")?.let(::File)
        assumeTrue(src != null && src.isFile)
        val out = File(System.getProperty("inksheets.refOut") ?: "build/ref.wav")
        out.parentFile?.mkdirs()
        AudioFiles.open(src!!).use { raw ->
            val pcm = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, raw.format.sampleRate, 16, raw.format.channels, raw.format.channels * 2, raw.format.sampleRate, false)
            val decoded = AudioSystem.getAudioInputStream(pcm, raw)
            val mono = AudioFormat(pcm.sampleRate, 16, 1, true, false)
            val bytes = decoded.readAllBytes()
            val ch = pcm.channels
            val frames = bytes.size / (2 * ch)
            val outBytes = ByteArray(frames * 2)
            for (i in 0 until frames) {
                var sum = 0
                for (c in 0 until ch) { val k = (i * ch + c) * 2; sum += (bytes[k].toInt() and 0xff) or (bytes[k + 1].toInt() shl 8) }
                val v = sum / ch
                outBytes[2 * i] = v.toByte(); outBytes[2 * i + 1] = (v shr 8).toByte()
            }
            AudioSystem.write(AudioInputStream(outBytes.inputStream(), mono, frames.toLong()), AudioFileFormat.Type.WAVE, out)
            println("decoded ${src.name}: ${frames / pcm.sampleRate} s at ${pcm.sampleRate} Hz -> $out")
        }
    }
}
