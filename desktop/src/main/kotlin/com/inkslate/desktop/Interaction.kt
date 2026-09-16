package com.inkslate.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * What the Escape key should do right now.
 *
 * Android has a back button and a `BackHandler` to claim it; Windows has neither, so the screen
 * currently on top says what Escape means and the window's key handler calls it. Null means
 * nothing is stacked up - Escape then does nothing, rather than closing the application out from
 * under an unsaved document.
 */
class NavigationHooks {
    var back: (() -> Unit)? = null
}

/**
 * Right-click, for the menu a long press opens on the tablet.
 *
 * The handler is captured once on purpose: every caller passes a lambda that only writes to
 * remembered state, so re-arming the gesture on each recomposition would cost more than it buys.
 */
fun Modifier.secondaryClick(onClick: () -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                event.changes.forEach { it.consume() }
                onClick()
            }
        }
    }
}

/**
 * A click that says whether Ctrl or Shift was held, for picking several things out of a grid.
 *
 * Fired on release, and only when the pointer has not travelled: a touch that starts on a page and
 * turns into scrolling the grid is not a choice of that page. A press something inside has already
 * claimed - the tick in a page's corner - is left to it.
 */
fun Modifier.selectClick(onClick: (ctrl: Boolean, shift: Boolean) -> Unit): Modifier = composed {
    val latest by rememberUpdatedState(onClick)
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val down = awaitPointerEvent()
                if (down.type != PointerEventType.Press || down.buttons.isSecondaryPressed) continue
                val start = down.changes.firstOrNull()?.position ?: continue
                if (down.changes.any { it.isConsumed }) continue
                var travelled = false
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: break
                    if ((change.position - start).getDistance() > viewConfiguration.touchSlop) {
                        travelled = true
                    }
                    if (event.type == PointerEventType.Release) {
                        if (!travelled && !change.isConsumed) {
                            latest(
                                event.keyboardModifiers.isCtrlPressed,
                                event.keyboardModifiers.isShiftPressed
                            )
                        }
                        break
                    }
                }
            }
        }
    }
}
