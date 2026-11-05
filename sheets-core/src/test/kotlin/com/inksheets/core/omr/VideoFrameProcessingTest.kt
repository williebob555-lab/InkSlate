package com.inksheets.core.omr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFrameProcessingTest {
    @Test fun `temporal median removes a transient obstruction and retains printed ink`() {
        fun frame(time: Long, pixels: IntArray) = ScoreVideoFrame(time, 3, 2, ByteArray(6) { pixels[it].toByte() })
        val clean = intArrayOf(255, 0, 255, 255, 0, 255)
        val hand = intArrayOf(20, 20, 20, 20, 20, 20)
        val highlight = intArrayOf(255, 220, 220, 255, 220, 220)
        val merged = TemporalPageMerge.merge(listOf(frame(30, hand), frame(10, clean), frame(20, highlight)))

        assertEquals(20L, merged.timestampMs)
        assertEquals(listOf(255, 20, 220, 255, 20, 220), merged.grey.map { it.toInt() and 0xff })
        // Ink at the middle column appears in the majority and survives; one-frame obstructions do not.
        assertTrue((merged.grey[1].toInt() and 0xff) < 64)
    }

    @Test fun `analysis is deterministic bounded and reports visual change`() {
        val gradient = ScoreVideoFrame(100, 9, 8, ByteArray(72) { i -> ((i % 9) * 28).toByte() })
        val reversed = ScoreVideoFrame(200, 9, 8, ByteArray(72) { i -> (255 - (i % 9) * 28).toByte() })
        val a = VideoFrameAnalysis.evidence(gradient)
        val same = VideoFrameAnalysis.evidence(gradient, a.fingerprint)
        val changed = VideoFrameAnalysis.evidence(reversed, a.fingerprint)
        assertEquals(a, VideoFrameAnalysis.evidence(gradient))
        assertEquals(0f, same.change)
        assertTrue(a.sharpness in 0f..1f)
        assertTrue(changed.change > same.change)
    }

    @Test fun `sparse staff lines still clear the default sharpness gate`() {
        val pixels = ByteArray(100 * 100) { 0xff.toByte() }
        for (y in listOf(20, 30, 40, 50, 60)) for (x in 5 until 95) pixels[y * 100 + x] = 0
        val evidence = VideoFrameAnalysis.evidence(ScoreVideoFrame(0, 100, 100, pixels))
        assertTrue("Sparse notation edges should not be classified as a blurred frame: ${evidence.sharpness}",
            evidence.sharpness >= VideoFrameSelection().minSharpness)
    }

    @Test fun `merge rejects dimension frame-count and memory budget violations`() {
        val frame = ScoreVideoFrame(0, 2, 2, ByteArray(4))
        fun invalid(block: () -> Unit) {
            try { block(); throw AssertionError("Expected bounded merge to reject input") }
            catch (_: IllegalArgumentException) { }
        }
        invalid { TemporalPageMerge.merge(emptyList()) }
        invalid { TemporalPageMerge.merge(listOf(frame, ScoreVideoFrame(1, 1, 4, ByteArray(4)))) }
        invalid { TemporalPageMerge.merge(List(10) { frame }) }
        invalid { TemporalPageMerge.merge(listOf(frame), VideoImportBudget(maxResidentFrameBytes = 4)) }
    }
}
