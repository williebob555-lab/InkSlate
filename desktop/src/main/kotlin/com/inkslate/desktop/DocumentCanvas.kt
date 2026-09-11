package com.inkslate.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.inkslate.core.Box as InkBox
import com.inkslate.core.PageArranger
import com.inkslate.core.PageExtent
import com.inkslate.core.PageLayout
import com.inkslate.core.Stroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** One page placed in document space. */
class PageSlot(
    val index: Int,
    val width: Float,
    val height: Float,
    val originX: Float,
    val originY: Float,
    /**
     * Offset from the whole page to the visible area, when its margins are trimmed.
     *
     * Stroke coordinates stay relative to the whole page whatever this is, so toggling the crop
     * cannot move existing ink and export is unaffected either way.
     */
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f
) {
    val box: InkBox get() = InkBox(originX, originY, originX + width, originY + height)

    fun contains(x: Float, y: Float) =
        x >= originX && x <= originX + width && y >= originY && y <= originY + height

    /** Squared distance from a document point to this page, 0 when inside. */
    fun distanceSq(x: Float, y: Float): Float {
        val dx = when {
            x < originX -> originX - x
            x > originX + width -> x - (originX + width)
            else -> 0f
        }
        val dy = when {
            y < originY -> originY - y
            y > originY + height -> y - (originY + height)
            else -> 0f
        }
        return dx * dx + dy * dy
    }
}

/**
 * The whole document under one camera.
 *
 * This replaced a scrolling list of pages, and had to: the wheel is the zoom here, so the list
 * could not also be having it. Everything is drawn into a single canvas under one translate and
 * one scale, which is also what makes the page arrangements - column, row, grid, spread - fall
 * out of `core/PageArranger` rather than out of a layout container.
 *
 * ## The input model
 *
 * Deliberately a desktop's, not a translation of the tablet's:
 *
 *  - **Wheel** zooms, about the pointer, so what you are looking at stays put.
 *  - **Shift+wheel** and **Ctrl+wheel** pan sideways and vertically, with a throw that carries
 *    and decays rather than moving a fixed step per notch.
 *  - **Middle mouse drag** pans from anywhere, whatever tool is in hand - the one movement that
 *    never has to be selected first.
 *  - **Pen, finger and mouse** all draw. A stylus is preferred over a finger the moment one is
 *    on the glass, which is the tablet's palm rejection and matters just as much on a
 *    touchscreen laptop resting on a wrist.
 */
