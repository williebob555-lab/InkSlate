package com.inkslate.core

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The document model, shared verbatim by every platform.
 *
 * Nothing here touches a graphics framework. That is deliberate: the moment the model lives
 * inside one platform's UI code, the other platform needs its own copy, and a field added on one
 * side is silently dropped by the other on the next save. Since these files sync between devices,
 * that would be quiet data loss rather than a visible bug.
 *
 * Rendering, hit testing against real paths, and input handling stay platform-specific.
 */

/** A rectangle, in page coordinates. Deliberately not any platform's rect type. */
data class Box(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val isEmpty: Boolean get() = width <= 0f || height <= 0f

    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom

    fun expanded(by: Float) = Box(left - by, top - by, right + by, bottom + by)

    fun union(other: Box) = Box(
        min(left, other.left), min(top, other.top),
        max(right, other.right), max(bottom, other.bottom)
    )

    fun intersects(other: Box) =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    fun offset(dx: Float, dy: Float) = Box(left + dx, top + dy, right + dx, bottom + dy)

    companion object {
        fun of(x0: Float, y0: Float, x1: Float, y1: Float) =
            Box(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))
    }
}

/**
 * Brush character.
 *
 * Width is a range around the nominal stroke width: [minFactor] at no pressure, [maxFactor] at
 * full pressure. Wide ranges are what make pressure visible; a narrow one reads as "pressure does
 * not work" even when the data is arriving.
 */
