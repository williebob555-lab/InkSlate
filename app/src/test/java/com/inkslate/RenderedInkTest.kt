package com.inkslate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import com.inkslate.core.BrushType
import com.inkslate.core.AnnotationLayers
import com.inkslate.core.InkPoint
import com.inkslate.core.Stroke
import com.inkslate.ink.RenderedInk
import com.inkslate.ink.StrokeRenderer
import com.inkslate.ink.StrokeRasteriser
import com.inkslate.ink.RenderStats
import com.inkslate.core.Box
import com.inkslate.core.StrokeRegions
import com.inkslate.core.EraserMode
import com.inkslate.ink.DrawingView
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderedInkTest {
    private fun stroke(id: String, y: Float, brush: BrushType = BrushType.BALLPOINT) = Stroke(
        id, Stroke.Kind.FREEHAND, if (brush == BrushType.HIGHLIGHTER) Color.YELLOW else Color.BLUE,
        12f, listOf(InkPoint(440f, y, 12f), InkPoint(580f, y + 25f, 12f)), brush = brush)

    private fun image() = Bitmap.createBitmap(512, 80, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.rgb(185, 210, 220)) }

    @Test fun `rendered chunks preserve crossing strokes and multiply paint order`() {
        val marks = listOf(stroke("ink", 35f), stroke("hi", 40f, BrushType.HIGHLIGHTER), stroke("pencil", 60f, BrushType.PENCIL))
        val reference = image()
        val direct = Canvas(reference); direct.scale(.5f, .5f)
        StrokeRenderer(false).drawAll(direct, marks)
        val actual = image()
        val c = Canvas(actual); c.scale(.5f, .5f)
        for (x in 0..1) {
            val key = RenderedInk.Key(0, x, 0, -1)
            val tile = RenderedInk.render(key, marks, null, StrokeRenderer(false))!!
            val b = RenderedInk.box(key)
            c.save(); c.clipRect(b.left, b.top, b.right, b.bottom)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            for (layer in tile.layers) {
                paint.xfermode = if (layer.multiply) PorterDuffXfermode(PorterDuff.Mode.MULTIPLY) else null
                c.drawBitmap(layer.bitmap, null, RectF(b.left - 6f, b.top - 6f, b.right + 6f, b.bottom + 6f), paint)
            }
            c.restore()
        }
        val output = java.io.File("build/reports/ink-tiles").apply { mkdirs() }
        java.io.File(output, "reference.png").outputStream().use { reference.compress(Bitmap.CompressFormat.PNG, 100, it) }
        java.io.File(output, "actual.png").outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
        var maximum = 0; var error = 0L; var changed = 0
        for (y in 0 until 80) for (x in 0 until 512) {
            val expected = reference.getPixel(x, y); val got = actual.getPixel(x, y)
            for (shift in listOf(0, 8, 16, 24)) {
                val delta = kotlin.math.abs(((expected ushr shift) and 255) - ((got ushr shift) and 255))
                maximum = maxOf(maximum, delta); error += delta; if (delta > 0) changed++
            }
        }
        println("Raster comparison: max=$maximum error=$error changed=$changed")
        // Rasterizing transparent groups adds coverage quantization around antialiased edges.
        // Bound that difference independently of the seam check below.
        assertTrue("raster pixel error $maximum", maximum <= 32)
        assertTrue("mean channel error", error.toDouble() / (512 * 80 * 4) < .10)

        // An unpartitioned raster with the same blending must match across the entire seam.
        // Horizontal strokes have constant coverage here, avoiding diagonal edge quantization.
        val seamMarks = marks.map { it.copy(points = it.points.map { p -> p.copy(y = it.points.first().y) }) }
        val seamActual = image()
        val seamCanvas = Canvas(seamActual); seamCanvas.scale(.5f, .5f)
        for (x in 0..1) {
            val key = RenderedInk.Key(0, x, 0, -1)
            val tile = RenderedInk.render(key, seamMarks, null, StrokeRenderer(false))!!
            val b = RenderedInk.box(key)
            seamCanvas.save(); seamCanvas.clipRect(b.left, b.top, b.right, b.bottom)
            for (layer in tile.layers) {
                val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                paint.xfermode = if (layer.multiply) PorterDuffXfermode(PorterDuff.Mode.MULTIPLY) else null
                seamCanvas.drawBitmap(layer.bitmap, null, RectF(b.left - 6f, b.top - 6f, b.right + 6f, b.bottom + 6f), paint)
            }
            seamCanvas.restore()
        }
        val continuous = image()
        val out = Canvas(continuous)
        val ordered = listOf(seamMarks[1], seamMarks[0], seamMarks[2])
        for (s in ordered) {
            val layer = Bitmap.createBitmap(518, 86, Bitmap.Config.ARGB_8888)
            if (s.usesMultiply) layer.eraseColor(Color.WHITE)
            val lc = Canvas(layer); lc.translate(3f, 3f); lc.scale(.5f, .5f)
            StrokeRenderer(false).draw(lc, s)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            paint.xfermode = if (s.usesMultiply) PorterDuffXfermode(PorterDuff.Mode.MULTIPLY) else null
            out.drawBitmap(layer, null, RectF(-3f, -3f, 515f, 83f), paint)
        }
        for (y in 0 until 80) for (x in 250..262) {
            assertEquals("chunk seam at $x,$y", continuous.getPixel(x, y), seamActual.getPixel(x, y))
        }
    }
    @Test fun `tile rejects moved edited deleted and reordered strokes even when timestamps coincide`() {
        val a = stroke("a", 20f); val b = stroke("b", 30f)
        val tile = RenderedInk.render(RenderedInk.Key(0, 0, 0, -1), listOf(a, b), null, StrokeRenderer(false))!!
        assertTrue(tile.matches(listOf(a, b), null))
        assertFalse(tile.matches(listOf(b, a), null))
        assertFalse(tile.matches(listOf(a), null))
        assertFalse(tile.matches(listOf(a.copy(points = a.points.map { it.copy(x = it.x + 600f) }), b), null))
        assertFalse(tile.matches(listOf(a.copy(color = Color.RED), b), null))
    }

    @Test fun `warm camera movement reuses rasters and a cross boundary deletion never shows old ink`() {
        val callbacks = LinkedBlockingQueue<() -> Unit>()
        val cache = RenderedInk({ callbacks.add(it) }, {})
        StrokeRasteriser.colorFilter = null
        val marks = listOf(stroke("crossing", 35f))
        val regions = StrokeRegions(marks)
        val viewport = Box(400f, 0f, 600f, 100f)
        fun draw(index: StrokeRegions, scale: Float, moving: Boolean): Bitmap {
            val output = image()
            val canvas = Canvas(output); canvas.scale(scale, scale)
            assertTrue(cache.draw(canvas, 0, viewport, scale, index, null, 500f, 50f, moving))
            return output
        }
        try {
            draw(regions, .5f, false)
            repeat(2) { callbacks.poll(10, TimeUnit.SECONDS)?.invoke() ?: fail("tile preparation timed out") }
            val hits = RenderStats.cacheHits
            draw(regions, .5f, false)
            draw(regions, .25f, true)
            assertEquals("camera motion must not replay stroke geometry", hits, RenderStats.cacheHits)
            val erased = draw(StrokeRegions(emptyList()), .5f, false)
            for (y in 0 until erased.height) for (x in 0 until erased.width) assertEquals(Color.rgb(185, 210, 220), erased.getPixel(x, y))
        } finally { cache.close() }
    }

    @Test fun `dense cold tile never redraws all vectors on the interface thread`() {
        val callbacks = LinkedBlockingQueue<() -> Unit>()
        val cache = RenderedInk({ callbacks.add(it) }, {})
        val marks = (0 until 100).map { i -> stroke("dense-$i", (i % 20) * 3f) }
        val background = Color.rgb(185, 210, 220)
        val output = image()
        val beforeCold = RenderStats.tileColdMisses
        val beforeVectors = RenderStats.tileVectorFallbacks
        try {
            assertTrue(cache.draw(Canvas(output), 0, Box(0f, 0f, 512f, 80f), 1f,
                StrokeRegions(marks), null, 256f, 40f, false))
            assertTrue(RenderStats.tileColdMisses > beforeCold)
            assertEquals(beforeVectors, RenderStats.tileVectorFallbacks)
            // The source page remains immediately usable while its annotation tile is prepared.
            assertEquals(background, output.getPixel(256, 40))
        } finally { cache.close() }
    }

    @Test fun `tile cache honours the visible annotation channels`() {
        val cache = RenderedInk({}, {})
        val hidden = (0 until 20).map { i ->
            stroke("hidden-$i", 10f).copy(layerMask = AnnotationLayers.TWO)
        }
        val visible = stroke("visible", 45f).copy(layerMask = AnnotationLayers.ONE)
        val output = image()
        val background = Color.rgb(185, 210, 220)
        try {
            assertTrue(cache.draw(Canvas(output), 0, Box(0f, 0f, 512f, 80f), 1f,
                StrokeRegions(hidden + visible), null, 256f, 40f, false, AnnotationLayers.ONE))
            assertEquals(background, output.getPixel(480, 17))
            assertNotEquals(background, output.getPixel(480, 52))
        } finally { cache.close() }
    }

    @Test fun `large cross chunk selections delete and undo whole original objects`() {
        val view = DrawingView(RuntimeEnvironment.getApplication())
        val marks = (0 until 300).map { i ->
            stroke("group-$i", i * 20f).copy(points = listOf(InkPoint(-1000f, i * 20f, 12f), InkPoint(2000f, i * 20f, 12f)))
        }
        view.setStrokes(marks)
        view.selectAll()
        assertEquals(marks, view.selectedStrokes())
        view.deleteSelection()
        assertTrue(view.strokesSnapshot().isEmpty())
        view.undo()
        assertEquals(marks.toSet(), view.strokesSnapshot().toSet())
        view.redo()
        assertTrue(view.strokesSnapshot().isEmpty())
    }

    @Test fun `partial erasing across a tile edge splits the original stroke once`() {
        val view = DrawingView(RuntimeEnvironment.getApplication())
        val mark = stroke("crossing", 35f).copy(points = (400..620 step 5).map { InkPoint(it.toFloat(), 40f, 12f) })
        view.setStrokes(listOf(mark))
        view.penConfig.eraserMode = EraserMode.PARTIAL
        val erase = DrawingView::class.java.getDeclaredMethod("eraseAlong", List::class.java).apply { isAccessible = true }
        erase.invoke(view, listOf(508f, 40f, 516f, 40f))
        val pieces = view.strokesSnapshot()
        assertEquals(2, pieces.size)
        assertEquals(2, pieces.map { it.id }.toSet().size)
        assertFalse(pieces.any { it.hitTest(512f, 40f, 2f) })
        assertTrue(pieces.any { it.hitTest(420f, 40f, 2f) })
        assertTrue(pieces.any { it.hitTest(600f, 40f, 2f) })
    }
}
