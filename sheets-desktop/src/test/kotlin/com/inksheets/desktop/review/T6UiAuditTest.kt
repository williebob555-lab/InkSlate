package com.inksheets.desktop.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.inkslate.desktop.AppFlavor
import com.inkslate.desktop.AppRoot
import com.inkslate.desktop.InkSlateTheme
import com.inkslate.desktop.NavigationHooks
import com.inkslate.desktop.Shortcuts
import com.inksheets.core.omr.Recognizer
import com.inksheets.core.omr.Score
import com.inksheets.desktop.FakeWatch
import com.inksheets.desktop.OmrRealPagesTest
import com.inksheets.desktop.UiAudit
import com.inksheets.desktop.installInkSheets
import com.inksheets.ui.ActionStrip
import com.inksheets.ui.Recording
import com.inksheets.ui.ScoreTools
import com.inksheets.ui.SharedMetronome
import com.inksheets.ui.SheetsState
import com.inksheets.ui.Transcriber
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * T6: the screens that changed on 2026-10-08/09 and had no UiAudit scene, at all 12 sizes
 * (-Dinksheets.uiaudit=1 [-Dinksheets.uiaudit.sizes=phone-land,...]). Pictures: build/uiaudit/<size>-t6-<scene>.png.
 */
@OptIn(ExperimentalTestApi::class)
class T6UiAuditTest {
    @get:Rule val tmp = TemporaryFolder()
    private val music = File(System.getenv("USERPROFILE") ?: "", "Music/Sheet Music/InkSheets")
    private val only get() = System.getProperty("inksheets.uiaudit.sizes")?.split(",")?.toSet()
    private val sizes get() = UiAudit.SIZES.filter { only == null || it.first in only!! }
    private fun say(t: String) = T3.say("t6-audit.txt", t)

    @After
    fun reset() {
        ScoreTools.scoreSource = null
        runCatching { ScoreTools.endCheck() }
        setProgress(null)
        resetFlavor()
        AppFlavor.musicView = false
    }

    /** Transcriber is internal with a private setter: reached by reflection. */
    private fun setProgress(v: Float?) {
        val m = Transcriber::class.java.getDeclaredMethod("setProgress", java.lang.Float::class.java)
        m.isAccessible = true
        m.invoke(Transcriber, v)
    }

    private fun DesktopComposeUiTest.settleT(frames: Int = 12) = repeat(frames) { mainClock.advanceTimeBy(16); Thread.sleep(4) }

