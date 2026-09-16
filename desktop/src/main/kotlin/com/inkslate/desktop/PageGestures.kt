package com.inkslate.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.PointerType
import com.inkslate.core.Box as InkBox
import com.inkslate.core.DynamicWidth
import com.inkslate.core.EraserMode
import com.inkslate.core.InkPoint
import com.inkslate.core.InputAction
import com.inkslate.core.Stroke
import com.inkslate.core.Tool
import kotlin.math.abs

/** How close a click has to be to the ruler's ends and to its bar, in screen pixels. */
private const val RULER_END_TOUCH = 16f
private const val RULER_BAR_TOUCH = 12f

/**
 * Follow the pointer until it lifts, reporting where it ended.
 *
 * An extension on the gesture scope rather than a local function: [awaitEachGesture] runs in a
 * restricted suspension scope, which by design refuses to suspend anywhere except on its own
 * receiver, so a helper that awaits pointer events has to be one of its extensions.
 */
/**
 * Wait for a press from a button that draws.
 *
 * Not [androidx.compose.foundation.gestures.awaitFirstDown], which returns for the primary button
 * and nothing else: Compose marks a pointer pressed for that one alone, so a right-click never
 * began a gesture here at all. The right mouse button is a pen in its own right, with its own
 * colour and width, and none of it could be reached because the gesture never started.
 *
 * The middle button is passed over rather than handled - it pans, on a handler of its own, and it
 * consumes what it uses.
 */
/** How often a mark being drawn offers its extent to the canvas, so it can grow to meet it. */
private const val GROW_EVERY_NS = 120_000_000L

/** The extent of the points collected so far, for a canvas deciding whether to grow. */
private fun boundsOf(points: List<InkPoint>): InkBox {
    var left = Float.MAX_VALUE
    var top = Float.MAX_VALUE
    var right = -Float.MAX_VALUE
    var bottom = -Float.MAX_VALUE
    for (p in points) {
        if (p.x < left) left = p.x
        if (p.x > right) right = p.x
        if (p.y < top) top = p.y
        if (p.y > bottom) bottom = p.y
    }
    return InkBox(left, top, right, bottom)
}

suspend fun AwaitPointerEventScope.awaitDrawingDown(): PointerInputChange {
    while (true) {
        val event = awaitPointerEvent()
        if (event.type != PointerEventType.Press) continue

        // Only the two buttons that draw. A pen or a finger presses nothing at all - there are no
        // buttons on the event - so anything without buttons is a contact and draws; anything with
        // only the others is the middle button, which pans, or a thumb button, which was drawing
        // with the left pen from the moment the right one was allowed to draw at all.
        val buttons = event.buttons
        val draws = buttons.isPrimaryPressed || buttons.isSecondaryPressed
        val elsewhere = buttons.isTertiaryPressed ||
            buttons.isBackPressed ||
            buttons.isForwardPressed
        if (elsewhere && !draws) continue
        // Taken whether or not it has been consumed, as the call this replaces did explicitly:
        // something upstream having looked at the press is not a reason to refuse to draw.
        return event.changes.firstOrNull() ?: continue
    }
}

suspend fun AwaitPointerEventScope.dragUntilRelease(
    start: Offset,
    onMove: (PointerInputChange, PointerKeyboardModifiers) -> Unit
): Offset {
    var last = start
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull() ?: break
        if (event.type == PointerEventType.Move) {
            last = change.position
            // The modifiers come off the event, so pressing shift part-way through a drag starts
            // snapping immediately - which is how it gets used: draw the line, then straighten it.
            onMove(change, event.keyboardModifiers)
        }
        // A pointer is "pressed" only for the primary button, so a right-button drag would have
        // ended on its first event - one point, and no stroke. What holds a drag open is any
        // drawing button still being down, or a finger or pen still on the glass.
        val held = change.pressed ||
            event.buttons.isPrimaryPressed ||
            event.buttons.isSecondaryPressed
        if (!held) break
    }
    return last
}

