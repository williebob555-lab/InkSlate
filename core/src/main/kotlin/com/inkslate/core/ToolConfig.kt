package com.inkslate.core

/**
 * What the pointer is currently doing.
 *
 * Shared rather than defined per platform, because the toolbar that presents these is the same
 * toolbar on both, and a tool that exists on one side but not the other is a UI that cannot be
 * shared.
 */
enum class Tool {
    DRAW,
    ERASER,
    SELECT,
    PAN,
    REGION,
    LINE,
    ARROW,
    RECT,
    ELLIPSE,
    TEXT,
    TABLE;

    val isShape: Boolean get() = this == LINE || this == ARROW || this == RECT || this == ELLIPSE
}

/**
 * What the eraser takes away.
 *
 * A stroke eraser is right for a wrong letter and wrong for a stray tail: taking the whole stroke
 * when you meant to clean up its end costs you the letter as well. Both are kept, because both
 * are genuinely the right answer some of the time.
 */
enum class EraserMode(val label: String, val detail: String) {
    STROKE("Whole stroke", "Removes anything the eraser touches, entire"),
    PARTIAL("Part of a stroke", "Rubs out only the piece under the eraser")
}

/**
 * What the stylus's barrel button does.
 *
 * It was hardcoded to erase. On a pen that has one button, that button is the fastest control on
 * the device, and which job it should do depends entirely on how someone works.
 */
enum class StylusButtonAction(val label: String, val detail: String) {
    PROFILE("Its own pen", "Draws with this button's own tool, colour and width while held"),
    ERASE("Erase", "Rubs out while the button is held"),
    SELECT("Select", "Selects while the button is held"),
    PAN("Pan", "Moves the page while the button is held"),
    UNDO("Undo", "One step back per press"),
    REDO("Redo", "One step forward per press"),
    NEXT_PRESET("Next pen", "Steps through your saved pens"),
    HIGHLIGHTER("Highlighter", "Switches to the highlighter and back"),
    NONE("Nothing", "Ignore the button entirely");

    /** True when the action lasts as long as the button is down, rather than firing once. */
    val isHeld: Boolean
        get() = this == ERASE || this == SELECT || this == PAN || this == PROFILE

    /**
     * The tool this action stands in for while held, or null when the button's own profile
     * decides - which is what [PROFILE] means, and why it is not simply another entry here.
     */
    val heldTool: Tool?
        get() = when (this) {
            ERASE -> Tool.ERASER
            SELECT -> Tool.SELECT
            PAN -> Tool.PAN
            else -> null
        }
}

/**
 * Which physical input a [ToolConfig] belongs to.
 *
 * The two button profiles are not a third and fourth kind of pointer - they are the same stylus
 * with a barrel button held down. They get their own configs because that is what makes a button
 * worth having: the button is not merely "erase", it is a whole second pen with its own width,
 * colour and brush, reachable without putting anything down.
 *
 * They stay out of sight until a pen that actually has buttons reports one, so a device without
 * them never shows a control that cannot do anything.
 */
enum class InputMode(val label: String) {
    PEN("Pen"),
    TOUCH("Finger"),
    MOUSE("Left mouse"),
    MOUSE_RIGHT("Right mouse"),
    BUTTON_1("Pen button"),
    BUTTON_2("Pen button 2");

    val isStylusButton: Boolean get() = this == BUTTON_1 || this == BUTTON_2

    /** Whether this profile is chosen by a mouse button rather than by touching the screen. */
    val isMouse: Boolean get() = this == MOUSE || this == MOUSE_RIGHT

    companion object {
        /**
         * What a plain tap on the input switch selects next.
         *
         * The two that a plain touch can select, and nothing else. A barrel profile is not a
         * third thing in a loop - it is the pen with a button held, and the only way to reach it
         * is to hold that button. Putting it in the cycle meant it turned up while switching
         * between pen and finger, which is both surprising and hard to get back out of.
         *
         * The mouse profiles are not in the cycle either, for the same reason from the other end:
         * which one applies is decided by which button you draw with, not by a switch.
         */
        fun nextOnTap(current: InputMode): InputMode = when (current) {
            PEN -> TOUCH
            TOUCH -> PEN
            MOUSE -> MOUSE_RIGHT
            MOUSE_RIGHT -> MOUSE
            BUTTON_1, BUTTON_2 -> PEN
        }

        /** Which profile a held barrel button selects. */
        fun forHeldButton(heldButton: Int): InputMode =
            if (heldButton >= 2) BUTTON_2 else BUTTON_1

        /**
         * Which profile a pointer that has just landed belongs to.
         *
         * [isStylus] and [isTouch] are what the platform can tell us, and on Windows it can tell
         * us neither: a desktop program is handed a pen, a finger and a mouse as the same thing,
         * so a pen drawing there picks the left-mouse profile and a barrel press the right-mouse
         * one. The tablet knows the difference and uses all four.
         */
        fun forPointer(
            isStylus: Boolean,
            isTouch: Boolean,
            secondaryButton: Boolean,
            heldStylusButton: Int = 0
        ): InputMode = when {
            isStylus && heldStylusButton > 0 -> forHeldButton(heldStylusButton)
            isStylus -> PEN
            isTouch -> TOUCH
            secondaryButton -> MOUSE_RIGHT
            else -> MOUSE
        }
    }
}