@Serializable
enum class BrushType(
    val label: String,
    val minFactor: Float,
    val maxFactor: Float,
    /** Gamma on the pressure curve. Below 1 gives more sensitivity in the light range. */
    val pressureGamma: Float,
    /** How strongly speed thins the line when no pressure data exists. 0 disables it. */
    val velocityResponse: Float,
    val defaultAlpha: Float,
    val multiply: Boolean,
    val defaultWidth: Float,
    /**
     * Broad-nib angle in degrees, or -1 for a round nib.
     *
     * A brush with a nib angle is not drawn by offsetting the centreline: the nib itself - a
     * short straight edge held at this angle - is swept along the path. That is what a real
     * broad-nib pen does, and it is the only model that produces the hairline you get when the
     * stroke runs parallel to the nib.
     */
    val nibAngleDeg: Float = -1f,
    /** Edge softness in page points; graphite has a diffuse edge, ink does not. */
    val edgeSoftness: Float = 0f,
    /** Square ends and joins, for flat-tipped tools. */
    val flatTip: Boolean = false,
    /**
     * Thickness of a broad nib as a fraction of its length.
     *
     * Zero would make a stroke running exactly along the nib disappear. A real nib has a body,
     * and that body is what the hairline is made of.
     */
    val nibCore: Float = 0.12f,
    /**
     * Directional width bias for a *round* nib, 0 (none) to 1 (full).
     *
     * A brush or a flexible nib loaded at an angle is not symmetric: strokes pulled across it
     * are broader than strokes pulled along it. This is most of what separates ink that looks
     * handwritten from ink that looks like a plotter drew it.
     */
    val dirBias: Float = 0f,
    /** The direction the bias favours, in degrees. */
    val dirAngleDeg: Float = 40f,
    /** Fraction of the stroke tapered at the start, where the nib is landing. */
    val taperIn: Float = 0f,
    /** Fraction of the stroke tapered at the end, where it is lifting away. */
    val taperOut: Float = 0f,
    /**
     * How much speed thins the line even when pressure data *is* available, 0 to 1.
     *
     * Pressure alone gives even, deliberate ink. Real handwriting is fast down the long strokes
     * and slow through the turns, and letting speed thin the line is what puts that back.
     */
    val speedTaper: Float = 0f
) {
    BALLPOINT("Ballpoint", 0.72f, 1.25f, 0.75f, 0.25f, 1f, false, 2.0f),
    GEL("Gel pen", 0.88f, 1.15f, 0.80f, 0.12f, 1f, false, 2.6f),
    FOUNTAIN(
        "Fountain", 0.12f, 2.30f, 0.50f, 0.90f, 1f, false, 3.0f,
        dirBias = 0.38f, dirAngleDeg = 40f,
        taperIn = 0.04f, taperOut = 0.14f, speedTaper = 0.35f
    ),
    PENCIL("Pencil", 0.55f, 1.25f, 0.70f, 0.30f, 0.72f, true, 2.2f, edgeSoftness = 0.6f),
    MARKER("Marker", 1f, 1f, 1f, 0f, 0.92f, true, 7.0f, flatTip = true),
    BRUSH(
        "Brush", 0.05f, 3.40f, 0.45f, 1.0f, 1f, false, 5.0f,
        dirBias = 0.30f, taperIn = 0.10f, taperOut = 0.24f, speedTaper = 0.55f
    ),
    /**
     * The most expressive of the lot: an enormous width range, a strong directional bias, and
     * speed and taper turned right up. Deliberately over the top, because the point of it is to
     * be obviously more alive than the pens.
     */
    INK_BRUSH(
        "Ink brush", 0.02f, 4.40f, 0.38f, 1.20f, 1f, false, 6.0f,
        dirBias = 0.45f, dirAngleDeg = 55f,
        taperIn = 0.14f, taperOut = 0.32f, speedTaper = 0.80f
    ),
    HIGHLIGHTER("Highlighter", 1f, 1f, 1f, 0f, 0.38f, true, 15f, flatTip = true),
    CALLIGRAPHY(
        "Calligraphy", 0.45f, 1.70f, 0.85f, 0.30f, 1f, false, 6.0f,
        nibAngleDeg = 40f, flatTip = true, nibCore = 0.09f,
        taperIn = 0.03f, taperOut = 0.08f
    );

    val isHighlighter: Boolean get() = this == HIGHLIGHTER
    val isChisel: Boolean get() = nibAngleDeg >= 0f
    val isVariableWidth: Boolean get() = maxFactor - minFactor > 0.15f || isChisel || dirBias > 0f

    fun widthFor(base: Float, pressure: Float): Float =
        widthFor(base, pressure, dynamics = 1f)

    /**
     * Width at [pressure], with the brush's own range stretched by [dynamics].
     *
     * 1 is the brush as designed. Above that the light end gets lighter and the heavy end
     * heavier around an unchanged nominal width, which is what the expressiveness slider does -
     * the brushes ship with ranges honest about the tools they imitate, and honest turned out to
     * look timid.
     */
    fun widthFor(base: Float, pressure: Float, dynamics: Float): Float {
        val lo = minFactorAt(dynamics)
        val hi = maxFactorAt(dynamics)
        if (hi - lo <= 0.001f) return base
        val p = pressure.coerceIn(0f, 1f)
        val shaped = Math.pow(p.toDouble(), pressureGamma.toDouble()).toFloat()
        return base * (lo + (hi - lo) * shaped)
    }

    /**
     * Half-width multiplier for a round nib heading in [dirRadians].
     *
     * Chisel brushes return 1: their thick-and-thin comes out of the swept nib itself rather
     * than out of a width curve, and applying both would count it twice.
     */
    fun directionFactor(dirRadians: Float): Float {
        if (dirBias <= 0f || isChisel) return 1f
        val axis = Math.toRadians(dirAngleDeg.toDouble())
        val s = abs(sin(dirRadians.toDouble() - axis)).toFloat()
        return (1f - dirBias) + dirBias * s
    }

    /**
     * Widen the brush's own range by [dynamics], the user's expressiveness setting.
     *
     * 1 is the brush as designed; above that the light end gets lighter and the heavy end
     * heavier, around an unchanged nominal width.
     */
    fun minFactorAt(dynamics: Float): Float = (1f + (minFactor - 1f) * dynamics).coerceAtLeast(0.01f)
    fun maxFactorAt(dynamics: Float): Float = (1f + (maxFactor - 1f) * dynamics).coerceAtLeast(0.02f)
}

@Serializable
enum class DashStyle(val label: String, val pattern: FloatArray?) {
    SOLID("Solid", null),
    DASHED("Dashed", floatArrayOf(9f, 6f)),
    DOTTED("Dotted", floatArrayOf(1f, 5f)),
    DASH_DOT("Dash-dot", floatArrayOf(10f, 4f, 1.5f, 4f))
}

@Serializable
enum class FillStyle(val label: String) { NONE("Outline"), SOLID("Filled"), TINTED("Tinted") }

