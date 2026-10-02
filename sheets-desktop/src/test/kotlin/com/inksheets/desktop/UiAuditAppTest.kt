package com.inksheets.desktop

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.inkslate.desktop.AppFlavor
import com.inkslate.desktop.AppRoot
import com.inkslate.desktop.InkSlateTheme
import com.inkslate.desktop.NavigationHooks
import com.inkslate.desktop.Shortcuts
import com.inksheets.core.omr.Recognizer
import com.inksheets.core.omr.Score
import com.inksheets.ui.ScoreTools
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The whole app looked over at every size each way up (see [UiAudit]): Home, a part open with
 * its strips, each window over the music, the music tools, Fix going through the bars, Settings.
 * -Dinksheets.uiaudit=1 [-Dinksheets.uiaudit.sizes=phone,tablet10-land]
 */
@OptIn(ExperimentalTestApi::class)
class UiAuditAppTest {
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")

    @After
    fun reset() {
        ScoreTools.scoreSource = null
        runCatching { ScoreTools.endCheck() }
        AppFlavor.home = null; AppFlavor.paneOverlay = null; AppFlavor.onHomeShown = null; AppFlavor.onHome = null; AppFlavor.settingsSection = null
        AppFlavor.musicView = false; AppFlavor.fingerPans = false; AppFlavor.alwaysFullscreen = false
    }

    private fun DesktopComposeUiTest.settle(frames: Int = 12) = repeat(frames) { mainClock.advanceTimeBy(16); Thread.sleep(4) }

    @Test
    fun `every screen at every size`() {
        assumeTrue(System.getProperty("inksheets.uiaudit") != null)
        val src = File(music, "MobileSheets/Chester.pdf")
        assumeTrue(src.isFile)
        val only = System.getProperty("inksheets.uiaudit.sizes")?.split(",")?.toSet()
        UiAudit.reset()
        val lib = File("build/uiaudit-library").apply { deleteRecursively(); mkdirs() }
        val part = File(lib, src.name).also { src.copyTo(it) }
        val (ink, _) = OmrRealPagesTest().renderAt(part, 0)!!
        val reading = Recognizer().read(ink, 0)
        val score = Score(reading.measures, 3, listOf(ink.width, 0, 0), readPages = listOf(0))
        var total = 0
        for ((size, w, h) in UiAudit.SIZES) {
            if (only != null && size !in only) continue
            ScoreTools.scoreSource = { if (it == part.absolutePath) score else null }
            val sheets = installInkSheets { it.setPref("sheets_library", lib.absolutePath) }
            val home = AppFlavor.home!!
            var openFile: ((File) -> Unit)? = null
            var openSettings: (() -> Unit)? = null
            AppFlavor.home = { open, settings -> openFile = open; openSettings = settings; home(open, settings) }
            runDesktopComposeUiTest(width = w, height = h) {
                mainClock.autoAdvance = false
                setContent { InkSlateTheme { AppRoot(Shortcuts(), NavigationHooks()) } }
                settle(40)
                fun look(scene: String, vararg required: String) { settle(20); total += UiAudit.check(this, size, scene, w, h, required = required.toList()).size }
                look("home")
                runOnIdle { openFile!!(part) }
                val until = System.currentTimeMillis() + 30_000
                while (System.currentTimeMillis() < until && sheets().pageShown.second == 0) settle(4)
                settle(30)
                look("page")
                val s = sheets()
                for ((name, open) in listOf<Pair<String, (Boolean) -> Unit>>(
                    "tuner" to { v -> s.tunerOpen = v }, "metronome" to { v -> s.metronomeOpen = v },
                    "recordings" to { v -> s.audioOpen = v }, "together" to { v -> s.companionOpen = v })) {
                    runOnIdle { open(true) }
                    look("panel-$name")
                    runOnIdle { open(false) }
                }
                runOnIdle { ScoreTools.open = true }
                look("music-tools")
                runOnIdle { ScoreTools.startCheck(s) }
                settle(60)
                look("fix", "Edit", "None of these", "Not one bar", "Skip", "Done", "Clef, key or time wrong?")
                // The bar put right by hand, and its clef, key and time chosen anew: every control there.
                runOnIdle { ScoreTools.offered.firstOrNull()?.let { ScoreTools.startEdit(it) } }
                look("fix-editing", "Up", "Down", "♭", "♯", "Dot", "Quarter", "16th", "Triplet", "Remove", "Cancel", "Undo", "Use this")
                runOnIdle { ScoreTools.cancelEdit(); ScoreTools.sigDraft = com.inksheets.core.omr.SigFix(com.inksheets.core.omr.Clef.TREBLE, 0, 4, 4) }
                look("fix-signature", "Treble", "Tenor", "Key", "Beats", "4/16", "Cancel", "Use from bar")
                runOnIdle { ScoreTools.sigDraft = null; ScoreTools.endCheck() }
                // Playing: the bar along the foot that steers it.
                runOnIdle { ScoreTools.play(s) }
                look("playing", "Back a line", "Back a bar", "Pause", "On a bar", "On a line", "Slower", "Faster", "Stop")
                runOnIdle { ScoreTools.stop(s); ScoreTools.open = false }
                settle(10)
                runOnIdle { openSettings?.invoke() }
                look("settings")
            }
        }
        println("UIAUDIT app: $total problem(s) - see build/uiaudit/report.txt")
    }
}
