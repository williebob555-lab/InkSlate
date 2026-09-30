package com.inksheets.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.ui.ActionStrip
import com.inksheets.ui.AudioOut
import com.inksheets.ui.Ears
import com.inksheets.ui.Listener
import com.inksheets.ui.Microphone
import com.inksheets.ui.SheetsPlatform
import com.inksheets.ui.SheetsState
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.imageio.ImageIO

/**
 * Listen's gauge in the strip, in each of its states, on a laptop's screen and a phone's:
 * pictures for a look (-Dinksheets.shots), and the words checked.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class ListenGaugeShots {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Stand(root: File) : SheetsPlatform {
        private val prefs = HashMap<String, String?>().apply { put("sheets_library", root.absolutePath) }
        override val deviceId = "test"
        override val startFolder = root
        override fun pref(key: String) = prefs[key]
        override fun setPref(key: String, value: String?) { prefs[key] = value }
        override fun openPart(song: Song, part: Part, file: File) {}
        override fun pageText(file: File, page: Int): String? = null
        override val audioOut: AudioOut? = null
        override val microphone: Microphone? = null
        override fun onMain(block: () -> Unit) = block()
        override val deviceName = "Stand"
    }

    @Test
    fun `the gauge says what Listen is doing`() {
        val shots = System.getProperty("inksheets.shots")
        val state = SheetsState(Stand(tmp.newFolder("Music")))
        state.listenTurns = true
        val now = System.currentTimeMillis()
        val cases = listOf<Triple<String, () -> Unit, String>>(
            Triple("deaf", { Ears.deaf = true; Ears.device = "Microphone (USB Audio Device)"; Ears.level = 0f; Listener.follow = Listener.Follow(0, 0, 45_000, false, "learned", 0, 4) }, "Hears nothing yet"),
            Triple("waiting", { Ears.deaf = false; Ears.level = 0.2f; Listener.follow = Listener.Follow(0, 0, 45_000, false, "learned", 0, 4) }, "Waiting for the music"),
            Triple("following", { Ears.level = 0.75f; Listener.follow = Listener.Follow(33_000, 0, 45_000, true, "learned", 0, 4) }, "Turn in 12s"),
            Triple("turning", { Ears.level = 0.8f; Listener.follow = Listener.Follow(44_600, 0, 45_000, true, "learned", 0, 4) }, "Turning..."),
            Triple("turned", { Ears.level = 0.7f; Listener.follow = Listener.Follow(46_000, 45_000, 90_000, true, "learned", 1, 4, turnedAt = now + 60_000) }, "Turned to page 2"),
            Triple("guessed", { Ears.level = 0.6f; Listener.follow = Listener.Follow(20_000, 0, 45_000, true, "guessed", 0, 4) }, "(guessed)")
        )
        try {
            for ((w, h, name) in listOf(Triple(900, 640, "laptop"), Triple(390, 800, "phone"))) {
                val strips = ArrayList<java.awt.image.BufferedImage>()
                runDesktopComposeUiTest(width = w, height = h) {
                    Listener.active = true
                    setContent {
                        MaterialTheme {
                            Box(Modifier.fillMaxSize().background(Color(0xFFF4F1EA))) { ActionStrip(state) }
                        }
                    }
                    for ((label, set, words) in cases) {
                        runOnIdle { set() }
                        waitForIdle()
                        val said = Listener.summary(state).orEmpty()
                        assertTrue("$label: said \"$said\", wanted \"$words\"", said.contains(words))
                        strips += onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage()
                    }
                }
                if (shots != null) {
                    File(shots).mkdirs()
                    strips.forEachIndexed { i, img -> ImageIO.write(img, "png", File(shots, "listen-$name-${cases[i].first}.png")) }
                }
            }
        } finally {
            Listener.active = false
            Listener.follow = null
            Ears.deaf = false
            Ears.level = 0f
        }
    }
}
