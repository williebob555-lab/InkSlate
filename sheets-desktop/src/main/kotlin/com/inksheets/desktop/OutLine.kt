package com.inksheets.desktop

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * A sound card line - or, under tests (`-Dinksheets.silent=1`, set for every test run), one
 * that plays nothing at the same pace, so a test never sounds through the machine's speakers.
 */
internal class OutLine(private val format: AudioFormat, bufferBytes: Int) {
    private val line: SourceDataLine? = if (silent) null else AudioSystem.getSourceDataLine(format).apply { open(format, bufferBytes); start() }
    private val bytesPerSecond = format.frameRate * format.frameSize
    private var owed = 0.0

    fun write(bytes: ByteArray, length: Int) {
        val l = line
        if (l != null) { l.write(bytes, 0, length); return }
        // As long as the card would take to play it.
        owed += length / bytesPerSecond * 1000.0
        if (owed >= 5.0) { val ms = owed.toLong(); owed -= ms; Thread.sleep(ms) }
    }

    fun close() { line?.let { it.stop(); it.close() } }

    companion object {
        val silent: Boolean get() = System.getProperty("inksheets.silent") == "1"
    }
}
