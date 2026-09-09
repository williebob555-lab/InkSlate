package com.inkslate.desktop

import androidx.compose.ui.Modifier
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
