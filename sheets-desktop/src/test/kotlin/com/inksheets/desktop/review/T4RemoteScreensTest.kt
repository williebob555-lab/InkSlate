package com.inksheets.desktop.review

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.inksheets.core.RemoteLink
import com.inksheets.ui.SheetsHome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.imageio.ImageIO

/** The remote on a phone, upright and on its side, with a 300-song library behind it. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class T4RemoteScreensTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun shoot(name: String, img: java.awt.image.BufferedImage) {
        val dir = File("build/t4/shots").apply { mkdirs() }
        ImageIO.write(img, "png", File(dir, "$name.png"))
    }

    @Test
    fun `the remote on a phone upright and on its side with 300 songs`() {
        com.inksheets.ui.RemoteControl.port = T4.freePort()
        val (stand, _) = T4Lib.make(tmp.newFolder("Stand"), name = "Stand", extra = 281)
        val (phone, _) = T4Lib.make(tmp.newFolder("Phone"), name = "Phone")
        check(stand.remote.startHosting())
        try {
            val link = stand.remote.pairLink!!.replace(Regex("hosts=[^&]*"), "hosts=127.0.0.1")
            phone.remote.remoteOpen = true
            for ((label, w, h) in listOf(Triple("portrait", 390, 844), Triple("landscape", 844, 390))) {
                runDesktopComposeUiTest(width = w, height = h) {
                    setContent { MaterialTheme { Surface { SheetsHome(phone, onOpenSettings = {}) } } }
                    phone.remote.connect(RemoteLink.parsePair(link)!!)
                    waitUntil(timeoutMillis = 8000) { phone.remote.hostLibrary?.songs?.size ?: 0 > 100 }
                    waitForIdle()
                    val root = onAllNodes(isRoot()).onFirst()
                    shoot("$label-buttons", root.captureToImage().toAwtImage())
                    // The deck: how many of the 12 default buttons are visible without scrolling?
                    val shownButtons = listOf("Previous page", "Next page", "Previous song", "Next song", "The set...", "Songs...", "Toolbar", "All tools", "Count in")
                        .count { onAllNodesWithText(it).fetchSemanticsNodes().firstOrNull()?.let { n -> n.boundsInRoot.bottom <= h && n.boundsInRoot.top >= 0 } == true }
                    T4.log("PHONE $label ${w}x$h: $shownButtons of 9 sampled default buttons fully on screen")
                    // A vertical swipe over the buttons (to scroll them): does the page change?
                    root.performTouchInput { down(Offset(width * 0.5f, height * 0.7f)); moveBy(Offset(0f, -height * 0.3f)); moveBy(Offset(0f, -height * 0.2f)); up() }
                    waitForIdle()
                    val onReading = onAllNodesWithText("Reading the music").fetchSemanticsNodes().isNotEmpty()
                    T4.log("PHONE $label: vertical swipe up over the button grid -> " + if (onReading) "went to the Reading page" else "stayed on the buttons")
                    // The Songs picker with 300 songs.
                    if (!onReading) {
                        onNodeWithText("Songs...").performClick(); waitForIdle()
                        shoot("$label-songs-picker", root.captureToImage().toAwtImage())
                    }
                    phone.remote.disconnect()
                }
            }
        } finally { stand.remote.stopHosting() }
    }
}
