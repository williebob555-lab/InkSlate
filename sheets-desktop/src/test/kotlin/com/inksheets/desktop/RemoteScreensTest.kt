package com.inksheets.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.inkslate.core.Perform
import com.inkslate.core.PerformAction
import com.inksheets.core.Bookmark
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.ui.AudioOut
import com.inksheets.ui.BookmarkSort
import com.inksheets.ui.Microphone
import com.inksheets.ui.SheetsHome
import com.inksheets.ui.SheetsPlatform
import com.inksheets.ui.SheetsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.imageio.ImageIO

/**
 * The strip opened and closed by a tap on the middle of the page, the Bookmarks tab as a setlist
 * of its own, recordings with none yet, and a remote driving another device - driven without a
 * window. Pictures go to -Dinksheets.shots.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class RemoteScreensTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val opened = ArrayList<String>()

    /** Sound out that is pulled in real time, as a sound card would, and thrown away. */
    private class FakeOut : AudioOut {
        override val sampleRate = 48_000
        @Volatile private var thread: Thread? = null
        override fun start(fill: (FloatArray) -> Unit) {
            stop()
            thread = Thread {
                val buf = FloatArray(480)
                while (!Thread.currentThread().isInterrupted) {
                    fill(buf)
                    try { Thread.sleep(10) } catch (e: InterruptedException) { break }
                }
            }.apply { isDaemon = true; start() }
        }
        override fun stop() { thread?.interrupt(); thread = null }
    }

    private inner class FakePlatform(root: File, override val deviceName: String = "Test stand", out: AudioOut? = null) : SheetsPlatform {
        private val prefs = HashMap<String, String?>().apply { put("sheets_library", root.absolutePath) }
        override val deviceId = deviceName.replace(' ', '-')
        override val startFolder = root
        override fun pref(key: String) = prefs[key]
        override fun setPref(key: String, value: String?) { prefs[key] = value }
        override fun openPart(song: Song, part: Part, file: File) { opened += part.file }
        override fun pageText(file: File, page: Int): String? = null
        override val audioOut: AudioOut? = out
        override val microphone: Microphone? = null
        override fun onMain(block: () -> Unit) = javax.swing.SwingUtilities.invokeLater(block)  // as the desktop app does
        override val localFolder: File get() = File(startFolder.parentFile, "local-$deviceId")
    }

    private fun shoot(name: String, image: java.awt.image.BufferedImage) {
        val dir = System.getProperty("inksheets.shots") ?: return
        File(dir).mkdirs()
        ImageIO.write(image, "png", File(dir, "$name.png"))
    }

    private fun library(root: File, out: AudioOut? = null): SheetsState {
        listOf("Band/Take On Me - Alto Sax.pdf", "Band/Tom Sawyer - Alto Sax.pdf", "Band/Fight Song - Alto Sax.pdf")
            .forEach { File(root, it).apply { parentFile.mkdirs(); writeText(it) } }
        val state = SheetsState(FakePlatform(root, out = out))
        // Ids as the folder scan makes them, so the scan finds these songs already there.
        fun part(path: String) = Part(id = com.inksheets.core.Library.partIdFor(path), file = path, instrument = "alto-sax")
        val take = part("Band/Take On Me - Alto Sax.pdf")
        val tom = part("Band/Tom Sawyer - Alto Sax.pdf")
        state.change {
            editSong(ensureSong("Take On Me").id) {
                parts = listOf(take)
                notes = "Solo at D - take the repeat"
                bookmarks = listOf(Bookmark("Page 2", take.id, 2, at = 10), Bookmark("Page 4", take.id, 4, color = 0xFF43A047.toInt(), at = 30))
            }
            editSong(ensureSong("Tom Sawyer").id) {
                parts = listOf(tom)
                bookmarks = listOf(Bookmark("Page 1", tom.id, 1, at = 20))
            }
            editSong(ensureSong("Fight Song").id) { parts = listOf(part("Band/Fight Song - Alto Sax.pdf")) }
        }
        state.chooseProfile("alto-sax")
        return state
    }

    @Test
    fun `a tap on the middle opens the strip where the page is, and closes everything back to the fitted page`() {
        val state = library(tmp.newFolder("Music"))
        var toolsShown = false
        var recentred = 0
        Perform.isOn = { a -> a == PerformAction.FULLSCREEN && !toolsShown }
        Perform.workspace = { a -> if (a == PerformAction.FULLSCREEN) { toolsShown = !toolsShown; true } else false }
        Perform.recentre = { recentred++ }
        try {
            state.homeInFront = false
            state.stripCollapsed = true
            // Middle: the strip comes up, the page stays where it is.
            assertTrue(Perform.centreTap!!(false))
            assertFalse(state.stripCollapsed); assertFalse(toolsShown); assertEquals(0, recentred)
            // Middle again: put away, and the page fitted again.
            Perform.centreTap!!(false)
            assertTrue(state.stripCollapsed); assertEquals(1, recentred)
            // Near the bottom: the strip and the tools, the page still where it is.
            Perform.centreTap!!(true)
            assertFalse(state.stripCollapsed); assertTrue(toolsShown); assertEquals(1, recentred)
            // Middle again with both up: both away, and fitted.
            Perform.centreTap!!(false)
            assertTrue(state.stripCollapsed); assertFalse(toolsShown); assertEquals(2, recentred)
            // With only the strip up, near the bottom brings the tools too.
            Perform.centreTap!!(false)
            Perform.centreTap!!(true)
            assertFalse(state.stripCollapsed); assertTrue(toolsShown); assertEquals(2, recentred)
            // Home in front: not taken.
            state.homeInFront = true
            assertFalse(Perform.centreTap!!(false))
        } finally {
            Perform.isOn = null; Perform.workspace = null; Perform.recentre = null
        }
    }

    @Test
    fun `bookmarks are a list of their own - in your order, sorted, coloured, with notes`() {
        val state = library(tmp.newFolder("Music"))
        state.bookmarkSort = BookmarkSort.TITLE
        runDesktopComposeUiTest(width = 900, height = 600) {
            setContent { MaterialTheme { Surface { SheetsHome(state, onOpenSettings = {}) } } }
            waitForIdle()
            onNodeWithText("Bookmarks").performClick()
            waitForIdle()
            shoot("bookmarks-a-z", onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage())
            onNodeWithText("Keep this order").assertExists()
            assertEquals(2, onAllNodesWithText("Solo at D - take the repeat", useUnmergedTree = true).fetchSemanticsNodes().size)
            // A-Z kept as my order: Take On Me p2, p4, then Tom Sawyer.
            onNodeWithText("Keep this order").performClick()
            waitForIdle()
            assertEquals(BookmarkSort.MANUAL, state.bookmarkSort)
            val ranks = state.library!!.songs.flatMap { s -> s.bookmarks.map { (s.title + " " + it.page) to it.rank } }.sortedBy { it.second }.map { it.first }
            assertEquals(listOf("Take On Me 2", "Take On Me 4", "Tom Sawyer 1"), ranks)
            state.bookmarkSort = BookmarkSort.RECENT
            waitForIdle()
            shoot("bookmarks-newest", onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage())
        }
    }

    @Test
    fun `recordings offer recording yourself even with none paired`() {
        val state = library(tmp.newFolder("Music"))
        runDesktopComposeUiTest(width = 900, height = 700) {
            setContent { MaterialTheme { Surface { SheetsHome(state, onOpenSettings = {}) } } }
            waitForIdle()
            onAllNodesWithContentDescription("Song options").onFirst().performClick()
            waitForIdle()
            onAllNodesWithText("Recordings...").onFirst().performClick()
            waitForIdle()
            onNodeWithText("Record yourself").assertExists()
            onNodeWithText("Pair a recording").assertExists()
            shoot("recordings-none", onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage())
        }
    }

    @Test
    fun `a remote pairs with a device, shows where it is and turns its pages`() {
        val stand = library(tmp.newFolder("Music"))
        val phoneRoot = tmp.newFolder("PhoneMusic")
        val phone = SheetsState(FakePlatform(phoneRoot, deviceName = "Phone"))
        val turns = ArrayList<PerformAction>()
        Perform.document = { a -> turns += a; true }
        assertTrue(stand.remote.startHosting())
        try {
            val link = stand.remote.pairLink!!.replace(Regex("hosts=[^&]*"), "hosts=127.0.0.1")
            val song = stand.library!!.songs.first { it.title == "Take On Me" }
            stand.current = song
            stand.pageShown = 1 to 4
            phone.remote.remoteOpen = true
            runDesktopComposeUiTest(width = 480, height = 900) {
                setContent { MaterialTheme { Surface { SheetsHome(phone, onOpenSettings = {}) } } }
                waitForIdle()
                shoot("remote-setup", onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage())
                phone.remote.connect(com.inksheets.core.RemoteLink.parsePair(link)!!)
                waitUntil(timeoutMillis = 5000) { phone.remote.shown?.title == "Take On Me" }
                waitForIdle()
                onNodeWithText("Take On Me").assertExists()
                onNodeWithText("Next page").performClick()
                waitUntil(timeoutMillis = 3000) { turns.contains(PerformAction.NEXT_PAGE) }
                shoot("remote-deck", onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage())
                phone.remote.disconnect()
            }
        } finally {
            stand.remote.stopHosting()
            Perform.document = null
        }
    }

    @Test
    fun `a remote sets the tempo, counts in, and runs a sequence of steps in one press`() {
        val stand = library(tmp.newFolder("Music"), out = FakeOut())
        val phone = SheetsState(FakePlatform(tmp.newFolder("PhoneMusic"), deviceName = "Phone"))
        assertTrue(stand.remote.startHosting())
        try {
            val link = stand.remote.pairLink!!.replace(Regex("hosts=[^&]*"), "hosts=127.0.0.1")
            val fight = stand.library!!.songs.first { it.title == "Fight Song" }
            phone.remote.saveDeck(listOf(
                com.inksheets.core.RemoteButton(com.inksheets.core.RemoteButton.TEMPO_SET, value = 90.0),
                com.inksheets.core.RemoteButton(com.inksheets.core.RemoteButton.COUNT_IN, value = 1.0),
                com.inksheets.core.RemoteButton(com.inksheets.core.RemoteButton.MACRO, label = "Warm up", color = 0xFF43A047.toInt(), steps = listOf(
                    com.inksheets.core.RemoteButton(com.inksheets.core.RemoteButton.SONG, id = fight.id, title = fight.title),
                    com.inksheets.core.RemoteButton(com.inksheets.core.RemoteButton.TEMPO_SET, value = 132.0)
                )),
                com.inksheets.core.RemoteButton(com.inksheets.core.RemoteButton.TAP),
                com.inksheets.core.RemoteButton(com.inksheets.core.RemoteButton.AUDIO_SEEK, value = -5.0),
                com.inksheets.core.RemoteButton(com.inksheets.core.RemoteButton.RECORD)
            ))
            phone.remote.remoteOpen = true
            runDesktopComposeUiTest(width = 480, height = 1000) {
                setContent { MaterialTheme { Surface { SheetsHome(phone, onOpenSettings = {}) } } }
                phone.remote.connect(com.inksheets.core.RemoteLink.parsePair(link)!!)
                waitUntil(timeoutMillis = 5000) { phone.remote.shown != null }
                onNodeWithText("♩ = 90").performClick()
                waitUntil(timeoutMillis = 3000) { phone.remote.shown?.bpm == 90 }
                onNodeWithText("Count in 1 bar").performClick()
                waitUntil(timeoutMillis = 3000) { (phone.remote.shown?.counting ?: 0) > 0 }
                waitForIdle()
                shoot("remote-counting", onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage())
                // One bar of 4 at 90 is 2.7 s; then quiet.
                waitUntil(timeoutMillis = 6000) { phone.remote.shown?.counting == 0 && phone.remote.shown?.metronome == false }
                onNodeWithText("Warm up").performClick()
                waitUntil(timeoutMillis = 5000) { phone.remote.shown?.title == "Fight Song" && phone.remote.shown?.bpm == 132 }
                assertTrue(opened.any { it.contains("Fight Song") })
                onNodeWithText("Change buttons").performClick()
                onNodeWithText("Add a button").performClick()
                waitForIdle()
                shoot("remote-add", onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage())
                phone.remote.disconnect()
            }
        } finally {
            stand.remote.stopHosting()
        }
    }
}