@Composable
fun DocumentCanvas(
    source: DesktopSource,
    viewport: Viewport,
    layout: PageLayout,
    currentPage: Int,
    onPageChanged: (Int) -> Unit,
    strokes: MutableList<Stroke>,
    selection: Set<String>,
    onSelection: (Set<String>) -> Unit,
    tools: ToolState,
    textMeasurer: TextMeasurer,
    pageFilter: PageFilter,
    newId: () -> String,
    onCommitted: (Op) -> Unit,
    onEditText: (Stroke) -> Unit,
    onPlaceText: (Float, Float, Int) -> Unit,
    onStampPlaced: () -> Unit = {},
    onDrew: (InkBox) -> Unit = {},
    onCaptureRegion: (InkBox, Int) -> Unit = { _, _ -> },
    /** Pictures pasted into this document, looked up by the id a stroke carries. */
    images: (String) -> ImageBitmap? = { null },
    /** Trim each page to its printed area, hiding the margins a textbook gives up. */
    cropMargins: Boolean = false,
    /** The words a highlighter path crossed, when this document has a text layer at all. */
    wordsUnder: ((Int, List<Pair<Float, Float>>) -> List<InkBox>)? = null,
    /** Set when this document is a canvas that grows to fit what is written on it. */
    canvas: com.inkslate.core.InkCanvas? = null,
    modifier: Modifier = Modifier
) {
    val extents = remember(source, source.pageCount) {
        (0 until source.pageCount).map {
            val d = source.pageDim(it)
            PageExtent(d.width, d.height)
        }
    }
    // A canvas is one page placed at its own origin - which may be negative - and sized to the
    // room it has grown into rather than to the paper. Everything below then works unchanged,
    // because a grown canvas is still just a slot in document space.
    // The printed area of each page, worked out once from whatever raster arrives first and kept.
    // Re-measuring at every zoom would move the page under the reader's hand for no gain.
    val contentBoxes = remember(source) { mutableStateMapOf<Int, InkBox>() }

    val slots = remember(extents, layout, currentPage, canvas, cropMargins, contentBoxes.size) {
        val effective = when {
            canvas != null -> listOf(PageExtent(canvas.width, canvas.height))
            cropMargins -> extents.mapIndexed { i, e ->
                contentBoxes[i]?.let { PageExtent(it.width, it.height) } ?: e
            }
            else -> extents
        }
        val origins = PageArranger.arrange(effective, layout, currentPage, canvas?.box)
        effective.mapIndexed { i, e ->
            val (x, y) = origins[i]
            val box = if (canvas == null && cropMargins) contentBoxes[i] else null
            PageSlot(i, e.width, e.height, x, y, box?.left ?: 0f, box?.top ?: 0f)
        }
    }
    val bounds = remember(slots, layout, currentPage, canvas) {
        PageArranger.bounds(
            slots.map { PageExtent(it.width, it.height) },
            slots.map { it.originX to it.originY },
            layout,
            currentPage,
            canvas?.box
        )
    }
    viewport.content = bounds

    // Rendered page rasters, keyed by page and by the zoom bucket they were made for.
    val rasters = remember(source) { mutableStateMapOf<Int, Pair<Int, ImageBitmap>>() }

    var renderTick by remember { mutableStateOf(0) }

    // A live gesture, kept out of the committed list so that is not rewritten on every move.
    var live by remember { mutableStateOf<List<com.inkslate.core.InkPoint>>(emptyList()) }
    var livePage by remember { mutableStateOf(0) }
    var pending by remember { mutableStateOf<Stroke?>(null) }
    var pendingStamp by remember { mutableStateOf<List<Stroke>>(emptyList()) }
    var marquee by remember { mutableStateOf<InkBox?>(null) }

    /** Which page a document point belongs to: the one under it, or the nearest. */
    fun slotAt(x: Float, y: Float): PageSlot? =
        slots.firstOrNull { it.contains(x, y) } ?: slots.minByOrNull { it.distanceSq(x, y) }

    // ---- rendering the pages -------------------------------------------------

    // Rounded to a bucket so a pinch or a wheel spin does not queue a full-page render for every
    // intermediate scale, and debounced so it happens once the view has settled.
    LaunchedEffect(source) {
        snapshotFlow { viewport.scale }.debounce(90).collect { renderTick++ }
    }
    LaunchedEffect(source, renderTick, viewport.viewSize) {
        val scale = viewport.scale
        if (scale <= 0f || viewport.viewSize == Size.Zero) return@LaunchedEffect
        for (slot in slots) {
            // Only what is on screen, plus a screen of margin so scrolling is not a slide show.
            val onScreen = visible(slot, viewport)
            if (!onScreen) continue
            val wanted = (slot.width * scale).roundToInt().coerceIn(80, 4000)
            val bucket = (wanted / 160) * 160
            if (rasters[slot.index]?.first == bucket) continue
            val bmp = withContext(Dispatchers.IO) {
                runCatching { source.render(slot.index, max(160, bucket)) }.getOrNull()
            }
            if (bmp != null) {
                rasters[slot.index] = bucket to bmp
                if (slot.index !in contentBoxes) {
                    val pixels = bmp.toPixelMap()
                    com.inkslate.core.MarginCrop.detect(
                        bmp.width, bmp.height, slot.width, slot.height
                    ) { x, y -> pixels[x, y].toArgb() }?.let { contentBoxes[slot.index] = it }
                }
            }
        }
    }

    // The page in view is the one the toolbar and the page counter mean.
    LaunchedEffect(viewport.offset, viewport.scale, slots) {
        val centre = viewport.screenToDoc(
            Offset(viewport.viewSize.width / 2f, viewport.viewSize.height / 3f)
        )
        slotAt(centre.x, centre.y)?.let { if (it.index != currentPage) onPageChanged(it.index) }
    }

    // A throw keeps moving after the fingers or the wheel have stopped.
    LaunchedEffect(viewport) {
        var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                val dt =
                    if (last == 0L) 0f else ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                last = now
                viewport.advanceMomentum(dt)
            }
        }
    }

    val density = LocalDensity.current.density

    Box(
        modifier
            .fillMaxSize()
            .background(Color(0xFF14171B))
            .onSizeChanged { viewport.viewSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(Unit) { wheel(viewport) }
            .pointerInput(slots, tools.revision, selection, viewport.scale) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)

                    // Middle button pans from anywhere, whatever tool is in hand.
                    if (currentEvent.buttons.isTertiaryPressed) {
                        viewport.stop()
                        var last = down.position
                        var lastAt = System.nanoTime()
                        var vx = 0f
                        var vy = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (event.type == PointerEventType.Move) {
                                val now = System.nanoTime()
                                val dt = ((now - lastAt) / 1_000_000_000f).coerceAtLeast(0.001f)
                                val d = change.position - last
                                viewport.panBy(d.x, d.y)
                                vx = d.x / dt
                                vy = d.y / dt
                                last = change.position
                                lastAt = now
                            }
                            if (!change.pressed) break
                        }
                        viewport.throwBy(vx, vy)
                        return@awaitEachGesture
                    }

                    // A finger while a stylus is on the glass is a palm. The tablet ignores it
                    // and so does this, which matters just as much on a laptop resting on a wrist.
                    val stylusDown = currentEvent.changes.any {
                        it.pressed && it.type == PointerType.Stylus
                    }
                    if (down.type == PointerType.Touch && stylusDown) return@awaitEachGesture

                    // A stylus barrel reports as a secondary or tertiary button, exactly as a
                    // mouse does; what makes it a barrel is that the pointer is a stylus.
                    val heldButton = when {
                        down.type != PointerType.Stylus -> 0
                        currentEvent.buttons.isTertiaryPressed -> 2
                        currentEvent.buttons.isSecondaryPressed -> 1
                        else -> 0
                    }

                    val doc = viewport.screenToDoc(down.position)
                    val slot = slotAt(doc.x, doc.y) ?: return@awaitEachGesture
                    livePage = slot.index

                    handlePageGesture(
                        down = down,
                        slot = slot,
                        viewport = viewport,
                        strokes = strokes,
                        selection = selection,
                        onSelection = onSelection,
                        tools = tools,
                        newId = newId,
                        onCommitted = onCommitted,
                        onEditText = onEditText,
                        onPlaceText = onPlaceText,
                        onLive = { live = it },
                        onPending = { pending = it },
                        onMarquee = { marquee = it },
                        onPendingStamp = { pendingStamp = it },
                        onStampPlaced = onStampPlaced,
                        onDrew = onDrew,
                        onCaptureRegion = onCaptureRegion,
                        wordsUnder = wordsUnder,
                        heldButton = heldButton
                    )
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val vp = viewport
            // Measured around the whole frame rather than sampled, because a stutter is one slow
            // frame among fast ones and sampling is exactly what misses it.
            val startedNs = System.nanoTime()
            var drawnStrokes = 0
            var pagesVisible = 0
            translate(-vp.offset.x * vp.scale, -vp.offset.y * vp.scale) {
                scale(vp.scale, vp.scale, pivot = Offset.Zero) {
                    for (slot in slots) {
                        if (!visible(slot, vp)) continue
                        pagesVisible++
                        drawnStrokes += strokes.count { it.pageIndex == slot.index }
                        translate(slot.originX, slot.originY) {
                            drawPage(
                                canvas = canvas,
                                slot = slot,
                                raster = rasters[slot.index]?.second,
                                strokes = strokes,
                                selection = selection,
                                live = if (livePage == slot.index) live else emptyList(),
                                pending = pending?.takeIf { it.pageIndex == slot.index },
                                pendingStamp = pendingStamp.filter { it.pageIndex == slot.index },
                                marquee = marquee?.takeIf { livePage == slot.index },
                                tools = tools,
                                textMeasurer = textMeasurer,
                                pageFilter = pageFilter,
                                crop = if (cropMargins) contentBoxes[slot.index] else null,
                                scale = vp.scale,
                                ruler = tools.ruler?.takeIf {
                                    tools.rulerVisible && it.page == slot.index
                                },
                                images = images
                            )
                        }
                    }
                }
            }
            RenderStats.totalStrokes = strokes.size
            RenderStats.livePoints = live.size
            RenderStats.pageCount = slots.size
            RenderStats.pagesResident = rasters.size
            RenderStats.pagesVisible = pagesVisible
            RenderStats.recordFrame((System.nanoTime() - startedNs) / 1_000_000f, drawnStrokes)
        }
    }
}

