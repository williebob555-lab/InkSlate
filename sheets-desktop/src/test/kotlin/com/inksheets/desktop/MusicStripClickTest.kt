package com.inksheets.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.inkslate.desktop.AppFlavor
import com.inkslate.desktop.AppRoot
import com.inkslate.desktop.InkSlateTheme
import com.inkslate.desktop.NavigationHooks
import com.inkslate.desktop.Shortcuts
import com.inkslate.desktop.SimulatedTouch
import com.inksheets.ui.ScoreTools
import com.inksheets.ui.Transcriber
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Every button on the music strip pressed as a finger presses it, in the app as it ships, and what
 * it should do checked - not called from code: Music opens the strip, Page reads the page in front
 * (the progress bar showing while it does), Clean lays the clean part over the page and takes it
 * away again, Check colours the bars. Pictures to build/touch/strip-click-*.png.
 */
@OptIn(ExperimentalTestApi::class)
class MusicStripClickTest {
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")
    private val shots = File("build/touch").also { it.mkdirs() }

    @After
    fun reset() {
        SimulatedTouch.on = false
        ScoreTools.scoreSource = null
        ScoreTools.open = false
        AppFlavor.onHomeShown = null; AppFlavor.onHome = null; AppFlavor.settingsSection = null
        AppFlavor.musicView = false; AppFlavor.studioRailDp = 0f; AppFlavor.musicShelfDp = 0f; AppFlavor.musicLaneDp = 0f; AppFlavor.fingerPans = false; AppFlavor.alwaysFullscreen = false
        AppFlavor.home = null; AppFlavor.paneOverlay = null
    }

    private fun DesktopComposeUiTest.settle(frames: Int = 12) = repeat(frames) { mainClock.advanceTimeBy(16); Thread.sleep(4) }

