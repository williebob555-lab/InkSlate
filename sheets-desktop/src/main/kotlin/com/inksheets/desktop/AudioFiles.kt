package com.inksheets.desktop

import java.io.File
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

/** Audio files opened through Java Sound - .wav, .mp3, .ogg, and AAC (.m4a, a phone's recordings). */
object AudioFiles {
    /** [file] as a stream of its own format: an AAC one by the AAC reader, any other by Java Sound. */
    fun open(file: File): AudioInputStream =
        if (file.extension.lowercase() in setOf("m4a", "aac", "mp4", "m4b")) net.sourceforge.jaad.spi.javasound.AACAudioFileReader().getAudioInputStream(file)
        else AudioSystem.getAudioInputStream(file)
}
