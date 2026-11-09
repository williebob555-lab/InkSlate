package com.inksheets.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.inksheets.core.AudioTrack
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.ui.ActionStrip
import com.inksheets.ui.AudioOut
import com.inksheets.ui.AudioPlayer
import com.inksheets.ui.Microphone
import com.inksheets.ui.MusicShelfPreferences
import com.inksheets.ui.Recording
import com.inksheets.ui.ShelfWidget
import com.inksheets.ui.SheetsPlatform
import com.inksheets.ui.SheetsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class MusicShelfRedesignTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Player : AudioPlayer {
        override fun load(file: File) = true
        override var playing = false
        override fun play() { playing = true }
        override fun pause() { playing = false }
        override var positionMs = 84_000L
        override fun seek(ms: Long) { positionMs = ms }
        override val durationMs = 278_000L
        override var speed = 1.0
        override var pitch = 0
        override fun setLoop(startMs: Long?, endMs: Long?) {}
        override fun release() {}
    }

    private class Platform(val root: File) : SheetsPlatform {
        private val prefs = hashMapOf<String, String?>("sheets_library" to root.absolutePath, "sheets_strip_collapsed" to "false")
        var shelfHeight = 0f
        override val deviceId = "shelf-test"
        override val deviceName = "Test stand"
        override val startFolder = root
        override val localFolder = File(root, ".local")
        override val cacheFolder = File(root, ".cache")
        override fun pref(key: String) = prefs[key]
        override fun setPref(key: String, value: String?) { prefs[key] = value }
        override fun openPart(song: Song, part: Part, file: File) {}
        override fun pageText(file: File, page: Int): String? = null
        override val audioOut: AudioOut? = null
        override val microphone: Microphone? = null
        override fun onMain(block: () -> Unit) = block()
        override fun setMusicShelfHeight(dp: Float) { shelfHeight = dp }
        override fun audioPlayer(): AudioPlayer = Player()
    }

    private fun setup(name: String): Pair<SheetsState, Platform> {
        // Standalone dock fixture has no editor palette. Do not inherit a prior
        // full-app test's annotation callbacks or its open palette state.
        com.inkslate.core.Perform.isOn=null
        com.inkslate.core.Perform.workspace=null
        val root = tmp.newFolder(name)
        val platform = Platform(root)
        val state = SheetsState(platform)
        val file = File(root, "Nocturne - Euphonium.pdf").apply { writeText("fixture") }
        state.change {
            addSong("Nocturne", listOf(Part(file = file.name, instrument = "euphonium"))) {
                audio = listOf(AudioTrack(file = "rehearsal.wav", label = "Ensemble rehearsal"))
            }
        }
        state.current = state.library!!.songs.first { it.title == "Nocturne" }
        File(root, "rehearsal.wav").writeBytes(byteArrayOf(0)) // Player fixture still exercises real file validation.
        state.currentPath = file.absolutePath
        state.pageShown = 0 to 3
        state.homeInFront = false
        state.stripCollapsed = false
        state.readMusic = true
        state.listenTurns = true
        // This test audits the bottom-shelf arrangement specifically. Auto is allowed to choose
        // the studio rail in short landscape windows and has its own coverage elsewhere.
        state.shelf.chooseView(com.inksheets.ui.MusicDockView.SHELF)
        return state to platform
    }

    @Test fun `adaptive shelf and original annotation actions fit every supported orientation`() {
        for ((name, width, height) in UiAudit.SIZES) {
            val (state, platform) = setup(name)
            runDesktopComposeUiTest(width = width, height = height) {
                setContent { MaterialTheme { Box(Modifier.fillMaxSize().background(Color(0xFFF1EEE6))) { ActionStrip(state) } } }
                waitForIdle()
                assertTrue("$name: shelf not measured", platform.shelfHeight > 0f)
                val maxShare=if(width>height).56f else .42f
                assertTrue("$name: shelf uses ${platform.shelfHeight} of $height", platform.shelfHeight <= height * maxShare)
                runOnIdle { state.expandMusicCategory(ShelfWidget.ANNOTATION) }
                waitForIdle()
                runOnIdle { assertEquals(ShelfWidget.ANNOTATION,state.expandedMusicCategory) }
                onNodeWithContentDescription("Undo").assertIsDisplayed()
                val problems = UiAudit.check(this, name, "music-shelf-redesign", width, height)
                assertTrue("$name: ${problems.joinToString()}", problems.isEmpty())
                runOnIdle { state.stripCollapsed = true }
                waitForIdle()
                assertEquals("$name: collapsed shelf still reserves space", 0f, platform.shelfHeight, .01f)
            }
        }
    }

    @Test fun `arrangement can reorder hide reset and keeps active transport when all widgets hidden`() {
        val (state, platform) = setup("arrangement")
        val player = Player()
        Recording.player = player
        try {
            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent { MaterialTheme { Box(Modifier.fillMaxSize()) { ActionStrip(state) } } }
                waitForIdle()
                onAllNodesWithContentDescription("Arrange tabs").onFirst().performClick()
                waitForIdle()
                onNodeWithContentDescription("Move Pages later").performScrollTo().performClick()
                runOnIdle { assertEquals(ShelfWidget.PARTS, state.shelf.order.first()) }
                onNodeWithText("Reset").performClick()
                runOnIdle { assertEquals(ShelfWidget.PAGES, state.shelf.order.first()) }
                onNodeWithText("Done").performClick()
                runOnIdle {
                    ShelfWidget.entries.forEach { state.shelf.show(it, false) }
                    Recording.play(state, state.current!!.audio.first(), state.current)
                }
                waitForIdle()
                onNodeWithContentDescription("Pause or resume active recording").assertIsDisplayed()
                onNodeWithContentDescription("Stop recording playback").assertIsDisplayed()
                // Hiding every category intentionally hides Annotate too; live transport remains
                // independently reachable while the user can restore the tabs from settings.
                assertTrue(platform.shelfHeight > 0)
                runOnIdle { state.stripCollapsed = true }
                waitForIdle()
                onNodeWithContentDescription("Stop recording playback").assertIsDisplayed()
                assertTrue("Active playback keeps its stop control when the shelf is folded", platform.shelfHeight > 0)
                onNodeWithContentDescription("Show buttons").performClick()
                waitForIdle()
                onNodeWithText("Tab layout").performClick()
                onNodeWithText("Reset").performClick()
                runOnIdle { assertTrue(state.shelf.hidden.isEmpty()) }
            }
        } finally { Recording.end(state); Recording.player = null }
    }

    @Test fun `corrupt arrangement preference retains each widget once`() {
        assertEquals(ShelfWidget.entries.toSet(), MusicShelfPreferences.decode("SCORE,bogus,SCORE").toSet())
        assertEquals(ShelfWidget.entries.size, MusicShelfPreferences.decode("SCORE,bogus,SCORE").size)
    }

    @Test fun `phone widget shortcuts expose distant tools without opening layout settings`() {
        val (state,_)=setup("phone-shortcuts")
        runDesktopComposeUiTest(width=360,height=640) {
            setContent { MaterialTheme { Box(Modifier.fillMaxSize()) { ActionStrip(state) } } }
            onNodeWithContentDescription("Connections quick tab").performScrollTo().performClick()
            waitForIdle()
            onNodeWithContentDescription("Connections quick tab").assertIsSelected()
            onNodeWithContentDescription("Remote").assertIsDisplayed()
            onNodeWithText("Reading").performClick()
            waitForIdle()
            onNodeWithContentDescription("All score tools").performClick()
            waitForIdle()
            onNodeWithText("Score tools").assertIsDisplayed()
            onNodeWithContentDescription("Compact categories").assertIsDisplayed()
            runOnIdle { assertEquals(ShelfWidget.SCORE,state.expandedMusicCategory) }
            assertTrue(!state.arrangingShelf)
        }
    }

    @Test fun `portrait dock boundary resizes and a decisive pull closes and resets`() {
        val (state,platform)=setup("portrait-resize")
        var resets=0
        com.inkslate.core.Perform.recentre={resets++}
        try {
            runDesktopComposeUiTest(width=360,height=640) {
                setContent {MaterialTheme {Box(Modifier.fillMaxSize()) {ActionStrip(state)}}}
                val before=state.shelf.portraitHeight
                onNodeWithTag("music-dock-resize").performTouchInput {
                    swipe(center,center.copy(y=center.y-90f),durationMillis=300)
                }
                runOnIdle {
                    assertTrue(state.shelf.portraitHeight>before)
                    assertEquals(state.shelf.portraitHeight.toInt().toString(),platform.pref("sheets_dock_portrait_height"))
                }
                onNodeWithTag("music-dock-resize").performTouchInput {
                    swipe(center,center.copy(y=center.y+110f),durationMillis=300)
                }
                waitUntil {state.stripCollapsed && resets==1}
            }
        } finally {com.inkslate.core.Perform.recentre=null}
    }
}
