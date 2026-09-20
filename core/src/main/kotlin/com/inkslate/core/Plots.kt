package com.inkslate.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Curves, and the points on them worth pointing at.
 *
 * Kept apart from the drawing so the same numbers can be shown as text: a graph is worth more when
 * the app can also say where its maximum is than when it can only draw one. The drawing side turns
 * these into ordinary strokes, which is what keeps a plotted curve as movable, erasable and
 * restyleable as anything drawn by hand.
 */
object Plots {

    /** One point on a curve, in the graph's own units rather than in page points. */
    data class Point(val x: Double, val y: Double)

    /**
     * A sampled curve, in runs.
     *
     * More than one run where the curve has gaps in it: `tan(x)` and `1/x` are one curve with
     * several pieces, and joining the pieces would draw a vertical line through an asymptote that
     * is not part of the function.
     */
    data class Curve(val runs: List<List<Point>>) {
        val points: List<Point> get() = runs.flatten()
        val isEmpty: Boolean get() = runs.isEmpty()
        fun lowest(): Point? = points.minByOrNull { it.y }
        fun highest(): Point? = points.maxByOrNull { it.y }
    }

    /** What a marked point is. */
    enum class PoiKind(val label: String) {
        MAXIMUM("Maximum"),
        MINIMUM("Minimum"),
        ZERO("Zero"),
        Y_INTERCEPT("Crosses the y axis"),
        APEX("Highest point"),
        LANDING("Lands"),
        START("Starts")
    }

    data class Poi(val kind: PoiKind, val x: Double, val y: Double)

    /**
     * Sample [f] across the x range.
     *
     * [clipTo] bounds what counts as on the graph: a value far outside it is not drawn, and a jump
     * between two samples that crosses the whole graph is a break rather than a line - which is
     * what stops `tan(x)` from being drawn with its asymptotes joined up.
     */
    fun sample(
        from: Double,
        to: Double,
        steps: Int,
        clipTo: ClosedFloatingPointRange<Double>? = null,
        f: (Double) -> Double
    ): Curve {
        if (!(to > from) || steps < 2) return Curve(emptyList())
        val n = steps.coerceIn(2, 4000)
        val span = to - from
        // Just past the edge, so a segment leaving the graph is cut at the edge rather than a
        // whole step before it - but not so far that the curve is drawn outside its own axes.
        val slack = clipTo?.let { (it.endInclusive - it.start) * 0.02 } ?: 0.0
        val runs = ArrayList<List<Point>>()
        var run = ArrayList<Point>()
        var previous: Point? = null

        fun endRun() {
            if (run.size >= 2) runs.add(run)
            run = ArrayList()
        }

        for (i in 0..n) {
            val x = from + span * i / n
            val y = f(x)
            val usable = y.isFinite() &&
                (clipTo == null || (y >= clipTo.start - slack && y <= clipTo.endInclusive + slack))
            if (!usable) {
                endRun()
                previous = null
                continue
            }
            val point = Point(x, y)
            // A step that crosses more than the whole graph is a jump, not a slope.
            val jumped = previous != null && clipTo != null &&
                abs(y - previous.y) > (clipTo.endInclusive - clipTo.start) * 1.5
            if (jumped) endRun()
            run.add(point)
            previous = point
        }
        endRun()
        return Curve(runs)
    }

