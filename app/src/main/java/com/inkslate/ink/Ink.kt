package com.inkslate.ink

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import com.inkslate.core.Box
import com.inkslate.core.Stroke.Kind as StrokeKind
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import com.inkslate.core.Tool
import com.inkslate.core.InputMode
import com.inkslate.core.ToolConfig
import com.inkslate.core.ToolSnapshot

/**
 * Android bindings for the shared document model.
 *
 * The model itself lives in `:core` with no graphics dependency, so the desktop app uses exactly
 * the same classes and the file format cannot drift between platforms. Everything here is the
 * part that genuinely needs `android.graphics`: building paths, and working in [RectF] and
 * [Matrix] rather than the platform-neutral [Box].
 */

typealias Stroke = com.inkslate.core.Stroke
typealias InkPoint = com.inkslate.core.InkPoint
typealias BrushType = com.inkslate.core.BrushType
typealias DashStyle = com.inkslate.core.DashStyle
typealias FillStyle = com.inkslate.core.FillStyle
typealias TextFont = com.inkslate.core.TextFont
typealias TextAlign = com.inkslate.core.TextAlign


fun Box.toRectF() = RectF(left, top, right, bottom)
fun RectF.toBox() = Box(left, top, right, bottom)

/** What the user is doing with their pointer. Purely a UI concern, so it stays platform-side. */
// Tool, ToolConfig, ToolSnapshot and InputMode now live in :core. They are pure data with no
// platform dependency, and the toolbar that presents them is shared with the desktop build - a
// tool that existed on only one side would be a toolbar that could not be.

// ---- geometry, in Android's own types ---------------------------------------

fun Stroke.rawBounds(): RectF = rawBoundsBox().toRectF()
fun Stroke.bounds(): RectF = boundsBox().toRectF()
fun Stroke.rectOf(): RectF = rectBox().toRectF()
fun Stroke.cellRect(row: Int, col: Int): RectF = cellBox(row, col).toRectF()
fun Stroke.insideRect(r: RectF): Boolean = insideBox(r.toBox())

/** Rotation applied about the object centre, for the renderer and the exporter. */
fun Stroke.rotationMatrix(): Matrix? {
    if (rotation == 0f) return null
    val r = rawBoundsBox()
    return Matrix().apply { setRotate(rotation, r.centerX, r.centerY) }
}

/** Apply an affine transform to the geometry. Used by move, resize and rotate. */
fun Stroke.transformed(m: Matrix, newId: String = id): Stroke {
    val src = FloatArray(points.size * 2)
    points.forEachIndexed { i, p -> src[i * 2] = p.x; src[i * 2 + 1] = p.y }
    m.mapPoints(src)

    val v = FloatArray(9)
    m.getValues(v)
    val sx = hypot(v[Matrix.MSCALE_X], v[Matrix.MSKEW_Y])
    val sy = hypot(v[Matrix.MSKEW_X], v[Matrix.MSCALE_Y])
    val scale = ((sx + sy) / 2f).coerceAtLeast(0.01f)

    return copy(
        id = newId,
        points = points.mapIndexed { i, p -> InkPoint(src[i * 2], src[i * 2 + 1], p.width * scale) },
        baseWidth = baseWidth * scale,
        textSize = textSize * scale
    )
}

// ---- path construction -------------------------------------------------------

/** Display/export path in page space, excluding rotation. */
fun Stroke.toPath(): Path {
    val path = Path()
    if (points.isEmpty()) return path
    when (kind) {
        StrokeKind.FREEHAND -> {
            path.moveTo(points[0].x, points[0].y)
            if (points.size == 1) {
                path.lineTo(points[0].x + 0.01f, points[0].y)
            } else {
                // quadratics through segment midpoints remove the polygonal look of raw samples
                for (i in 1 until points.size) {
                    val prev = points[i - 1]; val cur = points[i]
                    path.quadTo(prev.x, prev.y, (prev.x + cur.x) / 2f, (prev.y + cur.y) / 2f)
                }
                path.lineTo(points.last().x, points.last().y)
            }
        }
        StrokeKind.LINE, StrokeKind.ARROW -> {
            val a = points.first(); val b = points.last()
            path.moveTo(a.x, a.y); path.lineTo(b.x, b.y)
            if (kind == StrokeKind.ARROW) {
                appendArrowHead(path, a.x, a.y, b.x, b.y, max(3f, baseWidth * 3.6f))
            }
        }
        StrokeKind.RECT -> path.addRect(rectOf(), Path.Direction.CW)
        StrokeKind.IMAGE -> path.addRect(rectOf(), Path.Direction.CW)
        StrokeKind.ELLIPSE -> path.addOval(rectOf(), Path.Direction.CW)
        StrokeKind.TABLE -> {
            val r = rectOf()
            path.addRect(r, Path.Direction.CW)
            if (cols > 1) {
                val cw = r.width() / cols
                for (c in 1 until cols) {
                    path.moveTo(r.left + cw * c, r.top); path.lineTo(r.left + cw * c, r.bottom)
                }
            }
            if (rows > 1) {
                val rh = r.height() / rows
                for (rr in 1 until rows) {
                    path.moveTo(r.left, r.top + rh * rr); path.lineTo(r.right, r.top + rh * rr)
                }
            }
        }
        StrokeKind.TEXT -> Unit
    }
    return path
}

private fun appendArrowHead(
    path: Path, x0: Float, y0: Float, x1: Float, y1: Float, size: Float
) {
    val angle = Math.atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())
    val spread = Math.toRadians(26.0)
    for (s in listOf(-spread, spread)) {
        val a = angle + Math.PI + s
        path.moveTo(x1, y1)
        path.lineTo(x1 + (cos(a) * size).toFloat(), y1 + (sin(a) * size).toFloat())
    }
}

/**
 * The filled shape of a variable-width freehand stroke.
 *
 * Geometry comes from [com.inkslate.core.StrokeOutline], which the PDF exporter also uses, so the
 * page on screen and the page in the file are the same shape by construction rather than by two
 * implementations agreeing. The path is explicitly non-zero filled: the contours are wound to
 * suit it, and their union is the ink.
 */
fun Stroke.toOutlinePath(): Path {
    val path = Path()
    path.fillType = Path.FillType.WINDING
    for (poly in com.inkslate.core.StrokeOutline.contours(this)) {
        if (poly.size < 6) continue
        path.moveTo(poly[0], poly[1])
        var i = 2
        while (i < poly.size) {
            path.lineTo(poly[i], poly[i + 1])
            i += 2
        }
        path.close()
    }
    return path
}
