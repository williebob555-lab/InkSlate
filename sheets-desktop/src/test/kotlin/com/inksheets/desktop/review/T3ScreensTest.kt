package com.inksheets.desktop.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.inksheets.desktop.FakeWatch
import com.inksheets.desktop.UiAudit
import com.inksheets.core.AudioTrack
import com.inksheets.ui.ActionStrip
import com.inksheets.ui.Click
import com.inksheets.ui.Ears
import com.inksheets.ui.Listener
import com.inksheets.ui.NotesDialog
import com.inksheets.ui.Recording
import com.inksheets.ui.SelfRecorder
import com.inksheets.ui.SharedMetronome
import com.inksheets.ui.SheetsSettings
import com.inksheets.ui.SheetsState
import com.inksheets.ui.TempoFollow
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The practice tools and the experimental features as a player sees them, at a laptop, a phone and
 * a phone on its side: pictures in build/uiaudit (look at them), and the UiAudit's overlap/cropping
 * check on each.  Run with -Dinksheets.uiaudit=1.
 */
@OptIn(ExperimentalTestApi::class)
class T3ScreensTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val sizes = listOf(Triple("laptop", 1600, 1000), Triple("phone", 390, 844), Triple("phone-land", 844, 390))

    @After
    fun reset() {
        Listener.active = false; Listener.follow = null
        Ears.deaf = false; Ears.level = 0f
        runCatching { SelfRecorder.recording = false }
    }

    private fun say(t: String) = T3.say("screens.txt", t)

    private fun rig(size: String): SheetsState {
        Recording.player = null; Recording.loadedFile = null
        SharedMetronome.engine = null
        val root = T3.library(tmp.newFolder("Music-$size"), "Sep")
        val src = File(T3.lib, "Sep/September.mp3")
        assumeTrue(src.isFile)
        val platform = T3Platform(root, mic = T3Mic(sampleRate = 48_000), out = T3Out(), player = T3Player { 190_000L }, watchLink = FakeWatch(0.02))
        val s = SheetsState(platform)
        s.scanFolder()
        val song = s.library!!.songs.first { it.title.contains("September", true) }
        s.change { editSong(song.id) { audio = song.audio + AudioTrack(file = song.audio.first().file, label = "Me, 5 Oct 19:40", speed = 0.8, clickBpm = 126.0); notes = "Solo at D. Mute in bar 40." } }
        val fresh = s.library!!.song(song.id)!!
        s.current = fresh
        s.currentPath = s.fileOf(fresh.parts.first { it.file.contains("Trombone 1") }.file)!!.absolutePath
        s.pageShown = 1 to 5
        s.listenTurns = true
        s.homeInFront = false
        return s
    }

    @Test
    fun `the strip, the playback column, Listen and the practice windows at three sizes`() {
        assumeTrue(System.getProperty("inksheets.uiaudit") != null)
        UiAudit.reset()
        var total = 0
        for ((size, w, h) in sizes) {
            val s = rig(size)
            runDesktopComposeUiTest(width = w, height = h) {
                setContent { MaterialTheme { Box(Modifier.fillMaxSize().background(Color(0xFFF4F1EA))) { ActionStrip(s) } } }
                fun look(scene: String, vararg required: String) { waitForIdle(); total += UiAudit.check(this, size, "t3-$scene", w, h, required = required.toList()).size }
                look("strip")
                // Playback mode: the column beside the strip.
                val song = s.library!!.song(s.current!!.id)!!
                runOnIdle { Recording.load(s, song.audio[0]); Recording.play(s, song.audio[0], song) }
                Thread.sleep(300)
                look("strip-playback", "Replay", "Pause")
                runOnIdle { Recording.end(s) }
                // Listen running, in its states.
                runOnIdle {
                    Listener.active = true; Ears.level = 0.6f
                    Listener.follow = Listener.Follow(33_000, 0, 45_000, true, "learned", 1, 5)
                }
                look("listen-following")
                runOnIdle { Ears.deaf = true; Ears.device = "Microphone Array (Intel Smart Sound)" }
                look("listen-deaf")
                runOnIdle { Listener.active = false; Listener.follow = null; Ears.deaf = false }
                // Windows
                runOnIdle { s.metronomeOpen = true }
                look("metronome", "Metronome", "Start", "Tap")
                runOnIdle { TempoFollow.set(s, true) }
                Thread.sleep(2600)
                look("metronome-follow-deaf", "Metronome")
                runOnIdle { TempoFollow.set(s, false); s.metronomeOpen = false }
                runOnIdle { s.tunerOpen = true }
                Thread.sleep(500)
                look("tuner", "Tuner")
                runOnIdle { s.tunerOpen = false; s.audioOpen = true }
                look("recordings", "Recordings - September", "Record yourself", "Pair a recording")
                runOnIdle { s.audioOpen = false }
                runOnIdle { s.change { editSong(s.current!!.id) { reminder = "Watch the key change at the bridge" } }; s.writingReminder = true }
                look("reminder-write", "Reminder for next time")
                runOnIdle { s.writingReminder = false; s.reminderShown = s.current!!.id }
                look("reminder-shown")
                runOnIdle { s.reminderShown = null; s.notesFor = s.library!!.song(s.current!!.id) }
                look("notes", "Save", "Cancel")
                runOnIdle { s.notesFor = null }
            }
        }
        say("UIAUDIT T3 strip/windows: $total problem(s) - build/uiaudit/report.txt")
    }

    @Test
    fun `settings - the experimental section with the microphone test and the watch`() {
        assumeTrue(System.getProperty("inksheets.uiaudit") != null)
        var total = 0
        for ((size, w, h) in sizes) {
            val s = rig(size)
            runDesktopComposeUiTest(width = w, height = h) {
                setContent { MaterialTheme { Surface { Column(Modifier.verticalScroll(rememberScrollState())) { SheetsSettings(s) } } } }
                fun look(scene: String, vararg required: String) { waitForIdle(); total += UiAudit.check(this, size, "t3-settings-$scene", w, h, required = required.toList()).size }
                onAllNodes(hasText("Experimental")).onLast().performScrollTo()
                look("experimental", "Experimental")
                onAllNodes(hasText("Listen and turn pages", substring = true)).onLast().performScrollTo()
                look("listen")
                runCatching { onAllNodes(hasText("Test")).onLast().performScrollTo(); onAllNodes(hasText("Test")).onLast().performClick() }
                Thread.sleep(2700)
                waitForIdle()
                look("mic-test")
                runCatching { onAllNodes(hasText("Stop test")).onLast().performClick() }
                onAllNodes(hasText("Turn pages with a flick", substring = true)).onLast().performScrollTo()
                onAllNodes(hasText("Turn pages with a flick", substring = true)).onLast().performClick()
                Thread.sleep(800)
                waitForIdle()
                look("watch-on")
            }
        }
        say("UIAUDIT T3 settings: $total problem(s)")
    }
}
