package com.inksheets.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange

/**
 * A whole row as its own handle. A mouse picks it up as soon as it moves; a finger or pen holds it
 * a moment first, so a swipe still scrolls the list and a tap still opens it.
 *
 * [onStart] hears where in the row it was picked up; [onDrag] each movement.
 */
internal fun Modifier.dragAnywhere(
    key: Any?,
    onStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit
): Modifier = pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val pickedAt: androidx.compose.ui.geometry.Offset = if (down.type == PointerType.Mouse) {
            (awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture).position
        } else {
            // A finger holds the row a moment - a sixth of a second, barely noticed - so a swipe
            // that is already moving still scrolls the list and a tap still opens it.
            val stillHeld = withTimeoutOrNull(HOLD_MS) {
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                    if (!change.pressed) return@withTimeoutOrNull false
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull false
                }
                @Suppress("UNREACHABLE_CODE") false
            }
            if (stillHeld != null) return@awaitEachGesture   // lifted or moved first: not a drag
            down.position
        }
        onStart(pickedAt)
        val finished = drag(down.id) { change ->
            onDrag(change.positionChange())
            change.consume()
        }
        if (finished) onEnd() else onCancel()
    }
}

/** How long a finger holds a row before it can be dragged. */
private const val HOLD_MS = 150L
