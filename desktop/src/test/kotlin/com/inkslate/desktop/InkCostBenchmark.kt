package com.inkslate.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.inkslate.core.BrushType
import com.inkslate.core.InkPoint
import com.inkslate.core.Stroke
import org.junit.Test
import kotlin.math.sin
import kotlin.random.Random

/**
 * What a frame of ink actually costs, at the magnifications it is drawn at.
 *
 * Written because the same symptom - "it stutters" - has had four different causes in this build
 * already, and the last of them was found by guessing twice before measuring. Reported as a
 * measurement rather than asserted: the numbers are the point, and a threshold here would only
 * fail on somebody else's machine.
 */
class InkCostBenchmark {

    private val width = 1_600
    private val height = 1_000

    private fun marks(count: Int, points: Int, brush: BrushType): List<Stroke> {
        val random = Random(7)
        return (0 until count).map { i ->
            val originX = random.nextFloat() * 500f
            val originY = random.nextFloat() * 700f
            Stroke(
                id = "s$i",
                kind = Stroke.Kind.FREEHAND,
                color = 0xFF1A1A1A.toInt(),
                baseWidth = 2.4f,
                brush = brush,
                points = (0 until points).map { p ->
                    val t = p / points.toFloat()
                    InkPoint(
                        originX + t * 90f,
                        originY + sin(t * 12f) * 14f,
                        1.2f + sin(t * 5f) * 0.8f
                    )
                },
                pageIndex = 0,
                updatedUtc = 1_000L + i
            )
        }
    }

    private fun timeFrames(strokes: List<Stroke>, scale: Float, frames: Int = 12): Double {
        val bitmap = ImageBitmap(width, height)
        val canvas = Canvas(bitmap)
        val scope = CanvasDrawScope()
        val size = Size(width.toFloat(), height.toFloat())

        fun frame() {
            scope.draw(Density(1f), LayoutDirection.Ltr, canvas, size) {
                translate(-40f * scale, -40f * scale) {
                    scale(scale, scale, pivot = Offset.Zero) {
                        for (s in strokes) drawStroke(s)
                    }
                }
            }
        }

        repeat(3) { frame() }
        val started = System.nanoTime()
        repeat(frames) { frame() }
        return (System.nanoTime() - started) / 1_000_000.0 / frames
    }

    @Test
    fun `what a frame of ink costs as the view magnifies`() {
        for (brush in listOf(BrushType.BALLPOINT, BrushType.FOUNTAIN)) {
            val strokes = marks(count = 120, points = 260, brush = brush)
            InkGeometry.clear()
            val report = StringBuilder("\n$brush, 120 marks of 260 points each\n")
            for (scale in listOf(0.5f, 1f, 2f, 4f, 8f, 16f)) {
                report.append(
                    "  scale %-5s %.1f ms/frame\n".format(scale, timeFrames(strokes, scale))
                )
            }
            println(report)
        }
    }

    @Test
    fun `what the point count costs`() {
        InkGeometry.clear()
        val report = StringBuilder("\npoints per mark, 120 marks, scale 8\n")
        for (points in listOf(40, 130, 260, 520, 1_040)) {
            val strokes = marks(count = 120, points = points, brush = BrushType.FOUNTAIN)
            InkGeometry.clear()
            report.append("  %-5d points  %.1f ms/frame\n".format(points, timeFrames(strokes, 8f)))
        }
        println(report)
    }

    /** How much of the cost is building the shape, against putting it on the screen. */
    @Test
    fun `what the cache is saving`() {
        val strokes = marks(count = 120, points = 260, brush = BrushType.FOUNTAIN)

        InkGeometry.clear()
        val cached = timeFrames(strokes, 8f)

        val rebuilt = run {
            val bitmap = ImageBitmap(width, height)
            val canvas = Canvas(bitmap)
            val scope = CanvasDrawScope()
            val size = Size(width.toFloat(), height.toFloat())
            fun frame() {
                scope.draw(Density(1f), LayoutDirection.Ltr, canvas, size) {
                    scale(8f, 8f, pivot = Offset.Zero) {
                        for (s in strokes) drawStroke(s, cached = false)
                    }
                }
            }
            repeat(3) { frame() }
            val started = System.nanoTime()
            repeat(12) { frame() }
            (System.nanoTime() - started) / 1_000_000.0 / 12
        }

        println("\nat scale 8: %.1f ms cached against %.1f ms rebuilt\n".format(cached, rebuilt))
    }
}