/**
 * A pasted picture, drawn into the rectangle its stroke describes.
 *
 * The crop is applied here rather than to the stored file, so the picture stays whole on disk: it
 * is shared between devices and referenced by id, and trimming the bytes would change what every
 * other copy shows - and would throw the trimmed edges away for good.
 */
private fun DrawScope.drawImageStroke(s: Stroke, images: (String) -> ImageBitmap?) {
    val box = s.rectBox()
    if (box.isEmpty) return
    val id = s.imageId
    val picture = id?.let(images)
    if (picture == null) {
        // A placeholder rather than nothing: a picture whose file has not synced yet should look
        // like a picture that is missing, not like a gap where nothing was ever put.
        drawRect(
            Color(0x22FFFFFF),
            topLeft = Offset(box.left, box.top),
            size = Size(box.width, box.height)
        )
        return
    }

    val crop = s.cropPixels(picture.width, picture.height)
    drawImage(
        image = picture,
        srcOffset = IntOffset(crop?.get(0) ?: 0, crop?.get(1) ?: 0),
        srcSize = IntSize(
            (crop?.let { it[2] - it[0] }) ?: picture.width,
            (crop?.let { it[3] - it[1] }) ?: picture.height
        ),
        dstOffset = IntOffset(box.left.roundToInt(), box.top.roundToInt()),
        dstSize = IntSize(box.width.roundToInt(), box.height.roundToInt()),
        alpha = s.opacity.coerceIn(0f, 1f)
    )
}

