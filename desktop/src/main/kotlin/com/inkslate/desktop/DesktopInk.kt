package com.inkslate.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.PathEffect
import com.inkslate.core.Box
import com.inkslate.core.DashStyle
import com.inkslate.core.FillStyle
import com.inkslate.core.Stroke
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Desktop bindings for the shared model.
 *
 * The counterpart of the Android `Ink.kt`: same geometry, expressed in Compose types instead of
 * `android.graphics`. This duplication is deliberate and confined - the *data* is shared, so the
 * two platforms cannot disagree about what a document contains, only about how to paint it.
 */

fun Box.toRect() = Rect(left, top, right, bottom)

fun Int.toComposeColor(): Color = Color(
    red = ((this shr 16) and 0xFF) / 255f,
    green = ((this shr 8) and 0xFF) / 255f,
    blue = (this and 0xFF) / 255f,
    alpha = ((this ushr 24) and 0xFF) / 255f
)

/** Path for shapes, tables and unmodulated freehand. */
fun Stroke.toComposePath(): Path {
    val path = Path()
    if (points.isEmpty()) return path
    when (kind) {
        Stroke.Kind.FREEHAND -> {
            path.moveTo(points[0].x, points[0].y)
            if (points.size == 1) {
                path.lineTo(points[0].x + 0.01f, points[0].y)
            } else {
                for (i in 1 until points.size) {
                    val prev = points[i - 1]
                    val cur = points[i]
                    path.quadraticTo(
                        prev.x, prev.y, (prev.x + cur.x) / 2f, (prev.y + cur.y) / 2f
                    )
                }
                path.lineTo(points.last().x, points.last().y)
            }
        }
        Stroke.Kind.LINE, Stroke.Kind.ARROW -> {
            val a = points.first()
            val b = points.last()
            path.moveTo(a.x, a.y)
            path.lineTo(b.x, b.y)
            if (kind == Stroke.Kind.ARROW) {
                val size = max(3f, baseWidth * 3.6f)
                val angle = kotlin.math.atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
                val spread = Math.toRadians(26.0)
                for (s in listOf(-spread, spread)) {
                    val ang = angle + Math.PI + s
                    path.moveTo(b.x, b.y)
                    path.lineTo(
                        b.x + (cos(ang) * size).toFloat(),
                        b.y + (sin(ang) * size).toFloat()
                    )
                }
            }
        }
        Stroke.Kind.RECT, Stroke.Kind.IMAGE -> path.addRect(rectBox().toRect())
        Stroke.Kind.ELLIPSE -> path.addOval(rectBox().toRect())
        Stroke.Kind.TABLE -> {
            val r = rectBox()
            path.addRect(r.toRect())
            if (cols > 1) {
                val cw = r.width / cols
                for (c in 1 until cols) {
                    path.moveTo(r.left + cw * c, r.top)
                    path.lineTo(r.left + cw * c, r.bottom)
                }
            }
            if (rows > 1) {
                val rh = r.height / rows
                for (rr in 1 until rows) {
                    path.moveTo(r.left, r.top + rh * rr)
                    path.lineTo(r.right, r.top + rh * rr)
                }
            }
        }
        Stroke.Kind.TEXT -> Unit
    }
    return path
}

/**
 * Filled shape for variable-width ink.
 *
 * Geometry comes from the shared [com.inkslate.core.StrokeOutline], the same builder the Android
 * renderer and the PDF exporter use, so a stroke drawn on the tablet and the same stroke opened on
 * the laptop are the same shape rather than two implementations that happen to agree.
 */
fun Stroke.toComposeOutline(): Path {
    val path = Path()
    path.fillType = PathFillType.NonZero
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

/**
 * Draw one object.
 *
 * Text is handled by the caller, which has access to the text measurer; everything else is pure
 * geometry and lives here.
 */
fun DrawScope.drawStroke(s: Stroke, cached: Boolean = true) {
    // A mark being drawn changes with every sample and keeps one id while it does, so its shape
    // is built fresh. Everything already committed is immutable until it is restamped, which is
    // exactly what the cache keys on.
    fun body() = if (cached) InkGeometry.path(s) else s.toComposePath()
    fun edge() = if (cached) InkGeometry.outline(s) else s.toComposeOutline()

    val colour = s.color.toComposeColor()
    val alpha = s.effectiveAlpha.coerceIn(0f, 1f)
    val blend = if (s.usesMultiply) BlendMode.Multiply else BlendMode.SrcOver

    if (s.isClosedShape && s.fill != FillStyle.NONE) {
        drawPath(
            body(),
            s.fillColor.toComposeColor(),
            alpha = if (s.fill == FillStyle.TINTED) 0.25f else alpha,
            blendMode = blend
        )
    }

    if (s.usesOutlineRender) {
        drawPath(edge(), colour, alpha = alpha, blendMode = blend)
        return
    }

    val effect = s.dash.pattern?.let { PathEffect.dashPathEffect(it, 0f) }
    drawPath(
        body(),
        colour,
        alpha = alpha,
        style = DrawStroke(
            width = max(0.3f, s.baseWidth),
            cap = if (s.brush.flatTip) StrokeCap.Butt else StrokeCap.Round,
            join = if (s.brush.flatTip) StrokeJoin.Miter else StrokeJoin.Round,
            pathEffect = effect
        ),
        blendMode = blend
    )
}
