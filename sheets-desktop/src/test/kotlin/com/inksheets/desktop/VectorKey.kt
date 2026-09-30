package com.inksheets.desktop

import org.apache.pdfbox.Loader
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.graphics.image.PDImage
import java.awt.geom.Point2D
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The strokes and filled shapes a notation program drew on a page - stems as thin upright lines
 * or bars, beams as filled four-sided slabs - in pixels at the page's drawing size. With
 * [AnswerKey]'s noteheads, how many beams each note has: its value, as printed.
 */
object VectorKey {
    /** A stem: upright, from [y0] to [y1] at [x]. */
    data class Stem(val x: Float, val y0: Float, val y1: Float)
    /** A beam: a filled slab across [x0]..[x1], its middle at [y0] and [y1] at each end, [thick] thick. */
    data class Beam(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val thick: Float) {
        fun yAt(x: Float) = if (x1 == x0) y0 else y0 + (y1 - y0) * (x - x0) / (x1 - x0)
    }

    class Page(val stems: List<Stem>, val beams: List<Beam>)

    fun read(file: File, index: Int, dpi: Float): Page? = runCatching {
        Loader.loadPDF(file).use { doc ->
            val page = doc.getPage(index)
            val h = page.mediaBox.height
            val k = dpi / 72f
            val stems = ArrayList<Stem>(); val beams = ArrayList<Beam>()
            val engine = object : PDFGraphicsStreamEngine(page) {
                val path = ArrayList<ArrayList<Point2D.Float>>()
                var at = Point2D.Float()
                fun pt(x: Float, y: Float) = Point2D.Float(x * k, (h - y) * k)
                override fun appendRectangle(p0: Point2D, p1: Point2D, p2: Point2D, p3: Point2D) {
                    path += arrayListOf(pt(p0.x.toFloat(), p0.y.toFloat()), pt(p1.x.toFloat(), p1.y.toFloat()), pt(p2.x.toFloat(), p2.y.toFloat()), pt(p3.x.toFloat(), p3.y.toFloat()))
                }
                override fun moveTo(x: Float, y: Float) { path += arrayListOf(pt(x, y)); at = Point2D.Float(x, y) }
                override fun lineTo(x: Float, y: Float) { (path.lastOrNull() ?: arrayListOf<Point2D.Float>().also { path += it }) += pt(x, y); at = Point2D.Float(x, y) }
                override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) { (path.lastOrNull() ?: arrayListOf<Point2D.Float>().also { path += it }) += pt(x3, y3); at = Point2D.Float(x3, y3) }
                override fun getCurrentPoint(): Point2D = at
                override fun closePath() {}
                override fun endPath() { path.clear() }
                override fun clip(windingRule: Int) {}
                override fun drawImage(pdImage: PDImage) {}
                override fun shadingFill(shadingName: COSName) {}
                override fun strokePath() {
                    // A thin upright line: a stem (or a barline, told apart later by its length).
                    val w = graphicsState.lineWidth * k * graphicsState.currentTransformationMatrix.scalingFactorX
                    for (seg in path) for (i in 1 until seg.size) {
                        val a = seg[i - 1]; val b = seg[i]
                        if (abs(a.x - b.x) < 1.5f && abs(a.y - b.y) > 8f) stems += Stem((a.x + b.x) / 2, min(a.y, b.y), max(a.y, b.y))
                    }
                    if (w < 0f) Unit
                    path.clear()
                }
                override fun fillPath(windingRule: Int) {
                    for (seg in path) {
                        if (seg.size !in 4..5) continue
                        val xs = seg.map { it.x }; val ys = seg.map { it.y }
                        val wx = xs.max() - xs.min(); val hy = ys.max() - ys.min()
                        when {
                            // A thin filled bar standing up: a stem drawn as a shape.
                            wx < 4f && hy > 8f -> stems += Stem((xs.max() + xs.min()) / 2, ys.min(), ys.max())
                            // A slab wider than tall: a beam - its ends' middles and its thickness.
                            wx > 8f -> {
                                val left = seg.filter { abs(it.x - xs.min()) < 1.5f }; val right = seg.filter { abs(it.x - xs.max()) < 1.5f }
                                if (left.size >= 2 && right.size >= 2) {
                                    val t = (left.maxOf { it.y } - left.minOf { it.y })
                                    if (t in 2f..30f) beams += Beam(xs.min(), left.map { it.y }.average().toFloat(), xs.max(), right.map { it.y }.average().toFloat(), t)
                                }
                            }
                        }
                    }
                    path.clear()
                }
                override fun fillAndStrokePath(windingRule: Int) = fillPath(windingRule)
            }
            engine.processPage(page)
            Page(stems, beams)
        }
    }.getOrNull()

    /**
     * The value of the printed head at ([x], [y]) (its left edge and middle), [w] wide: 4 for a
     * stemmed note with no beams, 8, 16, 32 by its beams; null when no stem is found beside it (a
     * whole note, or something drawn another way). [sp] is a staff space in pixels.
     */
    fun valueOf(p: Page, x: Float, y: Float, w: Float, sp: Float, flags: List<AnswerKey.Symbol> = emptyList()): Int? {
        // Its stem: upright, beside the head (its right edge going up, its left going down), one end at the head.
        val stem = p.stems.filter { s -> (abs(s.x - (x + w)) < sp * 0.35f || abs(s.x - x) < sp * 0.35f) &&
            (abs(s.y1 - y) < sp * 0.8f || abs(s.y0 - y) < sp * 0.8f) && s.y1 - s.y0 in sp * 2f..sp * 9f }
            .minByOrNull { s -> min(abs(s.y1 - y), abs(s.y0 - y)) } ?: return null
        // Its far end, away from the head; beams crossing the stem near there.
        val up = abs(stem.y1 - y) < abs(stem.y0 - y)
        val end = if (up) stem.y0 else stem.y1
        val n = p.beams.count { b -> stem.x in b.x0 - 1f..b.x1 + 1f && abs(b.yAt(stem.x) - end) < sp * 2.2f && (if (up) b.yAt(stem.x) <= end + sp * 2.2f else b.yAt(stem.x) >= end - sp * 2.2f) }
        if (n == 0) {
            // No beam: a flag at the stem's end, if any.
            flags.firstOrNull { f -> abs(f.x - stem.x) < sp * 0.8f && abs(f.y - end) < sp * 3.5f }?.let { f ->
                return when (f.kind) { AnswerKey.Kind.FLAG_8 -> 8; AnswerKey.Kind.FLAG_16 -> 16; else -> 32 }
            }
        }
        return when (n) { 0 -> 4; 1 -> 8; 2 -> 16; else -> 32 }
    }
}
