package com.inksheets.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.ui.*
import com.inkslate.desktop.AppFlavor
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class MusicDockLifecycleTest {
    @get:Rule val tmp = TemporaryFolder()
    @After fun resetGlobalGeometry() {
        AppFlavor.studioRailDp = 0f; AppFlavor.musicShelfDp = 0f
        Overlays.studioOut = androidx.compose.ui.unit.Dp(0f)
        Overlays.bottomOut = androidx.compose.ui.unit.Dp(0f)
    }
    private class Platform(override val startFolder: File) : SheetsPlatform {
        val prefs = mutableMapOf("sheets_library" to startFolder.absolutePath)
        override val deviceId = "dock-lifecycle"
        override val deviceName = "Test"
        override val localFolder = File(startFolder, ".local")
        override fun pref(key: String) = prefs[key]
        override fun setPref(key: String, value: String?) { if (value == null) prefs.remove(key) else prefs[key] = value }
        override fun openPart(song: Song, part: Part, file: File) {}
        override fun pageText(file: File, page: Int): String? = null
        override val audioOut: AudioOut? = null
        override val microphone: Microphone? = null
        override fun onMain(block: () -> Unit) = block()
        override fun setStudioRailWidth(dp: Float) { AppFlavor.studioRailDp = dp }
        override fun setMusicShelfHeight(dp: Float) { AppFlavor.musicShelfDp = dp }
    }
    private fun state(): SheetsState {
        val root = tmp.newFolder("music")
        val file = File(root, "Score.pdf").apply { writeText("fixture") }
        val state = SheetsState(Platform(root))
        state.change { addSong("Score", listOf(Part(file = file.name))) }
        state.current = state.library!!.songs.first { it.title == "Score" }
        state.currentPath = file.absolutePath
        state.pageShown = 0 to 3
        state.homeInFront = false; state.stripCollapsed = false
        return state
    }

    @Test fun `studio reservation clears on Home and disposal and returns on reopening`() {
        val state = state()
        state.shelf.chooseView(MusicDockView.STUDIO)
        val mounted = mutableStateOf(true)
        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent { MaterialTheme { Box(Modifier.fillMaxSize()) { if (mounted.value) ActionStrip(state) } } }
            waitForIdle()
            runOnIdle {
                assertEquals(236f, AppFlavor.studioRailDp, .01f)
                assertEquals(236f, Overlays.studioOut.value, .01f)
                state.homeInFront = true
            }
            waitForIdle()
            runOnIdle {
                assertEquals(0f, AppFlavor.studioRailDp, .01f)
                assertEquals(0f, AppFlavor.musicShelfDp, .01f)
                state.homeInFront = false
            }
            waitForIdle()
            runOnIdle { assertEquals(236f, AppFlavor.studioRailDp, .01f); mounted.value = false }
            waitForIdle()
            runOnIdle {
                assertEquals(0f, AppFlavor.studioRailDp, .01f)
                assertEquals(0f, AppFlavor.musicShelfDp, .01f)
                assertEquals(0f, Overlays.studioOut.value, .01f)
            }
        }
    }

    @Test fun `workspace Reset restores Auto and every default widget after custom Studio arrangement`() {
        val state = state()
        state.shelf.chooseView(MusicDockView.STUDIO)
        state.shelf.move(ShelfWidget.TIMING, 1)
        state.shelf.show(ShelfWidget.DEVICES, false)
        state.arrangingShelf = true
        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent { MaterialTheme { Box(Modifier.fillMaxSize()) { ActionStrip(state) } } }
            waitForIdle()
            onNodeWithText("Reset").performClick()
            runOnIdle {
                assertEquals(MusicDockView.AUTO, state.shelf.view)
                assertEquals(ShelfWidget.entries.toList(), state.shelf.order)
                assertTrue(state.shelf.hidden.isEmpty())
            }
            onNodeWithText("Done").performClick()
        }
    }

    @Test fun `wide floating panel borrows studio space on small landscape and restores it on close`() {
        val state=state()
        state.shelf.chooseView(MusicDockView.STUDIO)
        runDesktopComposeUiTest(width=640,height=360) {
            setContent {MaterialTheme {Box(Modifier.fillMaxSize()){ActionStrip(state)}}}
            runOnIdle {assertEquals(236f,AppFlavor.studioRailDp,.01f);state.companionOpen=true}
            waitForIdle()
            runOnIdle {assertEquals(0f,AppFlavor.studioRailDp,.01f)}
            onNodeWithText("Lead",useUnmergedTree=true).assertIsDisplayed()
            val issues=UiAudit.check(this,"phone-small-land","dock-floating-panel",640,360)
            assertTrue(issues.joinToString(),issues.isEmpty())
            runOnIdle {state.companionOpen=false}
            waitForIdle()
            runOnIdle {assertEquals(236f,AppFlavor.studioRailDp,.01f)}
        }
    }
}
