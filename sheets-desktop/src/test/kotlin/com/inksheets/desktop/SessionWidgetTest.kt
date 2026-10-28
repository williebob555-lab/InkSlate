package com.inksheets.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.inksheets.core.CompanionLink
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalTestApi::class)
class SessionWidgetTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun state(name: String): SheetsState {
        val root = tmp.newFolder(name)
        val platform = object : SheetsPlatform {
            val prefs = mutableMapOf("sheets_library" to root.absolutePath)
            val radio = object : MeshRadio {
                override fun ready() = true
                override fun start(onFrame: (ByteArray) -> Unit) = true
                override fun broadcast(frames: List<ByteArray>) {}
                override fun stop() {}
            }
            override val startFolder = root
            override val deviceId = "session-$name"
            override val deviceName = "Rehearsal tablet"
            override val localFolder = File(root, ".local")
            override val microphone: Microphone? = null
            override val audioOut: AudioOut? = null
            override fun pref(key: String) = prefs[key]
            override fun setPref(key: String, value: String?) { if (value == null) prefs.remove(key) else prefs[key] = value }
            override fun onMain(block: () -> Unit) = block()
            override fun pageText(file: File, page: Int): String? = null
            override fun openPart(song: Song, part: Part, file: File) {}
            override fun meshRadio(): MeshRadio = radio
        }
        return SheetsState(platform).apply { companion.chooseTransport(ConnectionTransport.BLUETOOTH) }
    }
    @Test fun `leading shortcuts toggle actual sharing and open message entry without a menu`() {
        val state = state("lead")
        assertTrue(state.companion.lead())
        try {
            runDesktopComposeUiTest(width = 390, height = 844) {
                setContent { MaterialTheme { Column(Modifier.width(236.dp).height(160.dp)) { SessionWidget(state) } } }
                waitForIdle()
                onNodeWithText("Leading the group").assertIsDisplayed()
                val before = state.companion.shareInk
                onNodeWithContentDescription("Marks").performClick()
                runOnIdle { assertEquals(!before, state.companion.shareInk) }
                onNodeWithContentDescription("Message").performClick()
                runOnIdle { assertTrue(state.groupMessageOpen) }
                val issues = UiAudit.check(this, "phone", "session-leading", 390, 844)
                assertTrue(issues.toString(), issues.isEmpty())
            }
        } finally { state.companion.stopLeading() }
    }
    @Test fun `active group shortcuts stay visible above short Studio scrolling on either side`() {
        for (side in StudioRailSide.entries) for (leading in listOf(true, false)) {
            val state = state("short-$side-$leading")
            state.homeInFront = false; state.stripCollapsed = false
            state.shelf.chooseView(MusicDockView.STUDIO); state.shelf.chooseRailSide(side)
            val savedOrder = state.shelf.order.toList()
            if (leading) assertTrue(state.companion.lead())
            else state.companion.followLeader(CompanionLink.Leader("Director", emptyList(), bluetoothSession = 42)) {}
            try {
                runDesktopComposeUiTest(width = 640, height = 360) {
                    setContent { MaterialTheme { Box(Modifier.fillMaxSize()) { ActionStrip(state) } } }
                    waitForIdle()
                    if (leading) {
                        onNodeWithContentDescription("Quick group message").assertIsDisplayed()
                        onNodeWithContentDescription("Quick share marks").assertIsDisplayed().performClick()
                        onNodeWithContentDescription("Quick group settings").assertIsDisplayed()
                    } else {
                        onNodeWithContentDescription("Quick return to leader").assertIsDisplayed()
                        onNodeWithContentDescription("Quick change following mode").assertIsDisplayed()
                        onNodeWithContentDescription("Quick leave group").assertIsDisplayed()
                    }
                    runOnIdle { assertEquals("Pinned actions must not rewrite custom widget order", savedOrder, state.shelf.order) }
                    val issues = UiAudit.check(this, "phone-land-$side", "session-${if (leading) "lead" else "follow"}-pinned", 640, 360)
                    assertTrue(issues.toString(), issues.isEmpty())
                }
            } finally { state.companion.stopLeading(); state.companion.stopFollowing() }
        }
    }
    @Test fun `following has readable mode cycling and one tap Leave across orientations`() {
        for ((name, width, height) in listOf(Triple("phone",390,844), Triple("phone-land",844,390), Triple("tablet",800,1280), Triple("tablet-land",1280,800))) {
            val state = state(name)
            state.companion.followLeader(CompanionLink.Leader("Director", emptyList(), bluetoothSession = 42)) {}
            state.companion.follow = CompanionLink.Follow.SONG_ONLY
            try {
                runDesktopComposeUiTest(width = width, height = height) {
                    setContent { MaterialTheme { Column(Modifier.width(236.dp).height(160.dp)) { SessionWidget(state) } } }
                    waitForIdle()
                    onNodeWithText("Song only").assertIsDisplayed()
                    onNodeWithContentDescription("Change following mode").performClick()
                    runOnIdle { assertEquals(CompanionLink.Follow.AUTO, state.companion.follow) }
                    onNodeWithText("Auto").assertIsDisplayed()
                    onNodeWithContentDescription("Leave").performClick()
                    runOnIdle { assertNull(state.companion.following) }
                }
            } finally { state.companion.stopFollowing() }
        }
    }
}
