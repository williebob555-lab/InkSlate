package com.inkslate.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.inkslate.core.BrushType
import com.inkslate.core.InputMode
import com.inkslate.core.Palette
import com.inkslate.core.PenPreset
import com.inkslate.core.Tool
import com.inkslate.core.ToolConfig

/**
 * The editor's drawing settings, held as separate profiles for each thing that can touch the page.
 *
 * The Android `ToolState`: a pen, a finger, and one for each stylus barrel button. Whichever
 * device last touched the glass is the one being edited, so writing with the pen and highlighting
 * with a finger needs no settings change in between - and a barrel button is a whole second pen,
 * with its own tool, colour and width, reachable without putting anything down.
 *
 * The button profiles stay out of sight until a pen that actually has buttons reports one, so a
 * machine with only a mouse never shows a control that cannot do anything.
 *
 * [ToolConfig] itself is shared, so what a pen *is* - brush, colour, width, opacity, smoothing,
 * pressure curve - cannot drift between the two builds. [revision] is bumped on every edit for
 * the same reason it exists on Android: the config is deliberately not Compose state, because the
 * canvas has to read it synchronously the instant a pointer lands.
 */
class ToolState {

    val pen = ToolConfig()
    val touch = ToolConfig(brush = BrushType.HIGHLIGHTER, strokeWidth = 15f, tool = Tool.DRAW)

    /**
     * The two a mouse can choose between, and on Windows the two that get used.
     *
     * A desktop program is handed a pen, a finger and a mouse identically - AWT has no notion of
     * either of the first two - so what a pen draws with here is the left-mouse profile, and what
     * its barrel button draws with is the right-mouse one. They are separate pens with separate
     * colours, widths and tools, which is the point: a right-click eraser is one gesture away.
     */
    val mouse = ToolConfig()
    val mouseRight = ToolConfig(tool = Tool.ERASER)

    val button1 = ToolConfig(tool = Tool.ERASER)
    val button2 = ToolConfig(tool = Tool.SELECT)

    var activeMode by mutableStateOf(InputMode.MOUSE)
        private set

    /** The profile currently being drawn and edited with. */
    val active: ToolConfig
        get() = configFor(activeMode)

    fun configFor(mode: InputMode): ToolConfig = when (mode) {
        InputMode.PEN -> pen
        InputMode.TOUCH -> touch
        InputMode.MOUSE -> mouse
        InputMode.MOUSE_RIGHT -> mouseRight
        InputMode.BUTTON_1 -> button1
        InputMode.BUTTON_2 -> button2
    }

    /** Button profiles join the lists the first time the hardware reports that button. */
    var button1Seen by mutableStateOf(DesktopPrefs.get(K_BTN1_SEEN)?.toBoolean() ?: false)
        private set
    var button2Seen by mutableStateOf(DesktopPrefs.get(K_BTN2_SEEN)?.toBoolean() ?: false)
        private set

    /**
     * Every profile that exists on this machine, for the screens that list them.
     *
     * Not what the toolbar switch steps through - see [advanceMode].
     */
    val availableModes: List<InputMode>
        get() = buildList {
            add(InputMode.MOUSE)
            add(InputMode.MOUSE_RIGHT)
            add(InputMode.PEN)
            add(InputMode.TOUCH)
            if (button1Seen) add(InputMode.BUTTON_1)
            if (button2Seen) add(InputMode.BUTTON_2)
        }

    /** Whichever device last touched the page picks its own profile. */
    var autoSwitchInput: Boolean
        get() = autoSwitchState.value
        set(value) {
            autoSwitchState.value = value
            DesktopPrefs.put(K_AUTO_SWITCH, value.toString())
        }
    private val autoSwitchState =
        mutableStateOf(DesktopPrefs.get(K_AUTO_SWITCH)?.toBoolean() ?: true)

    fun switchMode(mode: InputMode) {
        if (activeMode == mode) return
        activeMode = mode
        revision++
    }

    /**
     * Step the switch on.
     *
     * [heldButton] is which stylus barrel button was down when it was clicked, or 0 for a plain
     * click. Only a held button reaches a button profile - the rule is [InputMode.nextOnTap],
     * shared with the tablet, because a profile that turns up while switching between pen and
     * finger is both surprising and hard to get back out of.
     */
    fun advanceMode(heldButton: Int = 0) {
        if (heldButton > 0) {
            noteStylusButtonSeen(heldButton >= 2)
            switchMode(InputMode.forHeldButton(heldButton))
            return
        }
        switchMode(InputMode.nextOnTap(activeMode))
    }