/**
 * Everything a pointer can do to a page, dispatched by the tool in hand.
 *
 * Works in the page's own coordinates - the space strokes are stored in - by taking the viewport
 * and the slot and converting once at each sample. That is what lets the same code serve every
 * page arrangement: a page in a grid and a page in a column differ only by where their origin is.
 */
suspend fun AwaitPointerEventScope.handlePageGesture(
    down: PointerInputChange,
    slot: PageSlot,
    viewport: Viewport,
    strokes: MutableList<Stroke>,
    selection: Set<String>,
    onSelection: (Set<String>) -> Unit,
    tools: ToolState,
    newId: () -> String,
    onCommitted: (Op) -> Unit,
    onEditText: (Stroke) -> Unit,
    onPlaceText: (Float, Float, Int) -> Unit,
    onLive: (List<InkPoint>) -> Unit,
    onPending: (Stroke?) -> Unit,
    onMarquee: (InkBox?) -> Unit,
    onStampPlaced: () -> Unit,
    /** Told the bounds of whatever was just drawn, so a canvas can grow to fit it. */
    onDrew: (InkBox) -> Unit = {},
    /** A region of the page has been boxed, to be captured as a movable picture. */
    onCaptureRegion: (InkBox, Int) -> Unit = { _, _ -> },
    /**
     * The words a highlighter path crossed, when the document has a text layer.
     *
     * Passed in rather than looked up here because the gesture has no idea which file it is on -
     * and because a scanned page has no text at all, which is exactly the case that must fall back
     * to the freehand mark rather than snapping to nothing.
     */
    wordsUnder: ((Int, List<Pair<Float, Float>>) -> List<InkBox>)? = null,
    /**
     * What this input does, looked up in the table rather than worked out from the device.
     *
     * See InputBindings. This no longer asks what pressed it; it is told what that means. Every
     * device that turned up used to be another branch here, written against the one before it,
     * and each new branch broke a neighbour.
     */
    action: InputAction = InputAction.DRAW_MOUSE
) {
    // The pen the action names, if it names one. Moving the page or erasing keeps whatever pen is
    // in hand, so an erase is the width of the pen doing it.
    if (tools.autoSwitchInput) action.mode?.let { tools.adoptMode(it) }

    // Panning and erasing are tools this build already has, so an input bound to either runs as
    // that tool for the length of one gesture without disturbing what the pen is set to.
    val forced = when (action) {
        InputAction.PAN -> Tool.PAN
        InputAction.ERASE -> Tool.ERASER
        else -> null
    }

    // Read synchronously: the config is deliberately not Compose state so the tool in hand the
    // instant the pointer lands is the one that acts.
    val cfg = tools.active
    val inHand = forced ?: cfg.tool
    val index = slot.index
    val scale = viewport.scale

    /**
     * Screen pixels to the coordinates ink is stored in.
     *
     * The slot's own origin comes off, and the gap between the slot and those coordinates goes
     * back on - see PageSlot.inkLeft. On a canvas that gap is the canvas origin, which is
     * negative and moves further negative every time the canvas grows to meet a mark near its
     * edge. Leaving it out put every mark on a canvas that far from the pen, and further with
     * each growth, while the drawing side subtracted it faithfully.
     */
    fun toPage(p: Offset): Offset {
        val doc = viewport.screenToDoc(p)
        return slot.toInk(doc.x, doc.y)
    }

    val start = toPage(down.position)
    val px = start.x
    val py = start.y

    // Where the app thinks the pointer is, against where the machine says it is. Rate-limited to
    // one line every few seconds; it exists because ink landing away from the pen can only be a
    // disagreement about coordinates, and the numbers say which one.
    PointerDiagnostics.note(down.type, down.position, viewport, start)

    // The straightedge is taken hold of before any tool gets the pointer: it is a physical thing
    // resting on the page, and reaching for it should not depend on which pen is in hand.
    val ruler = tools.ruler
    if (tools.rulerVisible && ruler != null && ruler.page == index) {
        val grab = ruler.grabAt(
            px, py,
            endRadius = RULER_END_TOUCH / scale,
            barTolerance = RULER_BAR_TOUCH / scale
        )
        if (grab != com.inkslate.core.Ruler.Grab.NONE) {
            var last = start
            dragUntilRelease(down.position) { change, _ ->
                val n = toPage(change.position)
                val current = tools.ruler ?: return@dragUntilRelease
                tools.ruler = when (grab) {
                    com.inkslate.core.Ruler.Grab.BAR -> current.movedBy(n.x - last.x, n.y - last.y)
                    else -> current.withEnd(grab, n.x, n.y)
                }
                last = n
            }
            return
        }
    }

    /**
     * Drag one of the selection's handles, and build a stamp again at its new size when let go.
     *
     * A stamp rebuilt rather than stretched keeps its type and its lines their weight, and can be
     * made wider without being made taller. The size it ends at becomes the size the next one of
     * that stamp is placed at.
     */
    suspend fun AwaitPointerEventScope.dragHandle(frame: InkBox, handle: Handle, chosen: List<Stroke>) {
        var current = chosen
        dragUntilRelease(down.position) { change, _ ->
            val n = toPage(change.position)
            val ax = handle.anchorX(frame)
            val ay = handle.anchorY(frame)
            val sx = if (handle.scalesX && abs(frame.width) > 0.01f) {
                ((n.x - ax) / (handle.x(frame) - ax)).coerceIn(-20f, 20f)
            } else 1f
            val sy = if (handle.scalesY && abs(frame.height) > 0.01f) {
                ((n.y - ay) / (handle.y(frame) - ay)).coerceIn(-20f, 20f)
            } else 1f
            if (abs(sx) < 0.02f || abs(sy) < 0.02f) return@dragUntilRelease
            val now = System.currentTimeMillis()
            current = chosen.map { it.scaledAbout(ax, ay, sx, sy, now) }
            strokes.applyEdit(chosen, current)
        }
        if (current === chosen) return
        val tag = com.inkslate.core.Stamps.stampOf(current)
        val kind = tag?.let { com.inkslate.core.Stamps.kindOf(it) }
        if (tag != null && kind != null && !kind.isShape && current.all { it.rotation == 0f }) {
            val rebuilt = com.inkslate.core.Stamps.rebuild(current, tag.options, newId)
            strokes.applyEdit(current, rebuilt)
            current = rebuilt
            onSelection(rebuilt.map { it.id }.toSet())
            com.inkslate.core.Stamps.stampOf(rebuilt)
                ?.let { com.inkslate.core.Stamps.currentBox(it, rebuilt) }
                ?.let { box ->
                    tools.editStampShelf { it.withOptions(kind, it.optionsFor(kind).copy(size = box.width)) }
                    if (tools.armedStamp?.first == kind) tools.arm(kind, tools.stampShelf.optionsFor(kind))
                }
        }
        onCommitted(Op(chosen, current))
    }

    // Something in hand gets first say over the press - but only first say. A press that lets go
    // where it landed places one; a press that moves is whatever the pen does, and unless that is
    // moving the page it puts the item away. So placing five is five clicks, and stopping is just
    // starting to write. The handles of what was just placed still work in between.
    if (tools.hasArmed) {
        val chosen = strokes.filter { it.id in selection }
        val frame = chosen.unionBounds()
        val handle = frame?.let { handleAt(it, px, py, HANDLE_TOUCH / scale) }
        if (frame != null && handle != null) {
            dragHandle(frame, handle, chosen)
            return
        }

        var moved = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: break
            if ((change.position - down.position).getDistance() > PLACE_SLOP) {
                moved = true
                break
            }
            val held = change.pressed ||
                event.buttons.isPrimaryPressed ||
                event.buttons.isSecondaryPressed
            if (!held) break
        }

        if (!moved) {
            val stamp = tools.armedStamp
            val text = tools.armedText
            val placed: List<Stroke> = when {
                stamp != null -> {
                    val (kind, o) = stamp
                    com.inkslate.core.Stamps.build(
                        kind, placementBox(kind, o, px, py, slot.width, slot.height),
                        index, o, group = newId(), nextId = newId
                    )
                }
                text != null -> {
                    val size = cfg.textSize.coerceAtLeast(16f) * 1.25f
                    listOf(
                        Stroke(
                            id = newId(), kind = Stroke.Kind.TEXT, color = cfg.color, baseWidth = 1f,
                            points = listOf(InkPoint(px, py - size / 2f, 1f)),
                            text = text, textSize = size, pageIndex = index,
                            updatedUtc = System.currentTimeMillis()
                        )
                    )
                }
                else -> emptyList()
            }
            if (placed.isNotEmpty()) {
                strokes.addAll(placed)
                onCommitted(Op.added(placed))
                onSelection(placed.map { it.id }.toSet())
                placed.map { it.boundsBox() }.reduce { acc, b -> acc.union(b) }.let(onDrew)
                onStampPlaced()
            }
            return
        }

        if (inHand != Tool.PAN) {
            tools.disarm()
            tools.onPutAway?.invoke()
            onSelection(emptySet())
        }
    }

    when {
        inHand == Tool.PAN -> {
            viewport.stop()
            var last = down.position
            val throwing = PanThrow()
            throwing.begin()
            dragUntilRelease(down.position) { change, _ ->
                val moved = change.position - last
                viewport.panBy(moved.x, moved.y)
                throwing.sample(moved.x, moved.y)
                last = change.position
            }
            // Nothing at all if the hand had stopped before it let go. See PanThrow.
            val thrown = throwing.release()
            viewport.throwBy(thrown.x, thrown.y)
        }

        inHand == Tool.ERASER -> {
            // One rub can touch the same stroke repeatedly, and a partial erase replaces it with
            // pieces the next moment can erase again. So the undo record is built from the two
            // ends only: the strokes as they were when the gesture began, and whatever is left
            // when it finishes. Recording each intermediate step instead put pieces into the
            // "before" list that had never been on the page, and undoing brought them into
            // existence.
            val originals = LinkedHashMap<String, Stroke>()
            val minted = LinkedHashSet<String>()

            fun eraseAt(p: Offset) {
                val r = cfg.eraserRadius
                val hits = strokes.filter { it.pageIndex == index && it.hitTest(p.x, p.y, r) }
                for (hit in hits) {
                    val replacement =
                        if (cfg.eraserMode == EraserMode.STROKE) emptyList()
                        else hit.erasedAt(p.x, p.y, r, newId)
                    if (replacement.size == 1 && replacement[0] === hit) continue
                    if (hit.id !in minted) originals.putIfAbsent(hit.id, hit)
                    replacement.forEach { minted.add(it.id) }
                    strokes.applyEdit(listOf(hit), replacement)
                }
            }

            eraseAt(start)
            dragUntilRelease(down.position) { change, _ -> eraseAt(toPage(change.position)) }
            if (originals.isNotEmpty()) {
                onCommitted(Op(originals.values.toList(), strokes.filter { it.id in minted }))
            }
        }

        inHand == Tool.SELECT -> {
            val chosen = strokes.filter { it.id in selection }
            val box = chosen.unionBounds()
            // The frame and the handle being dragged travel together, so having one is having both.
            val grabbed = box?.let { b ->
                handleAt(b, px, py, HANDLE_TOUCH / scale)?.let { b to it }
            }
            when {
                grabbed != null -> {
                    val (frame, handle) = grabbed
                    dragHandle(frame, handle, chosen)
                }

                box != null && box.expanded(4f / scale).contains(px, py) -> {
                    var current = chosen
                    dragUntilRelease(down.position) { change, _ ->
                        val n = toPage(change.position)
                        val now = System.currentTimeMillis()
                        current = chosen.map { it.movedBy(n.x - px, n.y - py, now) }
                        strokes.applyEdit(chosen, current)
                    }
                    if (current !== chosen) onCommitted(Op(chosen, current))
                }

                else -> {
                    // A tap picks the topmost thing under it; a drag boxes.
                    var dragged = false
                    val end = dragUntilRelease(down.position) { change, _ ->
                        dragged = dragged || (change.position - down.position).getDistance() > 6f
                        if (dragged) {
                            val n = toPage(change.position)
                            onMarquee(InkBox.of(px, py, n.x, n.y))
                        }
                    }
                    if (dragged) {
                        val n = toPage(end)
                        val area = InkBox.of(px, py, n.x, n.y)
                        onSelection(
                            strokes.filter { it.pageIndex == index && it.insideBox(area) }
                                .map { it.id }.toSet()
                        )
                    } else {
                        val hit = strokes.lastOrNull {
                            it.pageIndex == index && it.hitTest(px, py, TAP_SLOP / scale)
                        }
                        onSelection(hit?.let { setOf(it.id) } ?: emptySet())
                    }
                    onMarquee(null)
                }
            }
        }

        inHand == Tool.REGION -> {
            // Box a figure on the page and it becomes a movable object. The rectangle is reported
            // in page coordinates; the editor renders that region and stores the picture, because
            // rendering needs the document and this does not have it.
            var boxed = InkBox.of(px, py, px, py)
            dragUntilRelease(down.position) { change, _ ->
                val n = toPage(change.position)
                boxed = InkBox.of(px, py, n.x, n.y)
                onMarquee(boxed)
            }
            onMarquee(null)
            if (boxed.width > 8f && boxed.height > 8f) onCaptureRegion(boxed, index)
        }

        inHand == Tool.TEXT -> {
            // Land on an existing text box and you are editing it, not stacking a second one
            // on top.
            val existing = strokes.lastOrNull {
                it.pageIndex == index && it.kind == Stroke.Kind.TEXT &&
                    it.hitTest(px, py, TAP_SLOP / scale)
            }
            dragUntilRelease(down.position) { _, _ -> }
            if (existing != null) onEditText(existing) else onPlaceText(px, py, index)
        }

        inHand.isShape || inHand == Tool.TABLE -> {
            val kind = when (inHand) {
                Tool.LINE -> Stroke.Kind.LINE
                Tool.ARROW -> Stroke.Kind.ARROW
                Tool.RECT -> Stroke.Kind.RECT
                Tool.ELLIPSE -> Stroke.Kind.ELLIPSE
                else -> Stroke.Kind.TABLE
            }
            fun build(id: String, bx: Float, by: Float) = shapeStroke(
                id = id, kind = kind, ax = px, ay = py, bx = bx, by = by,
                color = cfg.color, width = cfg.strokeWidth, dash = cfg.dash,
                fill = cfg.fillStyle, fillColor = cfg.fillColor, opacity = cfg.opacity,
                page = index, rows = tools.tableRows, cols = tools.tableCols
            )

            var endX = px
            var endY = py
            dragUntilRelease(down.position) { change, modifiers ->
                val n = toPage(change.position)
                // Shift snaps: squares and circles, and lines to fifteen degrees.
                val (sx, sy) =
                    if (modifiers.isShiftPressed || tools.snapShapes) {
                        Stroke.snapShape(kind, px, py, n.x, n.y)
                    } else {
                        n.x to n.y
                    }
                endX = sx
                endY = sy
                onPending(build("live", sx, sy))
            }
            onPending(null)
            // A click with no drag is not a shape; it is a misplaced click.
            if (abs(endX - px) > 2f || abs(endY - py) > 2f) {
                val s = build(newId(), endX, endY)
                strokes.add(s)
                onCommitted(Op.added(s))
                onDrew(s.boundsBox())
            }
        }

        else -> {
            // Freehand.
            val brush = cfg.brush
            val nominal = DynamicWidth.resolve(
                nominal = cfg.strokeWidth,
                referenceScale = 1f,
                currentScale = scale,
                enabled = cfg.dynamicWidth
            )
            val collected = ArrayList<InkPoint>()
            var lastAt = System.nanoTime()
            var lastPos = down.position
            var smooth = start
            val alpha = 1f - cfg.smoothing.coerceIn(0f, 0.92f)

            // Once a stroke is being ruled it stays ruled to the end, the same way the pen stays
            // against the edge rather than wandering off when the hand drifts.
            val liveRuler = tools.ruler?.takeIf { tools.rulerVisible && it.page == index }
            var ruled = false

            fun sample(screen: Offset, pressure: Float, type: PointerType) {
                val now = System.nanoTime()
                // Speed stands in for pressure when the device does not report it. A dead
                // constant width reads as a machine drawing; this at least thins on fast strokes
                // the way a pen does, and it is what the tablet does for finger input.
                val dt = ((now - lastAt) / 1_000_000f).coerceAtLeast(0.5f)
                val speed = (screen - lastPos).getDistance() / dt
                val fromSpeed = (1f - (speed / 3.2f)).coerceIn(0.25f, 1f)
                // A mouse always reports 1.0, which is not pressure data - it is the absence of
                // it wearing the same value, so speed has to stand in there too. On Windows the
                // pen reports 1.0 as well for the same reason, and the real figure is the one read
                // off the contact message rather than anything in the event.
                val fromPen = WindowsPointer.pressure
                    ?.takeIf { WindowsPointer.active && WindowsPointer.device == WindowsPointer.Device.PEN }
                val reported = when {
                    !tools.pressureEnabled -> 1f
                    fromPen != null -> fromPen
                    type == PointerType.Mouse -> 1f
                    else -> pressure
                }
                val hasPressure = reported in 0.02f..0.98f
                // The user's own curve applies to real pressure and to nothing else. Shaping the
                // speed fallback with it as well would make the pressure settings quietly change
                // how a mouse draws, which is the one device that has no pressure to tune.
                val p = if (hasPressure) {
                    (
                        cfg.pressureMin +
                            (1f - cfg.pressureMin) *
                            Math.pow(reported.toDouble(), cfg.pressureGamma.toDouble()).toFloat()
                        ).coerceIn(0f, 1f)
                } else {
                    fromSpeed
                }
                lastAt = now
                lastPos = screen
                val page = toPage(screen)
                smooth = Offset(
                    smooth.x + (page.x - smooth.x) * alpha,
                    smooth.y + (page.y - smooth.y) * alpha
                )
                var x = smooth.x
                var y = smooth.y
                if (liveRuler != null) {
                    val tolerance =
                        if (ruled) -1f
                        else com.inkslate.core.Ruler.SNAP_POINTS / scale.coerceAtLeast(0.05f)
                    liveRuler.project(x, y, tolerance)?.let { (rx, ry) ->
                        x = rx
                        y = ry
                        ruled = true
                    }
                }
                collected.add(InkPoint(x, y, brush.widthFor(nominal, p, cfg.dynamics)))
            }

            sample(down.position, down.pressure, down.type)
            onLive(ArrayList(collected))
            // A second finger turns what was a stroke into a pinch. The line drawn up to that
            // point has to be dropped rather than committed, or every two-finger zoom leaves a
            // mark across the page from wherever the first finger landed.
            var abandoned = false
            // A canvas grows under the pen, not when it is lifted. Writing past the edge of a
            // whiteboard and having the paper appear only on release means writing into the dark
            // for the length of a word - and on a long stroke, wondering whether it is working.
            var grownAt = 0L
            dragUntilRelease(down.position) { change, _ ->
                if (WindowsPointer.gesturing) {
                    abandoned = true
                    collected.clear()
                    onLive(emptyList())
                } else if (!abandoned) {
                    sample(change.position, change.pressure, change.type)
                    onLive(ArrayList(collected))

                    // Offered a few times a second rather than per sample: growing is free when
                    // there is nothing to grow - the canvas hands back the same one - but the
                    // bounds of the mark so far are not, and a stroke can carry thousands of
                    // points.
                    val now = System.nanoTime()
                    if (now - grownAt > GROW_EVERY_NS && collected.size >= 2) {
                        grownAt = now
                        onDrew(boundsOf(collected))
                    }
                }
            }
            onLive(emptyList())

            if (!abandoned && collected.size >= 2) {
                val drawn = Stroke(
                    id = newId(),
                    kind = Stroke.Kind.FREEHAND,
                    color = cfg.color,
                    baseWidth = nominal,
                    points = collected,
                    brush = brush,
                    dash = cfg.dash,
                    opacity = cfg.opacity,
                    pageIndex = index,
                    updatedUtc = System.currentTimeMillis()
                )
                // What was drawn is what is kept. Tidying rough shapes into lines and circles was an
                // option once; it rewrote handwriting on its own, and it is gone.
                val s = drawn

                // A highlighter dragged across a line becomes clean bars over the words it
                // crossed. Falls back to the freehand mark when there is no text layer, which is
                // what a scanned page is - snapping to nothing would silently erase the mark.
                val bars =
                    if (tools.snapHighlighterToText && s.isHighlighter && s.points.size > 2) {
                        wordsUnder?.invoke(index, s.points.map { it.x to it.y }).orEmpty()
                    } else {
                        emptyList()
                    }
                if (bars.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    val replacements = bars.map { r ->
                        Stroke(
                            id = newId(),
                            kind = Stroke.Kind.FREEHAND,
                            color = s.color,
                            baseWidth = r.height.coerceAtLeast(4f),
                            points = listOf(
                                InkPoint(r.left, r.centerY, r.height),
                                InkPoint(r.right, r.centerY, r.height)
                            ),
                            brush = com.inkslate.core.BrushType.HIGHLIGHTER,
                            opacity = s.opacity,
                            pageIndex = index,
                            updatedUtc = now
                        )
                    }
                    strokes.addAll(replacements)
                    onCommitted(Op.added(replacements))
                    replacements.forEach { onDrew(it.boundsBox()) }
                    return
                }

                strokes.add(s)
                onCommitted(Op.added(s))
                onDrew(s.boundsBox())
            }
        }
    }
}

