package com.inkslate.ui.editor

import android.content.Context
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.inkslate.ink.*
import com.inkslate.ink.BrushType
import com.inkslate.ink.DashStyle
import com.inkslate.ink.DrawingView
import com.inkslate.ink.FillStyle
import org.json.JSONArray
import org.json.JSONObject
import com.inkslate.core.Stamps
import com.inkslate.core.Tool
import com.inkslate.core.InputMode
import com.inkslate.core.EraserMode
import com.inkslate.core.StylusButtonAction
import com.inkslate.core.ToolConfig


/**
 * A saved pen: everything needed to reproduce one feel in a single tap.
 *
 * The pen itself lives in `:core`, shared with the Windows build so the starting kit is the same
 * on both. Only how it is written into this device's preferences is Android's business, and that
 * is what stays here.
 */
typealias Preset = com.inkslate.core.PenPreset

fun Preset.toJson(): JSONObject = JSONObject().apply {
    put("brush", brush.name); put("color", color)
    put("width", width.toDouble()); put("opacity", opacity.toDouble())
}

fun presetFromJson(o: JSONObject): Preset? = runCatching {
    Preset(
        brush = BrushType.valueOf(o.getString("brush")),
        color = o.getInt("color"),
        width = o.getDouble("width").toFloat(),
        opacity = o.optDouble("opacity", 1.0).toFloat()
    )
}.getOrNull()

/**
 * The editor's drawing settings, held as two independent profiles: one for the pen, one for
 * touch. Whichever device last touched the screen is the one being edited, so switching from pen
 * to finger silently switches the whole toolbar with it.
 *
 * [ToolConfig] is a plain mutable class rather than Compose state because the drawing surface has
 * to read it synchronously the instant a pointer lands. [revision] is bumped on every edit so the
 * UI still recomposes; read it once at the top of any composable that displays these values.
 */
class ToolState(private val context: Context) {

    private val sp = context.getSharedPreferences("tools", Context.MODE_PRIVATE)

    val pen = ToolConfig()
    val touch = ToolConfig(
        brush = BrushType.HIGHLIGHTER,
        strokeWidth = BrushType.HIGHLIGHTER.defaultWidth,
        color = Color.parseColor("#FDE047")
    )

    /**
     * The pen with its barrel button held, and the pen with its second button held.
     *
     * Full profiles rather than a single "what the button does" setting. A button is the fastest
     * control on the device and it was spending that on one verb; what someone actually reaches
     * for it to be is a second pen - the red one, or the eraser at this particular size - and
     * that is a tool, a colour, a brush and a width, not a menu choice.
     *
     * They default to an eraser and to a red correcting pen, which is what the two buttons on a
     * pen that has two are nearly always used for.
     */
    /** A mouse, when one is attached: its own two pens rather than borrowing the stylus's. */
    val mouse = ToolConfig()
    val mouseRight = ToolConfig(tool = Tool.ERASER, eraserRadius = 8f)

    val button1 = ToolConfig(tool = Tool.ERASER, eraserRadius = 8f)
    val button2 = ToolConfig(
        color = Color.parseColor("#DC2626"),
        strokeWidth = 2f
    )

    var activeMode by mutableStateOf(InputMode.PEN)
        private set

    /** Bumped on every settings change; observed by the toolbar to trigger recomposition. */
    var revision by mutableStateOf(0)
        private set

    var autoSwitchInput by mutableStateOf(true)
    var pressureEnabled by mutableStateOf(true)
    var snapShapes by mutableStateOf(false)
    var recogniseShapes by mutableStateOf(false)
    var snapHighlighterToText by mutableStateOf(true)
    var cropMargins by mutableStateOf(false)
    var rulerVisible by mutableStateOf(false)
    /**
     * What the barrel button does while it is held - always [StylusButtonAction.PROFILE], which
     * is to say "draw with the barrel's own tool, colour and width".
     *
     * The other actions used to be pickable from the toolbar, which put a second, differently
     * shaped set of controls inside a tab that already looked like a pen profile. The barrel tab
     * is now simply a third profile alongside Pen and Finger, with the same options.
     */
    var stylusButton by mutableStateOf(StylusButtonAction.PROFILE)
    /** The same, for a second button on pens that have one. */
    var stylusButton2 by mutableStateOf(StylusButtonAction.PROFILE)

