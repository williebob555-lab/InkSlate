package com.inkslate.core

import java.io.File
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignConfigTest {
    @Test fun `omitted sections use the bundled design defaults`() {
        assertEquals(DesignConfig(), DesignConfig.parse("{}"))
        val partial = DesignConfig.parse("""{"revision":2,"mixer":{"visibilityHeightDp":26}}""")
        assertEquals(2, partial.revision)
        assertEquals(26f, partial.mixer.visibilityHeightDp, 0f)
        assertEquals(DesignConfig.Wheel(), partial.wheel)
        assertEquals(DesignConfig.Mixer().actionHeightDp, partial.mixer.actionHeightDp, 0f)
    }

    @Test fun `unsupported schemas and misspelled keys are rejected before install`() {
        listOf(
            """{"schemaVersion":2}""",
            """{"schemaVersion":0}""",
            """{"executable":"downloaded code"}""",
            """{"wheel":{"mainRaduisDp":60}}"""
        ).forEach { json -> assertThrows("Should reject $json", Exception::class.java) { DesignConfig.parse(json) } }
    }

    @Test fun `nonfinite values and invalid geometry are rejected`() {
        val defaults = DesignConfig()
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f, 1000f).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                defaults.copy(mixer = defaults.mixer.copy(minimumFaderHeightDp = value)).validated()
            }
        }
        assertThrows(Exception::class.java) { DesignConfig.parse("""{"mixer":{"actionHeightDp":NaN}}""") }
        assertThrows(IllegalArgumentException::class.java) {
            defaults.copy(chrome = defaults.chrome.copy(tabMinWidthDp = 200f, tabMaxWidthDp = 100f)).validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            defaults.copy(chrome = defaults.chrome.copy(
                tabMinWidthDp = 96f, tabMaxWidthDp = 96f, tabContentInsetDp = 24f,
                tabHeightDp = 64f, tabShoulderFraction = .4f
            )).validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            defaults.copy(wheel = defaults.wheel.copy(activationRadiusDp = 80f, mainRadiusDp = 54f)).validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            defaults.copy(scribble = defaults.scribble.copy(minTraversals = 1)).validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            defaults.copy(scribble = defaults.scribble.copy(minimumSpeedDpPerSecond = 0f)).validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            defaults.copy(wheel = defaults.wheel.copy(layerTrackStartDp = 0f)).validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            defaults.copy(wheel = defaults.wheel.copy(layerLaneHalfAngleDegrees = Float.NaN)).validated()
        }
    }

    @Test fun `palettes require supported colors and a usable number of choices`() {
        val defaults = DesignConfig()
        listOf(emptyList(), listOf("#112233"), List(17) { "#112233" }, listOf("#123", "#FFFFFF"),
            listOf("red", "#FFFFFF"), listOf("#GG1122", "#FFFFFF")).forEach { palette ->
            assertThrows(IllegalArgumentException::class.java) {
                defaults.copy(wheel = defaults.wheel.copy(palette = palette)).validated()
            }
        }
        assertEquals(0xff112233.toInt(), DesignConfig.argb("#112233"))
        assertEquals(0x80112233.toInt(), DesignConfig.argb("#80112233"))
    }

    @Test fun `invalid metadata and oversized downloads are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { DesignConfig.parse("""{"revision":-1}""") }
        assertThrows(IllegalArgumentException::class.java) { DesignConfig(summary = "x".repeat(401)).validated() }
        assertThrows(IllegalArgumentException::class.java) { DesignConfig.parse(" ".repeat(65537)) }
    }

    @Test fun `published config validates roundtrips and matches bundled design values`() {
        // Gradle runs in :core; ancestor search also supports running this test from the repo root.
        val root = generateSequence(File(".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "config/inkslate-test.json").isFile }
            ?: error("Could not locate config/inkslate-test.json above the test working directory")
        val published = DesignConfig.parse(File(root, "config/inkslate-test.json").readText())
        assertTrue(published.revision > 0)
        assertEquals(published, DesignConfig.parse(DesignConfig.json.encodeToString(published)))
        assertEquals(DesignConfig(), published.copy(revision = 0, summary = DesignConfig().summary))
    }
}
