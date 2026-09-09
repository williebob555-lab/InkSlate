package com.inkslate.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.inkslate.core.Box as InkBox
import com.inkslate.core.DynamicWidth
import com.inkslate.core.EraserMode
import com.inkslate.core.InkPoint
import com.inkslate.core.Stroke
import com.inkslate.core.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One page, and everything you can do to it.
 *
 * The page raster underneath, the ink on top, and the pointer handling for every tool. Ink is
 * stored in page points; one scale puts the whole layer in the right place rather than converting
 * every point on every frame.
 */
@Composable
fun PageCanvas(
    source: DesktopSource,
    index: Int,
    strokes: MutableList<Stroke>,
    selection: Set<String>,
    onSelection: (Set<String>) -> Unit,
    zoom: Float,
    tools: ToolState,
    textMeasurer: TextMeasurer,
    newId: () -> String,
    onCommitted: (Op) -> Unit,
    onEditText: (Stroke) -> Unit,
    onPlaceText: (Float, Float, Int) -> Unit
) {
    val dim = remember(index) { source.pageDim(index) }
    val pxWidth = (dim.width * zoom).roundToInt().coerceAtLeast(80)
    val pxHeight = (dim.height * zoom).roundToInt().coerceAtLeast(80)

    // Rendered lazily and re-rendered when the zoom moves enough to matter. Rounding to a bucket
    // stops a slider drag from queueing a full-page render for every intermediate value.
    val bucket = remember(pxWidth) { (pxWidth / 120) * 120 }
    var bitmap by remember(index) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(index, bucket) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching { source.render(index, max(120, bucket)) }.getOrNull()
        }
    }

    // The live gesture, kept out of the committed list so that is not rewritten on every move.
    var live by remember { mutableStateOf<List<InkPoint>>(emptyList()) }
    var pending by remember { mutableStateOf<Stroke?>(null) }
    var marquee by remember { mutableStateOf<InkBox?>(null) }
    val scale = pxWidth / dim.width

    Card(
        shape = RoundedCornerShape(3.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        val density = LocalDensity.current.density
        Box(
            Modifier
                .size((pxWidth / density).dp, (pxHeight / density).dp)
                .pointerInput(index, scale, tools.revision, selection) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        // Read synchronously: the config is deliberately not Compose state so the
                        // tool in hand the instant the pointer lands is the one that acts.
                        val cfg = tools.active
                        val px = down.position.x / scale
                        val py = down.position.y / scale

                        when {
                            cfg.tool == Tool.ERASER -> {
                                // One rub can touch the same stroke repeatedly, and a partial
                                // erase replaces it with pieces that the next moment can erase
                                // again. So the undo record is built from the two ends only: the
                                // strokes as they were when the gesture began, and whatever is
                                // left when it finishes. Recording each intermediate step instead
                                // put pieces into the "before" list that had never been on the
                                // page, and undoing brought them into existence.
                                val originals = LinkedHashMap<String, Stroke>()
                                val minted = LinkedHashSet<String>()

                                fun eraseAt(pos: Offset) {
                                    val ex = pos.x / scale
                                    val ey = pos.y / scale
                                    val r = cfg.eraserRadius
                                    val hits = strokes.filter {
                                        it.pageIndex == index && it.hitTest(ex, ey, r)
                                    }
                                    for (hit in hits) {
                                        val replacement =
                                            if (cfg.eraserMode == EraserMode.STROKE) emptyList()
                                            else hit.erasedAt(ex, ey, r, newId)
                                        if (replacement.size == 1 && replacement[0] === hit) continue
                                        // Only a stroke that was on the page before this gesture
                                        // belongs in "before"; the rest are pieces we made.
                                        if (hit.id !in minted) originals.putIfAbsent(hit.id, hit)
                                        replacement.forEach { minted.add(it.id) }
                                        strokes.applyEdit(listOf(hit), replacement)
                                    }
                                }

                                eraseAt(down.position)
                                dragUntilRelease(down.position) { eraseAt(it) }
                                if (originals.isNotEmpty()) {
                                    val survivors = strokes.filter { it.id in minted }
                                    onCommitted(Op(originals.values.toList(), survivors))
                                }
                            }

                            cfg.tool == Tool.SELECT -> {
                                val chosen = strokes.filter { it.id in selection }
                                val box = chosen.unionBounds()
                                // The frame and the handle being dragged travel together, so
                                // that having one is having both.
                                val grabbed = box?.let { b ->
                                    handleAt(b, px, py, HANDLE_TOUCH / scale)?.let { b to it }
                                }
                                when {
                                    grabbed != null -> {
                                        val (box, handle) = grabbed
                                        var current = chosen
                                        dragUntilRelease(down.position) { pos ->
                                            val nx = pos.x / scale
                                            val ny = pos.y / scale
                                            val ax = handle.anchorX(box)
                                            val ay = handle.anchorY(box)
                                            val sx = if (handle.scalesX && abs(box.width) > 0.01f) {
                                                ((nx - ax) / (handle.x(box) - ax)).coerceIn(
                                                    -20f, 20f
                                                )
                                            } else 1f
                                            val sy = if (handle.scalesY && abs(box.height) > 0.01f) {
                                                ((ny - ay) / (handle.y(box) - ay)).coerceIn(
                                                    -20f, 20f
                                                )
                                            } else 1f
                                            if (abs(sx) < 0.02f || abs(sy) < 0.02f) return@dragUntilRelease
                                            val now = System.currentTimeMillis()
                                            current = chosen.map {
                                                it.scaledAbout(ax, ay, sx, sy, now)
                                            }
                                            strokes.applyEdit(chosen, current)
                                        }
                                        if (current !== chosen) onCommitted(Op(chosen, current))
                                    }

                                    box != null && box.expanded(4f / scale).contains(px, py) -> {
                                        var current = chosen
                                        dragUntilRelease(down.position) { pos ->
                                            val dx = (pos.x - down.position.x) / scale
                                            val dy = (pos.y - down.position.y) / scale
                                            val now = System.currentTimeMillis()
                                            current = chosen.map { it.movedBy(dx, dy, now) }
                                            strokes.applyEdit(chosen, current)
                                        }
                                        if (current !== chosen) onCommitted(Op(chosen, current))
                                    }

                                    else -> {
                                        // A tap picks the topmost thing under it; a drag boxes.
                                        var dragged = false
                                        val end = dragUntilRelease(down.position) { pos ->
                                            dragged = dragged ||
                                                (pos - down.position).getDistance() > 6f
                                            if (dragged) {
                                                marquee = InkBox.of(
                                                    px, py, pos.x / scale, pos.y / scale
                                                )
                                            }
                                        }
                                        if (dragged) {
                                            val area = InkBox.of(
                                                px, py, end.x / scale, end.y / scale
                                            )
                                            onSelection(
                                                strokes.filter {
                                                    it.pageIndex == index && it.insideBox(area)
                                                }.map { it.id }.toSet()
                                            )
                                        } else {
                                            val hit = strokes.lastOrNull {
                                                it.pageIndex == index &&
                                                    it.hitTest(px, py, TAP_SLOP / scale)
                                            }
                                            onSelection(hit?.let { setOf(it.id) } ?: emptySet())
                                        }
                                        marquee = null
                                    }
                                }
                            }

                            cfg.tool == Tool.TEXT -> {
                                // Land on an existing text box and you are editing it, not
                                // stacking a second one on top.
                                val existing = strokes.lastOrNull {
                                    it.pageIndex == index && it.kind == Stroke.Kind.TEXT &&
                                        it.hitTest(px, py, TAP_SLOP / scale)
                                }
                                dragUntilRelease(down.position) { }
                                if (existing != null) onEditText(existing)
                                else onPlaceText(px, py, index)
                            }

                            cfg.tool.isShape || cfg.tool == Tool.TABLE -> {
                                val kind = when (cfg.tool) {
                                    Tool.LINE -> Stroke.Kind.LINE
                                    Tool.ARROW -> Stroke.Kind.ARROW
                                    Tool.RECT -> Stroke.Kind.RECT
                                    Tool.ELLIPSE -> Stroke.Kind.ELLIPSE
                                    else -> Stroke.Kind.TABLE
                                }
                                val end = dragUntilRelease(down.position) { pos ->
                                    pending = shapeStroke(
                                        id = "live", kind = kind,
                                        ax = px, ay = py,
                                        bx = pos.x / scale, by = pos.y / scale,
                                        color = cfg.color, width = cfg.strokeWidth,
                                        dash = cfg.dash, fill = cfg.fillStyle,
                                        fillColor = cfg.fillColor, opacity = cfg.opacity,
                                        page = index,
                                        rows = tools.tableRows, cols = tools.tableCols
                                    )
                                }
                                pending = null
                                val bx = end.x / scale
                                val by = end.y / scale
                                // A click with no drag is not a shape; it is a misplaced click.
                                if (abs(bx - px) > 2f || abs(by - py) > 2f) {
                                    val s = shapeStroke(
                                        id = newId(), kind = kind,
                                        ax = px, ay = py, bx = bx, by = by,
                                        color = cfg.color, width = cfg.strokeWidth,
                                        dash = cfg.dash, fill = cfg.fillStyle,
                                        fillColor = cfg.fillColor, opacity = cfg.opacity,
                                        page = index,
                                        rows = tools.tableRows, cols = tools.tableCols
                                    )
                                    strokes.add(s)
                                    onCommitted(Op.added(s))
                                }
                            }

                            cfg.tool == Tool.PAN -> dragUntilRelease(down.position) { }

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
                                var smoothX = down.position.x
                                var smoothY = down.position.y
                                val alpha = 1f - cfg.smoothing.coerceIn(0f, 0.92f)

                                fun sample(pos: Offset, pressure: Float) {
                                    val now = System.nanoTime()
                                    // Speed stands in for pressure when the device does not
                                    // report it. A dead constant width reads as a machine
                                    // drawing; this at least thins on fast strokes the way a pen
                                    // does, and it is what the tablet does for finger input.
                                    val dt = ((now - lastAt) / 1_000_000f).coerceAtLeast(0.5f)
                                    val speed = (pos - lastPos).getDistance() / dt
                                    val fromSpeed = (1f - (speed / 3.2f)).coerceIn(0.25f, 1f)
                                    val p = if (pressure in 0.02f..0.98f) pressure else fromSpeed
                                    lastAt = now
                                    lastPos = pos
                                    smoothX += (pos.x - smoothX) * alpha
                                    smoothY += (pos.y - smoothY) * alpha
                                    collected.add(
                                        InkPoint(
                                            smoothX / scale,
                                            smoothY / scale,
                                            brush.widthFor(nominal, p, cfg.dynamics)
                                        )
                                    )
                                }

                                sample(down.position, down.pressure)
                                live = ArrayList(collected)
                                dragUntilRelease(down.position) { pos ->
                                    sample(pos, 1f)
                                    live = ArrayList(collected)
                                }
                                live = emptyList()

                                if (collected.size >= 2) {
                                    val s = Stroke(
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
                                    strokes.add(s)
                                    onCommitted(Op.added(s))
                                }
                            }
                        }
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                bitmap?.let {
                    drawImage(
                        it,
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
                    )
                }
                scale(scale, scale, pivot = Offset.Zero) {
                    // Highlighter first, so it sits under the ink it is marking rather than
                    // washing over it - the same order the exporter writes.
                    val page = strokes.filter { it.pageIndex == index }
                    for (s in page.sortedBy { if (it.isHighlighter) 0 else 1 }) {
                        if (s.kind == Stroke.Kind.TEXT) drawTextStroke(s, textMeasurer)
                        else drawStroke(s)
                    }

                    live.takeIf { it.size >= 2 }?.let { pts ->
                        val cfg = tools.active
                        drawStroke(
                            Stroke(
                                id = "live",
                                kind = Stroke.Kind.FREEHAND,
                                color = cfg.color,
                                baseWidth = cfg.strokeWidth,
                                points = pts,
                                brush = cfg.brush,
                                dash = cfg.dash,
                                opacity = cfg.opacity,
                                pageIndex = index
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

                    val chosen = page.filter { it.id in selection }
                    chosen.unionBounds()?.let { drawSelection(it, scale) }
                }
            }

            if (bitmap == null) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        "Page ${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF999999)
                    )
                }
            }
        }
    }
}

/**
 * Follow the pointer until it lifts, reporting where it ended.
 *
 * An extension on the gesture scope rather than a local function inside it: [awaitEachGesture]
 * runs in a restricted suspension scope, which by design refuses to suspend anywhere except on
 * its own receiver - so a helper that awaits pointer events has to be one of its extensions.
 */
private suspend fun AwaitPointerEventScope.dragUntilRelease(
    start: Offset,
    onMove: (Offset) -> Unit
): Offset {
    var last = start
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull() ?: break
        if (event.type == PointerEventType.Move) {
            last = change.position
            onMove(change.position)
        }
        if (!change.pressed) break
    }
    return last
}

/**
 * The selection frame and its handles.
 *
 * Drawn in page space like everything else, so the numbers below are divided by the zoom: a
 * handle has to stay the same size under the mouse however far in the page is, or it becomes
 * either unhittable or an obstruction.
 */
private fun DrawScope.drawSelection(box: InkBox, scale: Float) {
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

/** How close a click has to be to a handle, and how big one is drawn, in screen pixels. */
private const val HANDLE_TOUCH = 11f
private const val HANDLE_DRAW = 4.5f

/** How far a click may miss a stroke and still select it, in screen pixels. */
private const val TAP_SLOP = 9f
