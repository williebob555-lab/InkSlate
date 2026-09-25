package com.inksheets.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
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
        val picked = if (down.type == PointerType.Mouse) {
            awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
        } else {
            awaitLongPressOrCancellation(down.id)
        } ?: return@awaitEachGesture
        onStart(picked.position)
        val finished = drag(picked.id) { change ->
            onDrag(change.positionChange())
            change.consume()
        }
        if (finished) onEnd() else onCancel()
    }
}
