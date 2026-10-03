package com.inksheets.desktop

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Recordings in the formats players have them in load on the desktop: an .m4a from a phone among them. */
class AudioFormatsTest {
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")

    @Test
    fun `an m4a recording loads`() {
        val file = music.walkTopDown().firstOrNull { it.extension.equals("m4a", true) }
        assumeTrue("no .m4a here", file != null)
        val player = JavaSoundPlayer()
        assertTrue("${file!!.name} did not load", player.load(file))
        println("LOADED ${file.name}: ${player.durationMs / 1000} s")
    }
}
