package com.inkslate.library

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import java.io.File

/**
 * Picking documents up and dropping them on a folder - the way a file is moved everywhere else.
 *
 * Moving used to be: long press, Move, find the folder in a list, tap it. Now a document is
 * dragged onto a folder tile, onto a step of the path at the top (to move it up), or onto the
 * strip of folders that comes up at the bottom while something is being carried - which is there
 * so a folder scrolled off screen, or one inside another, can still be reached. Resting on a
 * folder in that strip opens it, to go deeper.
 *
 * Positions are all in the window's coordinates, which is what [boundsInRoot] and
 * [LayoutCoordinates.localToRoot] give.
 */
@Stable
class DragToFolder {
    /** What is being carried, or empty when nothing is. */
    var carrying by mutableStateOf<List<File>>(emptyList())
        private set
    /** Where the finger or the pointer is, in the window. */
    var pointer by mutableStateOf(Offset.Zero)
        private set

    /**
     * Everything that can be dropped on, by a key of its own. Plain rather than state: it is
     * written on every layout, and only read again when the pointer - which is state - moves.
     */
    private val targets = LinkedHashMap<String, Pair<File, Rect>>()

    val active: Boolean get() = carrying.isNotEmpty()

    /** The folder under the pointer, if it is somewhere the carried things can go. */
    val over: File?
        get() {
            if (!active) return null
            // The strip along the bottom sits over the page, so it wins where the two overlap.
            val hits = targets.entries.filter { it.value.second.contains(pointer) }
            val hit = (hits.firstOrNull { it.key.startsWith(DOCK) } ?: hits.lastOrNull())?.value?.first
                ?: return null
            return hit.takeIf { canDrop(it) }
        }

    /** Not into itself, and not where every carried thing already is. */
    fun canDrop(folder: File): Boolean = carrying.isNotEmpty() && carrying.all { item ->
        item.absolutePath != folder.absolutePath &&
            !folder.absolutePath.startsWith(item.absolutePath + File.separator)
    } && carrying.any { it.parentFile?.absolutePath != folder.absolutePath }

    fun start(items: List<File>, at: Offset) { carrying = items; pointer = at }
    fun moveTo(at: Offset) { pointer = at }

    /** Lets go: the folder it landed on, or null. */
    fun end(): File? {
        val landed = over
        carrying = emptyList()
        return landed
    }

    fun cancel() { carrying = emptyList() }

    fun register(key: String, folder: File, bounds: Rect) { targets[key] = folder to bounds }
    fun unregister(key: String) { targets.remove(key) }
}

/** Keys of drop targets in the strip that comes up while dragging. */
const val DOCK = "dock:"

/** Marks [folder] as somewhere things can be dropped, under [key], for as long as it is shown. */
fun Modifier.dropTarget(drag: DragToFolder, key: String, folder: File): Modifier = composed {
    DisposableEffect(key) { onDispose { drag.unregister(key) } }
    onGloballyPositioned { drag.register(key, folder, it.boundsInRoot()) }
}

/**
 * A tap opens, a long press (or a right-click) brings up the menu, and a long press that then
 * moves - or, with a mouse, just pressing and moving - picks the thing up to drag onto a folder.
 *
 * Done as one gesture rather than a click handler and a drag handler side by side, because the
 * two would both claim the same long press. A finger that moves before the long press is the list
 * being scrolled, and is left to it.
 */
fun Modifier.openMenuOrDrag(
    drag: DragToFolder,
    label: String,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    /** What to pick up: the thing itself, or everything selected along with it. */
    carry: () -> List<File>,
    onDrop: (List<File>, File) -> Unit
): Modifier = composed {
    val haptics = LocalHapticFeedback.current
    val open by rememberUpdatedState(onOpen)
    val menu by rememberUpdatedState(onMenu)
    val pick by rememberUpdatedState(carry)
    val drop by rememberUpdatedState(onDrop)
    val where = remember { arrayOfNulls<LayoutCoordinates>(1) }

    fun toRoot(c: PointerInputChange): Offset =
        where[0]?.takeIf { it.isAttached }?.localToRoot(c.position) ?: c.position

    this
        .onGloballyPositioned { where[0] = it }
        .semantics {
            onClick(label = "Open") { open(); true }
            onLongClick(label = "More actions") { menu(); true }
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (currentEvent.buttons.isSecondaryPressed) {
                    down.consume()
                    menu()
                    return@awaitEachGesture
                }
                val mouse = down.type == PointerType.Mouse
                var lifted = false
                var moved = false
                val waited = withTimeoutOrNull(if (mouse) Long.MAX_VALUE else viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) { lifted = true; if (!c.isConsumed) { c.consume() }; break }
                        if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            moved = true; break
                        }
                    }
                }
                if (waited != null) {
                    if (lifted && !moved) open()
                    // A finger that moved early is a scroll; a mouse that moved is a drag.
                    if (!(mouse && moved)) return@awaitEachGesture
                }
                // Held (a finger), or pressed and moved (a mouse): pick it up.
                if (!mouse) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                val start = down.position
                var travelled = mouse
                var dragStarted = false
                if (mouse) { drag.start(pick(), where[0]?.localToRoot(start) ?: start); dragStarted = true }
                while (true) {
                    val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) { c.consume(); break }
                    if (!travelled && (c.position - start).getDistance() > viewConfiguration.touchSlop) {
                        travelled = true
                    }
                    if (travelled && !dragStarted) { drag.start(pick(), toRoot(c)); dragStarted = true }
                    if (dragStarted) drag.moveTo(toRoot(c))
                    c.consume()
                }
                if (!dragStarted) {
                    // A long press that stayed put: the menu, as before.
                    menu()
                    return@awaitEachGesture
                }
                val items = drag.carrying
                drag.end()?.let { folder -> drop(items, folder) }
            }
        }
}
