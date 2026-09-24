package com.inksheets.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.ui.AudioOut
import com.inksheets.ui.Microphone
import com.inksheets.ui.SheetsHome
import com.inksheets.ui.SheetsPlatform
import com.inksheets.ui.SheetsState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.imageio.ImageIO

/**
 * The InkSheets screens, driven without a window and photographed.
 *
 * Pictures go to the folder named by -Dinksheets.shots, for a look at the layout without
 * installing anything; the assertions stand on their own.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class SheetsScreensTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val opened = ArrayList<String>()

    private inner class FakePlatform(root: File) : SheetsPlatform {
        private val prefs = HashMap<String, String?>().apply { put("sheets_library", root.absolutePath) }
        override val deviceId = "test"
        override val startFolder = root
        override fun pref(key: String) = prefs[key]
        override fun setPref(key: String, value: String?) { prefs[key] = value }
        override fun openPart(song: Song, part: Part, file: File) { opened += part.file }
        override fun pageText(file: File, page: Int): String? = null
        override val audioOut: AudioOut? = null
        override val microphone: Microphone? = null
    }

    private fun shoot(name: String, image: java.awt.image.BufferedImage) {
        val dir = System.getProperty("inksheets.shots") ?: return
        File(dir).mkdirs()
        ImageIO.write(image, "png", File(dir, "$name.png"))
    }

    @Test
    fun `the library shows the chosen instrument's songs and opens its part`() {
        val root = tmp.newFolder("Music")
        val platform = FakePlatform(root)
        val state = SheetsState(platform)
        state.change {
            addSong("The Liberty Bell", listOf(
                Part(file = "Band/Liberty Bell - Trombone.pdf", instrument = "trombone"),
                Part(file = "Band/Liberty Bell - Euphonium.pdf", instrument = "euphonium")
            )) { composers = listOf("John Philip Sousa"); tempo = 120; key = "Bb" }
            addSong("September", listOf(Part(file = "Jazz/September - Bass.pdf", instrument = "bass-guitar"))) {
                artists = listOf("Earth, Wind & Fire")
            }
            addSong("Unread scan", listOf(Part(file = "Scans/scan0042.pdf")))
            val ensemble = addFolder("Wind Ensemble")
            val year = addFolder("2025-26", ensemble.id)
            addSetlist("Spring Concert", year.id)
            addFolder("Jazz Band")
        }
        state.chooseProfile("baritone")

        runDesktopComposeUiTest(width = 1000, height = 700) {
            setContent {
                MaterialTheme { Surface { SheetsHome(state, onOpenSettings = {}) } }
            }
            waitForIdle()
            onNodeWithText("The Liberty Bell").assertExists()
            onNodeWithText("Unread scan").assertExists()          // unknown parts stay visible
            assertEquals(0, onAllNodesWithText("September").fetchSemanticsNodes().size)
            shoot("library-baritone", onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage())

            onNodeWithText("The Liberty Bell").performClick()
            assertEquals(listOf("Band/Liberty Bell - Euphonium.pdf"), opened)

            onNodeWithText("Setlists").performClick()
            waitForIdle()
            onNodeWithText("Wind Ensemble").performClick()
            waitForIdle()
            onNodeWithText("2025-26").performClick()
            waitForIdle()
            onNodeWithText("Spring Concert").assertExists()
            shoot("setlists-folder", onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage())
        }
    }

    @Test
    fun `the metronome and tuner open`() {
        val root = tmp.newFolder("Music")
        val state = SheetsState(FakePlatform(root))
        runDesktopComposeUiTest(width = 1000, height = 800) {
            setContent { MaterialTheme { Surface { SheetsHome(state, onOpenSettings = {}) } } }
            onNodeWithContentDescriptionSafe("Metronome")
            waitForIdle()
            onNodeWithText("beats per minute").assertExists()
            shoot("metronome", onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage())
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.onNodeWithContentDescriptionSafe(label: String) =
        onNode(androidx.compose.ui.test.hasContentDescription(label)).performClick()
}
