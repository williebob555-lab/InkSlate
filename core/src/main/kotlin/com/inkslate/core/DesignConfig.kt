package com.inkslate.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Data only: a downloaded design can select supported values, never execute code. */
@Serializable
data class DesignConfig(
    val schemaVersion: Int = 1,
    val revision: Int = 0,
    val summary: String = "Built-in design",
    val chrome: Chrome = Chrome(),
    val mixer: Mixer = Mixer(),
    val wheel: Wheel = Wheel(),
    val scribble: Scribble = Scribble(),
    val theme: Theme = Theme()
) {
    @Serializable data class Chrome(
        val tabMinWidthDp: Float = 96f,
        val tabMaxWidthDp: Float = 220f,
        val tabContentInsetDp: Float = 8f,
        val floatingTopGapDp: Float = 4f,
        val tabHeightDp: Float = 44f,
        val tabShoulderFraction: Float = .32f,
        val selectedTint: Float = .24f
    )
    @Serializable data class Mixer(
        val visibilityHeightDp: Float = 24f,
        val actionHeightDp: Float = 28f,
        val minimumFaderHeightDp: Float = 48f
    )
    @Serializable data class Wheel(
        val actionSizeDp: Float = 40f,
        val mainRadiusDp: Float = 54f,
        val activationRadiusDp: Float = 44f,
        val colorRadiusDp: Float = 108f,
        val colorDotDp: Float = 27f,
        val sizeTrackStartDp: Float = 74f,
        val sizeTrackLengthDp: Float = 190f,
        val colorArcDegrees: Float = 58f,
        val submenuEntryDegrees: Float = 15f,
        val layerTrackStartDp: Float = 74f,
        val layerTrackLengthDp: Float = 120f,
        val layerEntryAngleDegrees: Float = 45f,
        val layerLaneHalfAngleDegrees: Float = 22f,
        val palette: List<String> = listOf("#111827", "#2563EB", "#7C3AED", "#DC2626", "#F59E0B", "#16A34A", "#0891B2", "#FDE047", "#FFFFFF")
    )
    @Serializable data class Scribble(
        val maxCorridorRatio: Float = .85f,
        val minTraversals: Int = 3,
        val minSpan: Float = 4f,
        val minTravelRatio: Float = 3.4f,
        val allowDrifting: Boolean = true,
        val minimumSpeedDpPerSecond: Float = 240f
    )
    @Serializable data class Theme(
        val darkAccent: String = "#3B82F6",
        val lightAccent: String = "#1D4ED8",
        val darkBackground: String = "#14171B",
        val lightBackground: String = "#F6F7F9"
    )

    /** Reject a whole candidate instead of silently installing a partly broken design. */
    fun validated(): DesignConfig = apply {
        require(schemaVersion == 1) { "This design requires a different app build." }
        require(revision >= 0 && summary.length <= 400)
        fun range(value: Float, low: Float, high: Float) = require(value.isFinite() && value in low..high) { "Design value outside supported range: $value" }
        with(chrome) {
            range(tabMinWidthDp, 80f, 240f); range(tabMaxWidthDp, tabMinWidthDp, 400f)
            range(tabContentInsetDp, 4f, 24f); range(floatingTopGapDp, 0f, 16f)
            range(tabHeightDp, 40f, 64f); range(tabShoulderFraction, .1f, .4f); range(selectedTint, 0f, 1f)
            require(tabMaxWidthDp >= 48f + 2f * (tabContentInsetDp + tabHeightDp * tabShoulderFraction)) { "Tabs are too narrow for their icons and shoulders." }
        }
        with(mixer) {
            range(visibilityHeightDp, 20f, 32f); range(actionHeightDp, 24f, 36f); range(minimumFaderHeightDp, 40f, 100f)
        }
        with(wheel) {
            range(actionSizeDp, 28f, 64f); range(mainRadiusDp, 40f, 100f); range(activationRadiusDp, 20f, mainRadiusDp)
            range(colorRadiusDp, mainRadiusDp + 20f, 200f); range(colorDotDp, 16f, 40f)
            range(sizeTrackStartDp, activationRadiusDp, 150f); range(sizeTrackLengthDp, 60f, 300f)
            range(colorArcDegrees, 20f, 90f); range(submenuEntryDegrees, 5f, 35f)
            range(layerTrackStartDp, activationRadiusDp, 150f); range(layerTrackLengthDp, 60f, 240f)
            range(layerEntryAngleDegrees, -180f, 180f); range(layerLaneHalfAngleDegrees, 5f, 35f)
            require(palette.size in 2..16); palette.forEach(::argb)
        }
        with(scribble) {
            range(maxCorridorRatio, .3f, 2f); require(minTraversals in 2..8)
            range(minSpan, 2f, 30f); range(minTravelRatio, 2f, 10f)
            range(minimumSpeedDpPerSecond, 80f, 2000f)
        }
        with(theme) { listOf(darkAccent, lightAccent, darkBackground, lightBackground).forEach(::argb) }
    }

    companion object {
        val json = Json { ignoreUnknownKeys = false; encodeDefaults = true; prettyPrint = true }
        @Volatile var current = DesignConfig()
            private set
        fun install(config: DesignConfig) { current = config.validated() }
        fun parse(text: String): DesignConfig {
            require(text.length <= 65536) { "Design file is too large." }
            return json.decodeFromString<DesignConfig>(text).validated()
        }
        fun argb(hex: String): Int {
            require(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?").matches(hex)) { "Expected #RRGGBB or #AARRGGBB." }
            return (if (hex.length == 7) 0xff000000L or hex.drop(1).toLong(16) else hex.drop(1).toLong(16)).toInt()
        }
    }
}
