package com.inkslate.desktop

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.nativeKeyCode

/**
 * What each key does, on the same terms as what each button does.
 *
 * The keyboard was a list of conditions in the window's key handler - the same shape the pointer
 * had before it became a table, and with the same problem: a shortcut somebody wants is either
 * already there or cannot be had, and there is nowhere to look up what is.
 */
enum class KeyAction(val label: String, val perform: com.inkslate.core.PerformAction? = null) {
    SAVE("Save"),
    UNDO("Undo"),
    REDO("Redo"),
    CLOSE("Close the document"),
    COPY("Copy"),
    CUT("Cut"),
    PASTE("Paste"),
    DELETE("Delete what is selected"),
    SELECT_ALL("Select everything"),
    ZOOM_IN("Zoom in"),
    ZOOM_OUT("Zoom out"),
    RESET_ZOOM("Fit the width"),
    SHAPES("Open the shapes tray, with the last shape in hand"),
    BACK("Close whatever is open"),

    // Page turns and the rest of what a Bluetooth pedal is for. A pedal is a keyboard to the
    // computer, so it is set up here like any other key: press it where the key goes.
    NEXT_PAGE("Next page", com.inkslate.core.PerformAction.NEXT_PAGE),
    PREVIOUS_PAGE("Previous page", com.inkslate.core.PerformAction.PREVIOUS_PAGE),
    HALF_PAGE_FORWARD("Half a page on", com.inkslate.core.PerformAction.HALF_PAGE_FORWARD),
    HALF_PAGE_BACK("Half a page back", com.inkslate.core.PerformAction.HALF_PAGE_BACK),
    FIRST_PAGE("First page", com.inkslate.core.PerformAction.FIRST_PAGE),
    LAST_PAGE("Last page", com.inkslate.core.PerformAction.LAST_PAGE),
    NEXT_SONG("Next song in the setlist", com.inkslate.core.PerformAction.NEXT_SONG),
    PREVIOUS_SONG("Previous song in the setlist", com.inkslate.core.PerformAction.PREVIOUS_SONG),
    METRONOME("Start or stop the metronome", com.inkslate.core.PerformAction.METRONOME),
    TUNER("Open the tuner", com.inkslate.core.PerformAction.TUNER)
}

/** A key with the modifiers held down with it. */
data class KeyStroke(val code: Long, val ctrl: Boolean = false, val shift: Boolean = false) {

    /** How to write it down, in the words the keyboard itself uses. */
    fun label(): String = buildString {
        if (ctrl) append("Ctrl+")
        if (shift) append("Shift+")
        append(
            runCatching { java.awt.event.KeyEvent.getKeyText(Key(code).nativeKeyCode) }
                .getOrDefault("key $code")
        )
    }

    fun toText(): String = "$code:${if (ctrl) 1 else 0}${if (shift) 1 else 0}"

    companion object {
        fun fromText(text: String): KeyStroke? {
            val at = text.indexOf(':')
            if (at <= 0 || text.length < at + 3) return null
            val code = text.substring(0, at).toLongOrNull() ?: return null
            return KeyStroke(code, text[at + 1] == '1', text[at + 2] == '1')
        }
    }
}

/**
 * The one copy of what each key does.
 *
 * Defaults can hold more than one key for an action - undo has been both Ctrl+Z and Ctrl+Shift+Z's
 * partner for as long as either has existed - and a key somebody chooses replaces the lot for that
 * action, so there is never a shortcut that cannot be got rid of.
 */
object KeyBindingStore {

    private const val KEY = "key_bindings"