/** Whether a page is near enough the window to be worth drawing or rendering. */
private fun visible(slot: PageSlot, vp: Viewport): Boolean {
    if (slot.originX >= PageArranger.FAR_AWAY) return false
    if (vp.viewSize == Size.Zero) return true
    val marginX = vp.viewSize.width / vp.scale
    val marginY = vp.viewSize.height / vp.scale
    val left = vp.offset.x - marginX
    val top = vp.offset.y - marginY
    val right = vp.offset.x + vp.viewSize.width / vp.scale + marginX
    val bottom = vp.offset.y + vp.viewSize.height / vp.scale + marginY
    return slot.box.intersects(InkBox(left, top, right, bottom))
}

/**
 * One page: its paper, its raster, its ink, and whatever is being drawn on it right now.
 *
 * Called inside a translate to the page's own origin, so everything below is in page
 * coordinates - which is exactly the space strokes are stored in.
 */
private fun DrawScope.drawPage(
    canvas: com.inkslate.core.InkCanvas?,
    slot: PageSlot,
    raster: ImageBitmap?,
    strokes: List<Stroke>,
    selection: Set<String>,
    live: List<com.inkslate.core.InkPoint>,
    pending: Stroke?,
    pendingStamp: List<Stroke>,
    marquee: InkBox?,
    tools: ToolState,
    textMeasurer: TextMeasurer,
    pageFilter: PageFilter,
    crop: InkBox?,
    scale: Float,
    ruler: com.inkslate.core.Ruler?,
    images: (String) -> ImageBitmap?
) {
    if (canvas != null) {
        // The canvas is drawn in its own coordinates, which start where it starts - and that may
        // be negative. Everything on it, including the page's raster, is placed against that.
        translate(-canvas.left, -canvas.top) {
            with(CanvasPaper) { drawCanvasPaper(canvas, scale) }
            raster?.let {
                drawImage(
                    it,
                    dstOffset = IntOffset(
                        canvas.paperLeft.roundToInt(), canvas.paperTop.roundToInt()
                    ),
                    dstSize = IntSize(
                        canvas.paperWidth.roundToInt(), canvas.paperHeight.roundToInt()
                    ),
                    colorFilter = pageFilter.colorFilter
                )
            }
        }
    } else {
        drawRect(Color.White, topLeft = Offset.Zero, size = Size(slot.width, slot.height))
        raster?.let {
            // A cropped page draws the whole raster shifted, so the trimmed margins fall outside
            // the slot. Stroke coordinates stay relative to the full page, which is what makes
            // turning the crop on and off unable to move existing ink.
            val full = if (crop == null) {
                Size(slot.width, slot.height)
            } else {
                Size(
                    slot.width * it.width / (it.width * crop.width / slot.width).coerceAtLeast(1f),
                    slot.height * it.height / (it.height * crop.height / slot.height).coerceAtLeast(1f)
                )
            }
            drawImage(
                it,
                dstOffset = IntOffset(
                    (-(crop?.left ?: 0f)).roundToInt(),
                    (-(crop?.top ?: 0f)).roundToInt()
                ),
                dstSize = IntSize(full.width.roundToInt(), full.height.roundToInt()),
                colorFilter = pageFilter.colorFilter
            )
        }
    }

    // Ink is stored in page coordinates, which for a canvas are the canvas's own - so the whole
    // layer shifts by the canvas origin and nothing else changes.
    translate(
        if (canvas != null) -canvas.left else -(crop?.left ?: 0f),
        if (canvas != null) -canvas.top else -(crop?.top ?: 0f)
    ) {
    clipRect(
        if (canvas != null) canvas.left else 0f,
        if (canvas != null) canvas.top else 0f,
        if (canvas != null) canvas.right else slot.width,
        if (canvas != null) canvas.bottom else slot.height
    ) {
        // Highlighter first, so it sits under the ink it is marking rather than washing over it -
        // the same order the exporter writes.
        val page = strokes.filter { it.pageIndex == slot.index }
        for (s in page.sortedBy { if (it.isHighlighter) 0 else 1 }) {
            when (s.kind) {
                Stroke.Kind.TEXT -> drawTextStroke(s, textMeasurer)
                Stroke.Kind.IMAGE -> drawImageStroke(s, images)
                else -> drawStroke(s)
            }
        }

        if (live.size >= 2) {
            val cfg = tools.active
            drawStroke(
                Stroke(
                    id = "live",
                    kind = Stroke.Kind.FREEHAND,
                    color = cfg.color,
                    baseWidth = cfg.strokeWidth,
                    points = live,
                    brush = cfg.brush,
                    dash = cfg.dash,
                    opacity = cfg.opacity,
                    pageIndex = slot.index
                )
            )
        }
        pending?.let { drawStroke(it) }
        pendingStamp.forEach { if (it.kind != Stroke.Kind.TEXT) drawStroke(it) }

        marquee?.let { m ->
            drawRect(
                color = Color(0xFF3B82F6),
                topLeft = Offset(m.left, m.top),
                size = Size(m.width, m.height),
                style = DrawStroke(
                    width = 1f / scale,
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(6f / scale, 4f / scale), 0f
                    )
                )
            )
        }

        page.filter { it.id in selection }.unionBounds()?.let { drawSelection(it, scale) }
    }
    }

    // Outside the clip: a straightedge lies on top of the page and may hang over its edge, the
    // way a real one does.
    ruler?.let { drawRuler(it, scale, textMeasurer) }
}

