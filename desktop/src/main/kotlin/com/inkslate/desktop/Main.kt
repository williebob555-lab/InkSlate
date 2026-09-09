package com.inkslate.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/**
 * The Windows build.
 *
 * Deliberately the same application as the tablet's rather than a companion to it: the same four
 * screens, the same palette, the same document format, and `:core` shared between them so the two
 * cannot disagree about what a document contains. A file synced from the tablet opens here with
 * its handwriting already on it, and nothing to copy across.
 *
 * The window itself owns only the keyboard. A desktop user reaches for Ctrl+S long before a
 * toolbar, Ctrl+Z is not optional anywhere, and Escape is the closest thing Windows has to
 * Android's back button - so the screen on top says what each of them means and this handler
 * calls it.
 */
fun main() = application {
    val state = rememberWindowState(width = 1200.dp, height = 900.dp)
    val shortcuts = remember { Shortcuts() }
    val navigation = remember { NavigationHooks() }

    Window(
        onCloseRequest = ::exitApplication,
        state = state,
        title = "InkSlate",
        onKeyEvent = { event ->
            if (event.type != KeyEventType.KeyDown) false
            else if (!event.isCtrlPressed) {
                when (event.key) {
                    Key.Escape -> navigation.back?.let { it(); true } ?: false
                    else -> false
                }
            } else when (event.key) {
                Key.S -> { shortcuts.save?.invoke(); true }
                Key.Z -> { shortcuts.undo?.invoke(); true }
                Key.Y -> { shortcuts.redo?.invoke(); true }
                Key.W -> { shortcuts.close?.invoke(); true }
                else -> false
            }
        }
    ) {
        InkSlateTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                AppRoot(shortcuts, navigation)
            }
        }
    }
}

/**
 * Actions the window's key handler can invoke, filled in by whichever screen is on top.
 *
 * Cleared on every pass through [AppRoot] so a shortcut cannot outlive the screen that meant
 * something by it - Ctrl+S on the home screen saving the document you closed a minute ago would
 * be a genuinely bad surprise.
 */
class Shortcuts {
    var save: (() -> Unit)? = null
    var undo: (() -> Unit)? = null
    var redo: (() -> Unit)? = null
    var close: (() -> Unit)? = null

    fun clear() {
        save = null
        undo = null
        redo = null
        close = null
    }
}
