package com.inkslate.desktop

import com.inkslate.core.Box
import com.inkslate.core.InkPoint
import com.inkslate.core.Stroke
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * One reversible edit.
 *
 * [before] and [after] are the same objects seen from either side of the change, which is what
 * lets one type cover all three kinds of edit: adding is an empty [before], deleting an empty
 * [after], and moving, restyling or erasing part of a stroke has both. The alternative - a
 * separate op per verb - is how an undo stack ends up with a verb nobody remembered to make
 * reversible.
 */
data class Op(val before: List<Stroke>, val after: List<Stroke>) {
    companion object {
        fun added(vararg s: Stroke) = Op(emptyList(), s.toList())
        fun added(s: List<Stroke>) = Op(emptyList(), s)
        fun removed(s: List<Stroke>) = Op(s, emptyList())
        fun edited(before: List<Stroke>, after: List<Stroke>) = Op(before, after)
    }
}

/**
 * Put [target] into [strokes], replacing anything with the same id and removing what is gone.
 *
 * Replacing in place rather than removing and appending, because the list *is* the z-order.
 * Nudging a stroke a millimetre would otherwise bring it to the front, which is invisible until
 * the day it hides something underneath it.
 */
fun MutableList<Stroke>.applyEdit(removed: List<Stroke>, target: List<Stroke>) {
    val byId = target.associateBy { it.id }
    val seen = HashSet<String>(target.size * 2)
    for (i in indices) {
        val replacement = byId[this[i].id] ?: continue
        this[i] = replacement
        seen.add(replacement.id)
    }
    val goneIds = removed.map { it.id }.toSet() - seen
    if (goneIds.isNotEmpty()) removeAll { it.id in goneIds }
    for (s in target) if (s.id !in seen) add(s)
}

/** Apply an op forwards (redo, or the original edit) or backwards (undo). */
fun MutableList<Stroke>.apply(op: Op, forward: Boolean) {
    if (forward) applyEdit(op.before, op.after) else applyEdit(op.after, op.before)
}

// ---- transforms --------------------------------------------------------------

fun Stroke.movedBy(dx: Float, dy: Float, now: Long): Stroke = copy(
    points = points.map { InkPoint(it.x + dx, it.y + dy, it.width) },
    updatedUtc = now
)

/**
 * Scale a stroke about [anchor], the corner being dragged away from.
 *
 * Widths scale with the smaller of the two factors rather than with either one alone: a stroke
 * stretched only horizontally should not also get thicker, and taking the larger factor would
 * make a shape blow out its own outline when squashed.
 */
fun Stroke.scaledAbout(
    anchorX: Float,
    anchorY: Float,
    sx: Float,
    sy: Float,
    now: Long
): Stroke {
    val w = kotlin.math.min(kotlin.math.abs(sx), kotlin.math.abs(sy))
    return copy(
        points = points.map {
            InkPoint(
                anchorX + (it.x - anchorX) * sx,
                anchorY + (it.y - anchorY) * sy,
                max(0.05f, it.width * w)
            )
        },
        baseWidth = max(0.05f, baseWidth * w),
        textSize = if (kind == Stroke.Kind.TEXT) max(4f, textSize * w) else textSize,
        boxWidth = if (boxWidth > 0f) boxWidth * kotlin.math.abs(sx) else boxWidth,
        boxHeight = if (boxHeight > 0f) boxHeight * kotlin.math.abs(sy) else boxHeight,
        updatedUtc = now
    )
}

/** The union of several strokes' bounds, or null when there are none. */
fun List<Stroke>.unionBounds(): Box? {
    if (isEmpty()) return null
    var box = this[0].boundsBox()
    for (i in 1 until size) box = box.union(this[i].boundsBox())
    return box
}

/** The eight resize handles, clockwise from the top-left. */
enum class Handle(val fx: Float, val fy: Float) {
    TOP_LEFT(0f, 0f), TOP(0.5f, 0f), TOP_RIGHT(1f, 0f), RIGHT(1f, 0.5f),
    BOTTOM_RIGHT(1f, 1f), BOTTOM(0.5f, 1f), BOTTOM_LEFT(0f, 1f), LEFT(0f, 0.5f);