    /**
     * The turning points, zeros and intercept of a sampled curve.
     *
     * Found from the samples rather than by differentiating: the samples are what is drawn, so a
     * marker always lands on the curve as drawn, and anything anybody can type is covered without
     * a symbolic derivative for it. Refined by bisection at zeros and by a parabola through the
     * three samples at a turn, so the answer is better than the sampling step.
     */
    fun pointsOfInterest(
        curve: Curve,
        maxima: Boolean = true,
        minima: Boolean = true,
        zeros: Boolean = true,
        yIntercept: Boolean = false,
        f: ((Double) -> Double)? = null
    ): List<Poi> {
        val out = ArrayList<Poi>()
        for (run in curve.runs) {
            for (i in 1 until run.size - 1) {
                val a = run[i - 1]
                val b = run[i]
                val c = run[i + 1]
                val rising = b.y - a.y
                val falling = c.y - b.y
                if (rising > 0 && falling < 0 && maxima) out.add(refineTurn(a, b, c, PoiKind.MAXIMUM))
                if (rising < 0 && falling > 0 && minima) out.add(refineTurn(a, b, c, PoiKind.MINIMUM))
            }
            if (zeros) {
                // What counts as zero, against how big this curve gets: sin(2pi) comes out as
                // -2.4e-16, and a curve that ends exactly on a zero - which is where a sine over
                // one turn ends - has no next sample to change sign against.
                val scale = run.maxOf { abs(it.y) }.coerceAtLeast(1.0)
                val eps = scale * 1e-9
                for (i in 0 until run.size - 1) {
                    val a = run[i]
                    val b = run[i + 1]
                    if (abs(a.y) <= eps) out.add(Poi(PoiKind.ZERO, a.x, 0.0))
                    else if (abs(b.y) > eps && (a.y < 0) != (b.y < 0)) {
                        val x = if (f != null) bisect(f, a.x, b.x) else {
                            a.x + (b.x - a.x) * (0 - a.y) / (b.y - a.y)
                        }
                        out.add(Poi(PoiKind.ZERO, x, 0.0))
                    }
                }
                run.last().takeIf { abs(it.y) <= eps }?.let { out.add(Poi(PoiKind.ZERO, it.x, 0.0)) }
            }
        }
        if (yIntercept && f != null) {
            val y = f(0.0)
            val within = curve.runs.any { r -> r.first().x <= 0.0 && r.last().x >= 0.0 }
            if (y.isFinite() && within) out.add(Poi(PoiKind.Y_INTERCEPT, 0.0, y))
        }
        // Left to right, and never two markers on the same spot.
        return out.sortedBy { it.x }.fold(ArrayList()) { kept, p ->
            if (kept.none { it.kind == p.kind && abs(it.x - p.x) < 1e-6 }) kept.add(p)
            kept
        }
    }

    /** A turning point sharpened by the parabola through the three samples around it. */
    private fun refineTurn(a: Point, b: Point, c: Point, kind: PoiKind): Poi {
        val denominator = (a.y - 2 * b.y + c.y)
        if (abs(denominator) < 1e-12) return Poi(kind, b.x, b.y)
        val step = c.x - b.x
        val shift = 0.5 * (a.y - c.y) / denominator
        val x = b.x + shift * step
        val y = b.y - 0.25 * (a.y - c.y) * shift
        return Poi(kind, x, y)
    }

    /** Close in on a zero between two samples that straddle it. */
    private fun bisect(f: (Double) -> Double, lo: Double, hi: Double): Double {
        var a = lo
        var b = hi
        var fa = f(a)
        if (!fa.isFinite()) return (lo + hi) / 2
        repeat(40) {
            val mid = (a + b) / 2
            val fm = f(mid)
            if (!fm.isFinite()) return mid
            if ((fa < 0) != (fm < 0)) { b = mid } else { a = mid; fa = fm }
        }
        return (a + b) / 2
    }

    // ---- thrown things -------------------------------------------------------