/**
 * Everything the pointer does, for one kind of input device.
 *
 * Two of these exist at once - one for the stylus, one for touch - and the drawing surface picks
 * between them the instant a pointer lands. That is what lets you highlight with a finger and
 * write with the pen without changing a single setting in between: each device keeps its own
 * tool, brush, colour, width and everything else.
 *
 * Colours are plain ARGB integers here rather than a platform colour type, which is the only
 * thing that stopped this being shared before.
 */
class ToolConfig(
    var tool: Tool = Tool.DRAW,
    var brush: BrushType = BrushType.BALLPOINT,
    var color: Int = 0xFF000000.toInt(),
    var strokeWidth: Float = BrushType.BALLPOINT.defaultWidth,
    var opacity: Float = 1f,
    var dash: DashStyle = DashStyle.SOLID,
    var fillStyle: FillStyle = FillStyle.NONE,
    var fillColor: Int = 0xFFFDE047.toInt(),
    var eraserRadius: Float = 8f,
    /** Whether the eraser takes whole strokes or only the part under it. */
    var eraserMode: EraserMode = EraserMode.STROKE,
    var textSize: Float = 14f,
    /**
     * How hard incoming points are filtered, 0 (raw) to 1 (heavy).
     *
     * Raw digitiser samples are noisy, and at low zoom that noise reads as a shaky line. Too much
     * filtering, though, and the ink lags the pen tip and corners get rounded off - which is why
     * this is exposed to the user rather than fixed at whatever felt right on one device.
     */
    var smoothing: Float = 0.45f,
    /**
     * Overrides the brush's own pressure curve. Below 1 gives more width for a light press.
     * Per input profile, because a stylus and a finger have nothing in common here.
     */
    var pressureGamma: Float = 1f,
    /** Width at the lightest press, as a fraction of full width. */
    var pressureMin: Float = 0.35f,
    /**
     * How far the brush's own width range is stretched, 0 (flat) to about 3 (extreme).
     *
     * The brushes ship with ranges that are honest about the tools they imitate, and honest was
     * not what was wanted: a calligraphy nib at its designed contrast still looked timid. This
     * scales that range around the nominal width, so the light end gets lighter and the heavy end
     * heavier without changing what "size 3" means.
     */
    var dynamics: Float = 1f,
    /** Remembers the brush in use before the highlighter was toggled on. */
    var previousBrush: BrushType = BrushType.BALLPOINT,
    /**
     * Read [strokeWidth] as a thickness on the glass rather than on the page.
     *
     * Off, the slider is a width in page points: zoom in to write a subscript and the nib zooms
     * with the page, so the mark you make is enormous relative to the letters around it and you
     * have to go and find the slider. On, the width is divided back out by the zoom, so a stroke
     * looks the same thickness on screen wherever you are - which is what your hand is actually
     * judging - and the slider is left exactly where you put it.
     *
     * Per input profile, because a finger highlighting at arm's length and a nib at 8x want
     * opposite answers.
     */
    var dynamicWidth: Boolean = false
) {
    /**
     * An immutable snapshot of the current values.
     *
     * [ToolConfig] is deliberately not Compose state, because the drawing surface has to read it
     * synchronously the moment a pointer lands. That means the UI cannot observe it directly, and
     * a bare read of a revision counter is not reliably treated as a state read. Taking a
     * snapshot keyed on that counter is what makes the toolbar update the instant a value
     * changes rather than on the next unrelated recomposition.
     */
    fun snapshot() = ToolSnapshot(
        tool, brush, color, strokeWidth, opacity, dash, fillStyle, fillColor,
        eraserRadius, eraserMode, textSize, smoothing, dynamicWidth
    )

    fun copyFrom(other: ToolConfig) {
        tool = other.tool
        brush = other.brush
        color = other.color
        strokeWidth = other.strokeWidth
        opacity = other.opacity
        dash = other.dash
        fillStyle = other.fillStyle
        fillColor = other.fillColor
        eraserRadius = other.eraserRadius
        eraserMode = other.eraserMode
        textSize = other.textSize
        smoothing = other.smoothing
        pressureGamma = other.pressureGamma
        pressureMin = other.pressureMin
        dynamics = other.dynamics
        previousBrush = other.previousBrush
        dynamicWidth = other.dynamicWidth
    }

    /**
     * Turn the highlighter on or off, restoring whatever pen was in use before.
     *
     * Without this, leaving the highlighter meant opening the brush list and picking a pen back
     * out of it - several taps to undo one.
     */
    fun toggleHighlighter() {
        if (brush == BrushType.HIGHLIGHTER) {
            brush = previousBrush
            strokeWidth = previousBrush.defaultWidth
        } else {
            previousBrush = brush
            brush = BrushType.HIGHLIGHTER
            strokeWidth = BrushType.HIGHLIGHTER.defaultWidth
        }
        tool = Tool.DRAW
    }

    fun selectBrush(b: BrushType) {
        // adopt the brush's natural width unless the user had tuned it by hand
        val wasDefault = kotlin.math.abs(strokeWidth - brush.defaultWidth) < 0.01f
        if (b != BrushType.HIGHLIGHTER) previousBrush = b
        brush = b
        if (wasDefault) strokeWidth = b.defaultWidth
        tool = Tool.DRAW
    }
}

/** Read-only view of a [ToolConfig] for the UI to render from. */
data class ToolSnapshot(
    val tool: Tool,
    val brush: BrushType,
    val color: Int,
    val strokeWidth: Float,
    val opacity: Float,
    val dash: DashStyle,
    val fillStyle: FillStyle,
    val fillColor: Int,
    val eraserRadius: Float,
    val eraserMode: EraserMode,
    val textSize: Float,
    val smoothing: Float,
    val dynamicWidth: Boolean
)
