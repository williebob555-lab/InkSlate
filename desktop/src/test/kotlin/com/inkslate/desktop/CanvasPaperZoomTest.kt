package com.inkslate.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.inkslate.core.Box
import com.inkslate.core.InkCanvas
import org.junit.Test

/**
 * Whether the paper is still there as the view comes in.
 *
 * Reported as the background disappearing past a certain magnification. Driving the real drawing
 * into a bitmap is the only way to ask: the answer depends on the transform, the clamping and the
 * pattern together, and reasoning about the three of them has been wrong before.
 */
class CanvasPaperZoomTest {

    private val width = 400
    private val height = 300

    private fun paperAt(
        scale: Float,
        atX: Float,
        atY: Float,
        pattern: String = "GRAPH",
        grown: Boolean = true
    ): String {
        val fresh = InkCanvas.startingAt(612f, 792f, pattern, ownPaper = true)
        val canvas = if (grown) {
            fresh.copy(left = -2_000f, top = -2_000f, right = 4_000f, bottom = 4_000f)
        } else {
            fresh
        }

        val bitmap = ImageBitmap(width, height)
        val scope = CanvasDrawScope()
        scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(width.toFloat(), height.toFloat())) {
            // The real frame: the camera in document points, then the canvas's own origin. A
            // document point d is the canvas point d + canvas.left, so looking at canvas point
            // (atX, atY) means putting the camera at that minus the origin.
            val cameraX = atX - canvas.left
            val cameraY = atY - canvas.top
            translate(-cameraX * scale, -cameraY * scale) {
                scale(scale, scale, pivot = Offset.Zero) {
                    val seen = Box(atX, atY, atX + width / scale, atY + height / scale)
                    translate(-canvas.left, -canvas.top) {
                        with(CanvasPaper) { drawCanvasPaper(canvas, scale, seen) }
                    }
                }
            }
        }

        val pixels = bitmap.toPixelMap()
        var painted = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                if (pixels[x, y].alpha > 0.5f) painted++
                x += 3
            }
            y += 3
        }
        val total = ((height + 2) / 3) * ((width + 2) / 3)
        return "%-7s %s scale %-6.1f painted %d%%".format(
            pattern, if (grown) "grown" else "fresh", scale, painted * 100 / total
        )
    }

    @Test
    fun `paper is painted at every magnification`() {
        for (scale in listOf(0.25f, 1f, 4f, 12f, 30f, 60f)) {
            println(paperAt(scale, atX = 137f, atY = 211f))
        }
        // A whiteboard as it is the moment it is made, which is the case that changed.
        for (pattern in listOf("GRAPH", "RULED", "DOTS", "PLAIN")) {
            for (scale in listOf(1f, 8f, 32f)) {
                println(paperAt(scale, atX = 137f, atY = 211f, pattern = pattern, grown = false))
            }
        }
    }
}