    @Test
    fun `the music strip's buttons do what they say when pressed`() {
        val src = File(music, "Imported/PEP BAND/Music/Diva/Diva- Trumpet 1.pdf")
        assumeTrue(src.isFile)
        val lib = File("build/strip-click-library").apply { deleteRecursively(); mkdirs() }
        val part = File(lib, src.name).also { src.copyTo(it) }
        val sheets = installInkSheets { it.setPref("sheets_library", lib.absolutePath); it.setPref("sheets_read_music", "true"); it.setPref("sheets_strip_collapsed", "false") }
        val home = AppFlavor.home!!
        var openFile: ((File) -> Unit)? = null
        AppFlavor.home = { open, settings -> openFile = open; home(open, settings) }
        var step = 0
        runDesktopComposeUiTest(width = 1400, height = 1000) {
            mainClock.autoAdvance = false
            setContent { InkSlateTheme { AppRoot(Shortcuts(), NavigationHooks()) } }
            settle(30)
            runOnIdle { openFile!!(part) }
            val until = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < until && sheets().pageShown.second == 0) settle(4)
            repeat(10) { settle() }
            SimulatedTouch.on = true
            // From closed, whatever a test before this one left open (the tools are shared by the whole app).
            runOnIdle { ScoreTools.open = false }
            settle()
            fun root() = onAllNodes(isRoot()).onLast()
            fun shot(name: String) { settle(); ImageIO.write(root().captureToImage().toAwtImage(), "png", File(shots, "strip-click-%02d-%s.png".format(++step, name))) }
            // What happened to the music tools' open state, and where each tap landed - said in full if a tap
            // does not do what it should (it has failed only on a busy machine; this says why next time).
            val story = java.util.Collections.synchronizedList(ArrayList<String>())
            var lastOpen = ScoreTools.open
            val watch = androidx.compose.runtime.snapshots.Snapshot.registerApplyObserver { _, _ ->
                val now = ScoreTools.open
                if (now != lastOpen) { story += "open $lastOpen -> $now at ${System.currentTimeMillis() % 100_000}"; lastOpen = now }
            }
            fun tap(text: String) {
                // Prefer the visible label: the dock underneath the dialog can have the same
                // accessibility description (e.g. Clean view) as a directory row's text.
                val labels = onAllNodesWithText(text, substring = false, useUnmergedTree = true)
                val target = if (labels.fetchSemanticsNodes().isNotEmpty()) labels.onFirst()
                    else onAllNodes(hasContentDescription(text), useUnmergedTree = true).onFirst()
                val node = target.fetchSemanticsNode("no '$text' on screen")
                val c = node.boundsInRoot.center
                story += "tap '$text' at $c (${System.currentTimeMillis() % 100_000})"
                // Compose dialogs have their own input root, whose bounds are not necessarily
                // the last root's coordinates. Inject relative to the target in its owning root.
                SimulatedTouch.stamp(); target.performMouseInput { moveTo(center); press() }
                // A slow finger, as a busy machine makes it (-Dinksheets.slowTapMs): held this long in real time.
                System.getProperty("inksheets.slowTapMs")?.toLongOrNull()?.let { Thread.sleep(it) }
                settle(3); SimulatedTouch.stamp(); target.performMouseInput { release() }
                onAllNodesWithText(text, substring = false, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
                    ?.let { if (it.boundsInRoot.center != c) story += "  '$text' moved to ${it.boundsInRoot.center} under the finger" }
                settle(20)
            }
            tap("All score tools")
            shot("music-open")
            assertTrue("All score tools opens the directory - ${story.joinToString("; ")}", sheets().expandedMusicCategory == com.inksheets.ui.ShelfWidget.SCORE && ScoreTools.open)
            watch.dispose()
            val sawReading = java.util.concurrent.atomic.AtomicBoolean(false)
            val sawProgress = java.util.concurrent.atomic.AtomicBoolean(false)
            val readingWatch = androidx.compose.runtime.snapshots.Snapshot.registerApplyObserver { _, _ ->
                if (Transcriber.busy != null) sawReading.set(true)
                if (Transcriber.progress != null) sawProgress.set(true)
            }
            tap("Read page 1")
            settle(10)
            shot("page-pressed")
            assertTrue("Page 1 starts reading", sawReading.get() || runOnIdle { Transcriber.busy != null })
            val end = System.currentTimeMillis() + 180_000
            while (runOnIdle { Transcriber.busy != null } && System.currentTimeMillis() < end) {
                runOnIdle { if (Transcriber.progress != null) sawProgress.set(true) }
                settle(10)
            }
            readingWatch.dispose()
            settle(20)
            shot("page-read")
            assertTrue("the progress bar was up while it read", sawProgress.get())
            assertTrue("page 1 read", ScoreTools.scoreHere(sheets())?.hasRead(0) == true)
            tap("Clean view")
            shot("clean-on")
            assertTrue("Clean lays the clean part over the page", ScoreTools.cleanedWhole(sheets()))
            tap("Clean view")
            shot("clean-off")
            assertTrue("Clean again takes it away", !ScoreTools.cleanedWhole(sheets()))
            tap("Review reading")
            shot("check-on")
            assertTrue("Check colours the bars", ScoreTools.colours)
            // Check and playing are one or the other.
            tap("Play")
            shot("play-pressed")
            assertTrue("Play puts Check away", !ScoreTools.colours)
            tap("Review")
            shot("check-again")
            assertTrue("Check stops the music", ScoreTools.colours && ScoreTools.playing == null)
            // The two strips fold together, and come back together.
            runOnIdle { sheets().stripCollapsed = true }
            settle(20)
            shot("folded")
            assertTrue("the music tools fold with the strip", onAllNodesWithText("Clean", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
            runOnIdle { sheets().stripCollapsed = false }
            settle(20)
            assertTrue("and come back with it", onAllNodesWithText("Clean", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            runOnIdle { ScoreTools.close(sheets()) }
        }
    }
}