    /**
     * Something moving under a constant acceleration: where it starts, how fast it is going, and
     * what is pulling on it.
     *
     * Written as position, velocity and acceleration rather than as "speed and angle", because
     * that is the mix homework comes in: some questions give 12 m/s at 40 degrees, some give the
     * components, some put it on a slope or on the Moon, and some ask where it is after three
     * seconds rather than where it lands. All of those are this one object with different numbers.
     */
    data class Flight(
        val startX: Double = 0.0,
        val startY: Double = 0.0,
        val vx: Double = 0.0,
        val vy: Double = 0.0,
        val ax: Double = 0.0,
        /** Down is negative, as it is in the equations. */
        val ay: Double = -9.81,
        /** The height it is counted as landing at. */
        val groundY: Double = 0.0,
        /** Stop after this many seconds instead of at the ground, for "where is it after t?". */
        val stopAfter: Double? = null
    ) {
        companion object {
            /** The way a question usually puts it: this fast, at this angle, from this height. */
            fun fromSpeedAngle(
                speed: Double,
                angleDegrees: Double,
                gravity: Double = 9.81,
                startHeight: Double = 0.0,
                startX: Double = 0.0,
                accelX: Double = 0.0,
                stopAfter: Double? = null
            ): Flight {
                val radians = angleDegrees * Math.PI / 180.0
                return Flight(
                    startX = startX,
                    startY = startHeight,
                    vx = speed * cos(radians),
                    vy = speed * sin(radians),
                    ax = accelX,
                    ay = -abs(gravity),
                    stopAfter = stopAfter
                )
            }
        }

        val speed: Double get() = hypot(vx, vy)
        val angleDegrees: Double get() = Math.toDegrees(kotlin.math.atan2(vy, vx))

        fun at(t: Double) = Point(
            startX + vx * t + 0.5 * ax * t * t,
            startY + vy * t + 0.5 * ay * t * t
        )

        fun velocityAt(t: Double) = Point(vx + ax * t, vy + ay * t)
        fun speedAt(t: Double) = hypot(vx + ax * t, vy + ay * t)

        /** When it comes back down to [groundY], or the time it was told to stop at. */
        val timeOfFlight: Double
            get() {
                stopAfter?.let { return max(0.0, it) }
                val c = startY - groundY
                if (abs(ay) < 1e-12) return if (vy > 0 || c <= 0) 0.0 else max(0.0, -c / vy)
                // ½at² + vt + c = 0, taking the later of the two crossings.
                val disc = vy * vy - 2 * ay * c
                if (disc < 0) return 0.0
                val root = kotlin.math.sqrt(disc)
                val t1 = (-vy + root) / ay
                val t2 = (-vy - root) / ay
                val best = listOf(t1, t2).filter { it > 1e-9 }.maxOrNull()
                return best ?: 0.0
            }

        /** When it stops climbing. Zero when it is already falling. */
        val timeToApex: Double
            get() = if (abs(ay) < 1e-12 || vy <= 0) 0.0 else (-vy / ay).coerceAtMost(timeOfFlight)

        val apex: Point get() = at(timeToApex)
        val landing: Point get() = at(timeOfFlight)
        val range: Double get() = landing.x - startX
        val impactSpeed: Double get() = speedAt(timeOfFlight)
        val impactAngleDegrees: Double
            get() {
                val v = velocityAt(timeOfFlight)
                return Math.toDegrees(kotlin.math.atan2(v.y, v.x))
            }

        /** Height at a horizontal distance, for drawing it against an x axis in metres. */
        fun heightAt(x: Double): Double {
            if (abs(ax) < 1e-12) {
                if (abs(vx) < 1e-9) return Double.NaN
                val t = (x - startX) / vx
                if (t < -1e-9 || t > timeOfFlight + 1e-9) return Double.NaN
                return at(t).y
            }
            // With a horizontal acceleration the path is no longer a function of x in one step;
            // solve for the first time that reaches this x.
            val disc = vx * vx + 2 * ax * (x - startX)
            if (disc < 0) return Double.NaN
            val root = kotlin.math.sqrt(disc)
            val t = listOf((-vx + root) / ax, (-vx - root) / ax).filter { it >= -1e-9 }.minOrNull()
                ?: return Double.NaN
            if (t > timeOfFlight + 1e-9) return Double.NaN
            return at(t).y
        }

        /** The path itself, sampled in time so a curved-back path is drawn correctly. */
        fun arc(steps: Int = 160): Curve {
            val end = timeOfFlight
            if (!(end > 0) || steps < 2) return Curve(emptyList())
            val n = steps.coerceIn(2, 2000)
            return Curve(listOf((0..n).map { at(end * it / n) }))
        }

        /** Where it is at equal times: the trail that shows it speeding up as it falls. */
        fun marks(count: Int): List<Double> {
            if (count < 1) return emptyList()
            val end = timeOfFlight
            return (1 until count.coerceAtMost(60)).map { end * it / count }
        }
    }
}
