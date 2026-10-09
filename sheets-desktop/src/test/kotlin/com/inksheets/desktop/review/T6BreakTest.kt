package com.inksheets.desktop.review

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.inkslate.desktop.AppFlavor
import com.inkslate.desktop.AppRoot
import com.inkslate.desktop.InkSlateTheme
import com.inkslate.desktop.NavigationHooks
import com.inkslate.desktop.Shortcuts
import com.inksheets.core.omr.Recognizer
import com.inksheets.core.omr.Score
import com.inksheets.desktop.OmrRealPagesTest
import com.inksheets.desktop.installInkSheets
import com.inksheets.ui.ScoreTools
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/** T6 break-it round for the music tools on a real reading: rapid Check/Clean toggling during playback, Fix closed mid-edit. */
@OptIn(ExperimentalTestApi::class)
class T6BreakTest {
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")
    private fun say(t: String) = T3.say("t6-break.txt", t)
    private fun ok(name: String, cond: Boolean, detail: String = "") = say((if (cond) "PASS " else "FAIL ") + name + (if (detail.isNotEmpty()) " - $detail" else ""))

    @After
    fun reset() {
        ScoreTools.scoreSource = null
        runCatching { ScoreTools.endCheck() }
        resetFlavor()
        AppFlavor.musicView = false
    }

    @Test
    fun `rapid Check and Clean during playback, and Fix closed in the middle of an edit`() {
        val src = File(music, "MobileSheets/Chester.pdf")
        assumeTrue(src.isFile)
        val lib = File("build/t6-break-library").apply { deleteRecursively(); mkdirs() }
        val part = File(lib, src.name).also { src.copyTo(it) }
        val (ink, _) = OmrRealPagesTest().renderAt(part, 0)!!
        val reading = Recognizer().read(ink, 0)
        val score = Score(reading.measures, 1, listOf(ink.width))
        ScoreTools.scoreSource = { if (it == part.absolutePath) score else null }
        val sheets = installInkSheets { it.setPref("sheets_library", lib.absolutePath) }
        val home = AppFlavor.home!!
        var openFile: ((File) -> Unit)? = null
        AppFlavor.home = { open, settings -> openFile = open; home(open, settings) }
        runDesktopComposeUiTest(width = 1280, height = 720) {
            mainClock.autoAdvance = false
            setContent { InkSlateTheme { AppRoot(Shortcuts(), NavigationHooks()) } }
            fun settle(n: Int = 12) = repeat(n) { mainClock.advanceTimeBy(16); Thread.sleep(4) }
            fun shot(name: String) { settle(10); ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File("build/t6-shots").apply { mkdirs() }.resolve("$name.png")) }
            settle(40)
            runOnIdle { openFile!!(part) }
            val until = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < until && sheets().pageShown.second == 0) settle(4)
            settle(30)
            val s = sheets()
            runOnIdle { ScoreTools.open = true }; settle(20)
            // ---- rapid toggling while playing
            runOnIdle { ScoreTools.play(s) }; settle(30)
            ok("playing started", ScoreTools.playing != null, "${ScoreTools.playing}")
            var thrown: Throwable? = null
            try {
                repeat(40) { i ->
                    runOnIdle {
                        when (i % 4) {
                            0 -> ScoreTools.toggleCheck(s)
                            1 -> ScoreTools.cleanWhole(s, i % 8 == 1)
                            2 -> ScoreTools.choose(ScoreTools.Tool.CLEAN)
                            else -> ScoreTools.toggleCheck(s)
                        }
                    }
                    settle(1 + i % 3)
                }
            } catch (t: Throwable) { thrown = t }
            settle(40)
            ok("rapid Check/Clean toggling during playback: no exception", thrown == null, thrown?.toString().orEmpty())
            shot("rapid-toggle")
            ok("still playing after the toggling", ScoreTools.playing != null || ScoreTools.paused != null, "playing=${ScoreTools.playing} paused=${ScoreTools.paused}")
            say("INFO after toggling: tool=${ScoreTools.tool} colours=${ScoreTools.colours} cleanedWhole=${ScoreTools.cleanedWhole(s)} checking=${ScoreTools.checking}")
            runOnIdle { ScoreTools.undoClean(s); ScoreTools.undoClean(s); ScoreTools.undoClean(s) }; settle(10)
            runOnIdle { ScoreTools.stop(s) }; settle(30)
            ok("Stop works after it", ScoreTools.playing == null, "${ScoreTools.playing}")
            // Check on, Fix open on a red bar while playing starts
            if (!ScoreTools.colours) runOnIdle { ScoreTools.toggleCheck(s) }
            val red = score.measures.firstOrNull { !it.sure && it.bars == 1 }
            ok("the reading has a red bar", red != null)
            if (red != null) {
                runOnIdle { ScoreTools.fixBar(s, red.number) }; settle(60)
                ok("Fix opened", ScoreTools.checking)
                runOnIdle { ScoreTools.play(s) }; settle(40)
                say("INFO playing while Fix is open: playing=${ScoreTools.playing} checking=${ScoreTools.checking}")
                shot("play-with-fix-open")
                runOnIdle { ScoreTools.stop(s) }; settle(20)
                // Close Fix in the middle of an edit (every way it can be closed).
                for (way in listOf("endCheck", "tools closed", "Escape", "toggle Check", "Home and back")) {
                    runOnIdle { if (!ScoreTools.open) ScoreTools.open = true; if (!ScoreTools.colours) ScoreTools.toggleCheck(s); ScoreTools.chooseGoOn(true); ScoreTools.fixBar(s, red.number) }; settle(40)
                    runOnIdle { ScoreTools.offered.firstOrNull()?.let { ScoreTools.startEdit(it) } }; settle(20)
                    val editing = ScoreTools.editing != null
                    runOnIdle { ScoreTools.edit { e -> if (e.isEmpty()) e else e.drop(1) } }; settle(10)   // a change made, not used
                    when (way) {
                        "endCheck" -> runOnIdle { ScoreTools.endCheck() }
                        "tools closed" -> runOnIdle { ScoreTools.close(s) }
                        "Escape" -> onAllNodes(isRoot()).onFirst().performKeyInput { pressKey(Key.Escape) }
                        "toggle Check" -> runOnIdle { ScoreTools.toggleCheck(s) }
                        else -> { runCatching { onAllNodes(androidx.compose.ui.test.hasText("Home")).onFirst().let { n -> n.performClickT() } }; settle(30) }
                    }
                    settle(30)
                    say("INFO closed Fix by '$way' while editing=$editing: checking=${ScoreTools.checking} editing=${ScoreTools.editing != null} colours=${ScoreTools.colours} open=${ScoreTools.open} home=${s.homeInFront}")
                    shot("closed-by-" + way.replace(' ', '-'))
                    // The kept reading is untouched (the unused edit did not land).
                    val now = ScoreTools.scoreHere(s)?.measures?.first { it.number == red.number }
                    ok("closed by '$way' mid-edit: the bar is not changed behind the player's back", now != null && now.events.size == red.events.size, "events ${red.events.size} -> ${now?.events?.size}")
                    runOnIdle { ScoreTools.endCheck() }
                    if (way == "Home and back") runCatching { onAllNodes(androidx.compose.ui.test.hasText(part.name.removeSuffix(".pdf"), substring = true)).onFirst().performClickT(); settle(40) }
                }
            }
        }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performClickT() = this.performClick()
}
