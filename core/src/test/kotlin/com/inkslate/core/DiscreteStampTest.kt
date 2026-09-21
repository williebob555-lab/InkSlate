package com.inkslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A signal in discrete time, and a circle marked in the angles trigonometry is answered in.
 *
 * Both of these exist to produce the *numbers* a class asks for - the rate, the Nyquist limit, the
 * folded frequency, sin of 7pi/6 - and a drawing of them that got the numbers wrong would be worse
 * than no drawing at all, so the numbers are what is tested here rather than the picture.
 */
class DiscreteStampTest {

    private val box = Box(0f, 0f, 400f, 300f)

    private var ids = 0

    private fun build(kind: Stamps.Kind, o: Stamps.StampOptions) =
        Stamps.build(kind, box, page = 0, options = Stamps.sanitise(kind, o), group = "g") { "s${ids++}" }

    private fun rows(kind: Stamps.Kind, o: Stamps.StampOptions): Map<String, String> =
        Stamps.inspect(kind, o).toMap()

    private fun wave(
        omega: Float = 6.2832f,
        every: Float = 0f,
        quantize: Boolean = false,
        bits: Int = 3,
        style: DiscreteStyle = DiscreteStyle.STEMS,
        continuous: Boolean = true
    ) = Stamps.Kind.SINE.defaults.copy(
        omega = omega, sampleEvery = every, quantize = quantize, quantBits = bits,
        discreteStyle = style, showContinuous = continuous
    )

    // ---- sampling ---------------------------------------------------------------

    @Test
    fun `a sampling interval of zero leaves the wave continuous`() {
        val o = wave()
        assertEquals(0f, Stamps.rateOf(o), 0f)
        assertFalse(Stamps.aliases(o))
        assertTrue(Stamps.inspect(Stamps.Kind.SINE, o).none { it.first.startsWith("Sampling") })
    }

    @Test
    fun `the rate, the limit and the samples per period come off the interval`() {
        // A 1 Hz wave, sampled every 50 ms: 20 Hz, Nyquist at 10 Hz, 20 samples a period.
        val r = rows(Stamps.Kind.SINE, wave(every = 0.05f))
        assertEquals("0.05 s", r["Sampling interval Ts"])
        assertEquals("20 Hz", r["Sampling rate fs"])
        assertEquals("10 Hz", r["Nyquist limit"])
        assertEquals("20", r["Samples per period"])
        assertEquals("no, fs is above Nyquist", r["Aliasing"])
    }

    @Test
    fun `a wave above the Nyquist limit is reported as the one it will look like`() {
        // 900 Hz sampled at 1 kHz reads as 100 Hz, which is the whole point of the exercise.
        val o = wave(omega = (2.0 * Math.PI * 900).toFloat(), every = 0.001f)
        assertTrue(Stamps.aliases(o))
        assertEquals(100f, Stamps.aliasOf(o), 0.5f)
        assertTrue(rows(Stamps.Kind.SINE, o)["Aliasing"]!!.startsWith("yes"))
    }

    @Test
    fun `normalised frequency is omega times the interval`() {
        val o = wave(omega = 10f, every = 0.1f)
        assertEquals("1 rad/sample", rows(Stamps.Kind.SINE, o)["Ω = ωTs"])
    }

    @Test
    fun `samples are drawn as stems on the axis, one per interval`() {
        // A second of wave every 0.1 s: eleven samples, counting both ends.
        val plain = wave(every = 0.1f, continuous = false)
            .copy(markMax = false, markMin = false, markZeros = false)
        val marks = build(Stamps.Kind.SINE, plain)
        // A second of a 1 Hz wave every 0.1 s: eleven samples, counting both ends, each marked.
        assertEquals("a mark per sample", 11, marks.count { it.kind == Stroke.Kind.ELLIPSE })
        // Three of those samples land on zero, where a stem has nothing to stand up.
        assertEquals("a stem for every sample away from zero", 8, stems(marks).size)
        // And the wave itself is not drawn, because it was not asked for.
        assertTrue(marks.none { it.kind == Stroke.Kind.FREEHAND && it.points.size > 40 })
    }

    /**
     * Vertical strokes standing on the baseline: the stems, and not the axis or its ticks.
     *
     * An axis is vertical too, and ticks are little vertical lines all over the place. What marks a
     * stem out is that every one of them starts at the same height - the zero line they stand on.
     */
    private fun stems(marks: List<Stroke>): List<Stroke> {
        val vertical = marks.filter {
            it.kind == Stroke.Kind.LINE && it.points.size == 2 &&
                kotlin.math.abs(it.points[0].x - it.points[1].x) < 0.01f &&
                kotlin.math.abs(it.points[0].y - it.points[1].y) > 20f
        }
        val base = vertical.groupBy { kotlin.math.round(it.points[0].y) }
            .maxByOrNull { it.value.size }?.key ?: return emptyList()
        return vertical.filter { kotlin.math.round(it.points[0].y) == base }
    }

