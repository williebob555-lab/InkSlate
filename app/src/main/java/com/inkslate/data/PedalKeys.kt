package com.inkslate.data

import android.content.Context
import android.view.KeyEvent
import com.inkslate.core.Perform
import com.inkslate.core.PerformAction

/**
 * Page-turn pedals and keyboards on the tablet.
 *
 * A Bluetooth pedal pairs as a keyboard and sends ordinary keys - Page Down, an arrow, Space, a
 * media key - depending on its make and the mode it is in. So a pedal is set up the way a key
 * is: this table says which key does which [PerformAction], with defaults that cover the common
 * pedals out of the box, and Settings lets a key be pressed to reassign it.
 */
object PedalKeys {

    val DEFAULTS: Map<Int, PerformAction> = mapOf(
        KeyEvent.KEYCODE_PAGE_DOWN to PerformAction.NEXT_PAGE,
        KeyEvent.KEYCODE_DPAD_RIGHT to PerformAction.NEXT_PAGE,
        KeyEvent.KEYCODE_DPAD_DOWN to PerformAction.NEXT_PAGE,
        KeyEvent.KEYCODE_MEDIA_NEXT to PerformAction.NEXT_PAGE,
        KeyEvent.KEYCODE_PAGE_UP to PerformAction.PREVIOUS_PAGE,
        KeyEvent.KEYCODE_DPAD_LEFT to PerformAction.PREVIOUS_PAGE,
        KeyEvent.KEYCODE_DPAD_UP to PerformAction.PREVIOUS_PAGE,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS to PerformAction.PREVIOUS_PAGE,
        KeyEvent.KEYCODE_MOVE_HOME to PerformAction.FIRST_PAGE,
        KeyEvent.KEYCODE_MOVE_END to PerformAction.LAST_PAGE
    )

    private const val PREFS = "pedal_keys"

    /** The table in force: the defaults with every choice made in Settings laid over them. */
    fun table(context: Context): Map<Int, PerformAction> {
        val chosen = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
        val table = DEFAULTS.toMutableMap()
        for ((key, value) in chosen) {
            val code = key.toIntOrNull() ?: continue
            val action = PerformAction.entries.firstOrNull { it.name == value }
            if (action == null) table.remove(code) else table[code] = action
        }
        return table
    }

    /** Make [keyCode] do [action]; null makes it do nothing. */
    fun bind(context: Context, keyCode: Int, action: PerformAction?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(keyCode.toString(), action?.name ?: "")
            .apply()
    }

    fun reset(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()

    /** Set while Settings is waiting for a pedal to be pressed; the next key goes here instead. */
    @Volatile
    var learning: ((Int) -> Unit)? = null

    /**
     * Called by the activity before anything else sees a key. Returns true when the key was a
     * pedal and has been dealt with.
     *
     * [typing] is true while a text field has the keyboard: then the arrows move the cursor and
     * nothing here takes them.
     */
    fun handle(context: Context, event: KeyEvent, typing: Boolean): Boolean {
        learning?.let { learn ->
            if (event.action == KeyEvent.ACTION_UP) {
                learning = null
                learn(event.keyCode)
            }
            return true
        }
        if (typing) return false
        val action = table(context)[event.keyCode] ?: return false
        // Act on the press; swallow the release and any auto-repeat, so a pedal held a moment too
        // long turns one page, not five.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) Perform.run(action)
        return true
    }

    /** What a key is called, for the settings list. */
    fun name(keyCode: Int): String =
        KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").replace('_', ' ')
            .lowercase().replaceFirstChar { it.uppercase() }
}
