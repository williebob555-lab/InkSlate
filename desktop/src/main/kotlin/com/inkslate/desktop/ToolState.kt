package com.inkslate.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.inkslate.core.BrushType
import com.inkslate.core.Palette
import com.inkslate.core.PenPreset
import com.inkslate.core.Tool
import com.inkslate.core.ToolConfig

/**
 * The editor's drawing settings.
 *
 * The Android [com.inkslate.ui.editor.ToolState] with one profile instead of four. The tablet
 * keeps a separate pen, finger and two barrel-button profiles because those are four physical
 * things that touch the glass and want different tools; a mouse is one, and a desktop pen reports
 * through the same events, so splitting them here would be four settings screens for one input.
 *
 * [ToolConfig] itself is shared, so what a pen *is* - brush, colour, width, opacity, smoothing,
 * pressure curve - cannot drift between the two builds. [revision] is bumped on every edit for
 * the same reason it exists on Android: the config is deliberately not Compose state, because the
 * canvas has to read it synchronously the instant a pointer lands.
 */
class ToolState {

    val active = ToolConfig()

    var revision by mutableStateOf(0)
        private set

    var presets by mutableStateOf(loadPresets())
        private set

    var activePreset by mutableStateOf(-1)

    var customColors by mutableStateOf(loadColors())
        private set

    /** True while the ruler is on the page and ink is snapping to it. */
    var rulerVisible by mutableStateOf(false)

    var tableRows by mutableStateOf(3)
    var tableCols by mutableStateOf(3)

    /**
     * A stamp chosen but not yet placed.
     *
     * Held here rather than as an editor state because the canvas has to read it synchronously
     * the instant a pointer lands, which is the same reason [active] is not Compose state.
     */
    var armedStamp: Pair<com.inkslate.core.Stamps.Kind, com.inkslate.core.Stamps.StampOptions>? =
        null

    fun edit(block: (ToolConfig) -> Unit) {
        block(active)
        revision++
        persist()
    }

    fun snapshot() = active.snapshot()

    // ---- presets -------------------------------------------------------------

    fun selectPreset(index: Int) {
        val p = presets.getOrNull(index) ?: return
        active.selectBrush(p.brush)
        active.color = p.color
        active.strokeWidth = p.width
        active.opacity = p.opacity
        active.tool = Tool.DRAW
        activePreset = index
        revision++
        persist()
    }

    /** Step through the saved pens, which is what a preset row is for on a keyboard. */
    fun cyclePreset() {
        if (presets.isEmpty()) return
        selectPreset((activePreset + 1).mod(presets.size))
    }

    fun addPreset() {
        presets = presets + PenPreset(
            active.brush, active.color, active.strokeWidth, active.opacity
        )
        activePreset = presets.lastIndex
        savePresets()
    }

    /** Overwrite one preset with whatever is in hand - the desktop's right-click on a dot. */
    fun savePresetFromCurrent(index: Int) {
        if (index !in presets.indices) return
        presets = presets.toMutableList().also {
            it[index] = PenPreset(active.brush, active.color, active.strokeWidth, active.opacity)
        }
        activePreset = index
        savePresets()
    }

    fun removePreset(index: Int) {
        if (index !in presets.indices || presets.size <= 1) return
        presets = presets.toMutableList().also { it.removeAt(index) }
        if (activePreset >= presets.size) activePreset = -1
        savePresets()
    }

    fun rememberColor(c: Int) {
        if (c in customColors) return
        customColors = (listOf(c) + customColors).take(18)
        DesktopPrefs.put(K_COLORS, customColors.joinToString(","))
    }

    // ---- persistence ---------------------------------------------------------

    /**
     * Written as one line per pen rather than JSON.
     *
     * There is no JSON library on this side of the app that is not the document format's own, and
     * reaching for that to store five pens would tie the toolbar's storage to the thing that
     * syncs between devices. This file never leaves the machine.
     */
    private fun savePresets() {
        DesktopPrefs.putList(
            K_PRESETS,
            presets.map { "${it.brush.name}|${it.color}|${it.width}|${it.opacity}" }
        )
    }

