package com.inkslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stamps that work something out before they draw it: an equation, a sinusoid in the terms a
 * signals course uses, and a thrown object given as position, velocity and acceleration.
 *
 * What is checked here is that the picture and the numbers agree, that the numbers are the ones
 * the question asks for, and that everything drawn is still an ordinary stroke - because that is
 * what keeps a plotted curve as movable, erasable and restyleable as a drawn one.
 */
class SmartStampTest {

    private val box = Box(0f, 0f, 400f, 300f)

    private fun build(kind: Stamps.Kind, o: Stamps.StampOptions): List<Stroke> {
        var n = 0
        return Stamps.build(kind, box, page = 0, options = o, group = "g") { "s${n++}" }
    }

    private fun texts(strokes: List<Stroke>) =
        strokes.filter { it.kind == Stroke.Kind.TEXT }.mapNotNull { it.text }

    private fun rows(kind: Stamps.Kind, o: Stamps.StampOptions) = Stamps.inspect(kind, o).toMap()

    @Test
    fun `a plotted curve is ordinary strokes, and one stamp`() {
        val strokes = build(Stamps.Kind.PLOT, Stamps.Kind.PLOT.defaults.copy(expression = "sin(x)"))
        assertNotNull(Stamps.stampOf(strokes))
        val curve = strokes.filter { it.kind == Stroke.Kind.FREEHAND }
        assertTrue("the curve should be drawn", curve.isNotEmpty())
        assertTrue("and sampled, not two points", curve.first().points.size > 50)
        assertTrue(strokes.all { it.stamp?.group == "g" })
    }

    @Test
    fun `the curve has its own colour, weight and dash, apart from the axes`() {
        val red = 0xFFDC2626.toInt()
        val o = Stamps.Kind.PLOT.defaults.copy(
            color = Stamps.INK, curveColor = red, curveWeight = 3f, curveDash = DashStyle.DASHED,
            weight = 2f
        )
        val strokes = build(Stamps.Kind.PLOT, o)
        val curve = strokes.single { it.kind == Stroke.Kind.FREEHAND && it.color == red }
        assertEquals(6f, curve.baseWidth, 0.01f)
        assertEquals(DashStyle.DASHED, curve.dash)
        assertTrue("the axes keep the stamp's own colour", strokes.any { it.color == Stamps.INK })
    }

    @Test
    fun `the curve can be drawn without axes, to go over a graph already there`() {
        val bare = build(Stamps.Kind.PLOT, Stamps.Kind.PLOT.defaults.copy(showAxes = false, markerLabels = PoiLabel.NONE))
        assertTrue(bare.none { it.kind == Stroke.Kind.LINE && it.finishEnd == LineEnd.ARROW })
        assertTrue(bare.any { it.kind == Stroke.Kind.FREEHAND })
    }

    @Test
    fun `a curve with an asymptote is drawn in pieces`() {
        val o = Stamps.Kind.PLOT.defaults.copy(expression = "1/x", rangeFrom = -4f, rangeTo = 4f)
        val pieces = build(Stamps.Kind.PLOT, o).count { it.kind == Stroke.Kind.FREEHAND }
        assertTrue("1/x should not be joined through its asymptote", pieces >= 2)
    }

    @Test
    fun `an equation that cannot be read draws the axes and says so in the readout`() {
        val o = Stamps.Kind.PLOT.defaults.copy(expression = "wobble(x)")
        val strokes = build(Stamps.Kind.PLOT, o)
        assertTrue("no curve", strokes.none { it.kind == Stroke.Kind.FREEHAND })
        assertTrue("but still a graph", strokes.isNotEmpty())
        assertNotNull(rows(Stamps.Kind.PLOT, o)["Equation"])
    }

    @Test
    fun `points of interest are marked and labelled with their numbers`() {
        val o = Stamps.Kind.PLOT.defaults.copy(
            expression = "x^2-4", rangeFrom = -3f, rangeTo = 3f,
            markMax = false, markMin = true, markZeros = true, markerLabels = PoiLabel.COORDINATES
        )
        val words = texts(build(Stamps.Kind.PLOT, o))
        assertTrue("a zero at 2", words.any { it.startsWith("(2,") || it.startsWith("(-2,") })
        assertTrue("the minimum at -4", words.any { it.contains("-4)") })
    }

    @Test
    fun `a sinusoid is given in signals terms and reports the rest`() {
        // 50 Hz: omega = 100 pi
        val o = Stamps.Kind.SINE.defaults.copy(
            amplitude = 3f, omega = (2 * Math.PI * 50).toFloat(), phase = (Math.PI / 2).toFloat(),
            rangeFrom = 0f, rangeTo = 0.04f, yUnit = "V", xUnit = "s"
        )
        val readout = rows(Stamps.Kind.SINE, o)
        assertEquals("50 Hz", readout["f"])
        assertEquals("0.02 s", readout["T"])
        assertTrue(readout["ω"]!!.startsWith("314"))
        assertTrue(readout["Phase φ"]!!.contains("90"))
        assertEquals("3 V", readout["Amplitude"])
        // cos is sin a quarter turn along, so this peaks at t = 0
        assertEquals(3.0, Stamps.waveAt(o, 0.0), 1e-6)
    }

