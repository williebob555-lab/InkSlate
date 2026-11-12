package com.inkslate.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Build
import com.inkslate.core.Box
import com.inkslate.core.AnnotationLayers
import com.inkslate.core.Stroke
import com.inkslate.core.StrokeRegions
import java.util.concurrent.Executors
import java.util.IdentityHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

/** Display-only tiles. Editing always uses the complete original model and spatial index. */
internal class RenderedInk(private val deliver: (() -> Unit) -> Unit, private val redraw: () -> Unit) {
    internal data class Key(val page: Int, val x: Int, val y: Int, val level: Int)
    internal data class Layer(val bitmap: Bitmap, val multiply: Boolean)
    internal class Tile(val marks: List<Stroke>, val filter: ColorFilter?, val layers: List<Layer>) {
        val bytes = layers.sumOf { it.bitmap.allocationByteCount.toLong() }
        fun matches(other: List<Stroke>, treatment: ColorFilter?) = filter === treatment &&
            (marks === other || (marks.size == other.size && marks.indices.all { marks[it] === other[it] }))
    }
    private val tiles = LinkedHashMap<Key, Tile>(32, 0.75f, true)
    private val pending = HashMap<Key, List<Stroke>>()
    private val rejected = HashMap<Key, Pair<List<Stroke>, ColorFilter?>>()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "ink-tiles").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    // Tile geometry is deliberately not retained here. The bitmap cache is bounded by bytes;
    // keeping thousands of complex Paths as well would reintroduce the old unbounded-heap risk.
    private val renderer = StrokeRenderer(false)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val multiply = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
    private var bytes = 0L
    private var closed = false
    private var indexedRegions: StrokeRegions? = null
    private val layerCounts = IdentityHashMap<List<Stroke>, Int>()
    private val budget = (Runtime.getRuntime().maxMemory() / 24).coerceIn(4L shl 20, 24L shl 20)

    /** Recent resolution survives camera motion; sharper replacement is prepared off the UI thread. */
    fun draw(canvas: Canvas, page: Int, viewport: Box, scale: Float, regions: StrokeRegions,
             filter: ColorFilter?, focusX: Float, focusY: Float, moving: Boolean,
             visibleLayers: Int = AnnotationLayers.ALL): Boolean {
        if (closed || Build.VERSION.SDK_INT < 29 || !scale.isFinite() || scale <= 0f) return false
        if (indexedRegions !== regions || layerCounts.size > 256) {
            indexedRegions = regions; layerCounts.clear()
        }
        val x0 = floor(viewport.left / SIZE).toInt(); val x1 = floor(viewport.right / SIZE).toInt()
        val y0 = floor(viewport.top / SIZE).toInt(); val y1 = floor(viewport.bottom / SIZE).toInt()
        if ((x1.toLong() - x0 + 1) * (y1.toLong() - y0 + 1) !in 1..256) return false
        // A stationary zoomed view must remain on the tile path. Capping this at 1x caused the
        // caller to abandon tiles above 1.25x and synchronously rebuild thousands of outlines.
        // Admit sharper levels here; the byte-budget loop below steps back down when the visible
        // working set would be too large.
        var level = ceil(ln(scale.toDouble()) / ln(2.0)).toInt().coerceIn(-5, MAX_LEVEL)
        val positions = (x0..x1).flatMap { x -> (y0..y1).map { y -> Key(page, x, y, level) } }
            .sortedBy { val dx = (it.x + .5f) * SIZE - focusX; val dy = (it.y + .5f) * SIZE - focusY; dx * dx + dy * dy }
        val mask = AnnotationLayers.mixerMask(visibleLayers)
        val chunks = positions.map { position ->
            val candidates = regions.query(box(position).expanded(128f))
            val allVisible = mask == AnnotationLayers.ALL || candidates.all {
                AnnotationLayers.normalise(it.layerMask) and mask != 0
            }
            position to if (allVisible) candidates else candidates.filter {
                AnnotationLayers.normalise(it.layerMask) and mask != 0
            }
        }
        val layerCount = chunks.sumOf { (_, marks) -> layerCounts.getOrPut(marks) { blendCount(marks).coerceAtMost(8) } }
        // Fit the visible working set before admitting it. Otherwise a large viewport could
        // cycle through more bitmaps than the budget and reproduce the old cache-thrashing bug.
        while (level > -5 && layerCount * ((SIZE * 2.0.pow(level)).toLong() + BLEED * 2).let { it * it * 4 } > budget * .70) level--
        for ((position, marks) in chunks) {
            val key = position.copy(level = level)
            val box = box(key)
            if (marks.isEmpty()) continue
            // Images may acquire pixels without a model revision. Keep those tiles live.
            if (marks.any { it.kind == Stroke.Kind.IMAGE }) {
                drawVectors(canvas, box, marks); continue
            }
            val exact = tiles[key]?.takeIf { it.matches(marks, filter) }
            val retained = exact ?: tiles.entries.firstOrNull { (k, t) ->
                k.page == page && k.x == key.x && k.y == key.y && t.matches(marks, filter)
            }?.let { (k, _) -> tiles[k] }
            if (retained != null) {
                if (exact != null) RenderStats.tileExactHits++ else RenderStats.tileRetainedHits++
                canvas.save(); canvas.clipRect(box.left, box.top, box.right, box.bottom)
                for (layer in retained.layers) {
                    paint.xfermode = if (layer.multiply) multiply else null
                    canvas.drawBitmap(layer.bitmap, null, bitmapBox(key, retained.layers[0].bitmap.width), paint)
                }
                paint.xfermode = null; canvas.restore()
            } else if (marks.size <= DIRECT_FALLBACK_STROKES) {
                // A handful of marks is cheaper than waiting for a bitmap and cannot create a
                // noticeable frame stall. Dense tiles deliberately show the source page first;
                // repainting hundreds of paths on the interface thread defeats the cache.
                RenderStats.tileVectorFallbacks++
                drawVectors(canvas, box, marks)
            } else {
                RenderStats.tileColdMisses++
            }
            if (exact == null && (!moving || retained == null)) request(key, marks, filter)
        }
        return true
    }

    private fun drawVectors(canvas: Canvas, box: Box, marks: List<Stroke>) {
        canvas.save(); canvas.clipRect(box.left, box.top, box.right, box.bottom)
        StrokeRasteriser.drawAll(canvas, marks)
        canvas.restore()
    }

    private fun request(key: Key, marks: List<Stroke>, filter: ColorFilter?) {
        if (pending.containsKey(key) || pending.size >= 3) return
        rejected[key]?.let { (old, treatment) ->
            if (treatment === filter && old.size == marks.size && old.indices.all { old[it] === marks[it] }) return
        }
        pending[key] = marks
        worker.execute {
            val result = try { render(key, marks, filter, renderer) } catch (_: OutOfMemoryError) { null } catch (_: RuntimeException) { null }
            deliver {
                pending.remove(key)
                if (!closed && result != null && result.bytes <= budget) {
                    RenderStats.tileBuilds++
                    tiles.remove(key)?.let { bytes -= it.bytes }
                    tiles[key] = result; bytes += result.bytes
                    val iter = tiles.entries.iterator()
                    while (bytes > budget && iter.hasNext()) { bytes -= iter.next().value.bytes; iter.remove() }
                    // Let GC retire images after the render thread has finished using them.
                } else if (!closed) {
                    RenderStats.tileRejects++
                    if (rejected.size >= 256) rejected.clear()
                    rejected[key] = marks to filter
                }
                if (!closed) redraw()
            }
        }
    }

    fun close() {
        closed = true
        tiles.clear()
        rejected.clear()
        layerCounts.clear()
        indexedRegions = null
        renderer.clearCache()
        bytes = 0
        worker.shutdownNow()
    }

    companion object {
        internal const val SIZE = 512f
        internal const val MAX_LEVEL = 2
        private const val BLEED = 3
        private const val DIRECT_FALLBACK_STROKES = 8
        internal fun box(key: Key) = Box(key.x * SIZE, key.y * SIZE, (key.x + 1) * SIZE, (key.y + 1) * SIZE)
        private fun bitmapBox(key: Key, pixels: Int): RectF {
            val scale = (pixels - BLEED * 2) / SIZE
            val b = box(key); val pad = BLEED / scale
            return RectF(b.left - pad, b.top - pad, b.right + pad, b.bottom + pad)
        }
        /** Adjacent tiles rasterize full strokes with a gutter, then clip only when displayed. */
        internal fun render(key: Key, marks: List<Stroke>, filter: ColorFilter?, renderer: StrokeRenderer): Tile? {
            val runs = blendRuns(marks)
            // Pathological alternating blend modes remain vectors rather than allocating unbounded layers.
            if (runs.size > 8) return null
            val scale = 2.0.pow(key.level).toFloat()
            val pixels = (SIZE * scale).toInt() + BLEED * 2
            val b = box(key)
            renderer.colorFilter = filter
            val layers = runs.map { (mode, strokes) ->
                val bitmap = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888)
                val c = Canvas(bitmap)
                if (mode) c.drawColor(Color.WHITE)
                c.translate(BLEED.toFloat(), BLEED.toFloat()); c.scale(scale, scale); c.translate(-b.left, -b.top)
                strokes.forEach { renderer.draw(c, it) }
                Layer(bitmap, mode)
            }
            return Tile(marks, filter, layers)
        }

        private fun blendRuns(marks: List<Stroke>): List<Pair<Boolean, MutableList<Stroke>>> {
            val ordered = marks.filter { it.isHighlighter } + marks.filter { !it.isHighlighter }
            val runs = ArrayList<Pair<Boolean, MutableList<Stroke>>>()
            for (s in ordered) {
                val mode = s.usesMultiply
                if (runs.lastOrNull()?.first != mode) runs.add(mode to ArrayList())
                runs.last().second.add(s)
            }
            return runs
        }

        private fun blendCount(marks: List<Stroke>): Int {
            var count = 0
            var previous: Boolean? = null
            for (highlighter in listOf(true, false)) for (s in marks) if (s.isHighlighter == highlighter) {
                if (previous != s.usesMultiply) { count++; previous = s.usesMultiply }
            }
            return count
        }
    }
}