    /**
     * Told by the canvas that a pen button was pressed.
     *
     * This is the only thing that reveals the hidden profiles, so the control appears exactly on
     * the machines where it means something.
     */
    fun noteStylusButtonSeen(secondary: Boolean) {
        if (secondary) {
            if (!button2Seen) {
                button2Seen = true
                DesktopPrefs.put(K_BTN2_SEEN, "true")
            }
        } else if (!button1Seen) {
            button1Seen = true
            DesktopPrefs.put(K_BTN1_SEEN, "true")
        }
    }

    /**
     * Pick the profile for whatever has just landed on the page.
     *
     * A held barrel button wins, then the pointer's own kind. Called on every press, which is
     * what makes switching between a pen and a finger need no settings change at all.
     */
    fun adoptInput(
        isStylus: Boolean,
        isTouch: Boolean,
        heldButton: Int,
        secondaryButton: Boolean = false
    ) {
        if (!autoSwitchInput) return
        if (isStylus && heldButton > 0) noteStylusButtonSeen(heldButton >= 2)
        switchMode(
            InputMode.forPointer(
                isStylus = isStylus,
                isTouch = isTouch,
                secondaryButton = secondaryButton,
                heldStylusButton = heldButton
            )
        )
    }

    var revision by mutableStateOf(0)
        private set

    var presets by mutableStateOf(loadPresets())
        private set

    var activePreset by mutableStateOf(-1)

    var customColors by mutableStateOf(loadColors())
        private set

    /** True while the ruler is on the page and ink is snapping to it. */
    var rulerVisible by mutableStateOf(false)

    /**
     * Where the straightedge is lying, in the coordinates of the page it is on.
     *
     * Page coordinates rather than screen, so it stays put relative to the work when the view is
     * scrolled or zoomed - which is the whole point of a ruler resting on the paper.
     */
    var ruler by mutableStateOf<com.inkslate.core.Ruler?>(null)

    /**
     * True while shapes drawn freehand are tidied into the shape they were meant to be.
     *
     * Off until asked for, as on the tablet. It was on here and nowhere else, so handwriting
     * straightened itself into lines on one of the two builds - and a thing that rewrites what
     * you drew is not something to have on by default in either.
     */
    private val recogniseState =
        mutableStateOf(DesktopPrefs.get(K_RECOGNISE)?.toBoolean() ?: false)

    var recogniseShapes: Boolean
        get() = recogniseState.value
        set(value) {
            recogniseState.value = value
            DesktopPrefs.put(K_RECOGNISE, value.toString())
        }

    /** Whether a pen's reported pressure is used at all, or every line comes out one width. */
    var pressureEnabled: Boolean
        get() = pressureState.value
        set(value) {
            pressureState.value = value
            DesktopPrefs.put(K_PRESSURE, value.toString())
        }
    private val pressureState = mutableStateOf(DesktopPrefs.get(K_PRESSURE)?.toBoolean() ?: true)

    /** Shapes snap to square, circle and fifteen degrees without having to hold shift. */
    var snapShapes: Boolean
        get() = snapShapesState.value
        set(value) {
            snapShapesState.value = value
            DesktopPrefs.put(K_SNAP_SHAPES, value.toString())
        }
    private val snapShapesState =
        mutableStateOf(DesktopPrefs.get(K_SNAP_SHAPES)?.toBoolean() ?: false)

    /** A highlighter dragged across a line becomes clean bars over the words it crossed. */
    var snapHighlighterToText: Boolean
        get() = snapTextState.value
        set(value) {
            snapTextState.value = value
            DesktopPrefs.put(K_SNAP_TEXT, value.toString())
        }
    private val snapTextState = mutableStateOf(DesktopPrefs.get(K_SNAP_TEXT)?.toBoolean() ?: true)

    /** Trim each page to its printed area, hiding the margins a textbook gives up. */
    var cropMargins: Boolean
        get() = cropState.value
        set(value) {
            cropState.value = value
            DesktopPrefs.put(K_CROP, value.toString())
        }
    private val cropState = mutableStateOf(DesktopPrefs.get(K_CROP)?.toBoolean() ?: false)

    /** Whether a pan carries on after the hand leaves, and how far a flick throws it. */
    var flingEnabled: Boolean
        get() = flingState.value
        set(value) {
            flingState.value = value
            DesktopPrefs.put(K_FLING, value.toString())
        }
    private val flingState = mutableStateOf(DesktopPrefs.get(K_FLING)?.toBoolean() ?: true)

    var flingScale: Float
        get() = flingScaleState.value
        set(value) {
            flingScaleState.value = value
            DesktopPrefs.put(K_FLING_SCALE, value.toString())
        }
    private val flingScaleState =
        mutableStateOf(DesktopPrefs.get(K_FLING_SCALE)?.toFloatOrNull() ?: 1.35f)