    @Test
    fun `home - chosen songs, undo, recently deleted, move to, settings`() {
        assumeTrue(System.getProperty("inksheets.uiaudit") != null)
        assumeTrue(T2Lib.available())
        var total = 0
        for ((size, w, h) in sizes) {
            t6App("t6-$size", w, h) {
                fun look(scene: String, vararg req: String) {
                    settle(20)
                    val p = UiAudit.check(t, size, "t6-$scene", w, h, required = req.toList())
                    total += p.size
                    if (p.isNotEmpty()) say("PROBLEMS $size/$scene: ${p.joinToString(" | ")}")
                }
                fun esc() { root.performKeyInput { pressKey(Key.Escape) }; settle(10) }
                fun scene(name: String, block: () -> Unit) {
                    runCatching { root.performMouseInput { release() } }
                    sheets().undoOffer = null; settle(5)
                    runCatching(block).onFailure { say("SCENE $size/$name FAILED: ${it.message?.lines()?.first()}"); runCatching { esc(); esc() } } }
                look("home")
                scene("chosen") {
                    val shownRows = sheets().library!!.songs.map { it.title }.filter { tt -> t.onAllNodesWithText(tt, useUnmergedTree = false).fetchSemanticsNodes().isNotEmpty() }
                    say("INFO $size songs ${sheets().library!!.songs.size} shown rows $shownRows")
                    t.onAllNodesWithText(shownRows[0]).onFirst().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick); settle(20)
                    if (shownRows.size > 1) { t.onAllNodesWithText(shownRows[1]).onFirst().performClick(); settle(20) }
                    look("home-chosen", "chosen", "Add to a setlist...", "Remove", "Done")
                    t.onAllNodesWithText("Add to a setlist...", useUnmergedTree = true).onFirst().performClick(); settle(20)
                    look("home-chosen-add")
                    esc()
                    t.onAllNodesWithText("Remove", useUnmergedTree = true).onFirst().performClick(); settle(20)
                    look("home-chosen-removed-undo", "Undo")
                    t.onAllNodesWithText("Undo", useUnmergedTree = true).onFirst().performClick(); settle(20)
                }
                scene("undo") {
                    sheets().offerUndo("Removed Party Medley - Trombone 1 and a rather long song title to see how it wraps") {}
                    look("undo-bar-long", "Undo")
                    sheets().undoOffer = null
                }
                scene("recently deleted") {
                    val st = sheets()
                    st.removeSong(st.library!!.songs.first { it.title == "Toxic" })
                    st.deleteSetlist(setId)
                    settle(20)
                    t.onAllNodesWithContentDescription("More", useUnmergedTree = true).onFirst().performClick(); settle(10)
                    look("home-more-menu")
                    t.onAllNodesWithText("Recently deleted...", useUnmergedTree = true).onFirst().performClick(); settle(30)
                    look("recently-deleted", "Recently deleted", "Restore")
                    esc()
                }
                scene("move to") {
                    val st = sheets()
                    st.library!!.addFolder("Pep Band 2026"); st.library!!.addSetlist("Game 1"); st.refresh()
                    t.onAllNodesWithText("Setlists", useUnmergedTree = true).onFirst().performClick(); settle(20)
                    look("setlists-tab")
                    t.onAllNodesWithContentDescription("Options", useUnmergedTree = true).onLast().performClick(); settle(10)
                    look("setlist-row-menu")
                    t.onAllNodesWithText("Move to...", useUnmergedTree = true).onFirst().performClick(); settle(20)
                    look("move-to-dialog", "Setlists (the top)")
                    esc()
                }
                scene("settings") {
                    t.onAllNodesWithContentDescription("More", useUnmergedTree = true).onFirst().performClick(); settle(10)
                    t.onAllNodesWithText("Settings", useUnmergedTree = true).onFirst().performClick(); settle(30)
                    look("settings-top")
                    t.mainClock.autoAdvance = true
                    for (heading in listOf("Library health", "Experimental")) runCatching {
                        t.onAllNodesWithText(heading, useUnmergedTree = true).onLast().performScrollTo(); settle(10)
                        look("settings-" + heading.lowercase().replace(' ', '-'), heading)
                    }
                    runCatching {
                        t.onAllNodesWithText("Recently deleted (", substring = true, useUnmergedTree = true).onLast().performScrollTo()
                        t.onAllNodesWithText("Recently deleted (", substring = true, useUnmergedTree = true).onLast().performClick(); settle(30)
                        look("settings-recently-deleted", "Recently deleted")
                    }
                    t.mainClock.autoAdvance = false
                }
            }
        }
        say("UIAUDIT T6 home: $total problem(s)")
    }

    @Test
    fun `reading - check colours, fix, end of set, progress bar, undo over the music`() {
        assumeTrue(System.getProperty("inksheets.uiaudit") != null)
        val src = File(music, "MobileSheets/Chester.pdf")
        assumeTrue(src.isFile)
        val lib = File("build/t6-library").apply { deleteRecursively(); mkdirs() }
        val part = File(lib, src.name).also { src.copyTo(it) }
        val (ink, _) = OmrRealPagesTest().renderAt(part, 0)!!
        val reading = Recognizer().read(ink, 0)
        val score = Score(reading.measures, 1, listOf(ink.width))
        var total = 0
        for ((size, w, h) in sizes) {
            ScoreTools.scoreSource = { if (it == part.absolutePath) score else null }
            val sheets = installInkSheets { it.setPref("sheets_library", lib.absolutePath) }
            val home = AppFlavor.home!!
            var openFile: ((File) -> Unit)? = null
            AppFlavor.home = { open, settings -> openFile = open; home(open, settings) }
            runDesktopComposeUiTest(width = w, height = h) {
                mainClock.autoAdvance = false
                setContent { InkSlateTheme { AppRoot(Shortcuts(), NavigationHooks()) } }
                settleT(40)
                fun look(scene: String, vararg req: String) {
                    settleT(20)
                    val p = UiAudit.check(this, size, "t6-$scene", w, h, required = req.toList()); total += p.size
                    if (p.isNotEmpty()) say("PROBLEMS $size/$scene: ${p.joinToString(" | ")}")
                }
                runOnIdle { openFile!!(part) }
                val until = System.currentTimeMillis() + 30_000
                while (System.currentTimeMillis() < until && sheets().pageShown.second == 0) settleT(4)
                settleT(30)
                val s = sheets()
                runOnIdle { setProgress(0.4f) }
                look("page-reading-progress")
                runOnIdle { setProgress(null); s.edgeNotice = "End of the set"; s.edgeNoticeAt = System.currentTimeMillis() }
                look("page-end-of-set", "End of the set")
                runOnIdle { s.edgeNotice = null; s.offerUndo("Took Chester out") {} }
                look("page-undo-bar", "Undo")
                runOnIdle { s.undoOffer = null; s.stripCollapsed = false }
                look("page-strip-open", "Pen")
                runOnIdle { ScoreTools.open = true }
                settleT(20)
                if (ScoreTools.colours) runOnIdle { ScoreTools.choose(ScoreTools.Tool.CHECK) }
                settleT(10)
                runOnIdle { ScoreTools.choose(ScoreTools.Tool.CHECK) }
                settleT(30)
                look("music-strip-check-on", "Check")
                repeat(6) { runOnIdle { ScoreTools.choose(ScoreTools.Tool.CHECK) }; settleT(2) }
                settleT(20)
                look("music-strip-check-toggled")
                if (!ScoreTools.colours) runOnIdle { ScoreTools.choose(ScoreTools.Tool.CHECK) }
                val red = score.measures.firstOrNull { !it.sure && it.bars == 1 }
                if (red != null) {
                    runOnIdle { ScoreTools.chooseGoOn(true); ScoreTools.fixBar(s, red.number) }
                    settleT(60)
                    look("fix-go-on", "None of these", "Skip", "Done")
                    runOnIdle { setProgress(0.6f); s.offerUndo("Removed something") {} }
                    look("fix-with-progress-and-undo", "Done")
                    runOnIdle { setProgress(null); s.undoOffer = null; ScoreTools.endCheck(); ScoreTools.chooseGoOn(false); ScoreTools.fixBar(s, red.number) }
                    settleT(60)
                    look("fix-close-after", "None of these", "Done")
                    runOnIdle { ScoreTools.endCheck() }
                }
                runOnIdle { ScoreTools.open = false }
            }
            resetFlavor()
        }
        say("UIAUDIT T6 reading: $total problem(s)")
    }

    @Test
    fun `playback column - Play along hint`() {
        assumeTrue(System.getProperty("inksheets.uiaudit") != null)
        var total = 0
        for ((size, w, h) in sizes) {
            Recording.player = null; Recording.loadedFile = null; SharedMetronome.engine = null
            val root = T3.library(tmp.newFolder("t6-$size"), "Sep")
            assumeTrue(File(T3.lib, "Sep/September.mp3").isFile)
            val platform = T3Platform(root, mic = T3Mic(sampleRate = 48_000), out = T3Out(), player = T3Player { 190_000L }, watchLink = FakeWatch(0.02))
            val s = SheetsState(platform)
            s.scanFolder()
            val song = s.library!!.songs.first { it.title.contains("September", true) }
            val fresh = s.library!!.song(song.id)!!
            s.current = fresh
            s.currentPath = s.fileOf(fresh.parts.first().file)!!.absolutePath
            s.pageShown = 1 to 4
            s.homeInFront = false
            s.stripCollapsed = false
            runDesktopComposeUiTest(width = w, height = h) {
                setContent { MaterialTheme { Box(Modifier.fillMaxSize().background(Color(0xFFF4F1EA))) { ActionStrip(s) } } }
                fun look(scene: String, vararg req: String) {
                    waitForIdle()
                    val p = UiAudit.check(this, size, "t6-$scene", w, h, required = req.toList()); total += p.size
                    if (p.isNotEmpty()) say("PROBLEMS $size/$scene: ${p.joinToString(" | ")}")
                }
                val track = fresh.audio.first()
                runOnIdle { Recording.load(s, track); Recording.play(s, track, fresh) }
                Thread.sleep(500); waitForIdle()
                look("playalong-learning")
                val partId = s.partShown()?.id ?: fresh.parts.first().id
                runOnIdle { s.change { editSong(fresh.id) { audio = song(fresh.id)!!.audio.map { t -> if (t.file != track.file) t else t.withTurn(partId, 0, 20_000).withTurn(partId, 1, 40_000).withTurn(partId, 2, 60_000) } } } }
                Thread.sleep(600); waitForIdle()
                look("playalong-learned", "Turn")
                look("playalong-learned-pause", "Pause")
                runOnIdle { Recording.end(s) }
            }
        }
        say("UIAUDIT T6 playalong: $total problem(s)")
    }
}
