package com.inkslate.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce

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
fun main() {
    EventLog.installCrashHandler()
    EventLog.info("app", "InkSlate ${DesktopUpdates.installedVersion()} started")
    ui()
}

@OptIn(ExperimentalComposeUiApi::class, kotlinx.coroutines.FlowPreview::class)
private fun ui() = application {
    // Where it was left, rather than the middle of the screen at a fixed size every time.
    val state = remember { WindowMemory.restore() }

    // The screen can change shape under the window - the machine folds into a tablet and the
    // display turns - and the toolkit has no event for it, so it is looked at rather than waited
    // for. A window already fitting is left exactly where it is.
    LaunchedEffect(state) {
        var lastScreen = WindowMemory.screenSize()
        var lastPosture = TouchKeyboard.inTabletPosture()
        while (true) {
            delay(1_000)

            val screen = WindowMemory.screenSize()
            if (screen != lastScreen) {
                lastScreen = screen
                WindowMemory.refit(state)
            }

            // Folded into a tablet, the program should fill the screen the way it does on one.
            // Only the change is acted on, so a window maximised or restored by hand in either
            // posture stays how it was put.
            val posture = TouchKeyboard.inTabletPosture()
            if (posture != lastPosture) {
                lastPosture = posture
                WindowMemory.postureChanged(state, tablet = posture)
            }
        }
    }

    // Saved as it settles rather than on every pixel of a drag.
    LaunchedEffect(state) {
        snapshotFlow { Triple(state.size, state.position, state.placement) }
            .debounce(400)
            .collect { WindowMemory.remember(state) }
    }
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
                    Key.Escape -> shortcuts.fire(navigation.back)
                    Key.Delete, Key.Backspace -> shortcuts.fire(shortcuts.delete)
                    else -> false
                }
            } else when (event.key) {
                Key.S -> shortcuts.fire(shortcuts.save)
                // Ctrl+Shift+Z is the other half of undo everywhere except Windows' own apps,
                // and costs nothing to accept alongside Ctrl+Y.
                Key.Z -> shortcuts.fire(if (event.isShiftPressed) shortcuts.redo else shortcuts.undo)
                Key.Y -> shortcuts.fire(shortcuts.redo)
                Key.W -> shortcuts.fire(shortcuts.close)
                Key.C -> shortcuts.fire(shortcuts.copy)
                Key.X -> shortcuts.fire(shortcuts.cut)
                Key.V -> shortcuts.fire(shortcuts.paste)
                Key.A -> shortcuts.fire(shortcuts.selectAll)
                Key.Equals, Key.Plus -> shortcuts.fire(shortcuts.zoomIn)
                Key.Minus -> shortcuts.fire(shortcuts.zoomOut)
                Key.Zero -> shortcuts.fire(shortcuts.resetZoom)
                else -> false
            }
        }
    ) {
        // Windows tells the toolkit nothing about pens or fingers, so the window's own message
        // loop is read directly. Retried for a moment because the drawing surface is created a
        // little after the window is, and it is one of the windows that has to be hooked.
        LaunchedEffect(window) {
            repeat(12) {
                if (WindowsPointer.active) return@LaunchedEffect
                WindowsPointer.install(window)
                delay(250)
            }
        }
        DisposableEffect(window) { onDispose { WindowsPointer.uninstall() } }

        // Windows cannot see the text boxes in a drawing surface, so it never offers the
        // on-screen keyboard for them. Every text field in the program starts its input through
        // here, which makes this the one place to ask for it - and the one place to put it away.
        val keyboard = remember {
            PlatformTextInputInterceptor { request, next ->
                val wanted = TouchKeyboard.hasDigitiser() && (
                    TouchKeyboard.inTabletPosture() ||
                        WindowsPointer.device != WindowsPointer.Device.MOUSE
                    )
                if (wanted) TouchKeyboard.show()
                try {
                    next.startInputMethod(request)
                } finally {
                    if (wanted) TouchKeyboard.hide()
                }
            }
        }

        InterceptPlatformTextInput(keyboard) {
            InkSlateTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(shortcuts, navigation)
                }
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
    var copy: (() -> Unit)? = null
    var cut: (() -> Unit)? = null
    var paste: (() -> Unit)? = null
    var delete: (() -> Unit)? = null
    var selectAll: (() -> Unit)? = null
    var zoomIn: (() -> Unit)? = null
    var zoomOut: (() -> Unit)? = null
    var resetZoom: (() -> Unit)? = null

    /**
     * Run a binding if the screen on top set one, and report whether the key was consumed.
     *
     * Reporting honestly matters: a shortcut claimed but not handled swallows the keystroke, so
     * Ctrl+C on a screen with nothing to copy would stop copying from working in a text field.
     */
    fun fire(action: (() -> Unit)?): Boolean {
        action?.invoke() ?: return false
        return true
    }

    fun clear() {
        save = null
        undo = null
        redo = null
        close = null
        copy = null
        cut = null
        paste = null
        delete = null
        selectAll = null
        zoomIn = null
        zoomOut = null
        resetZoom = null
    }
}
