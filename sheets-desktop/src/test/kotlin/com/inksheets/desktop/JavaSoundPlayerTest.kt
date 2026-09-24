package com.inksheets.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.sin

class JavaSoundPlayerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a stereo recording loads, knows its length and seeks`() {
        val rate = 22_050
        val seconds = 3
        val bytes = ByteArray(rate * seconds * 4)
        for (i in 0 until rate * seconds) {
            val v = (sin(2 * PI * 440 * i / rate) * 12000).toInt()
            for (c in 0 until 2) {
                bytes[i * 4 + c * 2] = v.toByte()
                bytes[i * 4 + c * 2 + 1] = (v shr 8).toByte()
            }
        }
        val format = AudioFormat(rate.toFloat(), 16, 2, true, false)
        val wav = tmp.newFile("take.wav")
        AudioSystem.write(AudioInputStream(ByteArrayInputStream(bytes), format, (rate * seconds).toLong()), AudioFileFormat.Type.WAVE, wav)

        val player = JavaSoundPlayer()
        assertTrue(player.load(wav))
        assertEquals(3000L, player.durationMs)
        player.seek(1500)
        assertEquals(1500.0, player.positionMs.toDouble(), 5.0)
        player.release()
    }
}