    val DEFAULTS: Map<KeyAction, List<KeyStroke>> = mapOf(
        KeyAction.SAVE to listOf(KeyStroke(Key.S.keyCode, ctrl = true)),
        KeyAction.UNDO to listOf(KeyStroke(Key.Z.keyCode, ctrl = true)),
        KeyAction.REDO to listOf(
            KeyStroke(Key.Y.keyCode, ctrl = true),
            KeyStroke(Key.Z.keyCode, ctrl = true, shift = true)
        ),
        KeyAction.CLOSE to listOf(KeyStroke(Key.W.keyCode, ctrl = true)),
        KeyAction.COPY to listOf(KeyStroke(Key.C.keyCode, ctrl = true)),
        KeyAction.CUT to listOf(KeyStroke(Key.X.keyCode, ctrl = true)),
        KeyAction.PASTE to listOf(KeyStroke(Key.V.keyCode, ctrl = true)),
        KeyAction.SELECT_ALL to listOf(KeyStroke(Key.A.keyCode, ctrl = true)),
        KeyAction.ZOOM_IN to listOf(
            KeyStroke(Key.Equals.keyCode, ctrl = true),
            KeyStroke(Key.Plus.keyCode, ctrl = true)
        ),
        KeyAction.ZOOM_OUT to listOf(KeyStroke(Key.Minus.keyCode, ctrl = true)),
        KeyAction.RESET_ZOOM to listOf(KeyStroke(Key.Zero.keyCode, ctrl = true)),
        KeyAction.DELETE to listOf(
            KeyStroke(Key.Delete.keyCode),
            KeyStroke(Key.Backspace.keyCode)
        ),
        KeyAction.BACK to listOf(KeyStroke(Key.Escape.keyCode)),
        // What page-turn pedals send out of the box: AirTurn and PageFlip both default to one of
        // Page Down/Up or the arrows, depending on the model and its mode.
        KeyAction.NEXT_PAGE to listOf(
            KeyStroke(Key.PageDown.keyCode),
            KeyStroke(Key.DirectionRight.keyCode),
            KeyStroke(Key.DirectionDown.keyCode)
        ),
        KeyAction.PREVIOUS_PAGE to listOf(
            KeyStroke(Key.PageUp.keyCode),
            KeyStroke(Key.DirectionLeft.keyCode),
            KeyStroke(Key.DirectionUp.keyCode)
        ),
        KeyAction.FIRST_PAGE to listOf(KeyStroke(Key.MoveHome.keyCode)),
        KeyAction.LAST_PAGE to listOf(KeyStroke(Key.MoveEnd.keyCode)),
        KeyAction.NEXT_SONG to listOf(KeyStroke(Key.PageDown.keyCode, ctrl = true)),
        KeyAction.PREVIOUS_SONG to listOf(KeyStroke(Key.PageUp.keyCode, ctrl = true))
    )

    private val state = mutableStateOf(read())

    val chosen: Map<KeyAction, KeyStroke> get() = state.value

    /**
     * The key that runs [action] now, whether it was chosen or came with the program.
     *
     * A default that somebody has since given to another action is not offered: it does not run
     * this one any more, and showing it would say that pressing it saves when pressing it zooms.
     * Where an action came with more than one key, the next one that is still free stands in.
     */
    fun strokeFor(action: KeyAction): KeyStroke? {
        state.value[action]?.let { return it }
        val taken = state.value.values.toSet()
        return DEFAULTS[action]?.firstOrNull { it !in taken }
    }

    /**
     * What a key does, or nothing.
     *
     * A chosen key wins outright, and it also takes the action's defaults out of play - otherwise
     * moving save to another key would leave the old one still saving.
     */
    fun actionFor(stroke: KeyStroke): KeyAction? {
        state.value.entries.firstOrNull { it.value == stroke }?.let { return it.key }
        return DEFAULTS.entries
            .firstOrNull { (action, keys) -> action !in state.value && stroke in keys }
            ?.key
    }

    fun bind(action: KeyAction, stroke: KeyStroke) {
        // One key does one thing: whatever else claimed it gives it up.
        val without = state.value.filterValues { it != stroke }
        write(without + (action to stroke))
    }

    fun forget(action: KeyAction) = write(state.value - action)

    fun reset() = write(emptyMap())

    private fun write(map: Map<KeyAction, KeyStroke>) {
        state.value = map
        DesktopPrefs.put(
            KEY,
            map.entries.joinToString(";") { "${it.key.name}=${it.value.toText()}" }
        )
    }

    private fun read(): Map<KeyAction, KeyStroke> {
        val text = DesktopPrefs.get(KEY) ?: return emptyMap()
        return text.split(';').mapNotNull { row ->
            val at = row.indexOf('=')
            if (at <= 0) return@mapNotNull null
            val action = KeyAction.entries.firstOrNull { it.name == row.substring(0, at) }
                ?: return@mapNotNull null
            val stroke = KeyStroke.fromText(row.substring(at + 1)) ?: return@mapNotNull null
            action to stroke
        }.toMap()
    }
}
