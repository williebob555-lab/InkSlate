package com.inkslate.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.inkslate.core.Box as InkBox
import com.inkslate.core.PageArranger
import com.inkslate.core.PageExtent
import com.inkslate.core.PageLayout
import com.inkslate.core.InputAction
import com.inkslate.core.InputButton
import com.inkslate.core.InputDevice
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
     * Where this slot's top-left sits in the coordinates ink is stored in.
     *
     * Two things move that origin and they must agree, which is why it is one number rather than
     * two ideas. A page with its margins trimmed is laid out at the size of its content while ink
     * stays relative to the whole page, so the trimmed margin is the gap. A canvas is laid out
     * from its own top-left, which moves further negative every time the canvas grows, while ink
     * stays at the coordinates it was written at - so the canvas origin is the gap.
     *
     * Adding this when a point is read and subtracting it when a mark is drawn is what keeps ink
     * where it was put. Getting that pair out of step moves every mark on the page, and by a
     * distance that grows as the canvas does.
     */
    val inkLeft: Float = 0f,
    val inkTop: Float = 0f
) {
    val box: InkBox get() = InkBox(originX, originY, originX + width, originY + height)

    /** A point in document space, in the coordinates ink on this page is stored in. */
    fun toInk(docX: Float, docY: Float) =
        Offset(docX - originX + inkLeft, docY - originY + inkTop)

    /** And back again - what the drawing side does, which has to undo exactly the above. */
    fun fromInk(inkX: Float, inkY: Float) =
        Offset(inkX + originX - inkLeft, inkY + originY - inkTop)

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
    /** For inputs bound to undo and redo - a thumb button, by default. See InputBindings. */
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
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

    // Grouped by page and ordered highlighter-first once per edit rather than once per frame.
    // Highlighter under the ink it marks is the order the exporter writes, so the two agree.
    //
    // Derived rather than remembered against the list. The list is the same object for the life of
    // the document and changes by being mutated, so remembering against it computes the grouping
    // when the document opens and never again: every mark drawn afterwards stayed out of it and
    // went invisible the moment it stopped being the live one, while sitting in the list the whole
    // time and reappearing on reopening. A derived value watches the contents instead, which is
    // the same thing the frame was watching when the work was done inside it.
    val byPage by remember(strokes) {
        derivedStateOf {
            strokes.groupBy { it.pageIndex }
                .mapValues { (_, marks) -> marks.sortedBy { if (it.isHighlighter) 0 else 1 } }
        }
    }

    // The shapes belong to this document and nothing else; holding them past it is just memory.
    DisposableEffect(source) { onDispose { InkGeometry.clear() } }

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
            PageSlot(
                i, e.width, e.height, x, y,
                inkLeft = canvas?.left ?: box?.left ?: 0f,
                inkTop = canvas?.top ?: box?.top ?: 0f
            )
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

    // The piece of each page that has been rendered, and how big a picture it was rendered into.
    val rasters = remember(source) { mutableStateMapOf<Int, PageTile>() }

    // A small picture of each whole page, made once and kept.
    //
    // The sharp piece above covers the window and a margin, and a pan that outruns the margin
    // would otherwise show bare paper at the leading edge until a new piece had been rendered.
    // This sits underneath and is never missing, so an outrun pan goes momentarily soft instead
    // of blank. A few megabytes a page, which a graphics card keeps without complaint.
    val overviews = remember(source) { mutableStateMapOf<Int, PageTile>() }

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
    // Both, and from inside: a pan moves the window off the piece that was rendered for it just
    // as surely as a zoom outgrows its sharpness. Debounced, so a throw across a document asks
    // for one page rather than for every page it passes over.
    LaunchedEffect(source) {
        snapshotFlow { viewport.scale to viewport.offset }
            .debounce(90)
            .collect { renderTick++ }
    }
    LaunchedEffect(source, renderTick, viewport.viewSize) {
        val scale = viewport.scale
        if (scale <= 0f || viewport.viewSize == Size.Zero) return@LaunchedEffect
        for (slot in slots) {
            // Only what is on screen, plus a screen of margin so scrolling is not a slide show.
            val onScreen = visible(slot, viewport)
            if (!onScreen) continue
            // The page itself, in the coordinates ink is stored in. On a canvas the page is a
            // rectangle somewhere inside it; everywhere else the page is the whole of it.
            val printed = extents.getOrNull(slot.index)
            val originX = canvas?.paperLeft ?: 0f
            val originY = canvas?.paperTop ?: 0f
            val pageWidth = canvas?.paperWidth ?: printed?.width ?: slot.width
            val pageHeight = canvas?.paperHeight ?: printed?.height ?: slot.height
            if (pageWidth <= 0f || pageHeight <= 0f) continue

            // Nothing to render for paper this program ruled: it is painted, not photographed.
            if (canvas?.ownPaper == true) continue

            val wholePage = InkBox(0f, 0f, pageWidth, pageHeight)
            if (slot.index !in overviews) {
                val small = withContext(Dispatchers.IO) {
                    runCatching { source.render(slot.index, OVERVIEW_PX) }.getOrNull()
                }
                if (small != null) {
                    overviews[slot.index] = PageTile(wholePage, small)
                    if (slot.index !in contentBoxes) {
                        val pixels = small.toPixelMap()
                        com.inkslate.core.MarginCrop.detect(
                            small.width, small.height, pageWidth, pageHeight
                        ) { x, y -> pixels[x, y].toArgb() }?.let { contentBoxes[slot.index] = it }
                    }
                }
            }

            // What of the page is inside the window, in the page's own points.
            val corner = slot.toInk(viewport.offset.x, viewport.offset.y)
            val far = slot.toInk(
                viewport.offset.x + viewport.viewSize.width / scale,
                viewport.offset.y + viewport.viewSize.height / scale
            )
            val seen = InkBox(
                (corner.x - originX).coerceIn(0f, pageWidth),
                (corner.y - originY).coerceIn(0f, pageHeight),
                (far.x - originX).coerceIn(0f, pageWidth),
                (far.y - originY).coerceIn(0f, pageHeight)
            )
            if (seen.width <= 0f || seen.height <= 0f) continue

            // Rendered with a margin around it, so that moving the page a little does not mean
            // rendering it again - and so a throw has somewhere to land before it catches up.
            val marginX = seen.width * TILE_MARGIN
            val marginY = seen.height * TILE_MARGIN
            val want = InkBox(
                (seen.left - marginX).coerceAtLeast(0f),
                (seen.top - marginY).coerceAtLeast(0f),
                (seen.right + marginX).coerceAtMost(pageWidth),
                (seen.bottom + marginY).coerceAtMost(pageHeight)
            )

            // Sharp enough for the window and no sharper. A picture bigger than the graphics card
            // will keep is sent across again on every frame, which is what this is all about.
            val needed = (seen.width * scale).roundToInt()
            val held = rasters[slot.index]
            val enough = held != null &&
                held.covers(seen) &&
                !RasterLadder.shouldRemake(held.acrossPx(seen.width), needed)
            if (enough) continue

            val across = RasterLadder.rungFor((want.width * scale).roundToInt())
                .coerceAtMost(MAX_TILE_PX)
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    source.renderRegion(slot.index, want, across)
                        ?: source.render(slot.index, across)
                }.getOrNull()
            }
            // A piece has to look like the page it came from.
            //
            // Rendering part of a page is one call into a library, and the way it failed was to
            // return a perfectly valid black rectangle - which is not an error, so nothing threw
            // and nothing fell back, and every page went black. Checked against the small picture
            // of the whole page, which is rendered a different way: if the two disagree about how
            // light that part of the page is, the piece is wrong and the whole page is used.
            val trustworthy = bmp != null &&
                looksLike(bmp, overviews[slot.index], want, pageWidth, pageHeight)
            if (bmp != null && !trustworthy) {
                EventLog.warn(
                    "render",
                    "A rendered piece of page ${slot.index + 1} did not match the page; " +
                        "falling back to the whole page"
                )
            }
            if (trustworthy) rasters[slot.index] = PageTile(want, bmp!!)
        }

        // Pictures of pages nowhere near the window are memory and nothing else. A document read
        // end to end would otherwise hold a picture of every page it had passed through.
        val near = slots.filter { visible(it, viewport) }.map { it.index }
        if (near.isNotEmpty()) {
            val first = near.min()
            val last = near.max()
            rasters.keys.filter { it < first - 1 || it > last + 1 }
                .forEach { rasters.remove(it) }
            overviews.keys.filter { it < first - 4 || it > last + 4 }
                .forEach { overviews.remove(it) }
        }
    }

    // The page in view is the one the toolbar and the page counter mean.
    //
    // Watched from inside the effect rather than keyed on. The camera's position and zoom are
    // state, and reading them out here read them while composing - so every frame of a pan or a
    // zoom invalidated this whole composable and rebuilt it, which is what made moving the camera
    // stutter while drawing on it stayed smooth. Drawing only ever changes what the frame reads.
    val page = rememberUpdatedState(currentPage)
    LaunchedEffect(slots) {
        snapshotFlow { viewport.offset to viewport.scale }
            .debounce(120)
            .collect {
                val centre = viewport.screenToDoc(
                    Offset(viewport.viewSize.width / 2f, viewport.viewSize.height / 3f)
                )
                slotAt(centre.x, centre.y)?.let {
                    if (it.index != page.value) onPageChanged(it.index)
                }
            }
    }

    // Two fingers on the glass pan and zoom, which Windows reports to nobody but the window's own
    // message loop - so it arrives here rather than as a gesture. Positions come in screen pixels
    // and the canvas works in its own, which is the one conversion needed.
    DisposableEffect(viewport) {
        WindowsPointer.onGesture = { centreX, centreY, dx, dy, zoom ->
            val origin = PointerDiagnostics.canvasOriginOnScreen()
            if (origin != null) {
                val about = Offset(centreX - origin.x, centreY - origin.y)
                viewport.stop()
                viewport.panBy(dx, dy)
                viewport.zoomBy(zoom, about)
            }
        }
        onDispose { WindowsPointer.onGesture = null }
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

    Box(
        modifier
            .fillMaxSize()
            .background(Color(0xFF14171B))
            .onSizeChanged { viewport.viewSize = Size(it.width.toFloat(), it.height.toFloat()) }
            // Where the drawing surface sits in the window, which is what lets a pointer reading
            // be carried out to the screen and compared with the cursor. See PointerDiagnostics.
            .onGloballyPositioned { PointerDiagnostics.canvasAt(it.positionInWindow()) }
            .pointerInput(Unit) { wheel(viewport) }
            .pointerInput(tools.bindings) { middleDragPan(viewport, tools) }
            // Deliberately not keyed on the zoom. Changing a key tears the gesture detector down
            // and builds it again, which at one key per frame of a zoom is most of the work of
            // zooming; the gesture reads the camera as it runs rather than being rebuilt for it.
            .pointerInput(slots, selection) {
                awaitEachGesture {
                    val down = awaitDrawingDown()

                    // A finger while a stylus is on the glass is a palm. The tablet ignores it
                    // and so does this, which matters just as much on a laptop resting on a wrist.
                    val stylusDown = currentEvent.changes.any {
                        it.pressed && it.type == PointerType.Stylus
                    }
                    if (down.type == PointerType.Touch && stylusDown) return@awaitEachGesture

                    // Name what touched the screen, then ask the table what that does. Naming
                    // and deciding are separate on purpose: everything uncertain about a device
                    // is in the naming, and what follows from it is a row anybody can change.
                    val device = InputSignal.deviceOf(down)
                    val button = InputSignal.buttonOf(device, currentEvent.buttons)
                    val action = tools.bindings.actionFor(device, button)

                    // Two fingers are a pinch, not a stroke, so nothing starts under them.
                    if (WindowsPointer.gesturing) return@awaitEachGesture

                    // The three that are not gestures at all: they happen once, on the press.
                    when (action) {
                        InputAction.NOTHING -> return@awaitEachGesture
                        InputAction.UNDO -> {
                            onUndo()
                            return@awaitEachGesture
                        }

                        InputAction.REDO -> {
                            onRedo()
                            return@awaitEachGesture
                        }

                        else -> Unit
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
                        action = action
                    )
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val vp = viewport
            // Measured around the whole frame rather than sampled, because a stutter is one slow
            // frame among fast ones and sampling is exactly what misses it.
            val startedNs = System.nanoTime()
            RenderStats.startFrame()
            var drawnStrokes = 0
            var pagesVisible = 0
            translate(-vp.offset.x * vp.scale, -vp.offset.y * vp.scale) {
                scale(vp.scale, vp.scale, pivot = Offset.Zero) {
                    for (slot in slots) {
                        if (!visible(slot, vp)) continue
                        pagesVisible++
                        // What of this page is on screen, in the coordinates its marks are
                        // stored in - so a mark can be skipped by comparing two rectangles
                        // rather than by drawing it and letting the clip decide.
                        val topLeft = slot.toInk(vp.offset.x, vp.offset.y)
                        val bottomRight = slot.toInk(
                            vp.offset.x + vp.viewSize.width / vp.scale,
                            vp.offset.y + vp.viewSize.height / vp.scale
                        )
                        val onScreen =
                            InkBox(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
                        translate(slot.originX, slot.originY) {
                            drawnStrokes += drawPage(
                                canvas = canvas,
                                slot = slot,
                                visibleInPage = onScreen,
                                tile = rasters[slot.index],
                                overview = overviews[slot.index],
                                strokes = byPage[slot.index].orEmpty(),
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
            RenderStats.noteIfSlow(vp.scale)
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

/**
 * A rendered piece of a page: which part of it, and the picture of that part.
 *
 * Kept as a piece rather than the whole page because the whole page, at the size it is shown when
 * the view is magnified, is a bitmap of tens of megabytes - far more than a graphics card will
 * hold on to between frames, so it was being sent across again for every frame at around forty
 * milliseconds a page. What is on screen is never bigger than the window.
 */
class PageTile(val region: InkBox, val bitmap: ImageBitmap) {

    /** Whether this piece covers everything now being looked at. */
    fun covers(seen: InkBox): Boolean =
        region.left <= seen.left + 0.5f && region.top <= seen.top + 0.5f &&
            region.right >= seen.right - 0.5f && region.bottom >= seen.bottom - 0.5f

    /** How many pixels this holds across [pageSpan] page points, for comparing sharpness. */
    fun acrossPx(pageSpan: Float): Int =
        if (region.width <= 0f) 0 else (bitmap.width * pageSpan / region.width).roundToInt()
}

/** Put a rendered piece of page where it belongs, in the page's own coordinates. */
private fun DrawScope.drawTile(
    tile: PageTile,
    offsetX: Float,
    offsetY: Float,
    pageFilter: PageFilter
) {
    if (tile.bitmap.width <= 0 || tile.bitmap.height <= 0) return
    translate(offsetX + tile.region.left, offsetY + tile.region.top) {
        scale(
            tile.region.width / tile.bitmap.width,
            tile.region.height / tile.bitmap.height,
            pivot = Offset.Zero
        ) {
            drawImage(tile.bitmap, colorFilter = pageFilter.colorFilter)
        }
    }
}

/**
 * How much beyond the window is rendered, as a fraction of it.
 *
 * Enough that an ordinary nudge of the page does not mean rendering it again; little enough that
 * the picture stays small. Rendering exactly the window would re-render on every frame of a pan.
 */
private const val TILE_MARGIN = 0.15f

/** The widest a rendered piece may be: comfortably inside what a graphics card will keep. */
private const val MAX_TILE_PX = 3_000

/** How wide the small picture of a whole page is. Soft, but never missing. */
private const val OVERVIEW_PX = 1_100

/**
 * Whether a rendered piece of a page resembles the same part of the whole page.
 *
 * Only how light it is, sampled from a handful of patches. That is enough to catch the failure
 * this exists for - a piece that came back blank or black - without being so fussy that a piece
 * rendered sharper than the picture it is compared against counts as wrong.
 */
private fun looksLike(
    piece: ImageBitmap,
    overview: PageTile?,
    region: InkBox,
    pageWidth: Float,
    pageHeight: Float
): Boolean {
    val whole = overview ?: return true
    if (pageWidth <= 0f || pageHeight <= 0f) return true
    return runCatching {
        val mine = brightnessOf(piece, 0f, 0f, 1f, 1f)
        val theirs = brightnessOf(
            whole.bitmap,
            region.left / pageWidth,
            region.top / pageHeight,
            region.width / pageWidth,
            region.height / pageHeight
        )
        if (mine < 0f || theirs < 0f) true else kotlin.math.abs(mine - theirs) < 0.35f
    }.getOrDefault(true)
}

/** Mean brightness of a fraction of a picture, from sampled patches. -1 when it cannot be read. */
private fun brightnessOf(
    image: ImageBitmap,
    fromX: Float,
    fromY: Float,
    spanX: Float,
    spanY: Float
): Float {
    val left = (image.width * fromX).roundToInt().coerceIn(0, image.width - 1)
    val top = (image.height * fromY).roundToInt().coerceIn(0, image.height - 1)
    val width = (image.width * spanX).roundToInt().coerceIn(1, image.width - left)
    val height = (image.height * spanY).roundToInt().coerceIn(1, image.height - top)
    var total = 0f
    var seen = 0
    for (row in 0 until 4) {
        for (column in 0 until 4) {
            val x = (left + width * column / 4).coerceIn(left, left + width - 1)
            val y = (top + height * row / 4).coerceIn(top, top + height - 1)
            val patchW = minOf(6, left + width - x)
            val patchH = minOf(6, top + height - y)
            if (patchW <= 0 || patchH <= 0) continue
            val pixels = image.toPixelMap(x, y, patchW, patchH)
            for (py in 0 until patchH) {
                for (px in 0 until patchW) {
                    val colour = pixels[px, py]
                    total += (colour.red + colour.green + colour.blue) / 3f
                    seen++
                }
            }
        }
    }
    return if (seen == 0) -1f else total / seen
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
/** Draws one page and returns how many marks it actually put on the screen. */
private fun DrawScope.drawPage(
    canvas: com.inkslate.core.InkCanvas?,
    slot: PageSlot,
    /** The part of this page inside the window, in the coordinates marks are stored in. */
    visibleInPage: InkBox?,
    tile: PageTile?,
    /** The whole page, small, drawn under [tile] so there is never bare paper. */
    overview: PageTile?,
    /** This page's marks, already ordered - see the caller. */
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
): Int {
    var drawn = 0
    if (canvas != null) {
        // The canvas is drawn in its own coordinates, which start where it starts - and that may
        // be negative. Everything on it, including the page's raster, is placed against that.
        translate(-canvas.left, -canvas.top) {
            val paperAt = System.nanoTime()
            with(CanvasPaper) { drawCanvasPaper(canvas, scale, visibleInPage) }
            RenderStats.addPaper(System.nanoTime() - paperAt)
            val pageAt = System.nanoTime()
            // Paper this program ruled is already drawn, across the whole canvas and without a
            // seam, by the paint above. Drawing the page's picture over the middle of it would
            // put a photograph of the same lines on top of the lines themselves - the same ruling
            // twice, agreeing everywhere except in sharpness, which is what made the old page
            // edge visible at close zoom. A page somebody brought with them is drawn as it is.
            if (!canvas.ownPaper) {
                overview?.let { drawTile(it, canvas.paperLeft, canvas.paperTop, pageFilter) }
                tile?.let { drawTile(it, canvas.paperLeft, canvas.paperTop, pageFilter) }
            }
            RenderStats.addRaster(System.nanoTime() - pageAt)
        }
    } else {
        val pageAt = System.nanoTime()
        drawRect(Color.White, topLeft = Offset.Zero, size = Size(slot.width, slot.height))
        // A cropped page is laid out at its content's size while the picture is still of the whole
        // page, so it is drawn shifted by the trimmed margin. Stroke coordinates stay relative to
        // the whole page, which is what makes turning the crop on and off unable to move ink.
        overview?.let { drawTile(it, -(crop?.left ?: 0f), -(crop?.top ?: 0f), pageFilter) }
        tile?.let { drawTile(it, -(crop?.left ?: 0f), -(crop?.top ?: 0f), pageFilter) }
        RenderStats.addRaster(System.nanoTime() - pageAt)
    }

    // Ink is stored in page coordinates, which for a canvas are the canvas's own - so the whole
    // layer shifts by the canvas origin and nothing else changes. Taken from the slot rather than
    // worked out again here, because the reading side works it out from the slot and the two
    // being the same number is the whole point.
    translate(-slot.inkLeft, -slot.inkTop) {
    clipRect(
        if (canvas != null) canvas.left else 0f,
        if (canvas != null) canvas.top else 0f,
        if (canvas != null) canvas.right else slot.width,
        if (canvas != null) canvas.bottom else slot.height
    ) {
        // Already grouped per page and ordered highlighter-first by the caller. Doing it here
        // meant filtering and sorting every mark in the document on every frame, and a frame
        // happens for every point of the mark being drawn.
        val inkAt = System.nanoTime()
        for (s in strokes) {
            // Marks outside the window are skipped rather than handed to a clip that would have
            // to walk their geometry to discover the same thing. This is what stops the cost of a
            // frame growing with the size of the document rather than with what is on screen.
            if (s.kind != Stroke.Kind.TEXT && visibleInPage != null) {
                val b = InkGeometry.bounds(s)
                val pad = s.baseWidth + 2f
                val off = b.right + pad < visibleInPage.left ||
                    b.left - pad > visibleInPage.right ||
                    b.bottom + pad < visibleInPage.top ||
                    b.top - pad > visibleInPage.bottom
                if (off) continue
            }
            when (s.kind) {
                Stroke.Kind.TEXT -> drawTextStroke(s, textMeasurer)
                Stroke.Kind.IMAGE -> drawImageStroke(s, images)
                else -> drawStroke(s)
            }
            drawn++
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
                ),
                cached = false
            )
        }
        pending?.let { drawStroke(it, cached = false) }
        pendingStamp.forEach {
            if (it.kind != Stroke.Kind.TEXT) drawStroke(it, cached = false)
        }

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

        strokes.filter { it.id in selection }.unionBounds()?.let { drawSelection(it, scale) }
        RenderStats.addInk(System.nanoTime() - inkAt)
    }
    }

    // Outside the clip: a straightedge lies on top of the page and may hang over its edge, the
    // way a real one does.
    ruler?.let { drawRuler(it, scale, textMeasurer) }
    return drawn
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
 * Middle-button drag: pan, whatever tool is in hand.
 *
 * On its own handler rather than inside the drawing gesture, because the drawing gesture begins
 * with [awaitFirstDown] and a middle button is not a "down" - only the primary button makes a
 * pointer pressed. Waiting for one meant this code never ran at all, and the middle button did
 * nothing.
 *
 * Watched on the initial pass and consumed while it is panning, so the pen underneath never sees
 * the drag and no mark is left behind by moving the page.
 */
private suspend fun PointerInputScope.middleDragPan(viewport: Viewport, tools: ToolState) {
    // Only while the middle button is still the one that moves the page. Bound to something else
    // it falls through to the ordinary gesture like any other button, which is the point of a
    // table rather than a handler per button.
    val pans = tools.bindings.actionFor(InputDevice.MOUSE, InputButton.MIDDLE) == InputAction.PAN
    if (!pans) return
    awaitPointerEventScope {
        var last: Offset? = null
        val throwing = PanThrow()
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull()
            val held = event.buttons.isTertiaryPressed
            when {
                held && change != null -> {
                    val previous = last
                    if (previous == null) {
                        viewport.stop()
                        throwing.begin()
                    } else {
                        val moved = change.position - previous
                        viewport.panBy(moved.x, moved.y)
                        throwing.sample(moved.x, moved.y)
                    }
                    last = change.position
                    event.changes.forEach { it.consume() }
                }

                last != null -> {
                    // Nothing at all if the hand had stopped before it let go. See PanThrow.
                    val thrown = throwing.release()
                    viewport.throwBy(thrown.x, thrown.y)
                    last = null
                }
            }
        }
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
    // A trackpad that has just spoken is still a trackpad a moment later, even on an event that
    // happens to look like a wheel's. Without this a two-finger drag zooms every so often, which
    // is worse than it never working.
    var glassSeenAt = 0L

    while (true) {
        val event = awaitPointerEvent()
        if (event.type != PointerEventType.Scroll) continue
        val change = event.changes.firstOrNull() ?: continue
        val sideways = change.scrollDelta.x
        val notches = change.scrollDelta.y
        if (notches == 0f && sideways == 0f) continue

        val now = System.currentTimeMillis()
        if (twoFingered(event, sideways)) glassSeenAt = now
        val trackpad = now - glassSeenAt < GLASS_MEMORY_MS

        val shift = event.keyboardModifiers.isShiftPressed
        val ctrl = event.keyboardModifiers.isCtrlPressed
        when {
            // A pinch on a trackpad reaches a program as the wheel with control held, which is
            // also how every other program is asked to zoom.
            trackpad && ctrl -> {
                viewport.stop()
                viewport.zoomBy(zoomFor(notches), change.position)
            }

            // Two fingers on a trackpad move the page the way two fingers on the glass do: both
            // directions at once, following the fingers, with no throw of their own - the fingers
            // are still there to keep moving it.
            trackpad -> {
                viewport.stop()
                viewport.panBy(
                    -sideways * Viewport.PAN_PER_NOTCH,
                    -notches * Viewport.PAN_PER_NOTCH
                )
            }

            // Sideways, with the throw carrying on after the wheel stops. Whichever axis the
            // hardware used to say so: a trackpad reports sideways movement on its own axis and
            // nothing on the wheel's, so reading only the wheel's meant shift did nothing there.
            shift -> {
                val amount = if (notches != 0f) notches else sideways
                viewport.throwBy(
                    -amount * Viewport.PAN_VELOCITY_PER_NOTCH + viewport.velocity.x * 0.4f,
                    viewport.velocity.y * 0.4f
                )
            }

            // Down the page, the direction a wheel usually means when it is not zooming.
            ctrl -> viewport.throwBy(
                viewport.velocity.x * 0.4f,
                -notches * Viewport.PAN_VELOCITY_PER_NOTCH + viewport.velocity.y * 0.4f
            )

            else -> {
                viewport.stop()
                viewport.zoomBy(zoomFor(notches), change.position)
            }
        }
        change.consume()
    }
}

private fun zoomFor(notches: Float): Float =
    Math.pow(Viewport.ZOOM_PER_NOTCH.toDouble(), -notches.toDouble()).toFloat()

/** How long a trackpad is still assumed to be the thing scrolling. */
private const val GLASS_MEMORY_MS = 600L

/**
 * Whether this scroll came from two fingers rather than a wheel.
 *
 * Two tells, and either is enough. A wheel has no sideways axis at all, so anything arriving on it
 * came from a surface. And a wheel turns in whole notches while a trackpad reports the fraction of
 * one the fingers have moved - so a rotation that is not a whole number was not a wheel.
 */
private fun twoFingered(event: PointerEvent, sideways: Float): Boolean {
    if (sideways != 0f) return true
    val awt = event.nativeEvent as? java.awt.event.MouseWheelEvent ?: return false
    val turned = awt.preciseWheelRotation
    return kotlin.math.abs(turned - Math.round(turned)) > 0.01
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