    /**
     * Whether a pen with buttons has ever actually been used here.
     *
     * The button profiles are not in the toolbar until one of them has announced itself, which is
     * the whole point of putting them behind the pen/finger switch: a tablet with a button-less
     * stylus never shows a control that can do nothing, and a pen with two buttons ends up with
     * two extra profiles without anyone visiting a settings screen to say so.
     */
    var button1Seen by mutableStateOf(false)
        private set
    var button2Seen by mutableStateOf(false)
        private set

    /**
     * How many buttons the pen has actually been observed to have.
     *
     * Kept apart from [button1Seen] on purpose. This is what the hardware reports while drawing;
     * the other is whether the user has asked for the profile by tapping the switch with the
     * button held. Only the deliberate gesture puts a profile in the toolbar - but knowing the
     * hardware is there lets Settings offer a way in for anyone whose pen does not report its
     * button cleanly through a tap, which would otherwise leave the feature unreachable.
     */
    var hardwareButtons by mutableStateOf(0)
        private set

    fun noteButtonHardware(index: Int) {
        if (index <= hardwareButtons) return
        hardwareButtons = index
        sp.edit().putInt(K_BTN_HARDWARE, hardwareButtons).apply()
    }
    /**
     * Whether the "width follows the zoom" explanation has been shown once. It is a sentence
     * worth reading the first time and clutter every time after, so it is said once and then
     * kept out of the toolbar.
     */
    var dynamicWidthHintSeen by mutableStateOf(false)
        private set

    fun noteDynamicWidthHintSeen() {
        if (dynamicWidthHintSeen) return
        dynamicWidthHintSeen = true
        sp.edit().putBoolean(K_DYNAMIC_HINT, true).apply()
    }

    var flingEnabled by mutableStateOf(true)
    /** Reopen each document where it was left, at the same zoom and scroll. */
    var rememberView by mutableStateOf(true)
    /** Hold the screen awake while a document is open. */
    var keepScreenOn by mutableStateOf(true)
    /** Multiplier on release velocity. Higher covers more of a long document per flick. */
    var flingScale by mutableStateOf(1.35f)
    var tableRows by mutableStateOf(3)
    var tableCols by mutableStateOf(3)

    var presets by mutableStateOf(loadPresets())
        private set
    var activePreset by mutableStateOf(-1)
    var customColors by mutableStateOf(loadCustomColors())
        private set

    fun configFor(mode: InputMode): ToolConfig = when (mode) {
        InputMode.PEN -> pen
        InputMode.TOUCH -> touch
        // A mouse on a tablet is rare but real - a keyboard case, a desk setup - and when one is
        // there it should draw with its own pens rather than borrow the stylus's.
        InputMode.MOUSE -> mouse
        InputMode.MOUSE_RIGHT -> mouseRight
        InputMode.BUTTON_1 -> button1
        InputMode.BUTTON_2 -> button2
    }

    val active: ToolConfig get() = configFor(activeMode)

    /** What the button belonging to [mode] does while held, or null for the ordinary profiles. */
    fun actionFor(mode: InputMode): StylusButtonAction? = when (mode) {
        InputMode.BUTTON_1 -> stylusButton
        InputMode.BUTTON_2 -> stylusButton2
        else -> null
    }

    fun setActionFor(mode: InputMode, action: StylusButtonAction) {
        when (mode) {
            InputMode.BUTTON_1 -> stylusButton = action
            InputMode.BUTTON_2 -> stylusButton2 = action
            else -> return
        }
        revision++
        persist()
    }

    /**
     * Every profile that exists on this device, for the screens that list them.
     *
     * Button profiles join the list the first time the hardware reports that button, and stay.
     * This is *not* what the toolbar switch steps through - see [advanceMode].
     */
    val availableModes: List<InputMode>
        get() = buildList {
            add(InputMode.PEN)
            add(InputMode.TOUCH)
            // The mouse profiles appear once a mouse has actually drawn something, the same rule
            // the barrel profiles follow: a control for hardware you do not have is clutter.
            if (mouseSeen) {
                add(InputMode.MOUSE)
                add(InputMode.MOUSE_RIGHT)
            }
            if (button1Seen) add(InputMode.BUTTON_1)
            if (button2Seen) add(InputMode.BUTTON_2)
        }

    /** Set the first time a mouse draws on this device. */
    var mouseSeen by mutableStateOf(false)
        private set

    fun noteMouseSeen() {
        if (mouseSeen) return
        mouseSeen = true
        persist()
    }



