package com.inksheets.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.ui.AudioOut
import com.inksheets.ui.Microphone
import com.inksheets.ui.SheetsHome
import com.inksheets.ui.SheetsPlatform
import com.inksheets.ui.SheetsState
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The remote looked over at every size each way up (see [UiAudit]): before pairing, the buttons,
 * changing them, and each page round them - the set, tuner and click, reading, recordings.
 * -Dinksheets.uiaudit=1 [-Dinksheets.uiaudit.sizes=phone,phone-land]
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class UiAuditRemoteTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Platform(root: File, override val deviceName: String) : SheetsPlatform {
        private val prefs = HashMap<String, String?>().apply { put("sheets_library", root.absolutePath) }
        override val deviceId = deviceName.replace(' ', '-')
        override val startFolder = root
        override fun pref(key: String) = prefs[key]
        override fun setPref(key: String, value: String?) { prefs[key] = value }
        override fun openPart(song: Song, part: Part, file: File) {}
        override fun pageText(file: File, page: Int): String? = null
        override val audioOut: AudioOut? = null
        override val microphone: Microphone? = null
        override fun onMain(block: () -> Unit) = javax.swing.SwingUtilities.invokeLater(block)
        override val localFolder: File get() = File(startFolder.parentFile, "local-$deviceId")
    }

    @Test
    fun `the remote at every size`() {
        assumeTrue(System.getProperty("inksheets.uiaudit") != null)
        val only = System.getProperty("inksheets.uiaudit.sizes")?.split(",")?.toSet()
        com.inksheets.ui.RemoteControl.port = java.net.ServerSocket(0).use { it.localPort }
        val root = tmp.newFolder("Music")
        listOf("Band/Take On Me - Alto Sax.pdf", "Band/Tom Sawyer - Alto Sax.pdf").forEach { File(root, it).apply { parentFile.mkdirs(); writeText(it) } }
        val stand = SheetsState(Platform(root, "Test stand"))
        val titles = listOf("Hey Baby", "Crab Rave", "September", "Sweet Caroline", "Tom Sawyer", "Groove Is in the Heart", "Hot Hot Hot",
            "Boots on the Ground", "Seven Nation Army", "Uptown Funk", "Land of a Thousand Dances", "Shout", "Iron Man",
            "Zombie Nation", "Mr. Brightside", "Hey Song", "24K Magic", "Fight Song", "Don't Stop Believin'", "Party Medley - a long title to wrap")
        lateinit var setId: String
        stand.change {
            val ids = titles.map { t -> songs.firstOrNull { it.title == t }?.id ?: ensureSong(t).id }
            setId = addSetlist("Football - home").id
            editSetlist(setId) { entries = ids.map { com.inksheets.core.SetlistEntry(songId = it) } }
        }
        assumeTrue(stand.remote.startHosting())
        var total = 0
        try {
            val link = stand.remote.pairLink!!.replace(Regex("hosts=[^&]*"), "hosts=127.0.0.1")
            stand.playSetlist(setId, 4)
            for ((size, w, h) in UiAudit.SIZES) {
                if (only != null && size !in only) continue
                val phone = SheetsState(Platform(tmp.newFolder("Phone-$size"), "Phone $size"))
                phone.remote.remoteOpen = true
                runDesktopComposeUiTest(width = w, height = h) {
                    setContent { MaterialTheme { Surface { SheetsHome(phone, onOpenSettings = {}) } } }
                    waitForIdle()
                    // The page tabs nest in notches carved in what they lie over (see Notches): looked at in the pictures.
                    val tabs = setOf("The set", "Tuner & click", "Reading the music", "Recordings", "SET", "REC")
                    fun look(scene: String) { waitForIdle(); total += UiAudit.check(this, size, "remote-$scene", w, h, nested = tabs).size }
                    look("unpaired")
                    phone.remote.connect(com.inksheets.core.RemoteLink.parsePair(link)!!)
                    waitUntil(timeoutMillis = 8000) { phone.remote.shown?.set?.size == titles.size }
                    look("buttons")
                    val rootNode = onAllNodes(isRoot()).onFirst()
                    fun swipe(dx: Float, dy: Float) = rootNode.performTouchInput {
                        down(center); moveBy(androidx.compose.ui.geometry.Offset(dx / 2, dy / 2)); moveBy(androidx.compose.ui.geometry.Offset(dx / 2, dy / 2)); up()
                    }
                    fun home() = waitUntil(timeoutMillis = 3000) { onAllNodesWithText("Change buttons").fetchSemanticsNodes().isNotEmpty() }
                    val sx = w * 0.6f; val sy = h * 0.4f
                    swipe(-sx, 0f); look("set"); swipe(sx, 0f); home()
                    swipe(0f, sy); look("tools"); swipe(0f, -sy); home()
                    swipe(0f, -sy); look("reading"); swipe(0f, sy); home()
                    swipe(sx, 0f); look("recording"); swipe(-sx, 0f); home()
                    onAllNodesWithText("Change buttons").onFirst().performClick()
                    look("editing")
                    phone.remote.disconnect()
                }
            }
        } finally {
            stand.remote.stopHosting()
        }
        println("UIAUDIT remote: $total problem(s)")
    }
}