    /** Hold the display awake while a document is open. See [ScreenAwake]. */
    var keepScreenOn: Boolean
        get() = keepAwakeState.value
        set(value) {
            keepAwakeState.value = value
            DesktopPrefs.put(K_KEEP_AWAKE, value.toString())
        }
    private val keepAwakeState =
        mutableStateOf(DesktopPrefs.get(K_KEEP_AWAKE)?.toBoolean() ?: true)

    /** Reopen a document where it was left, rather than at the top of page one. */
    var rememberView: Boolean
        get() = rememberViewState.value
        set(value) {
            rememberViewState.value = value
            DesktopPrefs.put(K_REMEMBER_VIEW, value.toString())
        }
    private val rememberViewState =
        mutableStateOf(DesktopPrefs.get(K_REMEMBER_VIEW)?.toBoolean() ?: true)

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
        DesktopPrefs.put(K_AUTO_SWITCH, autoSwitchInput.toString())
        InputMode.entries.forEach { save(it, configFor(it)) }
    }

    /** One line per profile, so the four cannot overwrite one another's settings. */
    private fun save(mode: InputMode, c: ToolConfig) {
        DesktopPrefs.put(
            "tool_" + mode.name,
            listOf(
                c.tool.name, c.brush.name, c.color.toString(), c.strokeWidth.toString(),
                c.eraserRadius.toString(), c.eraserMode.name, c.smoothing.toString(),
                c.textSize.toString(), c.opacity.toString(), c.dash.name,
                c.fillStyle.name, c.dynamics.toString(), c.dynamicWidth.toString(),
                c.pressureGamma.toString(), c.pressureMin.toString()
            ).joinToString("|")
        )
    }

    private fun load(mode: InputMode, c: ToolConfig) {
        val parts = DesktopPrefs.get("tool_" + mode.name)?.split("|") ?: return
        runCatching {
            c.tool = Tool.valueOf(parts[0])
            c.brush = BrushType.valueOf(parts[1])
            c.color = parts[2].toInt()
            c.strokeWidth = parts[3].toFloat()
            c.eraserRadius = parts[4].toFloat()
            c.eraserMode = com.inkslate.core.EraserMode.valueOf(parts[5])
            c.smoothing = parts[6].toFloat()
            c.textSize = parts[7].toFloat()
            c.opacity = parts[8].toFloat()
            c.dash = com.inkslate.core.DashStyle.valueOf(parts[9])
            c.fillStyle = com.inkslate.core.FillStyle.valueOf(parts[10])
            c.dynamics = parts[11].toFloat()
            c.dynamicWidth = parts.getOrNull(12)?.toBoolean() ?: false
            parts.getOrNull(13)?.toFloatOrNull()?.let { c.pressureGamma = it }
            parts.getOrNull(14)?.toFloatOrNull()?.let { c.pressureMin = it }
        }
        // A tool that cannot be resumed sensibly. Landing in a half-finished capture because that
        // is how the last session ended is a poor way to open a page.
        if (c.tool == Tool.REGION) c.tool = Tool.DRAW
    }

    init {
        InputMode.entries.forEach { load(it, configFor(it)) }
    }

    private companion object {
        const val K_PRESETS = "tool_presets"
        const val K_COLORS = "tool_colors"
        const val K_BTN1_SEEN = "tool_button1_seen"
        const val K_BTN2_SEEN = "tool_button2_seen"
        const val K_AUTO_SWITCH = "tool_auto_switch"
        const val K_RECOGNISE = "tool_recognise_shapes"
        const val K_PRESSURE = "tool_pressure"
        const val K_SNAP_SHAPES = "tool_snap_shapes"
        const val K_SNAP_TEXT = "tool_snap_highlighter"
        const val K_CROP = "tool_crop_margins"
        const val K_FLING = "tool_fling"
        const val K_FLING_SCALE = "tool_fling_scale"
        const val K_REMEMBER_VIEW = "tool_remember_view"
        const val K_KEEP_AWAKE = "tool_keep_screen_on"
    }
}

/**
 * The tool state for a screen.
 *
 * Deliberately one per screen rather than a singleton, exactly as on the tablet: every setting in
 * here writes itself to the preferences as it changes, so two instances agree without having to
 * be the same object, and neither holds the other's ruler or half-placed stamp.
 */
@androidx.compose.runtime.Composable
fun rememberToolState(): ToolState = androidx.compose.runtime.remember { ToolState() }

/** The palette the toolbar shows: your own colours first, then the shared set. */
fun ToolState.swatches(): List<Int> = (customColors + Palette.COLORS).distinct()
