package com.inkslate.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The angles a unit circle is marked with, written the way they are written by hand.
 *
 * A unit circle chart is only worth having if it says 7pi/6 and (-sqrt3/2, -1/2) rather than 3.665
 * and (-0.87, -0.50) - those are the values a trig question is answered in, and reading them off a
 * decimal is where the marks go.
 */
object Angles {

    /** Steps a circle is usually divided into, as the denominator under pi. */
    val COMMON_STEPS: List<Int> = listOf(2, 3, 4, 6, 8, 12)

    /** The angles, in degrees, at a step of pi over [denominator]. */
    fun anglesFor(denominator: Int): List<Double> {
        val d = denominator.coerceIn(1, 36)
        val step = 180.0 / d
        return (0 until (360.0 / step).roundToInt()).map { it * step }
    }

    /**
     * An angle in degrees as a multiple of pi: "pi/6", "3pi/4", "2pi", "0".
     *
     * Reduced, because 6pi/6 is not how anybody writes a half turn.
     */
    fun asPi(degrees: Double, denominator: Int): String {
        val d = denominator.coerceIn(1, 36)
        var numerator = (degrees * d / 180.0).roundToInt()
        var below = d
        if (numerator == 0) return "0"
        val common = gcd(abs(numerator), below)
        numerator /= common
        below /= common
        val top = when (numerator) {
            1 -> "\u03c0"
            -1 -> "-\u03c0"
            else -> "$numerator\u03c0"
        }
        return if (below == 1) top else "$top/$below"
    }

    /** The cosine and sine of an angle, exactly where they can be: "\u221a3/2", "1/2", "0". */
    fun exactPair(degrees: Double): Pair<String, String>? {
        val at = ((degrees % 360) + 360) % 360
        val whole = at.roundToInt()
        if (abs(at - whole) > 0.01) return null
        return EXACT[whole % 360]
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    private const val HALF_ROOT3 = "\u221a3/2"
    private const val HALF_ROOT2 = "\u221a2/2"
    private const val HALF = "1/2"

    /** Cosine and sine at every angle a circle is usually marked at. */
    private val EXACT: Map<Int, Pair<String, String>> = mapOf(
        0 to ("1" to "0"),
        30 to (HALF_ROOT3 to HALF),
        45 to (HALF_ROOT2 to HALF_ROOT2),
        60 to (HALF to HALF_ROOT3),
        90 to ("0" to "1"),
        120 to ("-$HALF" to HALF_ROOT3),
        135 to ("-$HALF_ROOT2" to HALF_ROOT2),
        150 to ("-$HALF_ROOT3" to HALF),
        180 to ("-1" to "0"),
        210 to ("-$HALF_ROOT3" to "-$HALF"),
        225 to ("-$HALF_ROOT2" to "-$HALF_ROOT2"),
        240 to ("-$HALF" to "-$HALF_ROOT3"),
        270 to ("0" to "-1"),
        300 to (HALF to "-$HALF_ROOT3"),
        315 to (HALF_ROOT2 to "-$HALF_ROOT2"),
        330 to (HALF_ROOT3 to "-$HALF")
    )
}