    @Test
    fun `dots only draws no stems, and held draws a staircase`() {
        val dots = build(Stamps.Kind.SINE, wave(every = 0.1f, style = DiscreteStyle.DOTS, continuous = false))
        val standing = build(Stamps.Kind.SINE, wave(every = 0.1f, continuous = false))
        assertTrue(
            "dots only should not stand on stems",
            stems(dots).size + 5 < stems(standing).size
        )

        val held = build(Stamps.Kind.SINE, wave(every = 0.1f, style = DiscreteStyle.STEPS, continuous = false))
        // Eleven samples over a second every 0.1 s: twenty-one corners, each held twice so the
        // smoothing a freehand mark gets cannot round them off.
        val stair = held.filter { it.kind == Stroke.Kind.FREEHAND && it.points.size == 42 }
        assertTrue(
            "a held signal is one polyline through its samples, got " +
                held.joinToString { it.kind.name + ":" + it.points.size },
            stair.isNotEmpty()
        )
        // A staircase only ever moves along or up, never diagonally.
        val steps = stair.first().points
        for (i in 1 until steps.size) {
            val flat = kotlin.math.abs(steps[i].y - steps[i - 1].y) < 0.01f
            val upright = kotlin.math.abs(steps[i].x - steps[i - 1].x) < 0.01f
            assertTrue("segment $i of a staircase runs diagonally", flat || upright)
        }
    }

    @Test
    fun `a sampling interval typed far too small draws a crowded graph rather than a hundred thousand strokes`() {
        val marks = build(Stamps.Kind.SINE, wave(every = 1e-6f))
        assertTrue("got ${marks.size} strokes", marks.size < Stamps.MAX_SAMPLES * 3)
    }

    // ---- quantisation ----------------------------------------------------------

    @Test
    fun `no quantisation means no levels drawn and nothing said about depth`() {
        val r = rows(Stamps.Kind.SINE, wave(every = 0.1f))
        assertFalse(r.containsKey("Bit depth"))
        assertFalse(r.containsKey("Step Δ"))
    }

    @Test
    fun `a bit depth gives its levels, its step and the figure a class quotes`() {
        // Three bits over a full scale of -1..1: eight levels, a quarter apart.
        val r = rows(Stamps.Kind.SINE, wave(every = 0.05f, quantize = true, bits = 3))
        assertEquals("3 bit, 8 levels", r["Bit depth"])
        assertEquals("-1 to 1", r["Full scale"])
        assertEquals("0.25", r["Step Δ"])
        assertEquals("19.8 dB", r["SQNR"])
    }

    @Test
    fun `quantised samples land on levels rather than on the wave`() {
        val o = wave(every = 0.02f, quantize = true, bits = 2, continuous = false)
        val marks = build(Stamps.Kind.SINE, o)
        // Two bits over -1..1 is four levels, so every sample sits at one of four heights.
        val heights = stems(marks).map { kotlin.math.round(it.points[1].y * 10f) / 10f }.distinct()
        assertEquals("four levels should give four heights, got $heights", 4, heights.size)
    }

    @Test
    fun `the levels themselves can be drawn, and are not when they are turned off`() {
        fun horizontals(o: Stamps.StampOptions) = build(Stamps.Kind.SINE, o).count {
            it.kind == Stroke.Kind.LINE && it.points.size == 2 &&
                kotlin.math.abs(it.points[0].y - it.points[1].y) < 0.01f &&
                kotlin.math.abs(it.points[0].x - it.points[1].x) > 100f
        }
        val shown = wave(every = 0.05f, quantize = true, bits = 3)
        val hidden = shown.copy(showLevels = false)
        assertTrue(horizontals(shown) > horizontals(hidden))
    }

    // ---- the unit circle -------------------------------------------------------

    @Test
    fun `angles are written as the multiples of pi they are`() {
        assertEquals("0", Angles.asPi(0.0, 6))
        assertEquals("π/6", Angles.asPi(30.0, 6))
        assertEquals("π/2", Angles.asPi(90.0, 6))
        assertEquals("2π/3", Angles.asPi(120.0, 6))
        assertEquals("π", Angles.asPi(180.0, 6))
        assertEquals("7π/6", Angles.asPi(210.0, 6))
        assertEquals("π/4", Angles.asPi(45.0, 4))
        assertEquals("3π/4", Angles.asPi(135.0, 4))
    }

    @Test
    fun `a circle marked every pi over six has twelve angles on it`() {
        assertEquals(12, Angles.anglesFor(6).size)
        assertEquals(8, Angles.anglesFor(4).size)
        assertEquals(24, Angles.anglesFor(12).size)
        assertEquals(listOf(0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0), Angles.anglesFor(4))
    }

