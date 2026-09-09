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
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
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
    val originY: Float
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
    modifier: Modifier = Modifier
) {
    val extents = remember(source, source.pageCount) {
        (0 until source.pageCount).map {
            val d = source.pageDim(it)
            PageExtent(d.width, d.height)
        }
    }
    val slots = remember(extents, layout, currentPage) {
        val origins = PageArranger.arrange(extents, layout, currentPage)
        extents.mapIndexed { i, e ->
            val (x, y) = origins[i]
            PageSlot(i, e.width, e.height, x, y)
        }
    }
    val bounds = remember(slots, layout, currentPage) {
        PageArranger.bounds(extents, slots.map { it.originX to it.originY }, layout, currentPage)
    }
    viewport.content = bounds

    // Rendered page rasters, keyed by page and by the zoom bucket they were made for.
    val rasters = remember(source) { mutableStateMapOf<Int, Pair<Int, ImageBitmap>>() }
    var renderTick by remember { mutableStateOf(0) }

    // A live gesture, kept out of the committed list so that is not rewritten on every move.
    var live by remember { mutableStateOf<List<com.inkslate.core.InkPoint>>(emptyList()) }
    var livePage by remember { mutableStateOf(0) }
    var pending by remember { mutableStateOf<Stroke?>(null) }
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
            if (bmp != null) rasters[slot.index] = bucket to bmp
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
                        onMarquee = { marquee = it }
                    )
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val vp = viewport
            translate(-vp.offset.x * vp.scale, -vp.offset.y * vp.scale) {
                scale(vp.scale, vp.scale, pivot = Offset.Zero) {
                    for (slot in slots) {
                        if (!visible(slot, vp)) continue
                        translate(slot.originX, slot.originY) {
                            drawPage(
                                slot = slot,
                                raster = rasters[slot.index]?.second,
                                strokes = strokes,
                                selection = selection,
                                live = if (livePage == slot.index) live else emptyList(),
                                pending = pending?.takeIf { it.pageIndex == slot.index },
                                marquee = marquee?.takeIf { livePage == slot.index },
                                tools = tools,
                                textMeasurer = textMeasurer,
                                pageFilter = pageFilter,
                                scale = vp.scale
                            )
                        }
                    }
                }
            }
        }
    }
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
    slot: PageSlot,
    raster: ImageBitmap?,
    strokes: List<Stroke>,
    selection: Set<String>,
    live: List<com.inkslate.core.InkPoint>,
    pending: Stroke?,
    marquee: InkBox?,
    tools: ToolState,
    textMeasurer: TextMeasurer,
    pageFilter: PageFilter,
    scale: Float
) {
    drawRect(Color.White, topLeft = Offset.Zero, size = Size(slot.width, slot.height))
    raster?.let {
        drawImage(
            it,
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(slot.width.roundToInt(), slot.height.roundToInt()),
            colorFilter = pageFilter.colorFilter
        )
    }

    clipRect(0f, 0f, slot.width, slot.height) {
        // Highlighter first, so it sits under the ink it is marking rather than washing over it -
        // the same order the exporter writes.
        val page = strokes.filter { it.pageIndex == slot.index }
        for (s in page.sortedBy { if (it.isHighlighter) 0 else 1 }) {
            if (s.kind == Stroke.Kind.TEXT) drawTextStroke(s, textMeasurer) else drawStroke(s)
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
