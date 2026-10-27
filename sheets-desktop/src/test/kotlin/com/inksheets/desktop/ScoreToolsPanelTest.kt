package com.inksheets.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.core.omr.*
import com.inksheets.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalTestApi::class)
class ScoreToolsPanelTest {
    @get:Rule val tmp = TemporaryFolder()
    private class Platform(override val startFolder: File) : SheetsPlatform {
        private val prefs = mutableMapOf("sheets_library" to startFolder.absolutePath)
        override val deviceId = "score-panel-test"
        override val deviceName = "Score panel test"
        override val localFolder = File(startFolder, "local")
        override fun pref(key: String) = prefs[key]
        override fun setPref(key: String, value: String?) { if (value == null) prefs.remove(key) else prefs[key] = value }
        override fun openPart(song: Song, part: Part, file: File) {}
        override fun pageText(file: File, page: Int): String? = null
        override val audioOut: AudioOut? = null
        override val microphone: Microphone? = null
        override fun onMain(block: () -> Unit) = block()
    }
    private fun state(): SheetsState {
        val folder = tmp.newFolder()
        val path = File(folder, "score.pdf").apply { writeText("%PDF-1.4\nscore\n%%EOF") }.absolutePath
        return SheetsState(Platform(folder)).apply { currentPath = path; pageShown = 0 to 2; ScoreTools.install(this) }
    }
    private val reading get() = Score(listOf(Measure(1, 0, 0, Box(20, 40, 240, 80), 10f,
        Clef.TREBLE, Key(0), TimeSig(4, 4), emptyList())), pages = 2, pageWidths = listOf(800, 800), readPages = listOf(0))

    @Test fun `unread page offers direct reading while page tools remain disabled`() {
        val state = state().apply { pageShown = 1 to 2 }
        ScoreTools.scoreSource = { reading }
        try {
            runDesktopComposeUiTest(width = 1000, height = 800) {
                setContent { MaterialTheme { ScoreToolsPanel(state) {} } }
                onNodeWithText("Page 2 is not read yet").assertIsDisplayed()
                onNodeWithTag("score-action-Read page 2").assertIsEnabled()
                onNodeWithTag("score-action-Read whole part").assertIsEnabled()
                onNodeWithTag("score-action-Review reading").assertIsNotEnabled()
                onNodeWithTag("score-action-Mask brush").assertIsNotEnabled()
                onNodeWithTag("score-action-Select bars").assertIsNotEnabled()
            }
        } finally { ScoreTools.scoreSource = null; ScoreTools.close(state) }
    }

    @Test fun `selecting a page tool closes directory without discarding the tool`() {
        val state = state()
        ScoreTools.scoreSource = { reading }
        var closes = 0
        try {
            runDesktopComposeUiTest(width = 1000, height = 800) {
                setContent { MaterialTheme { ScoreToolsPanel(state) { closes++ } } }
                onNodeWithTag("score-action-Select bars").performClick()
                runOnIdle {
                    assertEquals(1, closes)
                    assertEquals(ScoreTools.Tool.SELECT, ScoreTools.tool)
                    assertTrue(ScoreTools.open)
                }
                onNodeWithText("Done").performClick()
                runOnIdle {
                    assertEquals(2, closes)
                    assertEquals(ScoreTools.Tool.SELECT, ScoreTools.tool)
                }
            }
        } finally { ScoreTools.scoreSource = null; ScoreTools.close(state) }
    }

    @Test fun `score tool directory reflows and scrolls to exports on all supported sizes`() {
        val state = state()
        ScoreTools.scoreSource = { reading }
        val only = System.getProperty("inksheets.panels.sizes")?.split(',')?.toSet()
        try {
            for ((name, width, height) in UiAudit.SIZES) {
                if (only != null && name !in only) continue
                runDesktopComposeUiTest(width = width, height = height) {
                    setContent { MaterialTheme { ScoreToolsPanel(state) {} } }
                    onNodeWithText("Done").assertIsDisplayed()
                    onNodeWithContentDescription("Close").assertIsDisplayed()
                    val issues = UiAudit.check(this, name, "score-directory", width, height, required = listOf("Done"))
                    assertTrue("$name: $issues", issues.isEmpty())
                    onNodeWithTag("score-action-PDF for another instrument").performScrollTo().assertIsDisplayed().assertIsEnabled()
                    onNodeWithTag("score-action-Read part again").performScrollTo().assertIsDisplayed().assertIsEnabled()
                    onNodeWithText("Done").assertIsDisplayed()
                    val exportIssues = UiAudit.check(this, name, "score-directory-export", width, height, required = listOf("Done"))
                    assertTrue("$name export: $exportIssues", exportIssues.isEmpty())
                }
            }
        } finally { ScoreTools.scoreSource = null; ScoreTools.close(state) }
    }
}
