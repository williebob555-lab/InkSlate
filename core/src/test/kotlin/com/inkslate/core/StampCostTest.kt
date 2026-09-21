package com.inkslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What it costs to show a stamp's settings, which is what the panel does on every keystroke.
 *
 * Moving a slider rebuilds the stamp, and the panel also asks which parts it draws so it can show
 * the sections for those and no others - and the only honest way to answer that is to build it and
 * watch. At a high spoke count that was two builds per pixel of slider travel, and the panel lagged
 * behind the finger. The answer is remembered per kind and options now, so a rebuild asks once.
 */
class StampCostTest {

    private val box = Box(0f, 0f, 400f, 400f)

    @Test
    fun `asking twice what a stamp draws gives the same answer`() {
        val kinds = listOf(
            Stamps.Kind.UNIT_CIRCLE, Stamps.Kind.SINE, Stamps.Kind.PROJECTILE,
            Stamps.Kind.PLOT, Stamps.Kind.COORD_GRID
        )
        for (kind in kinds) {
            val once = Stamps.features(kind, kind.defaults)
            val twice = Stamps.features(kind, kind.defaults)
            assertEquals("$kind changed its mind", once, twice)
        }
    }

    @Test
    fun `a remembered answer does not depend on what was asked before`() {
        val plain = Stamps.Kind.UNIT_CIRCLE.defaults
        val bare = plain.copy(labels = false, angleLabels = AngleLabel.NONE)
        val first = Stamps.features(Stamps.Kind.UNIT_CIRCLE, plain)
        Stamps.features(Stamps.Kind.UNIT_CIRCLE, bare)
        assertEquals(first, Stamps.features(Stamps.Kind.UNIT_CIRCLE, plain))
        // Writing is what the labels are, so the two answers are not the same answer.
        assertTrue(Stamps.Feature.TEXT in first)
        assertTrue(Stamps.Feature.TEXT !in Stamps.features(Stamps.Kind.UNIT_CIRCLE, bare))
    }

    @Test
    fun `the answer follows the options rather than the stamp`() {
        val wave = Stamps.Kind.SINE.defaults
        assertTrue(Stamps.Feature.MARKERS !in Stamps.features(Stamps.Kind.SINE, wave.copy(markMax = false, markMin = false, markZeros = false)))
        assertTrue(Stamps.Feature.MARKERS in Stamps.features(Stamps.Kind.SINE, wave.copy(markMax = true)))
        // Sampling brings the stems with it, which are the stamp's detail lines.
        val sampled = wave.copy(sampleEvery = 0.05f)
        assertTrue(Stamps.Feature.DETAIL in Stamps.features(Stamps.Kind.SINE, sampled))
    }

    @Test
    fun `a panel-sized run of rebuilds stays well inside a frame`() {
        // What a slider drag does: rebuild, and ask what was drawn, over and over.
        val kind = Stamps.Kind.UNIT_CIRCLE
        var marks = 0
        val started = System.nanoTime()
        for (i in 0 until 60) {
            val o = kind.defaults.copy(angleStep = Angles.COMMON_STEPS[i % Angles.COMMON_STEPS.size])
            var n = 0
            marks += Stamps.build(kind, box, page = 0, options = o, group = "g") { "s${n++}" }.size
            Stamps.features(kind, o)
        }
        val each = (System.nanoTime() - started) / 60.0 / 1_000_000.0
        assertTrue("built nothing", marks > 60)
        // Generous, because a test machine under load is not a tablet: this is here to catch a
        // rebuild that has gone quadratic, not to measure the drawing.
        assertTrue("a rebuild took %.1f ms".format(each), each < 25.0)
    }
}
