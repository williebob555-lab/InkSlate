package com.inksheets.core.omr

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class RasterMemoryTest {
    @Test fun `rolling threshold matches summed area threshold exactly`() {
        val random = Random(7319)
        for ((width, height) in listOf(1 to 1, 7 to 5, 31 to 19, 53 to 37)) {
            val grey = IntArray(width * height) { random.nextInt(256) }
            for (window in listOf(1, 3, 7, 31)) {
                val expected = reference(width, height, grey, window)
                val actual = Ink.fromGrey(width, height, grey, window)
                assertArrayEquals("$width x $height, window $window", expected.bits, actual.bits)
            }
        }
    }

    /** The former full-page summed-area implementation, retained only as a small test oracle. */
    private fun reference(width: Int, height: Int, grey: IntArray, window: Int, k: Double = .2): Ink {
        val stride = width + 1
        val sum = LongArray(stride * (height + 1))
        val square = LongArray(stride * (height + 1))
        for (y in 0 until height) {
            var row = 0L
            var rowSquare = 0L
            for (x in 0 until width) {
                val g = grey[y * width + x].toLong()
                row += g
                rowSquare += g * g
                sum[(y + 1) * stride + x + 1] = sum[y * stride + x + 1] + row
                square[(y + 1) * stride + x + 1] = square[y * stride + x + 1] + rowSquare
            }
        }
        val radius = window / 2
        return Ink(width, height).also { out ->
            for (y in 0 until height) for (x in 0 until width) {
                val x0 = max(0, x - radius); val x1 = min(width, x + radius + 1)
                val y0 = max(0, y - radius); val y1 = min(height, y + radius + 1)
                val count = ((x1 - x0) * (y1 - y0)).toDouble()
                val s = sum[y1 * stride + x1] - sum[y0 * stride + x1] - sum[y1 * stride + x0] + sum[y0 * stride + x0]
                val q = square[y1 * stride + x1] - square[y0 * stride + x1] - square[y1 * stride + x0] + square[y0 * stride + x0]
                val mean = s / count
                val deviation = kotlin.math.sqrt(max(0.0, q / count - mean * mean))
                val threshold = mean * (1 + k * (deviation / 128.0 - 1))
                out.bits[y * width + x] = grey[y * width + x] < threshold && grey[y * width + x] < 200
            }
        }
    }
}
