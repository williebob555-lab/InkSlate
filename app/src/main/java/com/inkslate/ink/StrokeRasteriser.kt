package com.inkslate.ink

import com.inkslate.core.Stroke.Kind as StrokeKind
import android.graphics.BlendMode
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.util.LruCache
import kotlin.math.max
import kotlin.math.min

/**
 * Draws strokes onto an Android [Canvas] in page coordinates.
 *
 * The single renderer used by the live editor, thumbnails and image export. Keeping one
 * implementation is the point: three subtly different renderers is how "it looked different when
 * I exported it" bugs get made.
 */
object StrokeRasteriser {

    /**
     * Cached geometry per stroke.
     *
     * Building a stroke outline means offsetting the centreline and then a path union for the
     * caps, and the union in particular is expensive. Recomputing that for every stroke on every
     * frame is what made a page full of writing stutter: the cost grew with everything ever
     * drawn, not with what changed. Strokes are immutable, so the result can simply be kept.
     */
    private class Geometry(val path: Path, val bounds: RectF)

    private val geometryCache = object : LruCache<String, Geometry>(2400) {}

    /** Identity plus everything that changes the geometry. */
    private fun keyOf(s: Stroke): String {
        // Position is part of the key: dragging a selection rewrites strokes in place, and two
        // frames landing in the same millisecond would otherwise share a cache entry.
        val first = s.points.firstOrNull()
        val last = s.points.lastOrNull()
        return "${s.id}|${s.updatedUtc}|${s.points.size}|${s.baseWidth}|${s.brush.ordinal}|" +
            "${s.rotation}|${s.textSize}|${s.boxWidth}|" +
            "${first?.x}:${first?.y}|${last?.x}:${last?.y}"
    }

    private fun geometryOf(s: Stroke): Geometry {
        val key = keyOf(s)
        geometryCache.get(key)?.let { RenderStats.cacheHits++; return it }
        RenderStats.cacheMisses++
        val path = if (s.usesOutlineRender) s.toOutlinePath() else s.toPath()
        val g = Geometry(path, s.bounds())
        geometryCache.put(key, g)
        return g
    }

    /** Cached bounds, for viewport culling and hit tests on the drawing hot path. */
    fun boundsOf(s: Stroke): RectF = geometryOf(s).bounds

    fun clearCache() = geometryCache.evictAll()


    // Widths clamp to 0.05pt rather than 0.3pt. A genuine hairline is a width the size ladder
    // can now reach, and clamping it back up to 0.3 made the bottom third of that ladder draw
    // exactly the same line.
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /**
     * Colour treatment applied to ink as well as paper.
     *
     * Set by the drawing surface before a frame. Inverting only the page would leave black
     * handwriting invisible on a black background, so the ink goes through the same filter.
     */
    var colorFilter: android.graphics.ColorFilter? = null

    /**
     * Resolves an image id to a bitmap. Supplied by the host so this stays free of any file or
     * document knowledge, and so thumbnails and export can plug in their own lookup.
     */
    var imageResolver: ((String) -> android.graphics.Bitmap?)? = null

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Highlighter first, so ink stays legible on top of it. */
    fun drawAll(canvas: Canvas, strokes: List<Stroke>) {
        for (s in strokes) if (s.isHighlighter) draw(canvas, s)
        for (s in strokes) if (!s.isHighlighter) draw(canvas, s)
    }

