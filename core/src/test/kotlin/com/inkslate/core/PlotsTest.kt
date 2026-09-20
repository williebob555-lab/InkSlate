package com.inkslate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

/**
 * The equations a graph can be asked to draw, and the points on a curve worth marking.
 *
 * These are the numbers a student would check by hand, so they are written as the answers a
 * student would expect: a sine peaks at 1, x squared minus four crosses at two, and a ball thrown
 * at forty-five degrees goes as far as the textbook says it does.
 */
class PlotsTest {

    private fun value(text: String, x: Double, degrees: Boolean = false): Double =
        Expr.parse(text, degrees).at(x)

    @Test
    fun `arithmetic follows the usual order`() {
        assertEquals(7.0, value("1 + 2 * 3", 0.0), 1e-9)
        assertEquals(9.0, value("(1 + 2) * 3", 0.0), 1e-9)
        assertEquals(-8.0, value("-2^3", 0.0), 1e-9)
        assertEquals(512.0, value("2^3^2", 0.0), 1e-9)
        assertEquals(0.5, value("1/2", 0.0), 1e-9)
    }

    @Test
    fun `the multiplication people leave out is understood`() {
        assertEquals(6.0, value("2x", 3.0), 1e-9)
        assertEquals(12.0, value("2(x+1)", 5.0), 1e-9)
        assertEquals(0.0, value("3sin(x)", 0.0), 1e-9)
        assertEquals(9.0, value("x^2", 3.0), 1e-9)
    }

    @Test
    fun `functions, constants and degrees`() {
        assertEquals(1.0, value("sin(pi/2)", 0.0), 1e-9)
        assertEquals(1.0, value("sin(90)", 0.0, degrees = true), 1e-9)
        assertEquals(30.0, value("asin(0.5)", 0.0, degrees = true), 1e-6)
        assertEquals(3.0, value("sqrt(9)", 0.0), 1e-9)
        assertEquals(2.0, value("log(100)", 0.0), 1e-9)
        assertEquals(1.0, value("ln(e)", 0.0), 1e-9)
    }

    @Test
    fun `x is the variable, and t means the same thing`() {
        assertEquals(10.0, value("x*2", 5.0), 1e-9)
        assertEquals(10.0, value("t*2", 5.0), 1e-9)
    }

    @Test
    fun `an equation that cannot be read says why, rather than drawing nothing in silence`() {
        val bad = Expr.parse("2 +* x")
        assertFalse(bad.ok)
        assertNotNull(bad.error)
        assertFalse(Expr.parse("sin(x").ok)
        assertFalse(Expr.parse("wobble(x)").ok)
        assertFalse(Expr.parse("").ok)
        assertTrue(Expr.parse("sin(x)").ok)
    }

    @Test
    fun `a curve with gaps is drawn in pieces rather than joined through them`() {
        val f = Expr.parse("1/x")
        val curve = Plots.sample(-5.0, 5.0, 200, -10.0..10.0) { f.at(it) }
        assertTrue("1/x should come out in two pieces", curve.runs.size >= 2)
        assertTrue(curve.points.none { abs(it.y) > 20 })
    }

    @Test
    fun `a sine's peaks, troughs and zeros are where they should be`() {
        val f = Expr.parse("sin(x)")
        val curve = Plots.sample(0.0, 2 * PI, 400, -2.0..2.0) { f.at(it) }
        val poi = Plots.pointsOfInterest(curve, f = { f.at(it) })
        val max = poi.single { it.kind == Plots.PoiKind.MAXIMUM }
        val min = poi.single { it.kind == Plots.PoiKind.MINIMUM }
        assertEquals(PI / 2, max.x, 0.01)
        assertEquals(1.0, max.y, 0.001)
        assertEquals(3 * PI / 2, min.x, 0.01)
        val zeros = poi.filter { it.kind == Plots.PoiKind.ZERO }.map { it.x }
        assertEquals(3, zeros.size)
        assertEquals(PI, zeros[1], 1e-6)
    }

    @Test
    fun `a parabola's zeros and turning point`() {
        val f = Expr.parse("x^2-4")
        val curve = Plots.sample(-5.0, 5.0, 400, -10.0..30.0) { f.at(it) }
        val poi = Plots.pointsOfInterest(curve, yIntercept = true, f = { f.at(it) })
        val zeros = poi.filter { it.kind == Plots.PoiKind.ZERO }.map { it.x }.sorted()
        assertEquals(2, zeros.size)
        assertEquals(-2.0, zeros[0], 1e-5)
        assertEquals(2.0, zeros[1], 1e-5)
        val min = poi.single { it.kind == Plots.PoiKind.MINIMUM }
        assertEquals(0.0, min.x, 0.02)
        assertEquals(-4.0, min.y, 0.01)
        assertEquals(-4.0, poi.single { it.kind == Plots.PoiKind.Y_INTERCEPT }.y, 1e-9)
    }

    @Test
    fun `a ball thrown at forty-five degrees goes where the textbook says`() {
        val flight = Plots.Flight.fromSpeedAngle(speed = 20.0, angleDegrees = 45.0)
        // v^2 sin(2a) / g = 400 / 9.81
        assertEquals(400.0 / 9.81, flight.range, 0.01)
        assertEquals(20.0 * 20.0 / (4 * 9.81), flight.apex.y, 0.01)
        assertEquals(2 * 20.0 * kotlin.math.sin(PI / 4) / 9.81, flight.timeOfFlight, 0.001)
        // No air, level ground: it lands as fast as it left.
        assertEquals(20.0, flight.impactSpeed, 0.001)
        assertEquals(flight.range / 2, flight.apex.x, 0.01)
    }

    @Test
    fun `thrown from a height it stays up longer and lands faster`() {
        val level = Plots.Flight.fromSpeedAngle(15.0, 30.0)
        val high = Plots.Flight.fromSpeedAngle(15.0, 30.0, startHeight = 10.0)
        assertTrue(high.timeOfFlight > level.timeOfFlight)
        assertTrue(high.range > level.range)
        assertTrue(high.impactSpeed > level.impactSpeed)
        assertEquals(10.0, high.at(0.0).y, 1e-9)
    }

    @Test
    fun `the arc starts where it was thrown from and ends on the ground`() {
        val flight = Plots.Flight.fromSpeedAngle(12.0, 60.0, startHeight = 1.5)
        val arc = flight.arc(100)
        assertEquals(1, arc.runs.size)
        assertEquals(1.5, arc.points.first().y, 1e-6)
        assertEquals(0.0, arc.points.last().y, 1e-6)
        assertEquals(flight.range, arc.points.last().x, 1e-6)
        assertEquals(4, flight.marks(5).size)
    }

    @Test
    fun `a flat throw and a still ball do not break the arc`() {
        assertTrue(Plots.Flight.fromSpeedAngle(0.0, 45.0).arc().isEmpty)
        val flat = Plots.Flight.fromSpeedAngle(10.0, 0.0, startHeight = 5.0)
        assertTrue(flat.timeOfFlight > 0)
        assertTrue(flat.arc().points.last().x > 0)
    }
}
