package com.inksheets.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.*
import com.inksheets.core.MeshFrames
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Responsive dialog audit with fake Bluetooth and an isolated empty library. */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class TogetherRedesignTest {
    private class Radio : MeshRadio {
        var callback: ((ByteArray) -> Unit)? = null
        override fun ready() = true
        override fun start(onFrame: (ByteArray) -> Unit): Boolean { callback = onFrame; return true }
        override fun broadcast(frames: List<ByteArray>) {}
        override fun stop() { callback = null }
    }
    private class Platform(val root: File, val radio: Radio) : SheetsPlatform {
        val prefs = hashMapOf<String,String?>("sheets_library" to root.path, "sheets_companion_transport" to "BLUETOOTH")
        override val deviceId = "together-layout"
        override val deviceName = "My rehearsal tablet"
        override val audioOut: com.inksheets.ui.AudioOut? = null
        override val microphone: com.inksheets.ui.Microphone? = null
        override val startFolder = root
        override val localFolder = File(root, "local")
        override fun pref(key: String) = prefs[key]
        override fun setPref(key: String, value: String?) { prefs[key] = value }
        override fun openPart(song: Song, part: Part, file: File) {}
        override fun pageText(file: File, page: Int): String? = null
        override fun onMain(block: () -> Unit) = block()
        override fun meshRadio() = radio
    }
    @Test fun togetherDiscoveryAndLeaderControlsFitAllOrientations() {
        for ((name, width, height) in UiAudit.SIZES) {
            val radio = Radio()
            val state = SheetsState(Platform(Files.createTempDirectory("together-$name").toFile(), radio))
            try {
                runDesktopComposeUiTest(width = width, height = height) {
                    setContent { MaterialTheme { CompanionDialog(state) {} } }
                    onNodeWithContentDescription("Close").assertIsDisplayed()
                    assertTrue("$name initial dialog", UiAudit.check(this, name, "together-redesign", width, height).isEmpty())
                    runOnIdle { radio.callback?.invoke(MeshFrames.encode(MeshFrames.Beacon(123, 1, "Stage stand"))) }
                    onNodeWithText("Search nearby devices").performScrollTo().performTextInput("Stage")
                    onNodeWithText("Stage stand").performScrollTo().assertIsDisplayed()
                    assertTrue("$name discovery", UiAudit.check(this, name, "together-devices", width, height).isEmpty())
                    onNodeWithText("Search nearby devices").performScrollTo().performTextClearance()
                    onNodeWithText("Lead", useUnmergedTree = true).performScrollTo().performClick()
                    onNodeWithText("Stop leading").performScrollTo().assertIsDisplayed()
                    onNodeWithText("Edit...").performScrollTo().assertIsDisplayed()
                    MARK_COLOURS.indices.forEach { index ->
                        onNodeWithContentDescription("Message colour ${index + 1}").performScrollTo().assertIsDisplayed()
                    }
                    onNodeWithText("A message for the band").performScrollTo().performTextInput("Eyes up")
                    onNodeWithText("Send message").performScrollTo().assertIsEnabled().performClick()
                    onNodeWithText("Send message").performScrollTo().assertIsDisplayed()
                    assertTrue("$name leading", UiAudit.check(this, name, "together-leading", width, height).isEmpty())
                }
            } finally { state.companion.stopLeading(); state.companion.stopFollowing(); state.companion.endDiscovery() }
        }
    }
}