    /** Draw a stroke that is still being made. Bypasses the cache, which it would only churn. */
    @Synchronized
    fun drawLive(canvas: Canvas, s: Stroke) {
        val rot = s.rotationMatrix()
        if (rot != null) { canvas.save(); canvas.concat(rot) }
        applyStrokePaint(s)
        if (s.usesOutlineRender) {
            strokePaint.style = Paint.Style.FILL
            if (s.brush.edgeSoftness > 0f) {
                strokePaint.maskFilter =
                    BlurMaskFilter(s.brush.edgeSoftness, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawPath(s.toOutlinePath(), strokePaint)
            strokePaint.maskFilter = null
        } else {
            strokePaint.strokeWidth = max(0.05f, s.baseWidth)
            canvas.drawPath(s.toPath(), strokePaint)
        }
        if (rot != null) canvas.restore()
    }

    @Synchronized
    fun draw(canvas: Canvas, s: Stroke) {
        val rot = s.rotationMatrix()
        if (rot != null) { canvas.save(); canvas.concat(rot) }
        when (s.kind) {
            StrokeKind.TEXT -> drawText(canvas, s)
            StrokeKind.TABLE -> drawTable(canvas, s)
            StrokeKind.IMAGE -> drawImage(canvas, s)
            else -> drawGeometry(canvas, s)
        }
        if (rot != null) canvas.restore()
    }

    private fun alphaOf(s: Stroke) = (s.effectiveAlpha * 255).toInt().coerceIn(0, 255)

    // ---- text ----------------------------------------------------------------

    fun typefaceFor(s: Stroke): Typeface {
        val base = when (s.font) {
            TextFont.SANS -> Typeface.SANS_SERIF
            TextFont.SERIF -> Typeface.SERIF
            TextFont.MONO -> Typeface.MONOSPACE
            TextFont.CASUAL -> Typeface.create("casual", Typeface.NORMAL)
        }
        val style = when {
            s.bold && s.italic -> Typeface.BOLD_ITALIC
            s.bold -> Typeface.BOLD
            s.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return Typeface.create(base, style)
    }

    /** Configure [textPaint] for this stroke and hand it back, so measuring matches drawing. */
    private fun preparedTextPaint(s: Stroke): Paint {
        textPaint.reset()
        textPaint.isAntiAlias = true
        textPaint.color = s.color
        textPaint.alpha = alphaOf(s)
        textPaint.textSize = s.textSize
        textPaint.typeface = typefaceFor(s)
        textPaint.colorFilter = colorFilter
        return textPaint
    }

    private fun drawText(canvas: Canvas, s: Stroke) {
        val paint = preparedTextPaint(s)
        val lines = s.wrapLines { paint.measureText(it) }
        val box = s.rawBounds()

        // box background and border, drawn first so text sits on top
        if (s.boxFillColor != Color.TRANSPARENT) {
            fillPaint.reset(); fillPaint.isAntiAlias = true
            fillPaint.style = Paint.Style.FILL
            fillPaint.color = s.boxFillColor
            canvas.drawRoundRect(RectF(box), 3f, 3f, fillPaint)
        }
        if (s.boxBorder) {
            strokePaint.reset(); strokePaint.isAntiAlias = true
            strokePaint.style = Paint.Style.STROKE
            strokePaint.color = s.color
            strokePaint.alpha = alphaOf(s)
            strokePaint.strokeWidth = max(0.4f, s.baseWidth)
            s.dash.pattern?.let { strokePaint.pathEffect = DashPathEffect(it, 0f) }
            canvas.drawRoundRect(RectF(box), 3f, 3f, strokePaint)
        }

        val contentWidth = box.width() - s.padding * 2f
        val fm = paint.fontMetrics
        // first baseline sits one ascent below the top padding
        var y = box.top + s.padding - fm.ascent
        for (line in lines) {
            val w = paint.measureText(line)
            canvas.drawText(line, box.left + s.padding + s.lineOffsetX(w, contentWidth), y, paint)
            y += s.textSize * s.lineSpacing
            if (s.boxHeight > 0f && y > box.bottom) break   // clip overflow to the fixed box
        }
    }

    private fun drawImage(canvas: Canvas, s: Stroke) {
        val rect = s.rectOf()
        val bmp = s.imageId?.let { imageResolver?.invoke(it) }
        imagePaint.colorFilter = colorFilter
        imagePaint.alpha = (s.opacity * 255).toInt().coerceIn(0, 255)
        if (bmp != null && !bmp.isRecycled) {
            // A null source rect means the whole picture, which is what an uncropped image wants
            // and avoids allocating a Rect on every frame for the common case.
            val crop = s.cropPixels(bmp.width, bmp.height)
            val src = crop?.let { android.graphics.Rect(it[0], it[1], it[2], it[3]) }
            canvas.drawBitmap(bmp, src, rect, imagePaint)
        } else {
            // A missing asset should look deliberately absent rather than invisible, so it is
            // obvious that something failed to sync rather than that nothing was ever there.
            fillPaint.reset(); fillPaint.isAntiAlias = true
            fillPaint.color = Color.argb(30, 128, 128, 128)
            canvas.drawRect(rect, fillPaint)
            strokePaint.reset(); strokePaint.isAntiAlias = true
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = 1f
            strokePaint.pathEffect = DashPathEffect(floatArrayOf(6f, 5f), 0f)
            strokePaint.color = Color.argb(120, 128, 128, 128)
            canvas.drawRect(rect, strokePaint)
        }
    }

    // ---- tables --------------------------------------------------------------

    private fun drawTable(canvas: Canvas, s: Stroke) {
        if (s.fill != FillStyle.NONE) {
            fillPaint.reset(); fillPaint.isAntiAlias = true
            fillPaint.style = Paint.Style.FILL
            fillPaint.color = s.fillColor
            fillPaint.alpha = if (s.fill == FillStyle.TINTED) 60 else 255
            canvas.drawRect(s.rectOf(), fillPaint)
        }
        applyStrokePaint(s)
        strokePaint.strokeWidth = max(0.05f, s.baseWidth)
        canvas.drawPath(s.toPath(), strokePaint)

        if (s.cells.isNotEmpty() && s.rows > 0 && s.cols > 0) {
            val paint = preparedTextPaint(s)
            paint.textSize = min(s.textSize, s.rectOf().height() / s.rows * 0.55f)
            for (r in 0 until s.rows) for (c in 0 until s.cols) {
                val txt = s.cells.getOrNull(r * s.cols + c).orEmpty()
                if (txt.isBlank()) continue
                val cell = s.cellRect(r, c)
                canvas.drawText(txt, cell.left + 3f, cell.centerY() + paint.textSize * 0.34f, paint)
            }
        }
    }

    // ---- ink and shapes ------------------------------------------------------

    private fun drawGeometry(canvas: Canvas, s: Stroke) {
        if (s.isClosedShape && s.fill != FillStyle.NONE) {
            fillPaint.reset(); fillPaint.isAntiAlias = true
            fillPaint.style = Paint.Style.FILL
            fillPaint.color = s.fillColor
            fillPaint.colorFilter = colorFilter
            fillPaint.alpha = if (s.fill == FillStyle.TINTED) 64 else alphaOf(s)
            canvas.drawPath(s.toPath(), fillPaint)
        }

        applyStrokePaint(s)

        if (s.usesOutlineRender) {
            // one filled ribbon, not a stack of segments: this is the whole difference between
            // ink that looks smooth and ink that looks faceted
            strokePaint.style = Paint.Style.FILL
            // Graphite has a diffuse edge; ink does not. Softening the outline is most of what
            // makes the pencil read as a pencil rather than as a grey pen.
            if (s.brush.edgeSoftness > 0f) {
                strokePaint.maskFilter = BlurMaskFilter(
                    s.brush.edgeSoftness, BlurMaskFilter.Blur.NORMAL
                )
            }
            canvas.drawPath(geometryOf(s).path, strokePaint)
            strokePaint.maskFilter = null
            strokePaint.style = Paint.Style.STROKE
        } else {
            strokePaint.strokeWidth = max(0.05f, s.baseWidth)
            canvas.drawPath(geometryOf(s).path, strokePaint)
        }
    }

    private fun applyStrokePaint(s: Stroke) {
        strokePaint.reset()
        strokePaint.isAntiAlias = true
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeJoin = Paint.Join.ROUND
        strokePaint.strokeCap = if (s.brush.flatTip) Paint.Cap.BUTT else Paint.Cap.ROUND
        strokePaint.strokeJoin = if (s.brush.flatTip) Paint.Join.MITER else Paint.Join.ROUND
        strokePaint.color = s.color
        strokePaint.alpha = alphaOf(s)
        strokePaint.pathEffect = s.dash.pattern?.let { DashPathEffect(it, 0f) }
        strokePaint.colorFilter = colorFilter
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            strokePaint.blendMode = if (s.usesMultiply) BlendMode.MULTIPLY else BlendMode.SRC_OVER
        }
    }
}
