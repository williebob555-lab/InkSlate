package com.inksheets.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.ui.AudioOut
import com.inksheets.ui.Microphone
import com.inksheets.ui.SheetsPlatform
import com.inksheets.ui.SheetsSettings
import com.inksheets.ui.SheetsState
import com.inksheets.ui.WatchFlicks
import com.inksheets.ui.WatchLink
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Turning pages with the watch, looked over at every size each way up (see [UiAudit]): the
 * settings, naming a calibration, each step of calibrating, and how it went.
 * -Dinksheets.uiaudit=1 [-Dinksheets.uiaudit.sizes=phone,phone-land]
 */
@OptIn(ExperimentalTestApi::class)
class UiAuditWatchTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Platform(root: File, val fake: WatchLink) : SheetsPlatform {
        private val prefs = HashMap<String, String?>().apply { put("sheets_library", root.absolutePath) }
        override val deviceName = "Audit"
        override val deviceId = "audit"
        override val startFolder = root
        override fun pref(key: String) = prefs[key]
        override fun setPref(key: String, value: String?) { prefs[key] = value }
        override fun openPart(song: Song, part: Part, file: File) {}
        override fun pageText(file: File, page: Int): String? = null
        override val audioOut: AudioOut? = null
        override val microphone: Microphone? = null
        override fun onMain(block: () -> Unit) = javax.swing.SwingUtilities.invokeLater(block)
        override val localFolder: File get() = File(startFolder.parentFile, "local-$deviceId")
        override val watch: WatchLink get() = fake
    }

    @After
    fun normalTime() { WatchFlicks.timeScale = 1.0 }

    @Test
    fun `the watch settings and calibrating at every size`() {
        assumeTrue(System.getProperty("inksheets.uiaudit") != null)
        val only = System.getProperty("inksheets.uiaudit.sizes")?.split(",")?.toSet()
        WatchFlicks.timeScale = 0.02
        var total = 0
        for ((size, w, h) in UiAudit.SIZES) {
            if (only != null && size !in only) continue
            val state = SheetsState(Platform(tmp.newFolder("Music-$size"), FakeWatch(0.02)))
            runDesktopComposeUiTest(width = w, height = h) {
                setContent { MaterialTheme { Surface { Column(Modifier.verticalScroll(rememberScrollState())) { SheetsSettings(state) } } } }
                fun look(scene: String, vararg required: String) { waitForIdle(); total += UiAudit.check(this, size, "watch-$scene", w, h, required = required.toList()).size }
                fun click(text: String) = onAllNodes(hasText(text, substring = true)).onLast().apply { runCatching { performScrollTo() } }.performClick()
                fun settle(ok: () -> Boolean) { val until = System.currentTimeMillis() + 20_000; while (!ok() && System.currentTimeMillis() < until) { Thread.sleep(20); waitForIdle() } }

                onAllNodes(hasText("Turn pages with a flick", substring = true)).onLast().performScrollTo()
                look("off", "Turn pages with a flick of the watch (experimental)")
                click("Turn pages with a flick of the watch")
                settle { state.watch.hello != null }
                onAllNodes(hasText("New calibration", substring = true)).onLast().performScrollTo()
                look("on", "New calibration...")
                click("New calibration...")
                look("naming", "Cancel", "Next")
                click("Bass Guitar")
                onAllNodes(hasSetTextAction()).onLast().performScrollTo()
                look("naming-chosen", "Cancel", "Next")
                onAllNodes(hasSetTextAction()).onLast().performTextClearance()
                onAllNodes(hasSetTextAction()).onLast().performTextInput("Electric bass")
                click("Next")
                look("starting", "Stop")
                val c = state.watch.calibration!!
                settle { c.phase == "done" }
                // Each step as the player sees it (the calibration is over; only its screen is shown again).
                c.phase = "playing"; look("playing", "Play normally", "Stop")
                c.phase = "next"; look("cue-next", "Flick: NEXT ▶", "Stop")
                c.phase = "back"; look("cue-back", "Flick: ◀ BACK", "Stop")
                c.phase = "done"; look("done", "Calibrate again", "Done")
                click("Done")
                onAllNodes(hasText("Calibrate more", substring = true)).onLast().performScrollTo()
                look("calibrated", "Electric bass", "Calibrate more")
                click("Instruments...")
                look("instruments", "Cancel", "Keep")
                click("String Bass")
                click("Keep")
                onAllNodes(hasText("New calibration", substring = true)).onLast().performScrollTo()
                look("calibrated-end", "New calibration...")
                org.junit.Assert.assertEquals(listOf("bass-guitar", "string-bass"), state.watch.instrumentsOf("Electric bass"))
            }
        }
        println("UIAUDIT watch: $total problem(s)")
    }
}
