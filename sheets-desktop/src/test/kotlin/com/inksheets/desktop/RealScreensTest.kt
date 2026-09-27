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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.inksheets.core.LibraryScan
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.ui.AudioOut
import com.inksheets.ui.Microphone
import com.inksheets.ui.SheetsHome
import com.inksheets.ui.SheetsPlatform
import com.inksheets.ui.SheetsState
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * A real library's screens photographed at a phone's, a small tablet's and a laptop's size, from a
 * copy of its records (files stood in for by empty ones):
 *
 *     ./gradlew :sheets-desktop:test --tests '*RealScreens*' -Dinksheets.lib=... -Dinksheets.shots=...
 *
 * For looking at, not asserting: text squeezed into a column a letter wide shows at once.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class RealScreensTest {

    private class Platform(root: File) : SheetsPlatform {
        private val prefs = HashMap<String, String?>().apply { put("sheets_library", root.absolutePath) }
        override val deviceId = "shots"
        override val startFolder = root
        override fun pref(key: String) = prefs[key]
        override fun setPref(key: String, value: String?) { prefs[key] = value }
        override fun openPart(song: Song, part: Part, file: File) {}
        override fun pageText(file: File, page: Int): String? = null
        override val audioOut: AudioOut? = null
        override val microphone: Microphone? = null
        override fun onMain(block: () -> Unit) = block()
        override val deviceName = "Shots"
        override val localFolder: File get() = File(startFolder.parentFile, "local").apply { mkdirs() }
    }

    @Test
    fun `a real library at three sizes`() {
        val lib = System.getProperty("inksheets.lib")?.let(::File)
        val shots = System.getProperty("inksheets.shots")?.let(::File)
        assumeTrue(lib != null && lib.isDirectory && shots != null)
        val work = File(System.getProperty("java.io.tmpdir"), "inksheets-shots/InkSheets").apply { parentFile.deleteRecursively(); mkdirs() }
        File(lib!!, ".inksheets/log").copyRecursively(File(work, ".inksheets/log"))
        LibraryScan.listMusic(lib).forEach { f -> File(work, f.path).apply { parentFile.mkdirs(); writeText(f.path) } }
        shots!!.mkdirs()

        for ((name, w, h) in listOf(Triple("phone", 360, 760), Triple("tablet", 700, 1000), Triple("laptop", 1280, 800))) {
            val state = SheetsState(Platform(work))
            runCatching { state.scanFolder() }
            state.chooseProfile("bass-guitar")
            runDesktopComposeUiTest(width = w, height = h) {
                setContent { MaterialTheme { Surface { SheetsHome(state, onOpenSettings = {}) } } }
                waitForIdle()
                fun shoot(what: String) {
                    onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { i ->
                        runCatching {
                            ImageIO.write(onAllNodes(isRoot())[i].captureToImage().toAwtImage(), "png", File(shots, "$name-$what-$i.png"))
                        }
                    }
                }
                shoot("home")
                onAllNodesWithText("parts", substring = true).onFirst().performClick()
                waitForIdle()
                shoot("home-parts")
                onAllNodesWithText("Setlists").onFirst().performClick()
                waitForIdle()
                shoot("setlists")
                runCatching {
                    onAllNodesWithText("PEP BAND", substring = true).onFirst().performClick()
                    waitForIdle()
                    shoot("pepband")
                }
            }
        }
    }

    @Test
    fun `the panels at a phone's size`() {
        val lib = System.getProperty("inksheets.lib")?.let(::File)
        val shots = System.getProperty("inksheets.shots")?.let(::File)
        assumeTrue(lib != null && lib.isDirectory && shots != null)
        val work = File(System.getProperty("java.io.tmpdir"), "inksheets-panels/InkSheets").apply { parentFile.deleteRecursively(); mkdirs() }
        File(lib!!, ".inksheets/log").copyRecursively(File(work, ".inksheets/log"))
        LibraryScan.listMusic(lib).forEach { f -> File(work, f.path).apply { parentFile.mkdirs(); writeText(f.path) } }
        shots!!.mkdirs()
        val state = SheetsState(Platform(work))
        state.chooseProfile("bass-guitar")
        val song = state.library!!.songs.first { it.title.startsWith("24") }
        state.current = song
        val panels: List<Pair<String, @androidx.compose.runtime.Composable () -> Unit>> = listOf(
            "together" to { com.inksheets.ui.CompanionDialog(state) {} },
            "recordings" to { com.inksheets.ui.AudioDialog(state, song) {} },
            "tuner" to { com.inksheets.ui.TunerDialog(state) {} },
            "metronome" to { com.inksheets.ui.MetronomeDialog(state) {} },
            "song" to { com.inksheets.ui.SongEditorDialog(state, song) {} },
            "settings" to { com.inksheets.ui.SheetsSettings(state) }
        )
        for ((size, w, h) in listOf(Triple("phone", 360, 760), Triple("tablet", 800, 1100))) for ((name, panel) in panels) {
            runCatching {
                runDesktopComposeUiTest(width = w, height = h) {
                    setContent { MaterialTheme { Surface { panel() } } }
                    waitForIdle()
                    onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { i ->
                        runCatching { ImageIO.write(onAllNodes(isRoot())[i].captureToImage().toAwtImage(), "png", File(shots, "$size-panel-$name-$i.png")) }
                    }
                }
            }.onFailure { println("panel $name failed: $it") }
        }
    }
}
