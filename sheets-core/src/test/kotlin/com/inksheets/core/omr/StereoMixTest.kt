package com.inksheets.core.omr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class StereoMixTest {
    private val tolerance = 1e-6f

    @Test fun `centre and endpoint pans use equal power coefficients`() {
        val mono = floatArrayOf(1f, -.5f)
        val centre = DirectStereoMix.render(listOf(StereoStem("c", mono)), 48_000, levelOutput = false)
        val halfPower = (1.0 / sqrt(2.0)).toFloat()
        assertArrayEquals(floatArrayOf(halfPower, -.5f * halfPower), centre.left, tolerance)
        assertArrayEquals(centre.left, centre.right, tolerance)
        val sides = DirectStereoMix.render(listOf(
            StereoStem("left", floatArrayOf(.25f), pan = -1f),
            StereoStem("right", floatArrayOf(.5f), pan = 1f)
        ), 48_000, levelOutput = false)
        assertEquals(.25f, sides.left[0], tolerance)
        assertEquals(.5f, sides.right[0], tolerance)
    }

    @Test fun `delays and gains preserve part alignment without changing inputs`() {
        val source = floatArrayOf(.2f, .4f)
        val signal = DirectStereoMix.render(listOf(StereoStem("horn", source, gain = .5f, pan = -1f, delaySamples = 2)),
            1000, levelOutput = false)
        assertEquals(4, signal.frames)
        assertArrayEquals(floatArrayOf(0f, 0f, .1f, .2f), signal.left, tolerance)
        assertArrayEquals(FloatArray(4), signal.right, tolerance)
        assertArrayEquals(floatArrayOf(.2f, .4f), source, 0f)
    }

    @Test fun `linked level control stays finite bounded and deterministic`() {
        val stems = listOf(
            StereoStem("a", FloatArray(2000) { 1f }, gain = 3f, pan = -.5f),
            StereoStem("b", FloatArray(2000) { 1f }, gain = 3f, pan = .5f)
        )
        val first = DirectStereoMix.render(stems, 48_000)
        val second = DirectStereoMix.render(stems, 48_000)
        assertArrayEquals(first.left, second.left, 0f)
        assertArrayEquals(first.right, second.right, 0f)
        assertTrue(first.left.all { it.isFinite() && kotlin.math.abs(it) <= .971f })
        assertTrue(first.right.all { it.isFinite() && kotlin.math.abs(it) <= .971f })
    }

    @Test fun `empty and invalid inputs are explicit`() {
        assertEquals(0, DirectStereoMix.render(emptyList(), 48_000).frames)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            DirectStereoMix.render(listOf(StereoStem("", floatArrayOf(0f))), 48_000)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            DirectStereoMix.render(listOf(StereoStem("x", floatArrayOf(Float.NaN))), 48_000)
        }
    }
}
