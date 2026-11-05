package com.inksheets.core.omr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartStemRendererTest {
    private val rate = 8_000
    private val patch = Synth.Patch(floatArrayOf(1f), .01, .02, .8f, .05, gain = .2f)

    private fun plan() = PartPerformance.Plan(
        parts = listOf(
            PartPerformance.Part("high", listOf(Synth.Tone(72, 0, 800, .7f, patch))),
            PartPerformance.Part("low", listOf(Synth.Tone(48, 0, 800, .7f, patch)))
        ),
        barStarts = longArrayOf(0), barNumbers = intArrayOf(1), length = 1_000
    )

    private fun collect(cancelAfterWrites: Int? = null): Pair<Map<String, FloatArray>, List<String>> {
        val arrays = LinkedHashMap<String, FloatArray>()
        val ended = ArrayList<String>()
        var writes = 0
        PartStemRenderer.render(plan(), rate, object : PartStemSink {
            override fun begin(partId: String, totalFrames: Long) {
                arrays[partId] = FloatArray(totalFrames.toInt())
            }
            override fun write(partId: String, offsetFrames: Long, samples: FloatArray, frames: Int) {
                System.arraycopy(samples, 0, arrays.getValue(partId), offsetFrames.toInt(), frames)
                writes++
            }
            override fun end(partId: String) { ended += partId }
        }, blockFrames = 127, tailMs = 100, cancelled = { cancelAfterWrites != null && writes >= cancelAfterWrites })
        return arrays to ended
    }

    @Test fun `renders deterministic independent bounded blocks with a release tail`() {
        val (first, ended) = collect()
        val (again, _) = collect()
        assertEquals(listOf("high", "low"), first.keys.toList())
        assertEquals(listOf("high", "low"), ended)
        assertEquals(1_800, first.getValue("high").size)
        assertArrayEquals(first.getValue("high"), again.getValue("high"), 0f)
        assertTrue(first.values.all { stem -> stem.all(Float::isFinite) && stem.any { it != 0f } })
        assertTrue("Different parts must remain independent", !first.getValue("high").contentEquals(first.getValue("low")))
    }

    @Test fun `cancellation stops before beginning another part`() {
        val (arrays, ended) = collect(cancelAfterWrites = 1)
        assertEquals(setOf("high"), arrays.keys)
        assertEquals(listOf("high"), ended)
    }
}