    @Test
    fun `the common angles carry their exact values`() {
        assertEquals("√3/2" to "1/2", Angles.exactPair(30.0))
        assertEquals("√2/2" to "√2/2", Angles.exactPair(45.0))
        assertEquals("0" to "-1", Angles.exactPair(270.0))
        assertEquals("-√3/2" to "-1/2", Angles.exactPair(210.0))
        // A turn round the circle is the same angle, and an odd one has no exact form.
        assertEquals(Angles.exactPair(30.0), Angles.exactPair(390.0))
        assertEquals(null, Angles.exactPair(37.0))
    }

    @Test
    fun `a marked angle is worked through, exactly where it can be`() {
        val o = Stamps.Kind.UNIT_CIRCLE.defaults.copy(markAngle = 210f)
        val r = rows(Stamps.Kind.UNIT_CIRCLE, o)
        assertEquals("7π/6  (210°)", r["Angle θ"])
        assertEquals("-√3/2  = -0.87", r["cos θ"])
        assertEquals("-1/2  = -0.5", r["sin θ"])
        assertEquals("3", r["Quadrant"])
        assertEquals("π/6  (30°)", r["Reference angle"])
    }

    @Test
    fun `a vertical angle has no tangent and sits on an axis`() {
        val r = rows(Stamps.Kind.UNIT_CIRCLE, Stamps.Kind.UNIT_CIRCLE.defaults.copy(markAngle = 90f))
        assertEquals("undefined", r["tan θ"])
        assertEquals("on an axis", r["Quadrant"])
    }

    @Test
    fun `the circle is drawn with a spoke for every angle off the axes`() {
        val o = Stamps.Kind.UNIT_CIRCLE.defaults.copy(angleStep = 6, labels = false)
        val marks = build(Stamps.Kind.UNIT_CIRCLE, o)
        // Twelve angles, four of them on the axes, so eight spokes.
        val spokes = marks.count {
            it.kind == Stroke.Kind.LINE && it.points.size == 2 &&
                kotlin.math.abs(it.points[0].x - box.centerX) < 0.5f &&
                kotlin.math.abs(it.points[0].y - box.centerY) < 0.5f
        }
        assertEquals(8, spokes)
    }

    @Test
    fun `the angles are labelled in the form that was asked for`() {
        fun texts(labels: AngleLabel): List<String> = build(
            Stamps.Kind.UNIT_CIRCLE,
            Stamps.Kind.UNIT_CIRCLE.defaults.copy(angleStep = 4, angleLabels = labels)
        ).filter { it.kind == Stroke.Kind.TEXT }.mapNotNull { it.text }

        assertTrue(texts(AngleLabel.RADIANS).contains("3π/4"))
        assertTrue(texts(AngleLabel.DEGREES).contains("135°"))
        assertTrue(texts(AngleLabel.BOTH).contains("3π/4 = 135°"))
        assertTrue(texts(AngleLabel.COORDINATES).contains("(-√2/2, √2/2)"))
        // Nothing at the angles, but the axes still say 1 and -1.
        val none = texts(AngleLabel.NONE)
        assertTrue(none.none { it.contains("π") })
        assertTrue(none.contains("1"))
    }

    @Test
    fun `a mark at each angle is drawn when it is asked for`() {
        fun dots(on: Boolean) = build(
            Stamps.Kind.UNIT_CIRCLE,
            Stamps.Kind.UNIT_CIRCLE.defaults.copy(angleStep = 4, angleDots = on, labels = false)
        ).count { it.kind == Stroke.Kind.ELLIPSE }
        // The circle itself is one; eight angles bring eight more.
        assertEquals(1, dots(on = false))
        assertEquals(9, dots(on = true))
    }

    @Test
    fun `a marked angle draws its legs and can be asked not to`() {
        fun fine(legs: Boolean) = build(
            Stamps.Kind.UNIT_CIRCLE,
            Stamps.Kind.UNIT_CIRCLE.defaults.copy(markAngle = 60f, angleLegs = legs, labels = false)
        ).size
        assertTrue(fine(legs = true) > fine(legs = false))
    }

    @Test
    fun `nonsense typed into the new settings is made sane`() {
        val o = Stamps.sanitise(
            Stamps.Kind.SINE,
            wave(every = -3f).copy(quantBits = 99, angleStep = 0, markAngle = Float.NaN)
        )
        assertEquals(0f, o.sampleEvery, 0f)
        assertEquals(Stamps.QUANT_BITS.last, o.quantBits)
        assertEquals(1, o.angleStep)
        assertEquals(-1f, o.markAngle, 0f)
    }
}
