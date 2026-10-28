package com.inksheets.desktop

import com.inkslate.core.*
import com.inkslate.core.Stroke.Kind
import com.inksheets.core.Part
import com.inksheets.core.Song
import com.inksheets.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.inksheets.core.omr.*
import com.inksheets.core.omr.Box

class CleanMaskTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `native mask path prefix cache matches cold rebuild while avoiding historical boolean work`() {
        val method = Class.forName("com.inkslate.desktop.DocumentCanvasKt").declaredMethods.single {
            it.name == "musicMaskPath" && it.parameterCount == 1
        }.apply { isAccessible = true }
        fun circle(x: Float, y: Float, erase: Boolean = false) = PageMark.MaskStroke(listOf(FloatArray(48) { coordinate ->
            val angle = (coordinate / 2) * Math.PI * 2 / 24
            if (coordinate % 2 == 0) x + kotlin.math.cos(angle).toFloat() * 10f else y + kotlin.math.sin(angle).toFloat() * 10f
        }), erase)
        val history = (0 until 120).map { i -> circle(40f + (i % 12) * 14, 40f + (i / 12) * 12, i % 7 == 6) }
        val previews = (0 until 16).map { circle(225f + it * 2, 80f) }
        fun path(strokes: List<PageMark.MaskStroke>, source: Any) = method.invoke(null, PageMark.Mask(strokes, width = 800f, height = 800f, sourceKey = source)) as androidx.compose.ui.graphics.Path
        repeat(3) { path(history + previews.first(), "warmup-$it") }
        val coldTimes = ArrayList<Long>(); val warmTimes = ArrayList<Long>()
        var cold: androidx.compose.ui.graphics.Path? = null; var warm: androidx.compose.ui.graphics.Path? = null
        repeat(3) { round ->
            val coldStart = System.nanoTime()
            previews.forEachIndexed { i, preview -> cold = path(history + preview, "cold-$round-$i") }
            coldTimes += System.nanoTime() - coldStart
            path(history, "cached-$round")
            val warmStart = System.nanoTime()
            previews.forEach { preview -> warm = path(history + preview, "cached-$round") }
            warmTimes += System.nanoTime() - warmStart
        }
        assertEquals(cold!!.getBounds(), warm!!.getBounds())
        // Compare the actual native paths visually; caching must retain both positive and erased regions.
        fun paint(path: androidx.compose.ui.graphics.Path): PixelMap {
            val bitmap = ImageBitmap(300, 200)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(300f, 200f)) {
                drawRect(Color.White); drawPath(path, Color.Black)
            }
            return bitmap.toPixelMap()
        }
        val a = paint(cold!!); val b = paint(warm!!)
        for (y in 0 until 200 step 3) for (x in 0 until 300 step 3) assertEquals("Mask changed at $x,$y", a[x, y], b[x, y])
        val coldMs = coldTimes.sorted()[1] / 1_000_000.0
        val warmMs = warmTimes.sorted()[1] / 1_000_000.0
        File("build/mask-native-performance.txt").writeText("Actual desktop native Path.union/difference, 120 committed strokes + 16 live previews; median of 3 rounds.\nCold historical rebuild: $coldMs ms\nIncremental live-stroke update: $warmMs ms\nSpeedup: ${coldMs / warmMs}x\nPixel parity: verified on 6700 sampled pixels.\n")
    }

    private class Platform(override val startFolder: File) : SheetsPlatform {
        private val prefs = mutableMapOf("sheets_library" to startFolder.absolutePath)
        override val deviceId = "clean-mask-test"
        override val deviceName = "Clean mask test"
        override val localFolder = File(startFolder, "local")
        override fun pref(key: String) = prefs[key]
        override fun setPref(key: String, value: String?) { if (value == null) prefs.remove(key) else prefs[key] = value }
        override fun openPart(song: Song, part: Part, file: File) {}
        override fun pageText(file: File, page: Int): String? = null
        override val audioOut: AudioOut? = null
        override val microphone: Microphone? = null
        override fun onMain(block: () -> Unit) = block()
    }

    private fun file(folder: File, name: String) = File(folder, name).apply {
        writeText("%PDF-1.4\n$name\nstartxref\n0\n%%EOF")
    }.absolutePath

    @Test fun `preview replaces itself and one completed stroke is one undo step`() {
        val folder = tmp.newFolder()
        val state = SheetsState(Platform(folder))
        val path = file(folder, "preview.pdf")
        val config = ToolConfig(strokeWidth = 12f)
        CleanMasks.gesture(state, path, 0, floatArrayOf(10f, 20f), false, config, 1f)
        assertEquals(1, CleanMasks.getMask(state, path, 0)!!.strokes.size)
        assertFalse(CleanMasks.canUndo(state, path))
        CleanMasks.gesture(state, path, 0, floatArrayOf(10f, 20f, 40f, 20f), false, config, 1f)
        assertEquals(1, CleanMasks.getMask(state, path, 0)!!.strokes.size)
        // A second instance reads only committed work, never the moving pointer's preview.
        assertFalse(CleanMasks.hasMask(SheetsState(Platform(folder)), path, 0))
        CleanMasks.gesture(state, path, 0, floatArrayOf(10f, 20f, 40f, 20f), true, config, 1f)
        assertEquals(1, CleanMasks.getMask(state, path, 0)!!.strokes.size)
        assertTrue(CleanMasks.undo(state, path))
        assertFalse(CleanMasks.hasMask(state, path, 0))
        assertFalse(CleanMasks.undo(state, path))
        CleanMasks.gesture(state, path, 0, floatArrayOf(4f, 5f), false, config, 1f)
        CleanMasks.cancel(state, path)
        assertFalse(CleanMasks.hasMask(state, path, 0))
        assertFalse(CleanMasks.hasMask(SheetsState(Platform(folder)), path, 0))
    }

    @Test fun `erase restores print over full clean and persists in stroke order across pages and files`() {
        val folder = tmp.newFolder()
        val state = SheetsState(Platform(folder))
        val path = file(folder, "one.pdf")
        val other = file(folder, "two.pdf")
        val draw = ToolConfig(strokeWidth = 14f)
        val erase = ToolConfig(tool = Tool.ERASER, eraserRadius = 5f)
        CleanMasks.gesture(state, path, 0, floatArrayOf(20f, 20f, 50f, 20f), true, draw, 1f)
        CleanMasks.gesture(state, path, 0, floatArrayOf(30f, 20f), true, erase, 1f)
        CleanMasks.gesture(state, path, 1, floatArrayOf(80f, 90f), true, erase, 1f)
        val loaded = SheetsState(Platform(folder))
        val mask = CleanMasks.getMask(loaded, path, 0, baseFull = true)!!
        assertTrue(mask.baseFull)
        assertEquals(listOf(false, true), mask.strokes.map { it.erase })
        assertTrue(CleanMasks.getMask(loaded, path, 1)!!.strokes.single().erase)
        assertFalse(CleanMasks.hasMask(loaded, path, 2))
        assertFalse(CleanMasks.hasMask(loaded, other, 0))
        // Undo is per file in reverse drawing order, not per visible page.
        assertTrue(CleanMasks.undo(state, path))
        assertFalse(CleanMasks.hasMask(state, path, 1))
        assertEquals(2, CleanMasks.getMask(state, path, 0)!!.strokes.size)
        assertTrue(CleanMasks.undo(state, path))
        assertEquals(listOf(false), CleanMasks.getMask(state, path, 0)!!.strokes.map { it.erase })
        assertEquals(1, CleanMasks.getMask(SheetsState(Platform(folder)), path, 0)!!.strokes.size)
    }

    @Test fun `every brush uses the same outline as annotation and scale normalizes width and position`() {
        val folder = tmp.newFolder()
        val state = SheetsState(Platform(folder))
        val path = file(folder, "brushes.pdf")
        val readingPoints = floatArrayOf(10f, 12f, 30f, 15f, 42f, 33f)
        BrushType.entries.forEachIndexed { page, brush ->
            val config = ToolConfig(brush = brush, strokeWidth = 8f)
            val expected = StrokeOutline.contours(Stroke("reference", Kind.FREEHAND, 0, 8f,
                listOf(InkPoint(10f, 12f, 8f), InkPoint(30f, 15f, 8f), InkPoint(42f, 33f, 8f)), brush = brush))
            CleanMasks.gesture(state, path, page, readingPoints, true, config, 1f)
            val actual = CleanMasks.getMask(state, path, page)!!.strokes.single().contours
            assertContours(expected, actual)
            CleanMasks.gesture(state, path, page + 100, FloatArray(readingPoints.size) { readingPoints[it] * 2f },
                true, ToolConfig(brush = brush, strokeWidth = 16f), 2f)
            assertContours(expected, CleanMasks.getMask(state, path, page + 100)!!.strokes.single().contours)
        }
    }

    @Test fun `invalid pointer data never commits or leaves a stale preview`() {
        val folder = tmp.newFolder()
        val state = SheetsState(Platform(folder))
        val path = file(folder, "invalid.pdf")
        CleanMasks.gesture(state, path, 0, floatArrayOf(3f, 4f), false, ToolConfig(), 1f)
        CleanMasks.gesture(state, path, 0, floatArrayOf(Float.NaN, 4f), true, ToolConfig(), 1f)
        assertFalse(CleanMasks.hasMask(state, path, 0))
        CleanMasks.gesture(state, path, 0, floatArrayOf(3f, 4f), true, ToolConfig(), 0f)
        assertFalse(CleanMasks.canUndo(state, path))
    }

    @Test fun `real score overlay renderer reveals brush area restores print on erase and undo and rescales`() {
        val folder = tmp.newFolder()
        val state = SheetsState(Platform(folder))
        val path = file(folder, "rendered-mask.pdf")
        state.currentPath = path
        state.pageShown = 0 to 1
        val score = Score(listOf(Measure(1, 0, 0, Box(20, 40, 240, 80), 10f,
            Clef.TREBLE, Key(0), TimeSig(4, 4), emptyList())), 1, listOf(800))
        ScoreTools.install(state)
        ScoreTools.scoreSource = { if (it == path) score else null }
        val print = Color(0xFFBA3348)
        val previousDocument = Perform.document
        var inkUndos = 0
        Perform.document = { action -> if (action == PerformAction.UNDO) inkUndos++; true }
        try {
            ScoreTools.open = true
            ScoreTools.marks(path, 0, 800f, 800f) // Register the actual PDF page-point width.
            ScoreTools.toggleMaskBrush(state)
            Perform.musicBrush = ToolConfig(strokeWidth = 20f)
            Perform.musicGesture!!.invoke(path, 0, floatArrayOf(50f, 55f, 120f, 55f), true)
            val painted = raster(ScoreTools.marks(path, 0, 800f, 800f).orEmpty(), print)
            assertEquals("Clean paper replaces print under brush", Color.White, painted[70, 55])
            assertEquals("Print outside brush remains untouched", print, painted[150, 55])

            // Rotation/renderer size changes must scale both the masks and the notation, even
            // when no edit/version changes between these two draws.
            val scaled = raster(ScoreTools.marks(path, 0, 400f, 400f).orEmpty(), print)
            assertEquals(Color.White, scaled[35, 27])
            assertEquals(print, scaled[75, 27])

            Perform.musicBrush = ToolConfig(tool = Tool.ERASER, eraserRadius = 2f)
            Perform.musicGesture!!.invoke(path, 0, floatArrayOf(40f, 27.5f), true)
            val erased = raster(ScoreTools.marks(path, 0, 400f, 400f).orEmpty(), print)
            assertEquals("Eraser restores the original print within the brush", print, erased[40, 27])
            assertEquals(Color.White, erased[30, 27])
            assertTrue(Perform.musicUndo!!.invoke())
            val undone = raster(ScoreTools.marks(path, 0, 400f, 400f).orEmpty(), print)
            assertEquals("Undo brings cleaned notation back", Color.White, undone[40, 27])

            ScoreTools.cleanWhole(state, true)
            Perform.musicGesture!!.invoke(path, 0, floatArrayOf(40f, 27.5f), true)
            val full = raster(ScoreTools.marks(path, 0, 400f, 400f).orEmpty(), print)
            assertEquals("Eraser also restores print over a fully cleaned bar", print, full[40, 27])
            assertEquals("Rest of fully cleaned bar stays clean", Color.White, full[75, 27])
            while (CleanMasks.canUndo(state, path)) assertTrue(Perform.run(PerformAction.UNDO))
            assertTrue("Empty mask history still consumes Undo while mask brush is active", Perform.run(PerformAction.UNDO))
            assertEquals("Mask Undo must never undo unrelated handwritten annotations", 0, inkUndos)
            ScoreTools.toggleMaskBrush(state)
            assertTrue(Perform.run(PerformAction.UNDO))
            assertEquals("Normal annotation Undo resumes after leaving the mask brush", 1, inkUndos)
        } finally {
            if (ScoreTools.maskBrushEnabled) ScoreTools.toggleMaskBrush(state)
            ScoreTools.close(state)
            ScoreTools.scoreSource = null
            Perform.musicBrush = ToolConfig()
            Perform.document = previousDocument
        }
    }

    /** Invoke the actual private desktop overlay renderer, without introducing a production
     * test-only API or duplicating its path union/difference/clipping implementation here. */
    private fun raster(marks: List<PageMark>, print: Color): PixelMap {
        val draw = Class.forName("com.inkslate.desktop.DocumentCanvasKt").declaredMethods.single {
            it.name == "drawPageMarks" && it.parameterCount == 2
        }.apply { isAccessible = true }
        val bitmap = ImageBitmap(800, 800)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(800f, 800f)) {
            drawRect(print)
            draw.invoke(null, this, marks)
        }
        return bitmap.toPixelMap()
    }

    private fun assertContours(expected: List<FloatArray>, actual: List<FloatArray>) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { assertArrayEquals(expected[it], actual[it], 0.0001f) }
    }
}
