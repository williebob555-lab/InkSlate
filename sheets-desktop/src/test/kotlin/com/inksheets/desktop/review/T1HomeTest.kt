package com.inksheets.desktop.review

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.input.key.Key
import com.inkslate.desktop.AppFlavor
import com.inkslate.desktop.AppRoot
import com.inkslate.desktop.InkSlateTheme
import com.inkslate.desktop.NavigationHooks
import com.inkslate.desktop.Shortcuts
import com.inksheets.desktop.UiAudit
import com.inksheets.desktop.installInkSheets
import com.inksheets.desktop.review.T1.say
import org.junit.After
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * T1: Home as a player sees it, at laptop, phone and tablet-landscape sizes, photographed to
 * build/t1shots/. Each scene is tried on its own so one missing control does not hide the rest.
 */
@OptIn(ExperimentalTestApi::class)
class T1HomeTest {
    private val shots = File("build/t1shots").also { it.mkdirs() }

    @After
    fun reset() {
        AppFlavor.home = null; AppFlavor.paneOverlay = null; AppFlavor.onHomeShown = null; AppFlavor.onHome = null; AppFlavor.settingsSection = null
        AppFlavor.musicView = false; AppFlavor.fingerPans = false; AppFlavor.alwaysFullscreen = false
    }

    private fun DesktopComposeUiTest.settle(frames: Int = 12) = repeat(frames) { mainClock.advanceTimeBy(16); Thread.sleep(4) }

    private fun scenesAt(tag: String, w: Int, h: Int, deep: Boolean) {
        val lib = T1.copyLib("ui-$tag")
        // Give a few songs details so rows are realistic.
        val sheets = installInkSheets { it.setPref("sheets_library", lib.absolutePath) }
        runDesktopComposeUiTest(width = w, height = h) {
            mainClock.autoAdvance = false
            setContent { InkSlateTheme { AppRoot(Shortcuts(), NavigationHooks()) } }
            settle(40)
            val until = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < until && (sheets().library == null || sheets().library!!.songs.isEmpty())) settle(8)
            settle(30)
            var n = 0
            fun shot(name: String) {
                settle(10)
                val img = onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage()
                ImageIO.write(img, "png", File(shots, "$tag-%02d-%s.png".format(++n, name)))
                val issues = UiAudit.check(this, tag, name, w, h)
                if (issues.isNotEmpty()) say("AUDIT $tag/$name: ${issues.joinToString(" | ")}")
            }
            fun scene(name: String, block: () -> Unit) {
                runCatching(block).onFailure { say("SCENE $tag/$name FAILED: ${it.message?.lines()?.first()}") }
            }
            fun click(text: String, index: Int = 0) {
                onAllNodesWithText(text, useUnmergedTree = true)[index].performClick(); settle(10)
            }
            fun clickDesc(desc: String, index: Int = 0) {
                onAllNodesWithContentDescription(desc, useUnmergedTree = true)[index].performClick(); settle(10)
            }
            fun type(text: String) { onNode(hasSetTextAction()).performTextInput(text); settle(10) }
            fun esc() { onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.Escape) }; settle(10) }

            val s = sheets()
            say("$tag: library songs=${s.library?.songs?.size} version=${s.version}")
            scene("home") { shot("home") }
            scene("expand parts") { click("parts", 0); shot("expanded-parts") }
            scene("search") {
                type("trom"); shot("search-trom")
                val hits = s.library!!.songs.count { it.title.lowercase().contains("trom") }
                say("$tag: search 'trom' songs whose title matches=$hits; rows shown with text 'Trombone'? (instrument search is not a thing)")
                type("zzzz"); shot("search-nothing")
            }
            scene("sorts") {
                for (label in listOf("Recently opened", "Recently added", "Composer", "A to Z")) {
                    onAllNodesWithText(label, useUnmergedTree = true).onFirst().performClick(); settle(10)
                    if (label != "A to Z") shot("sort-" + label.take(8).lowercase().replace(' ', '-'))
                }
            }
            scene("song menu") {
                // clear any search text
                clickDesc("Song options", 0); shot("song-menu")
                click("Details and parts"); shot("song-details")
                esc()
            }
            scene("instrument menu") {
                click("All instruments"); shot("instrument-menu")
                click("Edit instruments..."); shot("profiles")
                esc()
            }
            scene("add music") { clickDesc("Add music"); shot("add-music"); esc() }
            scene("more menu") { clickDesc("More"); shot("more-menu"); esc() }
            scene("setlists tab") {
                click("Setlists"); shot("setlists-empty")
                click("New folder"); shot("new-folder-dialog")
                type("Pep Band 2026");
                onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.Enter) }; settle(10)
                val made = s.library!!.folders.any { it.name == "Pep Band 2026" }
                say("$tag: Enter in New folder dialog confirms=$made")
                if (!made) { click("OK"); }
                shot("setlists-with-folder")
                click("Pep Band 2026")
                click("New setlist"); type("Game 1"); click("OK"); shot("setlist-in-folder")
                click("Game 1"); shot("setlist-view")
            }
            scene("setlist add") {
                click("Songs")
                clickDesc("Song options", 1); click("Add to setlist..."); shot("add-to-setlist-chooser")
                esc()
            }
            scene("incoming") {
                val f = File(lib, "MobileSheets").listFiles { x -> x.extension == "pdf" }!!.take(4)
                val outside = File("build/t1work/outside-$tag").apply { deleteRecursively(); mkdirs() }
                val copies = f.map { it.copyTo(File(outside, "New " + it.name)) } + File(outside, "Mystery Song - Euphonium.pdf").also { it.writeText("x") }
                s.offer(copies); settle(20); shot("incoming-5-files")
                esc(); s.incoming = null; settle(5)
            }
            scene("settings") {
                clickDesc("More"); click("Settings"); settle(20); shot("settings")
            }
        }
    }

    @Test
    fun `laptop`() = scenesAt("laptop", 1600, 1000, true)

    @Test
    fun `phone`() = scenesAt("phone", 390, 844, false)

    @Test
    fun `tablet land`() = scenesAt("tabland", 1280, 800, false)
}