/**
 * The straightedge, its grab handles, and what angle it is at.
 *
 * The angle is the point of drawing it at all rather than just snapping silently - a protractor
 * readout is what turns "a straight line" into "a line at 30 degrees", which is most of what a
 * ruler gets used for in a maths exercise.
 */
private fun DrawScope.drawRuler(
    ruler: com.inkslate.core.Ruler,
    scale: Float,
    textMeasurer: TextMeasurer
) {
    val body = Color(0x33_3B82F6)
    val edge = Color(0xBE_3C82F6)
    val a = Offset(ruler.ax, ruler.ay)
    val b = Offset(ruler.bx, ruler.by)

    // A band with a solid edge, so it reads as an object resting on the paper rather than as
    // another line drawn on it.
    val dx = ruler.bx - ruler.ax
    val dy = ruler.by - ruler.ay
    val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1f)
    val nx = -dy / len
    val ny = dx / len
    val half = RULER_BAND / scale

    val band = androidx.compose.ui.graphics.Path().apply {
        moveTo(ruler.ax + nx * half, ruler.ay + ny * half)
        lineTo(ruler.bx + nx * half, ruler.by + ny * half)
        lineTo(ruler.bx - nx * half, ruler.by - ny * half)
        lineTo(ruler.ax - nx * half, ruler.ay - ny * half)
        close()
    }
    drawPath(band, body)
    drawLine(edge, a, b, strokeWidth = 2f / scale)

    val handle = RULER_HANDLE / scale
    for (end in listOf(a, b)) {
        drawCircle(Color.White, handle, end)
        drawCircle(edge, handle, end, style = DrawStroke(1.6f / scale))
    }

    val label = "%.0f\u00B0".format(ruler.angleDegrees)
    val measured = textMeasurer.measure(
        label,
        androidx.compose.ui.text.TextStyle(
            color = Color.White,
            fontSize = androidx.compose.ui.unit.TextUnit(
                13f / scale, androidx.compose.ui.unit.TextUnitType.Sp
            )
        )
    )
    val cx = ruler.centerX - measured.size.width / 2f
    val cy = ruler.centerY - half - measured.size.height - 4f / scale
    drawRect(
        Color(0xCC_1B1F24),
        topLeft = Offset(cx - 4f / scale, cy - 2f / scale),
        size = Size(
            measured.size.width + 8f / scale,
            measured.size.height + 4f / scale
        )
    )
    drawText(measured, topLeft = Offset(cx, cy))
}