/** How far, in screen pixels, a press may travel over an item in hand and still place it. */
private const val PLACE_SLOP = 6f

/**
 * Where a click at [x], [y] puts a stamp: centred on it, at the size it was last used at, or at a
 * size that suits a page [pageWidth] by [pageHeight] when it has not been used yet.
 */
internal fun placementBox(
    kind: com.inkslate.core.Stamps.Kind,
    o: com.inkslate.core.Stamps.StampOptions,
    x: Float,
    y: Float,
    pageWidth: Float,
    pageHeight: Float
): InkBox {
    val aspect = com.inkslate.core.Stamps.aspectFor(kind, o)
    var w = if (o.size > 0f) o.size else when (kind) {
        com.inkslate.core.Stamps.Kind.LINE, com.inkslate.core.Stamps.Kind.ARROW -> pageWidth * 0.2f
        com.inkslate.core.Stamps.Kind.BOX, com.inkslate.core.Stamps.Kind.OVAL -> pageWidth * 0.16f
        com.inkslate.core.Stamps.Kind.CHECK, com.inkslate.core.Stamps.Kind.CROSS,
        com.inkslate.core.Stamps.Kind.STAR -> pageWidth * 0.05f
        else -> pageWidth * 0.42f
    }
    var h = w / aspect
    if (o.size <= 0f && h > pageHeight * 0.3f) { h = pageHeight * 0.3f; w = h * aspect }
    return InkBox(x - w / 2f, y - h / 2f, x + w / 2f, y + h / 2f)
}
