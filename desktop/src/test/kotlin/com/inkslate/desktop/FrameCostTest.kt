package com.inkslate.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skia.Rect
import org.junit.Test
import java.awt.image.BufferedImage

/**
 * What one frame costs to draw a rendered page into, as the window does every frame: recorded,
 * then finished. A page picture the graphics library may keep as it is, against one it must copy.
 */
class FrameCostTest {
    private fun frameMs(bitmap: androidx.compose.ui.graphics.ImageBitmap, frames: Int): Double {
        val t = System.nanoTime()
        repeat(frames) {
            val recorder = PictureRecorder()
            val canvas = recorder.beginRecording(Rect.makeWH(1600f, 1000f))
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas.asComposeCanvas(), Size(1600f, 1000f)) {
                drawImage(bitmap)
            }
            recorder.finishRecordingAsPicture().close()
        }
        return (System.nanoTime() - t) / 1e6 / frames
    }

    @Test
    fun `a page drawn each frame, copied or kept`() {
        val page = BufferedImage(2400, 3100, BufferedImage.TYPE_INT_ARGB)
        val mutable = page.toComposeImageBitmap()
        val frozen = page.toComposeImageBitmap().frozen()
        frameMs(mutable, 5); frameMs(frozen, 5)
        println("FRAME copied each frame: %.2f ms".format(frameMs(mutable, 30)))
        println("FRAME kept as it is:     %.2f ms".format(frameMs(frozen, 30)))
    }
}