    fun x(b: Box) = b.left + b.width * fx
    fun y(b: Box) = b.top + b.height * fy

    /** The point that stays put while this handle is dragged. */
    fun anchorX(b: Box) = b.left + b.width * (1f - fx)
    fun anchorY(b: Box) = b.top + b.height * (1f - fy)

    val scalesX: Boolean get() = fx != 0.5f
    val scalesY: Boolean get() = fy != 0.5f
}

/** Which handle, if any, is within [radius] of a point. */
fun handleAt(box: Box, px: Float, py: Float, radius: Float): Handle? =
    Handle.entries.firstOrNull { hypot(it.x(box) - px, it.y(box) - py) <= radius }

// ---- erasing -----------------------------------------------------------------

/**
 * Rub out the part of a freehand stroke within [radius] of a point.
 *
 * Returns the pieces that survive, which may be none, one, or several: erasing through the middle
 * of a line leaves two. Each piece is a new object with its own id, because they are new objects -
 * giving them the original's id would make two strokes that a merge could not tell apart, and
 * this file syncs.
 *
 * Anything that is not freehand comes back unchanged; a partial eraser through a text box or a
 * table has no sensible meaning, and taking those away whole is what the stroke eraser is for.
 */
fun Stroke.erasedAt(px: Float, py: Float, radius: Float, newId: () -> String): List<Stroke> {
    if (kind != Stroke.Kind.FREEHAND || points.size < 2) return listOf(this)
    val keep = points.map { hypot(it.x - px, it.y - py) > radius + it.width / 2f }
    if (keep.all { it }) return listOf(this)
    if (keep.none { it }) return emptyList()

    val out = ArrayList<Stroke>()
    var run = ArrayList<InkPoint>()
    val now = System.currentTimeMillis()
    fun flush() {
        // A single surviving sample is a dot nobody drew; it reads as grit left behind.
        if (run.size >= 2) {
            out.add(copy(id = newId(), points = ArrayList(run), updatedUtc = now))
        }
        run = ArrayList()
    }
    for (i in points.indices) {
        if (keep[i]) run.add(points[i]) else flush()
    }
    flush()
    return out
}

// ---- building ----------------------------------------------------------------

/** A shape dragged from [ax],[ay] to [bx],[by]. */
fun shapeStroke(
    id: String,
    kind: Stroke.Kind,
    ax: Float, ay: Float, bx: Float, by: Float,
    color: Int,
    width: Float,
    dash: com.inkslate.core.DashStyle,
    fill: com.inkslate.core.FillStyle,
    fillColor: Int,
    opacity: Float,
    page: Int,
    rows: Int = 0,
    cols: Int = 0
): Stroke = Stroke(
    id = id,
    kind = kind,
    color = color,
    baseWidth = width,
    points = listOf(InkPoint(ax, ay, width), InkPoint(bx, by, width)),
    dash = dash,
    fill = fill,
    fillColor = fillColor,
    opacity = opacity,
    rows = rows,
    cols = cols,
    cells = if (kind == Stroke.Kind.TABLE) List(rows * cols) { "" } else emptyList(),
    pageIndex = page,
    updatedUtc = System.currentTimeMillis()
)

/**
 * Where an arrow's head goes, as two line segments from the tip.
 *
 * Shared by the screen and the exporter through [toComposePath]; kept here as well because the
 * ruler and the selection overlay want the same geometry without building a path.
 */
fun arrowHead(ax: Float, ay: Float, bx: Float, by: Float, baseWidth: Float): List<Pair<Float, Float>> {
    val size = max(3f, baseWidth * 3.6f)
    val angle = kotlin.math.atan2((by - ay).toDouble(), (bx - ax).toDouble())
    val spread = Math.toRadians(26.0)
    return listOf(-spread, spread).map { s ->
        val a = angle + Math.PI + s
        (bx + (cos(a) * size).toFloat()) to (by + (sin(a) * size).toFloat())
    }
}
