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
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.core.omr.Box as OmrBox
import com.inksheets.core.omr.Clef
import com.inksheets.core.omr.Duration
import com.inksheets.core.omr.Key
import com.inksheets.core.omr.Measure
import com.inksheets.core.omr.Rest
import com.inksheets.core.omr.Score
import com.inksheets.core.omr.TimeSig
import com.inksheets.ui.ActionStrip
import com.inksheets.ui.AudioOut
import com.inksheets.ui.Microphone
import com.inksheets.ui.ScoreTools
import com.inksheets.ui.SheetsPlatform
import com.inksheets.ui.SheetsState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.imageio.ImageIO

/** Direct score actions and the full directory remain reachable on laptop and phone. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class MusicStripShots {
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
    fun `the dock and score directory keep the original score capabilities reachable`() {
        val root = tmp.newFolder("Music")
        File(root, "Band").mkdirs()
        File(root, "Band/Tune - Trumpet.pdf").writeText("x")
        val state = SheetsState(Stand(root))
        state.readMusic = true
        state.listenTurns = true
        state.stripCollapsed = false
        state.change { editSong(ensureSong("Tune").id) { parts = listOf(Part(id = com.inksheets.core.Library.partIdFor("Band/Tune - Trumpet.pdf"), file = "Band/Tune - Trumpet.pdf")) } }
        state.current = state.library!!.songs.first()
        val path = File(root, "Band/Tune - Trumpet.pdf").absolutePath
        state.currentPath = path
        state.homeInFront = false
        val bars = (1..16).map { Measure(it, 0, 0, OmrBox(0, 0, 100, 40), 10f, Clef.TREBLE, Key(0), TimeSig(4, 4), listOf(Rest(Duration(1), 5f))) }
        ScoreTools.scoreSource = { if (it == path) Score(bars, 1, listOf(1000)) else null }
        val shots = System.getProperty("inksheets.shots")
        try {
            for ((w, h, name) in listOf(Triple(1100, 700, "laptop"), Triple(390, 800, "phone"))) {
                runDesktopComposeUiTest(width = w, height = h) {
                    setContent { MaterialTheme { Box(Modifier.fillMaxSize().background(Color(0xFFF4F1EA))) { ActionStrip(state) } } }
                    waitForIdle()
                    runOnIdle { ScoreTools.open = true; ScoreTools.select(3..5) }
                    waitForIdle()
                    for (d in listOf("Play notes", "Play other parts", "Go to bar", "Review recognized notes")) {
                        val target = onNode(hasContentDescription(d))
                        target.performScrollTo().assertIsDisplayed().assertIsEnabled()
                        val node = target.fetchSemanticsNode()
                        val b = node.boundsInRoot
                        assertTrue("$name: $d on screen", b.top >= 0f && b.bottom <= h.toFloat() && b.left >= 0f && b.right <= w.toFloat())
                    }
                    onAllNodes(hasContentDescription("All score tools")).onLast().performScrollTo().performClick()
                    onNodeWithText("Score tools").assertIsDisplayed()
                    onNodeWithText("Done").assertIsDisplayed()
                    for (tool in listOf("Select bars", "Tempo ramp", "Playback sound", "Cue notes", "Save MIDI", "Clean PDF", "PDF for another instrument")) {
                        onNodeWithTag("score-action-$tool").performScrollTo().assertIsDisplayed()
                    }
                    if (shots != null) {
                        File(shots).mkdirs()
                        ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(shots, "music-strip-$name.png"))
                    }
                    onNodeWithText("Done").performClick()
                    runOnIdle { ScoreTools.close(state) }
                }
            }
        } finally {
            ScoreTools.scoreSource = null
        }
    }
}
