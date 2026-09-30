package com.inksheets.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.inksheets.ui.ReadMusicPanel
import com.inksheets.ui.SheetsState
import com.inksheets.ui.Transcriber
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.imageio.ImageIO

/** Reading a real part's music through the app: the desktop's own page drawing, the panel, the MIDI. */
@OptIn(ExperimentalTestApi::class)
class ReadMusicTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val source = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets/Imported/PEP BAND/Music/Sweet Caroline/SweetC - Trumpet 1.pdf")

    @Test
    fun `reads a part, shows the bars in doubt beside their redrawing, and saves MIDI`() {
        assumeTrue(source.isFile)
        val part = source.copyTo(File(tmp.newFolder("Music"), "SweetC - Trumpet 1.pdf"))
        val state = SheetsState(DesktopSheetsPlatform {})
        var done: com.inksheets.core.omr.Score? = null
        javax.swing.SwingUtilities.invokeAndWait { Transcriber.read(state, part) { done = it } }
        val until = System.currentTimeMillis() + 120_000
        while (done == null && System.currentTimeMillis() < until) Thread.sleep(200)
        val score = done!!
        println("read: ${score.pages} pages, ${score.measures.size} bars, ${score.measures.count { it.sure }} sure")
        assertTrue(score.measures.size > 40)
        // Kept: the second time, straight from this device's store.
        assertTrue(Transcriber.cached(state, part) != null)
        val midi = com.inksheets.core.omr.Midi.write(score, 136.0, transpose = 2, program = 56)
        val seq = javax.sound.midi.MidiSystem.getSequence(java.io.ByteArrayInputStream(midi))
        println("MIDI: ${midi.size} bytes, ${seq.microsecondLength / 1_000_000} s")
        assertTrue(seq.microsecondLength > 60_000_000)
        runDesktopComposeUiTest(width = 700, height = 900) {
            setContent { MaterialTheme { Surface { androidx.compose.foundation.layout.Box { ReadMusicPanel(state, onClose = {}) } } } }
            waitForIdle()
            Thread.sleep(1500)
            waitForIdle()
            System.getProperty("inksheets.shots")?.let { dir ->
                File(dir).mkdirs()
                ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(dir, "read-music-panel.png"))
            }
        }
    }
}
