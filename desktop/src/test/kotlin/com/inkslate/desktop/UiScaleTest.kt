package com.inkslate.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The size setting is Compose's density, set once around the whole window: everything inside it
 * - dialogs and menus too, which are drawn in layers of their own - must come out at that size.
 */
@OptIn(ExperimentalTestApi::class)
class UiScaleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `dialogs and menus are drawn at the chosen size too`() = runDesktopComposeUiTest(width = 1200, height = 900) {
        var widths = mapOf<String, Float>()
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1.5f)) {
                Box(Modifier.size(100.dp).testTag("page"))
                DropdownMenu(expanded = true, onDismissRequest = {}) {
                    DropdownMenuItem(text = { Box(Modifier.size(100.dp).testTag("menu")) }, onClick = {})
                }
                AlertDialog(
                    onDismissRequest = {},
                    confirmButton = { TextButton(onClick = {}) { Text("OK") } },
                    text = { Box(Modifier.size(100.dp).testTag("dialog")) }
                )
            }
        }
        waitForIdle()
        // In pixels: at 1.5 a 100 dp box is 150.
        widths = listOf("page", "menu", "dialog").associateWith { tag ->
            onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.width
        }
        println("widths at 150%: $widths (test density ${density.density})")
        widths.forEach { (tag, w) -> assertEquals("$tag: $widths", 150f * density.density, w, 1f) }
    }

    @Test
    fun `the desktop's scale is read from Plasma's settings`() {
        val json = tmp.newFile("kwinoutputconfig.json").apply {
            writeText("""[{"data":[{"allowSdrSoftwareBrightness":true,"scale":1.5,"transform":"Normal"}],"name":"outputs"}]""")
        }
        assertEquals(1.5, UiScale.kwinOutputScale(json)!!, 0.001)
        val kdeglobals = tmp.newFile("kdeglobals").apply { writeText("[General]\nfoo=1\n\n[KScreen]\nScaleFactor=1.25\nScreenScaleFactors=eDP-1=1.25;\n") }
        assertEquals(1.25, UiScale.iniValue(kdeglobals, "KScreen", "ScaleFactor")!!, 0.001)
    }
}