/**
 * The wheel: zoom, or a throw sideways or down while a modifier is held.
 *
 * Its own pointer handler so it cannot be swallowed by whichever tool is drawing. Compose reports
 * a scroll as a change with a delta rather than as a gesture, so this reads events directly and
 * turns each notch into either a zoom ratio or a velocity.
 */
private suspend fun AwaitPointerEventScope.wheelLoop(viewport: Viewport) {
    while (true) {
        val event = awaitPointerEvent()
        if (event.type != PointerEventType.Scroll) continue
        val change = event.changes.firstOrNull() ?: continue
        val notches = change.scrollDelta.y
        if (notches == 0f && change.scrollDelta.x == 0f) continue

        val shift = event.keyboardModifiers.isShiftPressed
        val ctrl = event.keyboardModifiers.isCtrlPressed
        when {
            // Sideways, with the throw carrying on after the wheel stops.
            shift -> viewport.throwBy(
                -notches * Viewport.PAN_VELOCITY_PER_NOTCH + viewport.velocity.x * 0.4f,
                viewport.velocity.y * 0.4f
            )
            // Down the page, the direction a wheel usually means when it is not zooming.
            ctrl -> viewport.throwBy(
                viewport.velocity.x * 0.4f,
                -notches * Viewport.PAN_VELOCITY_PER_NOTCH + viewport.velocity.y * 0.4f
            )
            else -> {
                viewport.stop()
                val factor = Math.pow(
                    Viewport.ZOOM_PER_NOTCH.toDouble(), -notches.toDouble()
                ).toFloat()
                viewport.zoomBy(factor, change.position)
            }
        }
        change.consume()
    }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.wheel(viewport: Viewport) {
    awaitPointerEventScope { wheelLoop(viewport) }
}

/** How close a click has to be to a handle, and how big one is drawn, in screen pixels. */
internal const val HANDLE_TOUCH = 11f
internal const val HANDLE_DRAW = 4.5f

/** How far a click may miss a stroke and still select it, in screen pixels. */
internal const val TAP_SLOP = 9f

/** Half the width of the ruler's band, and the size of its end handles, in screen pixels. */
private const val RULER_BAND = 17f
private const val RULER_HANDLE = 6f

/**
 * The selection frame and its handles.
 *
 * Sizes are divided by the zoom: a handle has to stay the same size under the pointer however far
 * in the page is, or it becomes either unhittable or an obstruction.
 */
internal fun DrawScope.drawSelection(box: InkBox, scale: Float) {
    val accent = Color(0xFF3B82F6)
    drawRect(
        color = accent,
        topLeft = Offset(box.left, box.top),
        size = Size(box.width, box.height),
        style = DrawStroke(
            width = 1.2f / scale,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f / scale, 4f / scale), 0f)
        )
    )
    val r = HANDLE_DRAW / scale
    for (h in Handle.entries) {
        val c = Offset(h.x(box), h.y(box))
        drawCircle(Color.White, r, c)
        drawCircle(accent, r, c, style = DrawStroke(1.4f / scale))
    }
}