    /**
     * Told by the drawing surface that a pen button was pressed.
     *
     * This is what reveals the hidden profiles - and it is deliberately the only thing that does,
     * so the control appears exactly on the devices where it means something.
     */
    fun revealButtonProfiles() {
        if (hardwareButtons <= 0) return
        noteStylusButtonSeen(false)
        if (hardwareButtons >= 2) noteStylusButtonSeen(true)
    }

    fun noteStylusButtonSeen(secondary: Boolean) {
        val changed = if (secondary) !button2Seen else !button1Seen
        if (!changed) return
        if (secondary) button2Seen = true else button1Seen = true
        revision++
        persist()
    }

    init { restore() }

    /** Mutate the active profile and let the UI know. */
    fun edit(block: (ToolConfig) -> Unit) {
        block(active)
        revision++
        persist()
    }

    /** Called when the drawing surface reports that the input device changed. */
    fun onModeChanged(mode: InputMode) {
        // A mouse revealing itself is the same rule the barrel buttons follow: the profile joins
        // the list the first time the hardware actually turns up.
        if (mode.isMouse) noteMouseSeen()
        if (activeMode == mode) return
        activeMode = mode
        activePreset = -1
        revision++
    }

    fun switchMode(mode: InputMode, view: DrawingView?) {
        activeMode = mode
        activePreset = -1
        revision++
        view?.setActiveMode(mode)
    }

    /**
     * Step the toolbar's input switch on by one.
     *
     * [heldButton] is which pen button was down at the moment it was tapped: 1 for the barrel, 2
     * for a second button, 0 for an ordinary tap. Tapping it with a button held is what reveals
     * that button's profile and jumps straight to it - so on a pen without buttons the switch is
     * exactly the two-way pen/finger toggle it has always been, and on a pen with them the extra
     * profiles are one gesture away without ever appearing as clutter.
     */
    /**
     * Step the switch on.
     *
     * [heldButton] is which stylus barrel button was down when it was tapped, or 0 for a plain
     * tap with a finger or an unmodified pen. Only a held button reaches a button profile: a
     * plain tap moves between the pen and the finger, and a plain tap while a button profile
     * happens to be active comes back to the pen rather than continuing round a loop.
     */
    fun advanceMode(view: DrawingView?, heldButton: Int = 0) {
        if (heldButton > 0) {
            noteStylusButtonSeen(heldButton == 2)
            switchMode(InputMode.forHeldButton(heldButton), view)
            return
        }
        switchMode(InputMode.nextOnTap(activeMode), view)
    }

    fun applyTo(view: DrawingView?) {
        view ?: return
        view.penConfig.copyFrom(pen)
        view.touchConfig.copyFrom(touch)
        view.button1Config.copyFrom(button1)
        view.button2Config.copyFrom(button2)
        view.stylusButton2 = stylusButton2
        view.autoSwitchInput = autoSwitchInput
        view.pressureEnabled = pressureEnabled
        view.snapShapes = snapShapes
        view.recogniseShapes = recogniseShapes
        view.snapHighlighterToText = snapHighlighterToText
        view.cropMargins = cropMargins
        view.rulerVisible = rulerVisible
        view.stylusButton = stylusButton
        view.flingEnabled = flingEnabled
        view.flingScale = flingScale
        view.newTableRows = tableRows
        view.newTableCols = tableCols
        if (view.activeMode != activeMode) view.setActiveMode(activeMode)
        view.invalidate()
    }

    // ---- presets -------------------------------------------------------------

    fun selectPreset(index: Int) {
        val p = presets.getOrNull(index) ?: return
        activePreset = index
        edit { c ->
            c.tool = Tool.DRAW
            c.brush = p.brush
            c.color = p.color
            c.strokeWidth = p.width
            c.opacity = p.opacity
            if (p.brush != BrushType.HIGHLIGHTER) c.previousBrush = p.brush
        }
    }

    /** Step to the next saved pen, wrapping. Used by the stylus button. */
    fun cyclePreset() {
        if (presets.isEmpty()) return
        selectPreset((activePreset + 1).mod(presets.size))
    }

    fun savePresetFromCurrent(index: Int) {
        val c = active
        val updated = presets.toMutableList()
        val p = Preset(c.brush, c.color, c.strokeWidth, c.opacity)
        if (index in updated.indices) updated[index] = p else updated.add(p)
        presets = updated
        storePresets()
    }

    fun addPreset() {
        val c = active
        presets = presets + Preset(c.brush, c.color, c.strokeWidth, c.opacity)
        activePreset = presets.lastIndex
        storePresets()
    }

