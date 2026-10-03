package com.inksheets.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.inksheets.core.ControlEvent
import com.inksheets.core.ControllerInput
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.core.podgo.PodGoEvents
import com.inksheets.ui.AudioOut
import com.inksheets.ui.Microphone
import com.inksheets.ui.SheetsPlatform
import com.inksheets.ui.SheetsSettings
import com.inksheets.ui.SheetsState
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The POD Go's picture looked over at every size each way up (see [UiAudit]): empty, listening for
 * a footswitch, the switch set and given an action, the actions to choose from, the pedal moving.
 * -Dinksheets.uiaudit=1 [-Dinksheets.uiaudit.sizes=phone,phone-land]
 */
@OptIn(ExperimentalTestApi::class)
class UiAuditControllersTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** A POD Go that says what the test has it say. */
    private class FakePodGo : ControllerInput {
        var send: ((ControlEvent) -> Unit)? = null
        override fun start(onEvent: (ControlEvent) -> Unit, onDevices: (List<String>) -> Unit) { send = onEvent; onDevices(listOf(PodGoEvents.DEVICE)) }
        override fun stop() { send = null }
    }

    private class Platform(root: File, val pod: FakePodGo) : SheetsPlatform {
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
        override val controllers: ControllerInput get() = pod
    }

    @Test
    fun `the POD Go picture at every size`() {
        assumeTrue(System.getProperty("inksheets.uiaudit") != null)
        val only = System.getProperty("inksheets.uiaudit.sizes")?.split(",")?.toSet()
        var total = 0
        for ((size, w, h) in UiAudit.SIZES) {
            if (only != null && size !in only) continue
            val pod = FakePodGo()
            val state = SheetsState(Platform(tmp.newFolder("Music-$size"), pod))
            state.controllers.start()
            state.change { ensureSong("Hey Baby"); addSetlist("Football - home") }
            runDesktopComposeUiTest(width = w, height = h) {
                setContent { MaterialTheme { Surface { Column(Modifier.verticalScroll(rememberScrollState())) { SheetsSettings(state) } } } }
                fun look(scene: String, vararg required: String) { waitForIdle(); total += UiAudit.check(this, size, "podgo-$scene", w, h, required = required.toList()).size }
                fun click(text: String) = onAllNodes(hasText(text, substring = true)).onLast().apply { runCatching { performScrollTo() } }.performClick()
                fun say(vararg events: ControlEvent) { for (e in events) { pod.send!!(e); Thread.sleep(5) }; Thread.sleep(50); waitForIdle() }
                val usb = PodGoEvents.DEVICE
                look("settings", "POD Go: set up on its picture...")
                click("POD Go: set up on its picture...")
                look("empty", "FS1", "FS6", "MODE", "TAP", "EXP", "VOL", "TOE", "Close")
                // FS1 tapped and pressed: the preset chosen, then what follows it.
                click("FS1")
                look("listening-quiet", "Press FS1", "Back", "Keep")
                say(ControlEvent(usb, ControlEvent.PROGRAM, 1, 2, 2), ControlEvent(usb, ControlEvent.CC, 16, 4, 127), ControlEvent(usb, ControlEvent.CC, 4, 16, 120))
                look("listening", "Back", "Keep")
                click("Keep")
                look("spot", "Take it off", "Learn again", "Back")
                click("Give it an action...")
                look("actions", "Find", "Close")
                click("Next page")
                look("spot-set", "Back")
                assertEquals(1, state.controllers.bindingsOf("fs1").size)
                click("Back")
                // FS2: a song of this device's library, chosen now; then a tempo of its own, set in the editor.
                click("FS2")
                say(ControlEvent(usb, ControlEvent.PROGRAM, 1, 3, 3))
                click("Keep")
                click("Give it an action...")
                look("actions-switch", "Find")
                for (o in listOf("Go to a song you choose now", "Play a setlist you choose now", "Go to a bookmark you choose now", "A sequence"))
                    assertEquals(o, true, onAllNodes(hasText(o, substring = true)).fetchSemanticsNodes().isNotEmpty())
                click("Go to a song you choose now")
                look("song-pick", "Hey Baby")
                click("Hey Baby")
                assertEquals("Hey Baby", state.controllers.bindingsOf("fs2").single().action.title)
                click("Give it another action...")
                click("Set the tempo to...")
                look("editor", "Cancel", "Add")
                click("Add")
                state.controllers.bindingsOf("fs2").last().let { assertEquals(120.0, it.action.value); assertEquals(false, it.continuous) }
                click("Back")
                // The pedal: put on, given the tempo, and moved.
                click("EXP")
                say(ControlEvent(usb, ControlEvent.CC, 2, 8, 20), ControlEvent(usb, ControlEvent.CC, 2, 8, 90))
                click("Keep")
                click("Give it an action...")
                look("actions-pedal", "Tempo, 40 to 240")
                click("Tempo, 40 to 240")
                click("Back")
                click("TOE")
                say(ControlEvent(usb, ControlEvent.CC, 5, 8, 127))
                click("Keep")
                click("Give it an action...")
                click("Next page")
                look("toggle", "Back")   // (the choices under the picture, scrolled to on a phone on its side)
                click("When it lights")
                assertEquals(false, state.controllers.bindingsOf("toe").single().everyMessage)
                click("Back")
                say(ControlEvent(usb, ControlEvent.CC, 2, 8, 64), ControlEvent(usb, ControlEvent.PROGRAM, 1, 2, 2))
                look("lit", "Close")
                assertEquals(true, state.controllers.bindingsOf("exp").single().continuous)
            }
        }
        println("UIAUDIT podgo: $total problem(s)")
    }
}
