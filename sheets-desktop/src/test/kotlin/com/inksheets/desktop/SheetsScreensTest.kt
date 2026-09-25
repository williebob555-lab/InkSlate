package com.inksheets.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import com.inksheets.ui.ActionStrip
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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.ui.AudioOut
import com.inksheets.ui.Microphone
import com.inksheets.ui.SheetsHome
import com.inksheets.ui.SheetsPlatform
import com.inksheets.ui.SheetsState
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        override fun onMain(block: () -> Unit) = block()
        override val deviceName = "Test stand"
        override fun openSet(parts: List<Pair<File, String>>, focus: Int) { tabs = parts.map { it.second }; closed = false }
        override fun closeSet() { closed = true }
    }
    private var tabs: List<String> = emptyList()
    private var closed = false

    private fun shoot(name: String, image: java.awt.image.BufferedImage) {
        val dir = System.getProperty("inksheets.shots") ?: return
        File(dir).mkdirs()
        ImageIO.write(image, "png", File(dir, "$name.png"))
    }

    @Test
    fun `the library shows the chosen instrument's songs and opens its part`() {
        val root = tmp.newFolder("Music")
        listOf("Band/Liberty Bell - Trombone.pdf", "Band/Liberty Bell - Euphonium.pdf", "Jazz/September - Bass.pdf", "Scans/scan0042.pdf")
            .forEach { File(root, it).apply { parentFile.mkdirs(); writeText("x") } }
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
            onNodeWithText("Tap").assertExists()
            onNodeWithText("Start").assertExists()
            shoot("metronome", onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage())
        }
    }

    @Test
    fun `the action strip sits over the page and runs pedal actions`() {
        val root = tmp.newFolder("Music")
        val state = SheetsState(FakePlatform(root))
        val ran = ArrayList<com.inkslate.core.PerformAction>()
        com.inkslate.core.Perform.document = { ran += it; true }
        runDesktopComposeUiTest(width = 900, height = 700) {
            setContent {
                MaterialTheme {
                    androidx.compose.foundation.layout.Box(
                        androidx.compose.ui.Modifier.fillMaxSize()
                            .background(androidx.compose.ui.graphics.Color(0xFFF4F1EA))
                    ) {
                        ActionStrip(state)
                    }
                }
            }
            waitForIdle()
            onNode(androidx.compose.ui.test.hasContentDescription("Next page")).performClick()
            assertEquals(listOf(com.inkslate.core.PerformAction.NEXT_PAGE), ran)
            shoot("action-strip", onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage())
        }
        com.inkslate.core.Perform.document = null
    }

    @Test
    fun `on a short screen every strip button is on screen, none scrolled away`() {
        val root = tmp.newFolder("Music")
        val state = SheetsState(FakePlatform(root))
        state.setStripActions(com.inkslate.core.PerformAction.entries.toList())
        runDesktopComposeUiTest(width = 1000, height = 420) {
            setContent {
                MaterialTheme {
                    androidx.compose.foundation.layout.Box(
                        androidx.compose.ui.Modifier.fillMaxSize()
                            .background(androidx.compose.ui.graphics.Color(0xFFF4F1EA))
                    ) { ActionStrip(state) }
                }
            }
            waitForIdle()
            for (action in listOf("Previous page", "Undo", "Show or hide the tools", "Buttons")) {
                onNode(androidx.compose.ui.test.hasContentDescription(action)).assertIsDisplayedFully(1000f, 420f)
            }
            shoot("action-strip-short", onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage())
        }
    }

    @Test
    fun `leading shows a code to scan, and the count of followers`() {
        val root = tmp.newFolder("Music")
        val state = SheetsState(FakePlatform(root))
        try {
            runDesktopComposeUiTest(width = 1000, height = 800) {
                setContent { MaterialTheme { Surface { SheetsHome(state, onOpenSettings = {}) } } }
                state.companionOpen = true
                waitForIdle()
                shoot("play-together", onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage())
                onNodeWithText("Lead from this device").performClick()
                waitForIdle()
                onNodeWithText("Scan to follow Test stand").assertExists()
                onNodeWithText("Nobody following yet").assertExists()
                shoot("leading", onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage())
            }
        } finally {
            state.companion.stopLeading()
        }
    }

    @Test
    fun `adding music offers a download, or what is in the folder`() {
        val root = tmp.newFolder("Music")
        val state = SheetsState(FakePlatform(root))
        runDesktopComposeUiTest(width = 1000, height = 800) {
            setContent { MaterialTheme { Surface { SheetsHome(state, onOpenSettings = {}) } } }
            onNodeWithContentDescriptionSafe("Add music")
            waitForIdle()
            onNodeWithText("A download").assertExists()
            onNodeWithText("Already in my music folder").assertExists()
            shoot("add-music", onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage())
        }
    }

    /** A node fully inside the window: a strip that scrolled would leave some half off it. */
    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertIsDisplayedFully(width: Float, height: Float) {
        assertIsDisplayed()
        val bounds = fetchSemanticsNode().boundsInRoot
        assertTrue("$bounds is cut off", bounds.top >= 0f && bounds.bottom <= height && bounds.right <= width)
    }

    @Test
    fun `a part whose file was moved is found by name and the library corrected`() {
        val root = tmp.newFolder("Music")
        File(root, "MobileSheets").mkdirs()
        File(root, "Chester.pdf").writeText("moved up a folder")
        File(root, "Hurricane Season - Trombone 1.pdf").writeText("also moved")
        val state = SheetsState(FakePlatform(root))
        state.change {
            addSong("Chester", listOf(Part(file = "MobileSheets/Chester.pdf", instrument = "euphonium")))
            addSong("Hurricane Season", listOf(Part(file = "MobileSheets/Hurricane Season - Trombone 1.pdf")))
        }
        // Opening one finds it at once.
        val chester = state.library!!.songs.first { it.title == "Chester" }
        assertEquals(File(root, "Chester.pdf"), state.partFile(chester, chester.parts.single()))
        assertEquals("Chester.pdf", state.library!!.song(chester.id)!!.parts.single().file)
        // The background pass puts the rest right.
        assertEquals(1, state.relinkMoved())
        assertEquals("Hurricane Season - Trombone 1.pdf", state.library!!.songs.first { it.title == "Hurricane Season" }.parts.single().file)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.onNodeWithContentDescriptionSafe(label: String) =
        onNode(androidx.compose.ui.test.hasContentDescription(label)).performClick()

    @Test
    fun `tab order and setlist order follow each other, and Home puts the set away`() {
        val root = tmp.newFolder("Music")
        listOf("A.pdf", "B.pdf", "C.pdf").forEach { File(root, it).writeText("x") }
        val state = SheetsState(FakePlatform(root))
        lateinit var setId: String
        state.change {
            val ids = listOf("Alpha", "Bravo", "Charlie").map { t -> addSong(t, listOf(Part(file = "${t.first()}.pdf"))).id }
            setId = addSetlist("Gig").id
            ids.forEach { addToSetlist(setId, it) }
        }
        state.playSetlist(setId, 1)
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), tabs)

        // Dragging Charlie's tab to the front reorders the setlist; Bravo is still the one playing.
        state.tabsMoved(listOf("C.pdf", "A.pdf", "B.pdf").map { File(root, it) })
        val titles = { state.library!!.setlist(setId)!!.entries.map { e -> state.library!!.song(e.songId)!!.title } }
        assertEquals(listOf("Charlie", "Alpha", "Bravo"), titles())
        assertEquals(setId to 2, state.playing)

        // Dragging in the list reorders the open tabs, keeping Bravo in front.
        val entries = state.library!!.setlist(setId)!!.entries
        state.reorderSetlist(setId, listOf(entries[2].id, entries[0].id, entries[1].id))
        state.setlistReordered(setId)
        assertEquals(listOf("Bravo", "Charlie", "Alpha"), titles())
        assertEquals(listOf("Bravo", "Charlie", "Alpha"), tabs)
        assertEquals(setId to 0, state.playing)

        // Home: the set is closed and Home opens on it.
        state.backToSetlist()
        assertTrue(closed)
        assertEquals(null, state.playing)
        assertEquals(1, state.homeTab)
        assertEquals(setId, state.setlistShown)
    }

    @Test
    fun `a setlist dragged onto a folder goes in, and dragged onto the path comes back out`() {
        val root = tmp.newFolder("Music")
        val state = SheetsState(FakePlatform(root))
        lateinit var folder: String
        lateinit var gig: String
        state.change {
            folder = addFolder("Jazz Band").id
            gig = addSetlist("Gig").id
        }
        state.homeTab = 1
        runDesktopComposeUiTest(width = 900, height = 600) {
            setContent { MaterialTheme { Surface { SheetsHome(state, onOpenSettings = {}) } } }
            waitForIdle()
            val target = onNodeWithText("Jazz Band").fetchSemanticsNode().boundsInRoot.center
            onAllNodes(androidx.compose.ui.test.hasContentDescription("Drag into a folder")).fetchSemanticsNodes().size.let { assertEquals(2, it) }
            // The second handle is the setlist's (folders come first).
            val handle = onAllNodes(androidx.compose.ui.test.hasContentDescription("Drag into a folder"))[1]
            val from = handle.fetchSemanticsNode().boundsInRoot.center
            handle.performTouchInput {
                down(center)
                moveBy(androidx.compose.ui.geometry.Offset(0f, 20f))
                moveBy(target - from - androidx.compose.ui.geometry.Offset(0f, 20f))
                up()
            }
            waitForIdle()
            assertEquals(folder, state.library!!.setlist(gig)!!.folderId)

            // Into the folder, and back out by dropping on "Setlists" at the top.
            onNodeWithText("Jazz Band").performClick()
            waitForIdle()
            val top = onAllNodesWithText("Setlists").fetchSemanticsNodes().maxBy { it.boundsInRoot.top }.boundsInRoot.center
            val inside = onAllNodes(androidx.compose.ui.test.hasContentDescription("Drag into a folder"))[0]
            val start = inside.fetchSemanticsNode().boundsInRoot.center
            inside.performTouchInput {
                down(center)
                moveBy(androidx.compose.ui.geometry.Offset(0f, -20f))
                moveBy(top - start - androidx.compose.ui.geometry.Offset(0f, -20f))
                up()
            }
            waitForIdle()
            assertEquals(null, state.library!!.setlist(gig)!!.folderId)
        }
    }
}