    fun removePreset(index: Int) {
        if (presets.size <= 1 || index !in presets.indices) return
        presets = presets.toMutableList().also { it.removeAt(index) }
        activePreset = -1
        storePresets()
    }

    fun rememberColor(c: Int) {
        if (c in customColors) return
        customColors = (listOf(c) + customColors).take(18)
        sp.edit().putString(K_COLORS, customColors.joinToString(",")).apply()
    }

    // ---- stamps ---------------------------------------------------------------

    /**
     * The options each stamp was last placed with, and which stamps were used recently.
     *
     * Remembered per stamp rather than globally: setting a fraction circle to sevenths should not
     * quietly turn the next grid into a seven-by-seven one, and coming back to a stamp you have
     * already configured to find it exactly as you left it is most of what makes the options
     * worth having at all.
     */
    var recentStamps by mutableStateOf(loadRecentStamps())
        private set

    private val stampOptions = HashMap<Stamps.Kind, Stamps.StampOptions>().also { loadStamps(it) }

    fun stampOptionsFor(kind: Stamps.Kind): Stamps.StampOptions =
        stampOptions[kind] ?: kind.defaults

    fun noteStampUsed(kind: Stamps.Kind, options: Stamps.StampOptions) {
        stampOptions[kind] = options
        recentStamps = (listOf(kind) + recentStamps).distinct().take(6)
        storeStamps()
    }

    private fun loadStamps(into: HashMap<Stamps.Kind, Stamps.StampOptions>) {
        val raw = sp.getString(K_STAMPS, null) ?: return
        runCatching {
            val root = JSONObject(raw)
            for (key in root.keys()) {
                val kind = runCatching { Stamps.Kind.valueOf(key) }.getOrNull() ?: continue
                val o = root.getJSONObject(key)
                into[kind] = Stamps.sanitise(
                    kind,
                    Stamps.StampOptions(
                        divisions = o.optInt("divisions", kind.defaults.divisions),
                        labels = o.optBoolean("labels", kind.defaults.labels),
                        rangeFrom = o.optDouble("from", kind.defaults.rangeFrom.toDouble()).toFloat(),
                        rangeTo = o.optDouble("to", kind.defaults.rangeTo.toDouble()).toFloat(),
                        filled = o.optInt("filled", kind.defaults.filled),
                        variant = o.optInt("variant", kind.defaults.variant)
                    )
                )
            }
        }
    }

    private fun storeStamps() {
        val root = JSONObject()
        stampOptions.forEach { (kind, o) ->
            root.put(
                kind.name,
                JSONObject().apply {
                    put("divisions", o.divisions); put("labels", o.labels)
                    put("from", o.rangeFrom.toDouble()); put("to", o.rangeTo.toDouble())
                    put("filled", o.filled); put("variant", o.variant)
                }
            )
        }
        sp.edit()
            .putString(K_STAMPS, root.toString())
            .putString(K_STAMP_RECENT, recentStamps.joinToString(",") { it.name })
            .apply()
    }

    private fun loadRecentStamps(): List<Stamps.Kind> =
        (sp.getString(K_STAMP_RECENT, null) ?: "")
            .split(',')
            .mapNotNull { name -> runCatching { Stamps.Kind.valueOf(name.trim()) }.getOrNull() }
            .take(6)

    // ---- persistence ---------------------------------------------------------

    private fun configJson(c: ToolConfig) = JSONObject().apply {
        put("tool", c.tool.name); put("brush", c.brush.name)
        put("color", c.color); put("width", c.strokeWidth.toDouble())
        put("opacity", c.opacity.toDouble()); put("dash", c.dash.name)
        put("fill", c.fillStyle.name); put("fillColor", c.fillColor)
        put("eraser", c.eraserRadius.toDouble()); put("textSize", c.textSize.toDouble())
        put("eraserMode", c.eraserMode.name)
        put("smoothing", c.smoothing.toDouble()); put("prevBrush", c.previousBrush.name)
        put("gamma", c.pressureGamma.toDouble()); put("pmin", c.pressureMin.toDouble())
        put("dynamics", c.dynamics.toDouble())
        put("dynamicWidth", c.dynamicWidth)
    }