@Serializable
enum class TextFont(val label: String) {
    SANS("Sans"), SERIF("Serif"), MONO("Mono"), CASUAL("Casual")
}

@Serializable
enum class TextAlign(val label: String) { LEFT("Left"), CENTER("Centre"), RIGHT("Right") }

/** A sampled input point in page space, y increasing downward. */
@Serializable
data class InkPoint(val x: Float, val y: Float, val width: Float)

/**
 * One drawable object.
 *
 * Freehand strokes carry many points; shapes, tables and images carry two defining corners; text
 * carries one anchor at its top-left. Everything is a [Stroke] so selection, transform, z-order,
 * undo and export all have a single code path.
 */
@Serializable
data class Stroke(
    val id: String,
    val kind: Kind,
    val color: Int,
    val baseWidth: Float,
    val points: List<InkPoint>,
    val brush: BrushType = BrushType.BALLPOINT,
    val dash: DashStyle = DashStyle.SOLID,
    val fill: FillStyle = FillStyle.NONE,
    val fillColor: Int = 0,
    val opacity: Float = 1f,
    val rotation: Float = 0f,
    val text: String? = null,
    val textSize: Float = 14f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val font: TextFont = TextFont.SANS,
    val align: TextAlign = TextAlign.LEFT,
    val boxWidth: Float = 0f,
    val boxHeight: Float = 0f,
    val boxFillColor: Int = 0,
    val boxBorder: Boolean = false,
    val lineSpacing: Float = 1.25f,
    val padding: Float = 3f,
    val rows: Int = 0,
    val cols: Int = 0,
    val cells: List<String> = emptyList(),
    val imageId: String? = null,
    /**
     * Which part of the source picture an [Kind.IMAGE] stroke shows, as fractions of the whole:
     * 0,0,1,1 is the entire picture and is what every image starts as.
     *
     * Cropping is stored rather than applied to the stored bytes on purpose. The picture is
     * shared between devices and referenced by [imageId], so trimming the file would change what
     * every other copy of it shows; and keeping the original means a crop can be widened again
     * later, or undone, without the thrown-away edges being gone for good.
     */
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
    val pageIndex: Int = 0,
    /** Wall-clock millis of the last edit. Last-writer-wins key when two devices merge. */
    val updatedUtc: Long = 0L
) {
    @Serializable
    enum class Kind { FREEHAND, LINE, ARROW, RECT, ELLIPSE, TEXT, TABLE, IMAGE }

    /** True when this image shows less than the whole of its source picture. */
    val isCropped: Boolean
        get() = kind == Kind.IMAGE &&
            (cropLeft > 0.0001f || cropTop > 0.0001f ||
                cropRight < 0.9999f || cropBottom < 0.9999f)

    /**
     * The crop as pixel bounds inside a source picture of [width] by [height], clamped so a
     * malformed or inverted crop can never ask the renderer for a region outside the bitmap.
     * Returns null when the whole picture is wanted, which is the common case.
     */
    fun cropPixels(width: Int, height: Int): IntArray? {
        if (!isCropped || width <= 0 || height <= 0) return null
        val l = (cropLeft.coerceIn(0f, 1f) * width).toInt()
        val t = (cropTop.coerceIn(0f, 1f) * height).toInt()
        val r = (cropRight.coerceIn(0f, 1f) * width).toInt()
        val b = (cropBottom.coerceIn(0f, 1f) * height).toInt()
        // A zero-width or inverted region would render as nothing at all, which looks like the
        // picture was lost rather than cropped.
        if (r - l < 1 || b - t < 1) return null
        return intArrayOf(l, t, r, b)
    }

    val isHighlighter: Boolean get() = kind == Kind.FREEHAND && brush.isHighlighter
    val isFreehand: Boolean get() = kind == Kind.FREEHAND
    val isClosedShape: Boolean get() = kind == Kind.RECT || kind == Kind.ELLIPSE
    val usesMultiply: Boolean get() = isFreehand && brush.multiply

    val effectiveAlpha: Float
        get() = opacity * (if (isFreehand) brush.defaultAlpha else 1f) *
            (((color ushr 24) and 0xFF) / 255f)

    /** True when this stroke should be rendered as a filled variable-width outline. */
    val usesOutlineRender: Boolean
        get() = kind == Kind.FREEHAND && brush.isVariableWidth &&
            dash == DashStyle.SOLID && points.size > 1

    /** Two-corner rectangle, for shapes, tables and images. */
    fun rectBox(): Box {
        if (points.isEmpty()) return Box(0f, 0f, 0f, 0f)
        val a = points.first()
        val b = points.last()
        return Box.of(a.x, a.y, b.x, b.y)
    }

    /** Axis-aligned bounds before rotation. */
    fun rawBoundsBox(): Box {
        if (points.isEmpty()) return Box(0f, 0f, 0f, 0f)
        if (kind == Kind.TEXT) {
            val p = points[0]
            val lines = wrapLines { estimateWidth(it) }
            val w = if (boxWidth > 0f) boxWidth
            else max(8f, lines.maxOfOrNull { estimateWidth(it) } ?: 0f) + padding * 2f
            val h = if (boxHeight > 0f) boxHeight
            else max(1, lines.size) * textSize * lineSpacing + padding * 2f
            return Box(p.x, p.y, p.x + w, p.y + h)
        }
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var maxW = baseWidth
        for (p in points) {
            minX = min(minX, p.x); minY = min(minY, p.y)
            maxX = max(maxX, p.x); maxY = max(maxY, p.y)
            maxW = max(maxW, p.width)
        }
        // A little more than half the width: a broad nib's corners reach slightly past the
        // nominal half-width, and bounds that clip a stroke short make it vanish at the edge of
        // the viewport rather than merely look wrong.
        val pad = if (kind == Kind.TABLE || kind == Kind.IMAGE) 0f else maxW * 0.6f + 1f
        return Box(minX - pad, minY - pad, maxX + pad, maxY + pad)
    }

    /** Bounds including rotation. */
    fun boundsBox(): Box {
        val r = rawBoundsBox()
        if (rotation == 0f) return r
        val rad = Math.toRadians(rotation.toDouble())
        val cs = cos(rad).toFloat()
        val sn = sin(rad).toFloat()
        val cx = r.centerX
        val cy = r.centerY
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for ((px, py) in listOf(
            r.left to r.top, r.right to r.top, r.right to r.bottom, r.left to r.bottom
        )) {
            val dx = px - cx
            val dy = py - cy
            val x = cx + dx * cs - dy * sn
            val y = cy + dx * sn + dy * cs
            minX = min(minX, x); minY = min(minY, y)
            maxX = max(maxX, x); maxY = max(maxY, y)
        }
        return Box(minX, minY, maxX, maxY)
    }

    fun cellBox(row: Int, col: Int): Box {
        val r = rectBox()
        val cw = r.width / max(1, cols)
        val rh = r.height / max(1, rows)
        return Box(r.left + cw * col, r.top + rh * row, r.left + cw * (col + 1), r.top + rh * (row + 1))
    }

    /**
     * Split [text] into display lines, honouring newlines and wrapping to [boxWidth].
     * [measure] is supplied by the caller so line breaks match whichever font is drawing.
     */
    fun wrapLines(measure: (String) -> Float): List<String> {
        val raw = text ?: return emptyList()
        val hard = raw.lines()
        if (boxWidth <= 0f) return hard

        val limit = (boxWidth - padding * 2f).coerceAtLeast(1f)
        val out = ArrayList<String>()
        for (paragraph in hard) {
            if (paragraph.isEmpty()) { out.add(""); continue }
            var line = StringBuilder()
            for (word in paragraph.split(" ")) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (measure(candidate) <= limit || line.isEmpty()) {
                    if (line.isEmpty() && measure(word) > limit) {
                        var chunk = StringBuilder()
                        for (ch in word) {
                            if (measure("$chunk$ch") > limit && chunk.isNotEmpty()) {
                                out.add(chunk.toString()); chunk = StringBuilder()
                            }
                            chunk.append(ch)
                        }
                        line = chunk
                    } else {
                        line = StringBuilder(candidate)
                    }
                } else {
                    out.add(line.toString()); line = StringBuilder(word)
                }
            }
            out.add(line.toString())
        }
        return out
    }

    private fun estimateWidth(s: String): Float = s.length * textSize * 0.55f

    fun lineOffsetX(lineWidth: Float, boxContentWidth: Float): Float = when (align) {
        TextAlign.LEFT -> 0f
        TextAlign.CENTER -> (boxContentWidth - lineWidth) / 2f
        TextAlign.RIGHT -> boxContentWidth - lineWidth
    }

    /** Distance-based hit test, used by erasers and tap-to-select. Pure geometry. */
    fun hitTest(px: Float, py: Float, radius: Float): Boolean {
        var qx = px
        var qy = py
        if (rotation != 0f) {
            val r = rawBoundsBox()
            val rad = Math.toRadians(-rotation.toDouble())
            val dx = px - r.centerX
            val dy = py - r.centerY
            qx = r.centerX + (dx * cos(rad) - dy * sin(rad)).toFloat()
            qy = r.centerY + (dx * sin(rad) + dy * cos(rad)).toFloat()
        }
        val b = rawBoundsBox().expanded(radius)
        if (!b.contains(qx, qy)) return false

        if (kind == Kind.TEXT || kind == Kind.TABLE || kind == Kind.IMAGE) return true
        if (isClosedShape && fill != FillStyle.NONE) return true

        val thresh = radius + baseWidth / 2f
        val pts = if (kind == Kind.FREEHAND) points else outlineSamples()
        if (pts.size == 1) return dist(qx, qy, pts[0].x, pts[0].y) <= thresh
        for (i in 1 until pts.size) {
            if (pointToSegment(qx, qy, pts[i - 1].x, pts[i - 1].y, pts[i].x, pts[i].y) <= thresh) {
                return true
            }
        }
        return false
    }

    fun insideBox(b: Box): Boolean {
        val own = boundsBox()
        return own.left >= b.left && own.top >= b.top && own.right <= b.right && own.bottom <= b.bottom
    }

    private fun outlineSamples(): List<InkPoint> {
        val a = points.first()
        val b = points.last()
        return when (kind) {
            Kind.LINE, Kind.ARROW -> listOf(a, b)
            Kind.RECT, Kind.TABLE, Kind.IMAGE -> {
                val r = rectBox()
                listOf(
                    InkPoint(r.left, r.top, 0f), InkPoint(r.right, r.top, 0f),
                    InkPoint(r.right, r.bottom, 0f), InkPoint(r.left, r.bottom, 0f),
                    InkPoint(r.left, r.top, 0f)
                )
            }
            Kind.ELLIPSE -> {
                val r = rectBox()
                val rx = r.width / 2f
                val ry = r.height / 2f
                (0..28).map {
                    val t = it / 28.0 * 2 * Math.PI
                    InkPoint(
                        r.centerX + (cos(t) * rx).toFloat(),
                        r.centerY + (sin(t) * ry).toFloat(), 0f
                    )
                }
            }
            else -> points
        }
    }

    private fun dist(x0: Float, y0: Float, x1: Float, y1: Float) = hypot(x0 - x1, y0 - y1)

    private fun pointToSegment(
        px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float
    ): Float {
        val vx = bx - ax
        val vy = by - ay
        val len2 = vx * vx + vy * vy
        if (len2 == 0f) return dist(px, py, ax, ay)
        val t = (((px - ax) * vx + (py - ay) * vy) / len2).coerceIn(0f, 1f)
        return dist(px, py, ax + t * vx, ay + t * vy)
    }

    companion object {
        /** Snap a shape to a clean aspect: squares, circles, and 15-degree lines. */
        fun snapShape(kind: Kind, ax: Float, ay: Float, bx: Float, by: Float): Pair<Float, Float> =
            when (kind) {
                Kind.RECT, Kind.ELLIPSE, Kind.TABLE, Kind.IMAGE -> {
                    val s = max(abs(bx - ax), abs(by - ay))
                    (if (bx >= ax) ax + s else ax - s) to (if (by >= ay) ay + s else ay - s)
                }
                Kind.LINE, Kind.ARROW -> {
                    val len = hypot(bx - ax, by - ay)
                    val ang = Math.atan2((by - ay).toDouble(), (bx - ax).toDouble())
                    val step = Math.toRadians(15.0)
                    val snapped = Math.round(ang / step) * step
                    (ax + (cos(snapped) * len).toFloat()) to (ay + (sin(snapped) * len).toFloat())
                }
                else -> bx to by
            }
    }
}
