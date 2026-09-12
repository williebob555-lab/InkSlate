package com.inkslate.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.PointerType
import com.inkslate.core.Box as InkBox
import com.inkslate.core.DynamicWidth
import com.inkslate.core.EraserMode
import com.inkslate.core.InkPoint
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
        if (!change.pressed) break
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
    onPendingStamp: (List<Stroke>) -> Unit,
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
    /** Which stylus barrel button was down when the pointer landed, or 0 for none. */
    heldButton: Int = 0,
    /** Whether the secondary button was down, which is its own pen rather than a modifier. */
    secondaryButton: Boolean = false
) {
    // Whichever device landed picks its own profile first - a pen, a finger, or the pen with a
    // barrel button held, which is a whole second pen rather than a modifier on this one.
    // On Windows none of this arrives in the pointer event - every device is reported as a mouse -
    // so what Windows itself said about the contact is used where it is available. See
    // WindowsPointer. Elsewhere, and if the hook is not in place, the pointer type is the answer.
    val native = WindowsPointer.takeIf { it.active }
    val barrel = if (native?.barrelHeld == true) maxOf(heldButton, 1) else heldButton
    tools.adoptInput(
        isStylus = down.type == PointerType.Stylus || native?.device == WindowsPointer.Device.PEN,
        isTouch = down.type == PointerType.Touch || native?.device == WindowsPointer.Device.FINGER,
        heldButton = barrel,
        secondaryButton = secondaryButton
    )
    // Read synchronously: the config is deliberately not Compose state so the tool in hand the
    // instant the pointer lands is the one that acts.
    val cfg = tools.active
    val index = slot.index
    val scale = viewport.scale

    /**
     * Screen pixels to this page's own coordinates.
     *
     * A cropped page is laid out at its content's size but ink is still stored against the whole
     * page, so the trimmed offset goes back on here. That is what makes turning the crop on and
     * off unable to move a single existing mark.
     */
    fun toPage(p: Offset): Offset {
        val doc = viewport.screenToDoc(p)
        return Offset(
            doc.x - slot.originX + slot.cropLeft,
            doc.y - slot.originY + slot.cropTop
        )
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

    val armed = tools.armedStamp
    when {
        armed != null -> {
            // Dragged out like a shape, but keeping the stamp's own proportions: a unit circle
            // stretched into an ellipse is not a unit circle, and a number line squashed to a
            // square is unreadable. The drag sets the size; the aspect is the stamp's.
            val (stampKind, stampOptions) = armed
            val aspect = com.inkslate.core.Stamps.aspectFor(stampKind, stampOptions)
            var preview: List<Stroke> = emptyList()

            fun boxTo(n: Offset): InkBox {
                val w = kotlin.math.abs(n.x - px).coerceAtLeast(8f)
                val h = (w / aspect).coerceAtLeast(6f)
                val left = if (n.x >= px) px else px - w
                val top = if (n.y >= py) py else py - h
                return InkBox(left, top, left + w, top + h)
            }

            val end = dragUntilRelease(down.position) { change, _ ->
                var n = 0
                preview = com.inkslate.core.Stamps.build(
                    stampKind, boxTo(toPage(change.position)), index,
                    cfg.color, cfg.strokeWidth, stampOptions
                ) { "stamp-preview-${n++}" }
                onPendingStamp(preview)
            }
            onPendingStamp(emptyList())

            val box = boxTo(toPage(end))
            // A click with no drag gets a stamp at a sensible default size rather than nothing:
            // having picked one from the sheet, being given no stamp at all reads as a failure.
            val placed = com.inkslate.core.Stamps.build(
                stampKind,
                if (box.width > 12f) box else InkBox(px, py, px + 180f, py + 180f / aspect),
                index, cfg.color, cfg.strokeWidth, stampOptions, newId
            )
            if (placed.isNotEmpty()) {
                strokes.addAll(placed)
                onCommitted(Op.added(placed))
            }
            tools.armedStamp = null
            onStampPlaced()
        }

        cfg.tool == Tool.PAN -> {
            viewport.stop()
            var last = down.position
            var lastAt = System.nanoTime()
            var vx = 0f
            var vy = 0f
            dragUntilRelease(down.position) { change, _ ->
                val now = System.nanoTime()
                val dt = ((now - lastAt) / 1_000_000_000f).coerceAtLeast(0.001f)
                val d = change.position - last
                viewport.panBy(d.x, d.y)
                vx = d.x / dt
                vy = d.y / dt
                last = change.position
                lastAt = now
            }
            viewport.throwBy(vx, vy)
        }

        cfg.tool == Tool.ERASER -> {
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

        cfg.tool == Tool.SELECT -> {
            val chosen = strokes.filter { it.id in selection }
            val box = chosen.unionBounds()
            // The frame and the handle being dragged travel together, so having one is having both.
            val grabbed = box?.let { b ->
                handleAt(b, px, py, HANDLE_TOUCH / scale)?.let { b to it }
            }
            when {
                grabbed != null -> {
                    val (frame, handle) = grabbed
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
                    if (current !== chosen) onCommitted(Op(chosen, current))
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

        cfg.tool == Tool.REGION -> {
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

        cfg.tool == Tool.TEXT -> {
            // Land on an existing text box and you are editing it, not stacking a second one
            // on top.
            val existing = strokes.lastOrNull {
                it.pageIndex == index && it.kind == Stroke.Kind.TEXT &&
                    it.hitTest(px, py, TAP_SLOP / scale)
            }
            dragUntilRelease(down.position) { _, _ -> }
            if (existing != null) onEditText(existing) else onPlaceText(px, py, index)
        }

        cfg.tool.isShape || cfg.tool == Tool.TABLE -> {
            val kind = when (cfg.tool) {
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
            dragUntilRelease(down.position) { change, _ ->
                if (WindowsPointer.gesturing) {
                    abandoned = true
                    collected.clear()
                    onLive(emptyList())
                } else if (!abandoned) {
                    sample(change.position, change.pressure, change.type)
                    onLive(ArrayList(collected))
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
                // A rough circle becomes a circle. The bar for replacing what someone drew is
                // deliberately high, and a stroke already ruled is left alone - it is straight
                // because it was meant to be, and tidying it further could only move it.
                val s = if (tools.recogniseShapes && !ruled) {
                    com.inkslate.core.ShapeRecogniser.recognise(drawn) ?: drawn
                } else {
                    drawn
                }

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
