package com.inkslate.core

/**
 * Things a pedal, a key or an on-screen action button can do while reading, rehearsing or
 * performing.
 *
 * A Bluetooth page-turn pedal is a keyboard as far as either system is concerned - it sends Page
 * Down, an arrow, or a media key - so pedals, keys and buttons all come down to one of these, and
 * each platform only has to say which key is which.
 *
 * The pen tools are here too, because in a rehearsal a note has to go on the part in a second
 * and the music has to be back before the downbeat: the pen always writes, even in fullscreen,
 * and switching it to the highlighter or the eraser is one tap on the strip over the page (or
 * one pedal) - never a trip out of fullscreen to the toolbar.
 */
enum class PerformAction(val label: String) {
    NEXT_PAGE("Next page"),
    PREVIOUS_PAGE("Previous page"),
    HALF_PAGE_FORWARD("Half a page on"),
    HALF_PAGE_BACK("Half a page back"),
    FIRST_PAGE("First page"),
    LAST_PAGE("Last page"),
    NEXT_SONG("Next song in the setlist"),
    PREVIOUS_SONG("Previous song in the setlist"),
    METRONOME("Start or stop the metronome"),
    TUNER("Open the tuner"),
    PEN("Pen"),
    HIGHLIGHTER("Highlighter"),
    ERASER("Eraser"),
    UNDO("Undo"),
    REDO("Redo"),
    FULLSCREEN("Fullscreen on or off");

    /** Handled by the document in front. */
    val forDocument: Boolean
        get() = this in setOf(NEXT_PAGE, PREVIOUS_PAGE, HALF_PAGE_FORWARD, HALF_PAGE_BACK, FIRST_PAGE, LAST_PAGE, UNDO, REDO)

    /** Handled by the workspace: the tools every document shares, and fullscreen. */
    val forWorkspace: Boolean
        get() = this in setOf(PEN, HIGHLIGHTER, ERASER, FULLSCREEN)
}

/**
 * Where performance actions go.
 *
 * Page turns and undo go to the document in front ([document], set by whichever editor has
 * focus); pen tools and fullscreen to the workspace ([workspace]); song, metronome and tuner
 * actions to the app ([app], set by InkSheets - InkSlate has no setlists). A page turn the document
 * cannot make - the last page going forward, the first going back - is passed on as a song turn,
 * which is what a pedal under a musician's foot should do at the end of a piece.
 */
object Perform {

    /** Returns false when it could not do it (already on the last page, say). */
    @Volatile
    var document: ((PerformAction) -> Boolean)? = null

    @Volatile
    var workspace: ((PerformAction) -> Boolean)? = null

    @Volatile
    var app: ((PerformAction) -> Boolean)? = null

    /**
     * Whether a toggle-like action is currently on - the pen tool in use, fullscreen - so a button
     * can show it lit. Reads the workspace's own state, so a screen reading it redraws when it
     * changes.
     */
    @Volatile
    var isOn: ((PerformAction) -> Boolean)? = null

    fun run(action: PerformAction): Boolean {
        if (action.forWorkspace) return workspace?.invoke(action) ?: false
        if (!action.forDocument) return app?.invoke(action) ?: false
        if (document?.invoke(action) == true) return true
        return when (action) {
            PerformAction.NEXT_PAGE, PerformAction.HALF_PAGE_FORWARD -> app?.invoke(PerformAction.NEXT_SONG) ?: false
            PerformAction.PREVIOUS_PAGE, PerformAction.HALF_PAGE_BACK -> app?.invoke(PerformAction.PREVIOUS_SONG) ?: false
            else -> false
        }
    }

    fun on(action: PerformAction): Boolean = isOn?.invoke(action) ?: false
}

/**
 * The three pen tools a musician switches between mid-rehearsal, applied to a pen profile.
 *
 * Switching to the highlighter keeps what the pen was, so coming back to the pen restores the
 * same colour and width rather than a default - the switch has to cost nothing to undo.
 */
object QuickTools {

    private var savedPen: Triple<BrushType, Int, Float>? = null

    fun current(c: ToolConfig): PerformAction? = when {
        c.tool == Tool.ERASER -> PerformAction.ERASER
        c.tool == Tool.DRAW && c.brush.isHighlighter -> PerformAction.HIGHLIGHTER
        c.tool == Tool.DRAW -> PerformAction.PEN
        else -> null
    }

    fun apply(c: ToolConfig, action: PerformAction) {
        if (c.tool == Tool.DRAW && !c.brush.isHighlighter) savedPen = Triple(c.brush, c.color, c.strokeWidth)
        when (action) {
            PerformAction.PEN -> {
                c.tool = Tool.DRAW
                if (c.brush.isHighlighter) {
                    val (brush, color, width) = savedPen ?: Triple(BrushType.BALLPOINT, 0xFF000000.toInt(), BrushType.BALLPOINT.defaultWidth)
                    c.brush = brush
                    c.color = color
                    c.strokeWidth = width
                }
            }
            PerformAction.HIGHLIGHTER -> {
                c.tool = Tool.DRAW
                if (!c.brush.isHighlighter) {
                    c.brush = BrushType.HIGHLIGHTER
                    c.color = 0xFFFDE047.toInt()
                    c.strokeWidth = BrushType.HIGHLIGHTER.defaultWidth
                }
            }
            PerformAction.ERASER -> c.tool = Tool.ERASER
            else -> Unit
        }
    }
}