    private fun readConfig(o: JSONObject, into: ToolConfig) {
        runCatching { into.tool = Tool.valueOf(o.getString("tool")) }
        runCatching { into.brush = BrushType.valueOf(o.getString("brush")) }
        runCatching { into.dash = DashStyle.valueOf(o.getString("dash")) }
        runCatching { into.fillStyle = FillStyle.valueOf(o.getString("fill")) }
        runCatching { into.previousBrush = BrushType.valueOf(o.getString("prevBrush")) }
        runCatching { into.eraserMode = EraserMode.valueOf(o.getString("eraserMode")) }
        into.color = o.optInt("color", into.color)
        into.strokeWidth = o.optDouble("width", into.strokeWidth.toDouble()).toFloat()
        into.opacity = o.optDouble("opacity", into.opacity.toDouble()).toFloat()
        into.fillColor = o.optInt("fillColor", into.fillColor)
        into.eraserRadius = o.optDouble("eraser", into.eraserRadius.toDouble()).toFloat()
        into.textSize = o.optDouble("textSize", into.textSize.toDouble()).toFloat()
        into.smoothing = o.optDouble("smoothing", into.smoothing.toDouble()).toFloat()
        into.pressureGamma = o.optDouble("gamma", into.pressureGamma.toDouble()).toFloat()
        into.pressureMin = o.optDouble("pmin", into.pressureMin.toDouble()).toFloat()
        into.dynamics = o.optDouble("dynamics", into.dynamics.toDouble()).toFloat()
        into.dynamicWidth = o.optBoolean("dynamicWidth", into.dynamicWidth)
        // never restore into a mode where the pointer appears to do nothing
        if (into.tool == Tool.SELECT) into.tool = Tool.DRAW
    }

    private fun persist() {
        sp.edit()
            .putString(K_PEN, configJson(pen).toString())
            .putString(K_TOUCH, configJson(touch).toString())
            .putString(K_MOUSE, configJson(mouse).toString())
            .putString(K_MOUSE_RIGHT, configJson(mouseRight).toString())
            .putString(K_BTN1, configJson(button1).toString())
            .putString(K_BTN2, configJson(button2).toString())
            .putString(K_STYLUS_BUTTON_2, stylusButton2.name)
            .putBoolean(K_MOUSE_SEEN, mouseSeen)
            .putBoolean(K_BTN1_SEEN, button1Seen)
            .putBoolean(K_BTN2_SEEN, button2Seen)
            .putBoolean(K_AUTO, autoSwitchInput)
            .putBoolean(K_PRESSURE, pressureEnabled)
            .putBoolean(K_SNAP, snapShapes)
            .putBoolean(K_RECOGNISE, recogniseShapes)
            .putBoolean(K_SNAP_TEXT, snapHighlighterToText)
            .putBoolean(K_CROP, cropMargins)
            .putBoolean(K_FLING, flingEnabled)
            .putBoolean(K_REMEMBER_VIEW, rememberView)
            .putBoolean(K_KEEP_AWAKE, keepScreenOn)
            .putString(K_STYLUS_BUTTON, stylusButton.name)
            .putFloat(K_FLING_SCALE, flingScale)
            .putInt(K_ROWS, tableRows)
            .putInt(K_COLS, tableCols)
            .apply()
    }

    private fun restore() {
        runCatching { sp.getString(K_PEN, null)?.let { readConfig(JSONObject(it), pen) } }
        runCatching { sp.getString(K_TOUCH, null)?.let { readConfig(JSONObject(it), touch) } }
        runCatching { sp.getString(K_MOUSE, null)?.let { readConfig(JSONObject(it), mouse) } }
        runCatching {
            sp.getString(K_MOUSE_RIGHT, null)?.let { readConfig(JSONObject(it), mouseRight) }
        }
        runCatching { sp.getString(K_BTN1, null)?.let { readConfig(JSONObject(it), button1) } }
        runCatching { sp.getString(K_BTN2, null)?.let { readConfig(JSONObject(it), button2) } }
        mouseSeen = sp.getBoolean(K_MOUSE_SEEN, false)
        button1Seen = sp.getBoolean(K_BTN1_SEEN, false)
        button2Seen = sp.getBoolean(K_BTN2_SEEN, false)
        hardwareButtons = sp.getInt(K_BTN_HARDWARE, 0)
        dynamicWidthHintSeen = sp.getBoolean(K_DYNAMIC_HINT, false)
        autoSwitchInput = sp.getBoolean(K_AUTO, true)
        pressureEnabled = sp.getBoolean(K_PRESSURE, true)
        snapShapes = sp.getBoolean(K_SNAP, false)
        recogniseShapes = sp.getBoolean(K_RECOGNISE, false)
        snapHighlighterToText = sp.getBoolean(K_SNAP_TEXT, true)
        cropMargins = sp.getBoolean(K_CROP, false)
        flingEnabled = sp.getBoolean(K_FLING, true)
        rememberView = sp.getBoolean(K_REMEMBER_VIEW, true)
        keepScreenOn = sp.getBoolean(K_KEEP_AWAKE, true)
        // Both barrel buttons are their own pen now, and nothing in the UI can change that, so
        // an older install's saved ERASE is deliberately not read back - it would leave the
        // barrel rubbing out with no control left to turn it off.
        stylusButton = StylusButtonAction.PROFILE
        stylusButton2 = StylusButtonAction.PROFILE
        flingScale = sp.getFloat(K_FLING_SCALE, 1.35f)
        tableRows = sp.getInt(K_ROWS, 3)
        tableCols = sp.getInt(K_COLS, 3)
    }