    private fun loadPresets(): List<PenPreset> {
        val lines = DesktopPrefs.getList(K_PRESETS)
        if (lines.isEmpty()) return PenPreset.defaults
        return lines.mapNotNull { line ->
            runCatching {
                val parts = line.split("|")
                PenPreset(
                    brush = BrushType.valueOf(parts[0]),
                    color = parts[1].toInt(),
                    width = parts[2].toFloat(),
                    opacity = parts.getOrNull(3)?.toFloatOrNull() ?: 1f
                )
            }.getOrNull()
        }.ifEmpty { PenPreset.defaults }
    }

    private fun loadColors(): List<Int> =
        DesktopPrefs.get(K_COLORS).orEmpty().split(",").mapNotNull { it.trim().toIntOrNull() }

    private fun persist() {
        DesktopPrefs.put(K_TOOL, active.tool.name)
        DesktopPrefs.put(K_BRUSH, active.brush.name)
        DesktopPrefs.put(K_COLOR, active.color.toString())
        DesktopPrefs.put(K_WIDTH, active.strokeWidth.toString())
        DesktopPrefs.put(K_ERASER, active.eraserRadius.toString())
        DesktopPrefs.put(K_ERASER_MODE, active.eraserMode.name)
        DesktopPrefs.put(K_SMOOTHING, active.smoothing.toString())
        DesktopPrefs.put(K_TEXT_SIZE, active.textSize.toString())
        DesktopPrefs.put(K_OPACITY, active.opacity.toString())
        DesktopPrefs.put(K_DASH, active.dash.name)
        DesktopPrefs.put(K_FILL, active.fillStyle.name)
        DesktopPrefs.put(K_DYNAMICS, active.dynamics.toString())
    }

    init {
        runCatching {
            DesktopPrefs.get(K_TOOL)?.let { active.tool = Tool.valueOf(it) }
            DesktopPrefs.get(K_BRUSH)?.let { active.brush = BrushType.valueOf(it) }
            DesktopPrefs.get(K_COLOR)?.toIntOrNull()?.let { active.color = it }
            DesktopPrefs.get(K_WIDTH)?.toFloatOrNull()?.let { active.strokeWidth = it }
            DesktopPrefs.get(K_ERASER)?.toFloatOrNull()?.let { active.eraserRadius = it }
            DesktopPrefs.get(K_ERASER_MODE)?.let {
                active.eraserMode = com.inkslate.core.EraserMode.valueOf(it)
            }
            DesktopPrefs.get(K_SMOOTHING)?.toFloatOrNull()?.let { active.smoothing = it }
            DesktopPrefs.get(K_TEXT_SIZE)?.toFloatOrNull()?.let { active.textSize = it }
            DesktopPrefs.get(K_OPACITY)?.toFloatOrNull()?.let { active.opacity = it }
            DesktopPrefs.get(K_DASH)?.let { active.dash = com.inkslate.core.DashStyle.valueOf(it) }
            DesktopPrefs.get(K_FILL)?.let {
                active.fillStyle = com.inkslate.core.FillStyle.valueOf(it)
            }
            DesktopPrefs.get(K_DYNAMICS)?.toFloatOrNull()?.let { active.dynamics = it }
        }
        // A tool that cannot be resumed sensibly. Landing in a half-finished capture or with the
        // eraser in hand because that is how the last session ended is a poor way to open a page.
        if (active.tool == Tool.REGION) active.tool = Tool.DRAW
    }

    private companion object {
        const val K_PRESETS = "tool_presets"
        const val K_COLORS = "tool_colors"
        const val K_TOOL = "tool_tool"
        const val K_BRUSH = "tool_brush"
        const val K_COLOR = "tool_color"
        const val K_WIDTH = "tool_width"
        const val K_ERASER = "tool_eraser"
        const val K_ERASER_MODE = "tool_eraser_mode"
        const val K_SMOOTHING = "tool_smoothing"
        const val K_TEXT_SIZE = "tool_text_size"
        const val K_OPACITY = "tool_opacity"
        const val K_DASH = "tool_dash"
        const val K_FILL = "tool_fill"
        const val K_DYNAMICS = "tool_dynamics"
    }
}

/** The palette the toolbar shows: your own colours first, then the shared set. */
fun ToolState.swatches(): List<Int> = (customColors + Palette.COLORS).distinct()