    @Test
    fun `a damped wave decays and carries its envelope`() {
        val o = Stamps.Kind.SINE.defaults.copy(
            damping = 20f, rangeFrom = 0f, rangeTo = 0.2f, omega = (2 * Math.PI * 25).toFloat()
        )
        assertTrue(Math.abs(Stamps.waveAt(o, 0.1)) < Math.abs(Stamps.waveAt(o, 0.005)))
        val curves = build(Stamps.Kind.SINE, o).filter { it.kind == Stroke.Kind.FREEHAND }
        assertTrue("the wave and both envelope lines", curves.size >= 3)
        assertTrue(curves.any { it.dash == DashStyle.DASHED })
        val bare = build(Stamps.Kind.SINE, o.copy(showEnvelope = false))
            .filter { it.kind == Stroke.Kind.FREEHAND }
        assertTrue(bare.none { it.dash == DashStyle.DASHED })
        assertEquals("0.05 s", rows(Stamps.Kind.SINE, o)["Time constant τ"])
    }

    @Test
    fun `axis numbers are written in the size the unit suits, with the unit on the axis`() {
        val o = Stamps.Kind.SINE.defaults.copy(
            rangeFrom = 0f, rangeTo = 0.004f, step = 0.001f, xUnit = "s", xName = "t",
            yUnit = "V", fitAxes = false, yFrom = -1f, yTo = 1f, yStep = 0.5f
        )
        val words = texts(build(Stamps.Kind.SINE, o))
        assertTrue("milliseconds, not thousandths of a second", words.contains("t (ms)"))
        assertTrue(words.contains("2"))
        assertTrue("nothing written as 0.002", words.none { it == "0.002" })
    }

    @Test
    fun `a thrown ball reports what the question asks for`() {
        val o = Stamps.Kind.PROJECTILE.defaults.copy(speed = 20f, launchAngle = 45f)
        val readout = rows(Stamps.Kind.PROJECTILE, o)
        assertEquals("40.77 m", readout["Range"])
        assertEquals("10.19 m", readout["Highest point"]!!.substringAfter(", ").removeSuffix(")"))
        assertEquals("2.88 s", readout["Time of flight"])
        assertEquals("20 m/s", readout["Speed on landing"])
    }

    @Test
    fun `velocity can be given as components, and an acceleration along the ground is obeyed`() {
        val components = Stamps.Kind.PROJECTILE.defaults.copy(
            velocityIn = Stamps.VelocityIn.COMPONENTS, velocityX = 14.142f, velocityY = 14.142f
        )
        val byAngle = Stamps.Kind.PROJECTILE.defaults.copy(speed = 20f, launchAngle = 45f)
        assertEquals(
            Stamps.flightOf(byAngle).range, Stamps.flightOf(components).range, 0.01
        )
        val pushed = Stamps.flightOf(byAngle.copy(accelX = 2f))
        assertTrue("a push along the ground carries it further", pushed.range > Stamps.flightOf(byAngle).range)
    }

    @Test
    fun `a question that stops the clock early stops the arc there`() {
        val o = Stamps.Kind.PROJECTILE.defaults.copy(speed = 20f, launchAngle = 60f, stopAfter = 1f)
        val flight = Stamps.flightOf(o)
        assertEquals(1.0, flight.timeOfFlight, 1e-9)
        assertEquals(flight.at(1.0).x, flight.landing.x, 1e-9)
    }

    @Test
    fun `velocity arrows are drawn, and can be split into components`() {
        val o = Stamps.Kind.PROJECTILE.defaults.copy(velocityArrows = 4)
        val arrows = build(Stamps.Kind.PROJECTILE, o).count { it.finishEnd == LineEnd.ARROW && it.kind == Stroke.Kind.LINE }
        assertTrue("one per marked time, plus the axes' own", arrows >= 4)
        val split = build(Stamps.Kind.PROJECTILE, o.copy(componentArrows = true))
            .count { it.dash == DashStyle.DASHED && it.finishEnd == LineEnd.ARROW }
        assertTrue("two components per arrow", split >= 8)
    }

    @Test
    fun `the parts a smart stamp draws are the parts its settings offer`() {
        val plot = Stamps.features(Stamps.Kind.PLOT)
        assertTrue(Stamps.Feature.CURVE in plot)
        assertTrue(Stamps.Feature.MARKERS in plot)
        assertTrue(Stamps.Feature.VECTORS !in plot)
        val thrown = Stamps.features(
            Stamps.Kind.PROJECTILE, Stamps.Kind.PROJECTILE.defaults.copy(velocityArrows = 3)
        )
        assertTrue(Stamps.Feature.VECTORS in thrown)
    }

    @Test
    fun `a plotted stamp can be rebuilt with new numbers where it stands`() {
        val placed = build(Stamps.Kind.SINE, Stamps.Kind.SINE.defaults)
        val tag = Stamps.stampOf(placed)!!
        var n = 0
        val louder = Stamps.rebuild(placed, tag.options.copy(amplitude = 5f)) { "r${n++}" }
        assertEquals(5f, Stamps.stampOf(louder)!!.options.amplitude, 0.001f)
        assertTrue(louder.any { it.kind == Stroke.Kind.FREEHAND })
    }
}