    fun persistNow() = persist()

    private fun loadPresets(): List<Preset> {
        val raw = sp.getString(K_PRESETS, null) ?: return Preset.defaults
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { presetFromJson(arr.getJSONObject(it)) }
                .ifEmpty { Preset.defaults }
        }.getOrDefault(Preset.defaults)
    }

    private fun storePresets() {
        val arr = JSONArray()
        presets.forEach { arr.put(it.toJson()) }
        sp.edit().putString(K_PRESETS, arr.toString()).apply()
    }

    private fun loadCustomColors(): List<Int> =
        (sp.getString(K_COLORS, null) ?: "")
            .split(',').mapNotNull { it.trim().toIntOrNull() }

    companion object {
        private const val K_PEN = "cfg_pen"
        private const val K_TOUCH = "cfg_touch"
        private const val K_AUTO = "auto_switch"
        private const val K_PRESSURE = "pressure"
        private const val K_SNAP = "snap"
        private const val K_RECOGNISE = "recognise"
        private const val K_SNAP_TEXT = "snap_text"
        private const val K_CROP = "crop_margins"
        private const val K_FLING = "fling"
        private const val K_REMEMBER_VIEW = "remember_view"
        private const val K_KEEP_AWAKE = "keep_awake"
        private const val K_STYLUS_BUTTON = "stylus_button"
        private const val K_STYLUS_BUTTON_2 = "stylus_button_2"
        private const val K_MOUSE_SEEN = "mouse_seen"
        private const val K_MOUSE = "cfg_mouse"
        private const val K_MOUSE_RIGHT = "cfg_mouse_right"
        private const val K_BTN1 = "cfg_button1"
        private const val K_BTN2 = "cfg_button2"
        private const val K_BTN1_SEEN = "button1_seen"
        private const val K_BTN_HARDWARE = "button_hardware"
        private const val K_DYNAMIC_HINT = "dynamic_width_hint_seen"
        private const val K_BTN2_SEEN = "button2_seen"
        private const val K_STAMPS = "stamp_options"
        private const val K_STAMP_RECENT = "stamp_recent"
        private const val K_FLING_SCALE = "fling_scale"
        private const val K_ROWS = "rows"
        private const val K_COLS = "cols"
        private const val K_PRESETS = "presets"
        private const val K_COLORS = "custom_colors"

        /**
         * Selectable pen widths.
         *
         * A finite ladder rather than a continuous range, for two reasons the continuous slider
         * got wrong: a hairline and a fine liner are less than a tenth of a point apart, so a
         * linear pixel-per-point slider could not address them at all, and nothing you picked
         * could be picked again. Stops are dense where nibs actually live and coarse where the
         * difference stops mattering.
         */
        /**
         * Widths, eraser radii and the palette all come from `:core`, shared with the Windows
         * build. A ladder that differed between the two would mean a width picked on one could
         * not be picked again on the other; a palette that differed would mean a document marked
         * up in the app's own red opened somewhere with no way to match it.
         */
        val WIDTHS = com.inkslate.core.Palette.WIDTHS
        val ERASER_SIZES = com.inkslate.core.Palette.ERASER_SIZES
        val PALETTE = com.inkslate.core.Palette.COLORS

        /** Index of the ladder stop nearest [value]. */
        fun stopIndex(ladder: FloatArray, value: Float): Int =
            com.inkslate.core.Palette.stopIndex(ladder, value)
    }
}

@Composable
fun rememberToolState(context: Context = LocalContext.current): ToolState =
    remember { ToolState(context) }
